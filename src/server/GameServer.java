package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lookup.LookupClient;
import models.Config;
import models.Question;
import models.ScoreEntry;
import models.User;
import utils.JsonLoader;

public class GameServer {
    public enum SubmitResult {
        ACCEPTED,
        NO_ACTIVE_GAME,
        QUESTION_CLOSED,
        DUPLICATE_ANSWER,
        INVALID_ANSWER
    }

    private static final int PORT = 5000;
    private static final String PUBLIC_ROOM_PREFIX = "public-room-";

    private ServerSocket serverSocket;
    private JsonLoader jsonLoader;
    private LookupClient lookupClient;

    private Map<String, User> users;
    private List<Question> questions;
    private List<ScoreEntry> scores;
    private Config config;

   
    private Map<String, ClientHandler> connectedClients;

    private Map<String, List<String>>  gameRooms;

    private Map<String, String> roomHosts;

    private Map<String, Boolean> roomInProgress;

    private Map<String, Map<String, Integer>> roomScores;

    private Map<String, Map<String, String>>  roomAnswers;

    private Map<String, Boolean> roomAcceptingAnswers;

    private Map<String, RoomSetup> roomSetups;

    private Map<String, Map<String, List<String>>> roomAnswerDetails;

    private AtomicInteger publicRoomCounter;

    private static class RoomSetup {
        private String category;
        private String difficulty;
        private int questionCount;
        private String teamAName;
        private String teamBName;
        private Map<String, String> teamByUser;

        private RoomSetup(String category, String difficulty, int questionCount,
                          String teamAName, String teamBName, Map<String, String> teamByUser) {
            this.category = category;
            this.difficulty = difficulty;
            this.questionCount = questionCount;
            this.teamAName = teamAName;
            this.teamBName = teamBName;
            this.teamByUser = teamByUser != null ? new ConcurrentHashMap<>(teamByUser) : new ConcurrentHashMap<>();
        }

        private boolean isTeamMode() {
            return teamAName != null && teamBName != null && !teamByUser.isEmpty();
        }
    }

    public GameServer() {
        jsonLoader = new JsonLoader();
        connectedClients = new ConcurrentHashMap<>();
        gameRooms= new ConcurrentHashMap<>();
        roomHosts  = new ConcurrentHashMap<>();
        roomInProgress = new ConcurrentHashMap<>();
        roomScores   = new ConcurrentHashMap<>();
        roomAnswers = new ConcurrentHashMap<>();
        roomAcceptingAnswers = new ConcurrentHashMap<>();
        roomSetups = new ConcurrentHashMap<>();
        roomAnswerDetails = new ConcurrentHashMap<>();
        publicRoomCounter = new AtomicInteger(1);
        loadData();
        String lookupHost = config != null ? config.getLookupHost() : "localhost";
        int lookupPort = config != null ? config.getLookupPort() : 6000;
        lookupClient = new LookupClient(lookupHost, lookupPort);
    }

    private void loadData() {
        System.out.println("Loading server data...");
        users = jsonLoader.loadUsers();
        questions = jsonLoader.loadQuestions();
        scores = jsonLoader.loadScores();
        config = jsonLoader.loadConfig();

        if (scores == null) scores = new ArrayList<>();
        if (questions == null) questions = new ArrayList<>();

        System.out.println("Users loaded: "   + users.size());
        System.out.println("Questions loaded: " + questions.size());
        System.out.println("Scores loaded: "    + scores.size());
        if (config != null) {
            System.out.println("Config: maxPlayers=" + config.getMaxPlayers()
                    + ", questionTime=" + config.getQuestionTime() + "s");
        }
    }

    public void startServer() {
        try {
            serverSocket = new ServerSocket(PORT);
            System.out.println("=== Game Server started on port " + PORT + " ===");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New connection: " + clientSocket.getInetAddress());
                new Thread(new ClientHandler(clientSocket, this)).start();
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }


    public boolean authenticateUser(String username, String password) {
        User user = users.get(username);
        return user != null && user.getPassword().equals(password);
    }

    public boolean registerUser(String name, String username, String password) {
        if (users.containsKey(username)) return false;
        users.put(username, new User(name, username, password, false));
        jsonLoader.saveUsers(users);
        return true;
    }

    public Map<String, User> getUsers() { return users; }

    
    public void addClient(String username, ClientHandler handler) {
        connectedClients.put(username, handler);
        System.out.println("[+] " + username + " online. Total: " + connectedClients.size());
    }

    public void removeClient(String username) {
        connectedClients.remove(username);
        List<String> rooms = gameRooms.entrySet().stream()
                .filter(e -> e.getValue().contains(username))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        for (String roomName : rooms) {
            leaveRoom(roomName, username);
        }
        System.out.println("[-] " + username + " offline. Total: " + connectedClients.size());
    }

    public Map<String, ClientHandler> getConnectedClients() { return connectedClients; }

   
    public boolean createRoom(String roomName, String hostUsername) {
        if (gameRooms.containsKey(roomName)) return false;
        List<String> players = new CopyOnWriteArrayList<>();
        players.add(hostUsername);
        gameRooms.put(roomName, players);
        roomHosts.put(roomName, hostUsername);
        roomInProgress.put(roomName, false);
        int defaultCount = config != null ? config.getDefaultQuestionCount() : 5;
        roomSetups.put(roomName, new RoomSetup("any", "any", defaultCount, null, null, null));
        System.out.println("Room created: '" + roomName + "' by " + hostUsername);
        return true;
    }

    public String joinOrCreatePublicRoom(String username) {
        for (Map.Entry<String, List<String>> entry : gameRooms.entrySet()) {
            String roomName = entry.getKey();
            List<String> players = entry.getValue();
            if (!roomName.startsWith(PUBLIC_ROOM_PREFIX)) continue;
            if (roomInProgress.getOrDefault(roomName, false)) continue;
            if (players.contains(username)) return roomName;
            if (config != null && players.size() >= config.getMaxPlayers()) continue;
            players.add(username);
            return roomName;
        }

        String roomName = PUBLIC_ROOM_PREFIX + publicRoomCounter.getAndIncrement();
        createRoom(roomName, username);
        return roomName;
    }

    public boolean isPublicRoom(String roomName) {
        return roomName != null && roomName.startsWith(PUBLIC_ROOM_PREFIX);
    }

    public synchronized boolean tryAutoStartPublicRoom(String roomName) {
        if (!isPublicRoom(roomName)) return false;
        if (isRoomInProgress(roomName)) return false;

        List<String> players = gameRooms.get(roomName);
        if (players == null) return false;
        int minPlayers = config != null ? config.getMinPlayers() : 2;
        if (players.size() < minPlayers) return false;

        roomSetups.put(roomName, new RoomSetup("any", "any",
                config != null ? config.getDefaultQuestionCount() : 5,
                null, null, null));

        new Thread(() -> startMultiplayerGame(roomName)).start();
        return true;
    }

    public boolean joinRoom(String roomName, String username) {
        List<String> players = gameRooms.get(roomName);
        if (players == null) return false;
        if (roomInProgress.getOrDefault(roomName, false)) return false;
        if (config != null && players.size() >= config.getMaxPlayers()) return false;
        if (players.contains(username)) return false;
        players.add(username);
        return true;
    }

    public boolean leaveRoom(String roomName, String username) {
        List<String> players = gameRooms.get(roomName);
        if (players == null) return false;
        players.remove(username);
        if (players.isEmpty()) {
            gameRooms.remove(roomName);
            roomHosts.remove(roomName);
            roomInProgress.remove(roomName);
            roomSetups.remove(roomName);
            roomAcceptingAnswers.remove(roomName);
            roomAnswers.remove(roomName);
            roomScores.remove(roomName);
            roomAnswerDetails.remove(roomName);
        } else if (username.equals(roomHosts.get(roomName))) {
            roomHosts.put(roomName, players.get(0));
            broadcastToRoom(roomName, "Host left. New host: " + players.get(0));
        }
        return true;
    }

    public Map<String, List<String>> getGameRooms() { return gameRooms; }
    public String  getRoomHost(String roomName) { return roomHosts.get(roomName); }
    public boolean isRoomInProgress(String roomName)  { return roomInProgress.getOrDefault(roomName, false); }
    public void    setRoomInProgress(String roomName, boolean v) { roomInProgress.put(roomName, v); }

    public void broadcastToRoom(String roomName, String message) {
        List<String> players = gameRooms.get(roomName);
        if (players == null) return;
        for (String p : players) {
            ClientHandler h = connectedClients.get(p);
            if (h != null) h.sendMessage(message);
        }
    }

    public void broadcastToRoomExcept(String roomName, String message, String except) {
        List<String> players = gameRooms.get(roomName);
        if (players == null) return;
        for (String p : players) {
            if (!p.equals(except)) {
                ClientHandler h = connectedClients.get(p);
                if (h != null) h.sendMessage(message);
            }
        }
    }

    public boolean configureRoomGame(String roomName, String category, String difficulty, int questionCount,
                                     String teamAName, String teamBName, Map<String, String> teamByUser) {
        List<String> players = gameRooms.get(roomName);
        if (players == null || players.isEmpty()) return false;
        if (isPublicRoom(roomName)) return false;

        String selectedCategory = (category == null || category.trim().isEmpty()) ? "any" : category.trim();
        String selectedDifficulty = (difficulty == null || difficulty.trim().isEmpty()) ? "any" : difficulty.trim().toLowerCase();

        int maxCount = questions.size();
        int safeCount = Math.max(1, Math.min(questionCount, maxCount == 0 ? 1 : maxCount));

        if (teamByUser != null && !teamByUser.isEmpty()) {
            if (teamAName == null || teamBName == null) return false;
            if (teamAName.trim().equalsIgnoreCase(teamBName.trim())) return false;
            if (players.size() < 2 || players.size() % 2 != 0) return false;

            int maxPerTeam = config != null
                    ? Math.max(1, config.getMaxPlayers() / 2)
                    : Math.max(1, players.size() / 2);

            int aSize = 0;
            int bSize = 0;
            for (String p : players) {
                String t = teamByUser.get(p);
                if (t == null) return false;
                if (t.equalsIgnoreCase(teamAName.trim())) aSize++;
                else if (t.equalsIgnoreCase(teamBName.trim())) bSize++;
                else return false;
            }
            if (aSize == 0 || bSize == 0 || aSize != bSize) return false;
            if (aSize > maxPerTeam || bSize > maxPerTeam) return false;
        }

        roomSetups.put(roomName, new RoomSetup(selectedCategory, selectedDifficulty, safeCount,
                teamAName != null ? teamAName.trim() : null,
                teamBName != null ? teamBName.trim() : null,
                teamByUser));
        return true;
    }

    public Set<String> getAvailableCategories() {
        return questions.stream()
                .map(Question::getCategory)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public void startMultiplayerGame(String roomName) {
        List<String> roomPlayers = gameRooms.get(roomName);
        if (roomPlayers == null || roomPlayers.isEmpty()) return;

        synchronized (this) {
            if (roomInProgress.getOrDefault(roomName, false)) return;
            roomInProgress.put(roomName, true);
        }

        List<String> players = new ArrayList<>(roomPlayers);

        RoomSetup setup = roomSetups.getOrDefault(roomName,
                new RoomSetup("any", "any", config != null ? config.getDefaultQuestionCount() : 5,
                        null, null, null));

        Map<String, Integer> scores = new ConcurrentHashMap<>();
        for (String p : players) scores.put(p, 0);
        roomScores.put(roomName, scores);

        Map<String, List<String>> answerDetails = new ConcurrentHashMap<>();
        for (String p : players) answerDetails.put(p, new CopyOnWriteArrayList<>());
        roomAnswerDetails.put(roomName, answerDetails);

        int qCount = setup.questionCount;
        int qTime  = config != null ? config.getQuestionTime()         : 15;
        List<Integer> warnings = config != null ? config.getWarningTimes() : Arrays.asList(10, 5);

        List<Question> gameQuestions = fetchQuestionsForGame(setup.category, setup.difficulty, qCount);
        if (gameQuestions.isEmpty()) {
            broadcastToRoom(roomName, "No questions available for selected criteria.");
            roomAcceptingAnswers.remove(roomName);
            roomAnswers.remove(roomName);
            roomScores.remove(roomName);
            roomAnswerDetails.remove(roomName);
            broadcastToRoom(roomName, "Returning to lobby...");
            setRoomInProgress(roomName, false);
            return;
        }

        Map<String, Integer> teamScores = new ConcurrentHashMap<>();
        if (setup.isTeamMode()) {
            teamScores.put(setup.teamAName, 0);
            teamScores.put(setup.teamBName, 0);
            broadcastToRoom(roomName, "Team mode ON: " + setup.teamAName + " vs " + setup.teamBName);
        }

        broadcastToRoom(roomName, "=== GAME STARTING! " + gameQuestions.size() + " questions ===");
        broadcastToRoom(roomName, "Category: " + setup.category + " | Difficulty: " + setup.difficulty);

        try {
            for (int i = 0; i < gameQuestions.size(); i++) {
                Question q = gameQuestions.get(i);
                String normalizedCorrect = normalizeAnswerToken(q.getCorrectAnswer());

                Map<String, String> answers = new ConcurrentHashMap<>();
                roomAnswers.put(roomName, answers);
                roomAcceptingAnswers.put(roomName, true);

                broadcastToRoom(roomName, "--- Question " + (i + 1) + "/" + gameQuestions.size()
                        + " [" + q.getDifficultyLevel() + "] ---");
                broadcastToRoom(roomName, q.getText());
                for (String choice : q.getChoices()) broadcastToRoom(roomName, choice);
                broadcastToRoom(roomName, "You have " + qTime + " seconds. Enter A/B/C/D:");

                boolean closedByCorrectAnswer = waitForQuestionWindow(
                        roomName,
                        answers,
                        players.size(),
                        qTime,
                        warnings,
                        normalizedCorrect
                );
                roomAcceptingAnswers.put(roomName, false);
                if (closedByCorrectAnswer) {
                    broadcastToRoom(roomName, "A correct answer was received. Closing this question now.");
                }

                
                for (String p : players) {
                    answers.putIfAbsent(p, "");
                }

               
                broadcastToRoom(roomName, "--- Time's up! Correct answer: " + normalizedCorrect + " ---");
                for (String p : players) {
                    String ans = normalizeAnswerToken(answers.getOrDefault(p, ""));
                    boolean correct = !ans.isEmpty() && ans.equalsIgnoreCase(normalizedCorrect);
                    if (correct) {
                        scores.merge(p, 10, Integer::sum);
                        if (setup.isTeamMode()) {
                            String team = setup.teamByUser.get(p);
                            if (team != null) teamScores.merge(team, 10, Integer::sum);
                        }
                        broadcastToRoom(roomName, p + ": CORRECT (+10)");
                        answerDetails.get(p).add("Q" + (i + 1) + ": correct (" + ans + ")");
                    } else {
                        String display = ans.isEmpty() ? "no answer" : ans;
                        broadcastToRoom(roomName, p + ": WRONG (" + display + ")");
                        answerDetails.get(p).add("Q" + (i + 1) + ": wrong (" + display + "), correct=" + normalizedCorrect);
                    }
                }

                
                broadcastToRoom(roomName, "-- Scores --");
                scores.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> broadcastToRoom(roomName, e.getKey() + ": " + e.getValue()));
                if (setup.isTeamMode()) {
                    broadcastToRoom(roomName, "-- Team Scores --");
                    broadcastToRoom(roomName, setup.teamAName + ": " + teamScores.getOrDefault(setup.teamAName, 0));
                    broadcastToRoom(roomName, setup.teamBName + ": " + teamScores.getOrDefault(setup.teamBName, 0));
                }

                if (i < gameQuestions.size() - 1) {
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                }
            }

       
            broadcastToRoom(roomName, "=== GAME OVER ===");
            int topScore = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<String> topPlayers = scores.entrySet().stream()
                    .filter(e -> e.getValue() == topScore)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .collect(Collectors.toList());

            if (setup.isTeamMode()) {
                int a = teamScores.getOrDefault(setup.teamAName, 0);
                int b = teamScores.getOrDefault(setup.teamBName, 0);
                if (a == b) broadcastToRoom(roomName, "Team result: draw (" + a + " - " + b + ")");
                else if (a > b) broadcastToRoom(roomName, "Team winner: " + setup.teamAName + " (" + a + " - " + b + ")");
                else broadcastToRoom(roomName, "Team winner: " + setup.teamBName + " (" + b + " - " + a + ")");
            }

            if (topPlayers.size() == 1) {
                String winner = topPlayers.get(0);
                broadcastToRoom(roomName, "Top player: " + winner + " with " + topScore + " points!");
            } else {
                broadcastToRoom(roomName, "Top player result: draw at " + topScore + " points between "
                        + String.join(", ", topPlayers));
            }
            broadcastToRoom(roomName, "=== PLAYER DETAILS ===");
            for (String p : players) {
                broadcastToRoom(roomName, p + " -> " + scores.getOrDefault(p, 0) + " pts");
                List<String> details = answerDetails.getOrDefault(p, Collections.emptyList());
                for (String d : details) broadcastToRoom(roomName, "  " + d);
            }

            
            Date now = new Date();
            for (String p : players) {
                User u = users.get(p);
                if (u != null) {
                    int s = scores.getOrDefault(p, 0);
                    this.scores.add(new ScoreEntry(u, s, now, "multiplayer",
                            gameQuestions.size(), s / 10));
                }
            }
            jsonLoader.saveScores(this.scores);
        } finally {
            setRoomInProgress(roomName, false);
            roomAcceptingAnswers.remove(roomName);
            roomAnswers.remove(roomName);
            roomScores.remove(roomName);
            roomAnswerDetails.remove(roomName);

            broadcastToRoom(roomName, "Returning to lobby...");

            if (isPublicRoom(roomName)) {
                gameRooms.remove(roomName);
                roomHosts.remove(roomName);
                roomInProgress.remove(roomName);
                roomSetups.remove(roomName);
            }
        }
    }

    private boolean waitForQuestionWindow(String roomName, Map<String, String> answers, int expectedPlayers,
                                          int questionTimeSeconds, List<Integer> warnings,
                                          String correctAnswer) {
        List<Integer> warningSchedule = (warnings == null ? Collections.<Integer>emptyList() : warnings).stream()
                .filter(w -> w != null && w > 0 && w < questionTimeSeconds)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
        Set<Integer> sentWarnings = new HashSet<>();

        long start = System.currentTimeMillis();
        long limit = questionTimeSeconds * 1000L;
        String normalizedCorrect = normalizeAnswerToken(correctAnswer);

        while ((System.currentTimeMillis() - start) < limit) {
            if (answers.size() >= expectedPlayers) {
                break;
            }
            if (!normalizedCorrect.isEmpty() && hasCorrectAnswer(answers, normalizedCorrect)) {
                return true;
            }

            long elapsedSeconds = (System.currentTimeMillis() - start) / 1000L;
            long remaining = questionTimeSeconds - elapsedSeconds;

            for (int w : warningSchedule) {
                if (remaining <= w && remaining > 0 && !sentWarnings.contains(w)) {
                    sentWarnings.add(w);
                    broadcastToRoom(roomName, "[!] " + w + " seconds remaining!");
                }
            }

            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private boolean hasCorrectAnswer(Map<String, String> answers, String normalizedCorrect) {
        for (String ans : answers.values()) {
            if (normalizeAnswerToken(ans).equalsIgnoreCase(normalizedCorrect)) {
                return true;
            }
        }
        return false;
    }

    String normalizeAnswerToken(String answer) {
        if (answer == null) return "";
        String normalized = answer.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return "";
        if (normalized.matches("[A-D]")) return normalized;
        char first = normalized.charAt(0);
        if (first >= 'A' && first <= 'D') return String.valueOf(first);
        return normalized;
    }

    private List<Question> fetchQuestionsForGame(String category, String difficulty, int count) {
        List<Question> fromLookup = lookupClient.fetchQuestions(category, difficulty, count);
        if (!fromLookup.isEmpty()) {
            return fromLookup;
        }

        List<Question> candidates = getQuestionsByCriteria(category, difficulty);
        if (candidates.isEmpty()) return new ArrayList<>();
        return getRandomQuestionsFrom(candidates, count);
    }

   
    public SubmitResult submitAnswer(String roomName, String username, String answer) {
        if (!isRoomInProgress(roomName)) return SubmitResult.NO_ACTIVE_GAME;
        if (!roomAcceptingAnswers.getOrDefault(roomName, false)) return SubmitResult.QUESTION_CLOSED;
        if (answer == null) return SubmitResult.INVALID_ANSWER;
        List<String> players = gameRooms.get(roomName);
        if (players == null || !players.contains(username)) return SubmitResult.NO_ACTIVE_GAME;

        String normalized = normalizeAnswerToken(answer);
        if (!normalized.matches("[A-D]")) return SubmitResult.INVALID_ANSWER;

        Map<String, String> answers = roomAnswers.get(roomName);
        if (answers == null) return SubmitResult.QUESTION_CLOSED;

        String previous = answers.putIfAbsent(username, normalized);
        if (previous != null) return SubmitResult.DUPLICATE_ANSWER;
        return SubmitResult.ACCEPTED;
    }

    
    public List<Question> getQuestions()                { return questions; }
    public List<Question> getQuestionsByCriteria(String category, String difficulty) {
        String c = category == null ? "any" : category.trim().toLowerCase();
        String d = difficulty == null ? "any" : difficulty.trim().toLowerCase();
        return questions.stream()
                .filter(q -> c.equals("any") || (q.getCategory() != null && q.getCategory().trim().toLowerCase().equals(c)))
                .filter(q -> d.equals("any") || (q.getDifficultyLevel() != null && q.getDifficultyLevel().trim().toLowerCase().equals(d)))
                .collect(Collectors.toList());
    }

    public List<Question> getRandomQuestionsFrom(List<Question> source, int count) {
        List<Question> shuffled = new ArrayList<>(source);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    public Config          getConfig()  { return config; }
    public List<ScoreEntry> getScores() { return scores; }

    public void addScore(ScoreEntry entry) {
        scores.add(entry);
        jsonLoader.saveScores(scores);
    }

}
