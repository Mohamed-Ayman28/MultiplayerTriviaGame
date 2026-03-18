package server.core;

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
    private List<ScoreEntry> scores;
    private Config config;

    private Map<String, ClientHandler> connectedClients;
    private Map<String, GameRoom> gameRooms;
    private AtomicInteger publicRoomCounter;

    public static class GameRoom {
        public List<String> players;
        public String host;
        public boolean inProgress;
        public Map<String, Integer> scores;
        public Map<String, String> answers;
        public boolean acceptingAnswers;
        public RoomSetup setup;
        public Map<String, List<String>> answerDetails;

        public GameRoom() {
            this.players = new CopyOnWriteArrayList<>();
            this.host = null;
            this.inProgress = false;
            this.scores = new ConcurrentHashMap<>();
            this.answers = new ConcurrentHashMap<>();
            this.acceptingAnswers = false;
            this.setup = null;
            this.answerDetails = new ConcurrentHashMap<>();
        }
    }

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
        gameRooms = new ConcurrentHashMap<>();
        publicRoomCounter = new AtomicInteger(1);
        loadData();
        String lookupHost = config != null ? config.getLookupHost() : "localhost";
        int lookupPort = config != null ? config.getLookupPort() : 6000;
        lookupClient = new LookupClient(lookupHost, lookupPort);
    }

    private void loadData() {
        System.out.println("Loading server data...");
        users = jsonLoader.loadUsers();
        scores = jsonLoader.loadScores();
        config = jsonLoader.loadConfig();

        if (scores == null) scores = new ArrayList<>();

        System.out.println("Users loaded: "   + users.size());
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
                .filter(e -> e.getValue().players.contains(username))
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
        GameRoom room = new GameRoom();
        room.players.add(hostUsername);
        room.host = hostUsername;
        room.inProgress = false;
        int defaultCount = config != null ? config.getDefaultQuestionCount() : 5;
        room.setup = new RoomSetup("any", "any", defaultCount, null, null, null);
        gameRooms.put(roomName, room);
        System.out.println("Room created: '" + roomName + "' by " + hostUsername);
        return true;
    }

    public String joinOrCreatePublicRoom(String username) {
        for (Map.Entry<String, GameRoom> entry : gameRooms.entrySet()) {
            String roomName = entry.getKey();
            GameRoom room = entry.getValue();
            if (!roomName.startsWith(PUBLIC_ROOM_PREFIX)) continue;
            if (room.inProgress) continue;
            if (room.players.contains(username)) return roomName;
            if (config != null && room.players.size() >= config.getMaxPlayers()) continue;
            room.players.add(username);
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
        GameRoom room = gameRooms.get(roomName);
        if (room == null || room.inProgress) return false;

        int minPlayers = config != null ? config.getMinPlayers() : 2;
        if (room.players.size() < minPlayers) return false;

        room.setup = new RoomSetup("any", "any",
                config != null ? config.getDefaultQuestionCount() : 5,
                null, null, null);

        new Thread(() -> startMultiplayerGame(roomName)).start();
        return true;
    }

    public boolean joinRoom(String roomName, String username) {
        GameRoom room = gameRooms.get(roomName);
        if (room == null) return false;
        if (room.inProgress) return false;
        if (config != null && room.players.size() >= config.getMaxPlayers()) return false;
        if (room.players.contains(username)) return false;
        room.players.add(username);
        return true;
    }

    public boolean leaveRoom(String roomName, String username) {
        GameRoom room = gameRooms.get(roomName);
        if (room == null) return false;
        room.players.remove(username);
        if (room.players.isEmpty()) {
            deleteRoom(roomName);
        } else if (username.equals(room.host)) {
            room.host = room.players.get(0);
            broadcastToRoom(roomName, "Host left. New host: " + room.players.get(0));
        }
        return true;
    }

    private void deleteRoom(String roomName) {
        gameRooms.remove(roomName);
    }

    private void broadcastQuestion(String roomName, Question q, int questionIndex, int totalQuestions, int timeSeconds) {
        broadcastToRoom(roomName, "--- Question " + questionIndex + "/" + totalQuestions
                + " [" + q.getDifficultyLevel() + "] ---");
        broadcastToRoom(roomName, q.getText());
        for (String choice : q.getChoices()) broadcastToRoom(roomName, choice);
        broadcastToRoom(roomName, "You have " + timeSeconds + " seconds. Enter A/B/C/D:");
    }

    private boolean isAnswerCorrect(String userAnswer, String normalizedCorrectAnswer) {
        String normalizedUser = normalizeAnswerToken(userAnswer);
        return !normalizedUser.isEmpty() && normalizedUser.equalsIgnoreCase(normalizedCorrectAnswer);
    }

    private void updatePlayerScore(GameRoom room, RoomSetup setup, String player, int points, Map<String, Integer> teamScores) {
        room.scores.merge(player, points, Integer::sum);
        if (setup.isTeamMode()) {
            String team = setup.teamByUser.get(player);
            if (team != null) teamScores.merge(team, points, Integer::sum);
        }
    }

    public Map<String, GameRoom> getGameRooms() { return gameRooms; }
    public String  getRoomHost(String roomName) { 
        GameRoom room = gameRooms.get(roomName);
        return room != null ? room.host : null;
    }
    public boolean isRoomInProgress(String roomName) { 
        GameRoom room = gameRooms.get(roomName);
        return room != null && room.inProgress;
    }
    public void    setRoomInProgress(String roomName, boolean v) { 
        GameRoom room = gameRooms.get(roomName);
        if (room != null) room.inProgress = v;
    }

    public void broadcastToRoom(String roomName, String message) {
        GameRoom room = gameRooms.get(roomName);
        if (room == null) return;
        for (String p : room.players) {
            ClientHandler h = connectedClients.get(p);
            if (h != null) h.sendMessage(message);
        }
    }

    public void broadcastToRoomExcept(String roomName, String message, String except) {
        GameRoom room = gameRooms.get(roomName);
        if (room == null) return;
        for (String p : room.players) {
            if (!p.equals(except)) {
                ClientHandler h = connectedClients.get(p);
                if (h != null) h.sendMessage(message);
            }
        }
    }

    public boolean configureRoomGame(String roomName, String category, String difficulty, int questionCount,
                                     String teamAName, String teamBName, Map<String, String> teamByUser) {
        GameRoom room = gameRooms.get(roomName);
        if (room == null || room.players.isEmpty()) return false;
        if (isPublicRoom(roomName)) return false;

        String selectedCategory = (category == null || category.trim().isEmpty()) ? "any" : category.trim();
        String selectedDifficulty = (difficulty == null || difficulty.trim().isEmpty()) ? "any" : difficulty.trim().toLowerCase();
        int safeCount = Math.max(1, questionCount);

        if (teamByUser != null && !teamByUser.isEmpty()) {
            if (teamAName == null || teamBName == null) return false;
            if (teamAName.trim().equalsIgnoreCase(teamBName.trim())) return false;
            if (room.players.size() < 2 || room.players.size() % 2 != 0) return false;

            int maxPerTeam = config != null
                    ? Math.max(1, config.getMaxPlayers() / 2)
                    : Math.max(1, room.players.size() / 2);

            int aSize = 0;
            int bSize = 0;
            for (String p : room.players) {
                String t = teamByUser.get(p);
                if (t == null) return false;
                if (t.equalsIgnoreCase(teamAName.trim())) aSize++;
                else if (t.equalsIgnoreCase(teamBName.trim())) bSize++;
                else return false;
            }
            if (aSize == 0 || bSize == 0 || aSize != bSize) return false;
            if (aSize > maxPerTeam || bSize > maxPerTeam) return false;
        }

        room.setup = new RoomSetup(selectedCategory, selectedDifficulty, safeCount,
                teamAName != null ? teamAName.trim() : null,
                teamBName != null ? teamBName.trim() : null,
                teamByUser);
        return true;
    }

    public Set<String> getAvailableCategories() {
        Set<String> categories = new TreeSet<>();
        categories.add("any");
        categories.addAll(lookupClient.fetchAvailableCategories());
        return categories;
    }

    public void startMultiplayerGame(String roomName) {
        GameRoom room = gameRooms.get(roomName);
        if (room == null || room.players.isEmpty()) return;

        synchronized (this) {
            if (room.inProgress) return;
            room.inProgress = true;
        }

        List<String> players = new ArrayList<>(room.players);

        RoomSetup setup = room.setup;
        if (setup == null) {
            setup = new RoomSetup("any", "any", config != null ? config.getDefaultQuestionCount() : 5,
                    null, null, null);
            room.setup = setup;
        }

        for (String p : players) room.scores.put(p, 0);

        for (String p : players) room.answerDetails.put(p, new CopyOnWriteArrayList<>());

        int qCount = setup.questionCount;
        int qTime  = config != null ? config.getQuestionTime()         : 15;
        List<Integer> warnings = config != null ? config.getWarningTimes() : Arrays.asList(10, 5);

        List<Question> gameQuestions = fetchQuestionsForGame(setup.category, setup.difficulty, qCount);
        if (gameQuestions.isEmpty()) {
            broadcastToRoom(roomName, "No questions available for selected criteria.");
            room.acceptingAnswers = false;
            room.answers.clear();
            room.scores.clear();
            room.answerDetails.clear();
            broadcastToRoom(roomName, "Returning to lobby...");
            room.inProgress = false;
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
                room.answers = answers;
                room.acceptingAnswers = true;

                broadcastQuestion(roomName, q, (i + 1), gameQuestions.size(), qTime);

                boolean closedByCorrectAnswer = waitForQuestionWindow(
                        roomName,
                        answers,
                        players.size(),
                        qTime,
                        warnings,
                        normalizedCorrect
                );
                room.acceptingAnswers = false;
                if (closedByCorrectAnswer) {
                    broadcastToRoom(roomName, "A correct answer was received. Closing this question now.");
                }

                
                for (String p : players) {
                    answers.putIfAbsent(p, "");
                }

               
                broadcastToRoom(roomName, "--- Time's up! Correct answer: " + normalizedCorrect + " ---");
                for (String p : players) {
                    String ans = normalizeAnswerToken(answers.getOrDefault(p, ""));
                    boolean correct = isAnswerCorrect(ans, normalizedCorrect);
                    if (correct) {
                        updatePlayerScore(room, setup, p, 10, teamScores);
                        broadcastToRoom(roomName, p + ": CORRECT (+10)");
                        room.answerDetails.get(p).add("Q" + (i + 1) + ": correct (" + ans + ")");
                    } else {
                        String display = ans.isEmpty() ? "no answer" : ans;
                        broadcastToRoom(roomName, p + ": WRONG (" + display + ")");
                        room.answerDetails.get(p).add("Q" + (i + 1) + ": wrong (" + display + "), correct=" + normalizedCorrect);
                    }
                }

                
                broadcastToRoom(roomName, "-- Scores --");
                room.scores.entrySet().stream()
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
            int topScore = room.scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            List<String> topPlayers = room.scores.entrySet().stream()
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
                broadcastToRoom(roomName, p + " -> " + room.scores.getOrDefault(p, 0) + " pts");
                List<String> details = room.answerDetails.getOrDefault(p, Collections.emptyList());
                for (String d : details) broadcastToRoom(roomName, "  " + d);
            }

            
            Date now = new Date();
            for (String p : players) {
                User u = users.get(p);
                if (u != null) {
                    int s = room.scores.getOrDefault(p, 0);
                    this.scores.add(new ScoreEntry(u, s, now, "multiplayer",
                            gameQuestions.size(), s / 10));
                }
            }
            jsonLoader.saveScores(this.scores);
        } finally {
            room.inProgress = false;
            room.acceptingAnswers = false;
            room.answers.clear();
            room.scores.clear();
            room.answerDetails.clear();

            broadcastToRoom(roomName, "Returning to lobby...");

            if (isPublicRoom(roomName)) {
                deleteRoom(roomName);
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

    public String normalizeAnswerToken(String answer) {
        if (answer == null) return "";
        String normalized = answer.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return "";
        if (normalized.matches("[A-D]")) return normalized;
        char first = normalized.charAt(0);
        if (first >= 'A' && first <= 'D') return String.valueOf(first);
        return normalized;
    }

    public List<Question> fetchQuestionsForGame(String category, String difficulty, int count) {
        return lookupClient.fetchQuestions(category, difficulty, count);
    }

   
    public SubmitResult submitAnswer(String roomName, String username, String answer) {
        GameRoom room = gameRooms.get(roomName);
        if (room == null || !room.inProgress) return SubmitResult.NO_ACTIVE_GAME;
        if (!room.acceptingAnswers) return SubmitResult.QUESTION_CLOSED;
        if (answer == null) return SubmitResult.INVALID_ANSWER;
        if (!room.players.contains(username)) return SubmitResult.NO_ACTIVE_GAME;

        String normalized = normalizeAnswerToken(answer);
        if (!normalized.matches("[A-D]")) return SubmitResult.INVALID_ANSWER;

        String previous = room.answers.putIfAbsent(username, normalized);
        if (previous != null) return SubmitResult.DUPLICATE_ANSWER;
        return SubmitResult.ACCEPTED;
    }

    
    public Config          getConfig()  { return config; }
    public List<ScoreEntry> getScores() { return scores; }

    public void addScore(ScoreEntry entry) {
        scores.add(entry);
        jsonLoader.saveScores(scores);
    }

}
