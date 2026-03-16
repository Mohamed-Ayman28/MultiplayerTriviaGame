package server;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import models.*;

public class ClientHandler implements Runnable {

    private static class ClientQuitException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private final Socket   socket;
    private final GameServer server;
    private BufferedReader in;
    private PrintWriter out;
    private String  username;
    private String  currentRoom; 

    public ClientHandler(Socket socket, GameServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);

            sendMessage("Welcome to Trivia Game!");
            sendMessage("Type [login] or [register]:");

            String input;
            while ((input = in.readLine()) != null) {
                input = input.trim();
                if (input.equals("-")) {
                    sendMessage("Goodbye!");
                    break;
                }
                if (username == null) {
                    handleAuth(input);
                } else {
                    handleMenu(input);
                }
            }
        } catch (ClientQuitException e) {
            sendMessage("Goodbye!");
        } catch (IOException e) {
            System.err.println("Client disconnected: " +
                (username != null ? username : socket.getInetAddress()));
        } finally {
            cleanup();
        }
    }
///////////////////////////////////////////////////////////////////////////////////////////////////////
    // -- Auth --//
    private void handleAuth(String input) throws IOException {
        if (input.equalsIgnoreCase("login")) {
            sendMessage("Enter username:");
            String uname = readLineAllowQuit();
            sendMessage("Enter password:");
            String pass  = readLineAllowQuit();

            if (!server.getUsers().containsKey(uname)) {
                sendMessage("ERROR 404: Username not found.");
            } else if (!server.authenticateUser(uname, pass)) {
                sendMessage("ERROR 401: Wrong password.");
            } 
            else {
                username = uname;
                server.addClient(username, this);
                sendMessage("Login successfuly! Welcome , " + username);
                showMenu();
            }

        } else if (input.equalsIgnoreCase("register")) {
            sendMessage("Enter name:");
            String name  = readLineAllowQuit();
            sendMessage("Enter username:");
            String uname = readLineAllowQuit();
            sendMessage("Enter password:");
            String pass  = readLineAllowQuit();

            if (server.getUsers().containsKey(uname)) {
                sendMessage("ERROR 409: Username already taken please choose another username.");
            } else {
                server.registerUser(name, uname, pass);
                sendMessage("Registered successfully! Please login.");
            }
        } else {
            sendMessage("Invalid option. Type [login] or [register]:");
        }
    }
////////////////////////////////////////////////////////////////////////////////////////////////////////
    // --Menu--//
    private void showMenu() {
        sendMessage("=== MAIN MENU ===");
        sendMessage("[1] Single Player");
        sendMessage("[2] Random Trivia");
        sendMessage("[3] Multiplayer");
        sendMessage("[4] Leaderboard");
        User u = server.getUsers().get(username);
        if (u != null && u.isAdmin()) sendMessage("[5] Admin Panel");
        sendMessage("[-] Quit");
        sendMessage("Choose:");
    }

    private void handleMenu(String input) throws IOException {
        switch (input) {
            case "1": handleSinglePlayer();  break;
            case "2": handleRandomTrivia();  break;
            case "3": handleMultiplayer();   break;
            case "4": showLeaderboard();     break;
            case "5":
                User u = server.getUsers().get(username);
                if (u != null && u.isAdmin()) handleAdmin();
                else sendMessage("Access denied.");
                break;
            default:
                showMenu();
        }
    }


    private void handleSinglePlayer() throws IOException {
        sendMessage("Enter category (or 'any'). Available: " + String.join(", ", server.getAvailableCategories()));
        String category = readLineAllowQuit();
        if (category == null || category.trim().equals("-")) {
            showMenu();
            return;
        }

        sendMessage("Enter difficulty (easy/medium/hard or 'any'):");
        String difficulty = readLineAllowQuit();
        if (difficulty == null || difficulty.trim().equals("-")) {
            showMenu();
            return;
        }

        playSinglePlayer(category.trim(), difficulty.trim(), "singleplayer", "=== SINGLE PLAYER ===");
    }

    private void handleRandomTrivia() throws IOException {
        playSinglePlayer("any", "any", "random-trivia", "=== RANDOM TRIVIA ===");
    }

    private void playSinglePlayer(String category, String difficulty, String gameType, String title) throws IOException {
        int qTime  = server.getConfig() != null ? server.getConfig().getQuestionTime()         : 15;
        int qCount = server.getConfig() != null ? server.getConfig().getDefaultQuestionCount() : 5;
        List<Integer> warnings = server.getConfig() != null
                ? server.getConfig().getWarningTimes() : Arrays.asList(10, 5);

        List<Question> filtered = server.getQuestionsByCriteria(category, difficulty);
        List<Question> gameQuestions = server.getRandomQuestionsFrom(filtered, qCount);
        if (gameQuestions.isEmpty()) {
            sendMessage("No questions available.");
            showMenu();
            return;
        }

        sendMessage(title);
        sendMessage("Category: " + category + " | Difficulty: " + difficulty);
        sendMessage("You have " + qTime + " seconds per question. " + gameQuestions.size() + " questions total.");

        int score   = 0;
        int correct = 0;

        for (int i = 0; i < gameQuestions.size(); i++) {
            Question q = gameQuestions.get(i);
            sendMessage("--- Question " + (i + 1) + "/" + gameQuestions.size()
                    + " [" + q.getDifficultyLevel() + "] ---");
            sendMessage(q.getText());
            for (String choice : q.getChoices()) sendMessage(choice);
            sendMessage("You have " + qTime + " seconds. Enter A/B/C/D:");

            // Timed read
            String answer = timedRead(qTime * 1000L, warnings, qTime);

            if ("-".equals(answer != null ? answer.trim() : null)) {
                throw new ClientQuitException();
            }

            if (answer == null || answer.isEmpty()) {
                sendMessage("Time's up! No answer given.");
            } else if (answer.equalsIgnoreCase(q.getCorrectAnswer())) {
                sendMessage("CORRECT! +10 points");
                score   += 10;
                correct += 1;
            } else {
                sendMessage("WRONG! Correct answer was: " + q.getCorrectAnswer());
            }
        }

        sendMessage("=== GAME OVER ===");
        sendMessage("Score: " + score + " | Correct: " + correct + "/" + gameQuestions.size());

        User u = server.getUsers().get(username);
        if (u != null) {
            server.addScore(new ScoreEntry(u, score, new Date(), gameType,
                    gameQuestions.size(), correct));
        }

        showMenu();
    }

    private String timedRead(long timeoutMs, List<Integer> warnings, int totalSecs) {
        final String[] result = {null};
        final boolean[] done  = {false};

        Thread reader = new Thread(() -> {
            try {
                result[0] = in.readLine();
            } catch (IOException ignored) {}
            done[0] = true;
        });
        reader.setDaemon(true);
        reader.start();

        long start = System.currentTimeMillis();

       
        for (int w : warnings) {
            long warnAt = timeoutMs - (w * 1000L);
            while (!done[0] && (System.currentTimeMillis() - start) < warnAt) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
            if (!done[0]) sendMessage("[!] " + w + " seconds remaining!");
        }

       
        while (!done[0] && (System.currentTimeMillis() - start) < timeoutMs) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        reader.interrupt();
        return result[0];
    }


    private void handleMultiplayer() throws IOException {
        showMultiplayerMenu();
        String input = readLineAllowQuit();
        if (input == null) return;
        if (input.trim().equals("-")) {
            showMenu();
            return;
        }
        switch (input.trim()) {
            case "1": createRoom();  break;
            case "2": joinRoom();    break;
            case "3": joinPublicRoom(); break;
            case "4": listRooms();   handleMultiplayer(); break;
            case "0": showMenu();    break;
            default:  handleMultiplayer(); break;
        }
    }

    private void showMultiplayerMenu() {
        sendMessage("=== MULTIPLAYER ===");
        sendMessage("[1] Create Room");
        sendMessage("[2] Join Room");
        sendMessage("[3] Join Public Room");
        sendMessage("[4] List Rooms");
        sendMessage("[0] Back");
        sendMessage("Choose:");
    }

    private void joinPublicRoom() throws IOException {
        currentRoom = server.joinOrCreatePublicRoom(username);
        server.broadcastToRoomExcept(currentRoom, username + " joined the public room.", username);
        sendMessage("Joined public room '" + currentRoom + "'. Waiting for auto-start...");
        server.tryAutoStartPublicRoom(currentRoom);
        handleRoomLobby();
    }

    private void createRoom() throws IOException {
        sendMessage("Enter room name:");
        String roomName = readLineAllowQuit();
        if (roomName == null || roomName.trim().isEmpty()) { showMenu(); return; }
        roomName = roomName.trim();

        if (!server.createRoom(roomName, username)) {
            sendMessage("Room '" + roomName + "' already exists.");
            handleMultiplayer();
            return;
        }

        currentRoom = roomName;
        sendMessage("Room '" + roomName + "' created. You are the host.");
        handleRoomLobby();
    }

    private void joinRoom() throws IOException {
        listRooms();
        sendMessage("Enter room name to join:");
        String roomName = readLineAllowQuit();
        if (roomName == null || roomName.trim().isEmpty()) { showMenu(); return; }
        roomName = roomName.trim();

        if (!server.joinRoom(roomName, username)) {
            sendMessage("Cannot join room '" + roomName + "' (full, in progress, or not found).");
            handleMultiplayer();
            return;
        }

        currentRoom = roomName;
        server.broadcastToRoomExcept(roomName, username + " joined the room.", username);
        sendMessage("Joined room '" + roomName + "'. Waiting for host to start...");
        handleRoomLobby();
    }

    private void listRooms() {
        Map<String, List<String>> rooms = server.getGameRooms();
        if (rooms.isEmpty()) {
            sendMessage("No rooms available.");
            return;
        }
        sendMessage("--- Available Rooms ---");
        for (Map.Entry<String, List<String>> e : rooms.entrySet()) {
            String status = server.isRoomInProgress(e.getKey()) ? "[IN PROGRESS]" : "[WAITING]";
            sendMessage(e.getKey() + " | Players: " + e.getValue().size()
                    + " | Host: " + server.getRoomHost(e.getKey()) + " " + status);
        }
    }

    private void handleRoomLobby() throws IOException {
        if (server.isPublicRoom(currentRoom)) {
            handlePublicRoomLobby();
            return;
        }

        boolean isHost = username.equals(server.getRoomHost(currentRoom));

        if (isHost) {
            sendMessage("=== LOBBY: " + currentRoom + " (you are host) ===");
            sendMessage("[1] Start Game");
            sendMessage("[2] Configure Game (category/difficulty/questions/teams)");
            sendMessage("[3] Show Players");
            sendMessage("[0] Leave Room");
            sendMessage("Choose:");

            String input = readLineAllowQuit();
            if (input == null) { leaveCurrentRoom(); return; }
            switch (input.trim()) {
                case "1":
                    List<String> players = server.getGameRooms().get(currentRoom);
                    int minPlayers = server.getConfig() != null ? server.getConfig().getMinPlayers() : 2;
                    if (players == null || players.size() < minPlayers) {
                        sendMessage("Need at least " + minPlayers + " players to start.");
                        handleRoomLobby();
                    } else {
                        String room = currentRoom;
                        new Thread(() -> server.startMultiplayerGame(room)).start();
                        if (waitForGameToStart(5000)) {
                            waitForGameEnd();
                        } else {
                            sendMessage("Game did not start. Please try again.");
                            handleRoomLobby();
                        }
                    }
                    break;
                case "2":
                    configureRoomGame();
                    break;
                case "3":
                    showCurrentRoomPlayers();
                    handleRoomLobby();
                    break;
                case "0":
                    leaveCurrentRoom();
                    break;
                default:
                    handleRoomLobby();
            }
        } else {
         
            sendMessage("=== LOBBY: " + currentRoom + " ===");
            sendMessage("[0] Leave Room  (or wait for host to start)");

         
            while (true) {
                if (server.isRoomInProgress(currentRoom)) {
                    waitForGameEnd();
                    break;
                }
                String line = tryReadLineWithTimeout(300);
                if (line != null && line.trim().equals("0")) {
                    leaveCurrentRoom();
                    return;
                }
            }
        }
    }

    private void handlePublicRoomLobby() throws IOException {
        sendMessage("=== PUBLIC LOBBY: " + currentRoom + " ===");
        sendMessage("Waiting for enough players. Type [0] to leave.");

        while (true) {
            if (server.isRoomInProgress(currentRoom)) {
                waitForGameEnd();
                return;
            }

            server.tryAutoStartPublicRoom(currentRoom);

            String line = tryReadLineWithTimeout(300);
            if (line != null && line.trim().equals("0")) {
                leaveCurrentRoom();
                return;
            }
        }
    }

    private void waitForGameEnd() throws IOException {
        while (currentRoom != null && server.isRoomInProgress(currentRoom)) {
            String line = tryReadLineWithTimeout(200);
            if (line == null) {
                continue;
            }

            if (line.trim().isEmpty()) {
                continue;
            }

            String ans = normalizeMultiplayerAnswer(line);
            if (!ans.matches("[A-D]")) {
                sendMessage("Invalid answer format. Use only A/B/C/D.");
                continue;
            }

            GameServer.SubmitResult submitResult = server.submitAnswer(currentRoom, username, ans);
            switch (submitResult) {
                case ACCEPTED:
                    sendMessage("Answer locked: " + ans);
                    break;
                case QUESTION_CLOSED:
                    sendMessage("No active question right now. Wait for the next question.");
                    break;
                case DUPLICATE_ANSWER:
                    sendMessage("You already answered this question. First answer is counted.");
                    break;
                case NO_ACTIVE_GAME:
                    sendMessage("No active game in this room.");
                    break;
                case INVALID_ANSWER:
                default:
                    sendMessage("Invalid answer format. Use only A/B/C/D.");
                    break;
            }
        }
        currentRoom = null;
        showMenu();
    }

    private void showCurrentRoomPlayers() {
        List<String> players = server.getGameRooms().get(currentRoom);
        if (players == null || players.isEmpty()) {
            sendMessage("Room is empty.");
            return;
        }
        sendMessage("Players in room " + currentRoom + ":");
        for (String p : players) sendMessage("- " + p);
    }

    private void configureRoomGame() throws IOException {
        List<String> players = server.getGameRooms().get(currentRoom);
        if (players == null || players.isEmpty()) {
            sendMessage("Room not found.");
            showMenu();
            return;
        }

        sendMessage("Enter category (or 'any'). Available: " + String.join(", ", server.getAvailableCategories()));
        String category = readLineAllowQuit();
        if (category == null) category = "any";

        sendMessage("Enter difficulty (easy/medium/hard or 'any'):");
        String difficulty = readLineAllowQuit();
        if (difficulty == null || difficulty.trim().isEmpty()) difficulty = "any";

        int defaultCount = server.getConfig() != null ? server.getConfig().getDefaultQuestionCount() : 5;
        sendMessage("Enter number of questions (default " + defaultCount + "):");
        String qCountStr = readLineAllowQuit();
        int qCount = defaultCount;
        if (qCountStr != null && !qCountStr.trim().isEmpty()) {
            try {
                qCount = Integer.parseInt(qCountStr.trim());
            } catch (NumberFormatException e) {
                sendMessage("Invalid number. Using default " + defaultCount + ".");
                qCount = defaultCount;
            }
        }

        sendMessage("Enable team-vs-team mode? (yes/no)");
        String teamMode = readLineAllowQuit();
        boolean enableTeams = teamMode != null && teamMode.trim().equalsIgnoreCase("yes");

        String teamA = null;
        String teamB = null;
        Map<String, String> teamByUser = null;

        if (enableTeams) {
            if (players.size() < 2) {
                sendMessage("At least 2 players required for teams.");
                handleRoomLobby();
                return;
            }

            sendMessage("Enter Team A name:");
            teamA = readLineAllowQuit();
            sendMessage("Enter Team B name:");
            teamB = readLineAllowQuit();

            if (teamA == null || teamB == null || teamA.trim().isEmpty() || teamB.trim().isEmpty()) {
                sendMessage("Team names are required.");
                handleRoomLobby();
                return;
            }
            if (teamA.trim().equalsIgnoreCase(teamB.trim())) {
                sendMessage("Team names must be unique.");
                handleRoomLobby();
                return;
            }

            teamByUser = new HashMap<>();
            sendMessage("Assign players to teams. Type A or B for each player:");
            for (String p : players) {
                while (true) {
                    sendMessage("Player " + p + " -> team (A/B):");
                    String t = readLineAllowQuit();
                    if (t == null) {
                        sendMessage("Cancelled.");
                        handleRoomLobby();
                        return;
                    }
                    String tt = t.trim().toUpperCase();
                    if (tt.equals("A")) {
                        teamByUser.put(p, teamA.trim());
                        break;
                    }
                    if (tt.equals("B")) {
                        teamByUser.put(p, teamB.trim());
                        break;
                    }
                    sendMessage("Invalid input. Please enter A or B.");
                }
            }
        }

        boolean ok = server.configureRoomGame(currentRoom, category, difficulty, qCount, teamA, teamB, teamByUser);
        if (!ok) {
            sendMessage("Invalid setup. Team mode requires unique team names, an even player count, equal players per team, and valid team assignments.");
        } else {
            sendMessage("Game setup updated successfully.");
            server.broadcastToRoomExcept(currentRoom, "Host updated game setup.", username);
        }
        handleRoomLobby();
    }

    private void leaveCurrentRoom() {
        if (currentRoom != null) {
            server.broadcastToRoomExcept(currentRoom, username + " left the room.", username);
            server.leaveRoom(currentRoom, username);
            sendMessage("You left the room.");
            currentRoom = null;
        }
        showMenu();
    }

   
    private void showLeaderboard() {
        List<ScoreEntry> all = server.getScores();
        if (all.isEmpty()) {
            sendMessage("No scores yet.");
            showMenu();
            return;
        }

      
        List<ScoreEntry> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> b.getScore() - a.getScore());

        sendMessage("=== LEADERBOARD (Top 10) ===");
        int limit = Math.min(10, sorted.size());
        for (int i = 0; i < limit; i++) {
            ScoreEntry e = sorted.get(i);
            sendMessage((i + 1) + ". " + e.getUser().getUsername()
                    + " | " + e.getScore() + " pts"
                    + " | " + e.getGametype()
                    + " | " + e.getCorrectAnswers() + "/" + e.getTotalQuestions());
        }

        sendMessage("=== YOUR LAST GAMES ===");
        List<ScoreEntry> mine = new ArrayList<>();
        for (ScoreEntry e : all) {
            if (e.getUser() != null && username.equals(e.getUser().getUsername())) {
                mine.add(e);
            }
        }
        mine.sort((a, b) -> b.getDate().compareTo(a.getDate()));
        if (mine.isEmpty()) {
            sendMessage("No history for user " + username + ".");
        } else {
            int mineLimit = Math.min(5, mine.size());
            for (int i = 0; i < mineLimit; i++) {
                ScoreEntry e = mine.get(i);
                sendMessage((i + 1) + ". " + e.getGametype()
                        + " | " + e.getScore() + " pts"
                        + " | " + e.getCorrectAnswers() + "/" + e.getTotalQuestions()
                        + " | " + e.getDate());
            }
        }
        showMenu();
    }

    private void handleAdmin() throws IOException {
        sendMessage("=== ADMIN PANEL ===");
        sendMessage("[1] View All Scores");
        sendMessage("[2] Kick Player");
        sendMessage("[0] Back");
        sendMessage("Choose:");

        String input = readLineAllowQuit();
        if (input == null) { showMenu(); return; }
        switch (input.trim()) {
            case "1": adminViewScores(); break;
            case "2": adminKickPlayer(); break;
            case "0": showMenu();            break;
            default:  handleAdmin();         break;
        }
    }

    private void adminAddQuestion() throws IOException {
        sendMessage("Question text:");
        String text = readLineAllowQuit();
        sendMessage("Category:");
        String category = readLineAllowQuit();
        sendMessage("Difficulty (easy/medium/hard):");
        String difficulty = readLineAllowQuit();
        sendMessage("Choice A:");
        String a = readLineAllowQuit();
        sendMessage("Choice B:");
        String b = readLineAllowQuit();
        sendMessage("Choice C:");
        String c = readLineAllowQuit();
        sendMessage("Choice D:");
        String d = readLineAllowQuit();
        sendMessage("Correct answer (A/B/C/D):");
        String correct = readLineAllowQuit();

        if (text == null || correct == null) { sendMessage("Cancelled."); handleAdmin(); return; }

        List<Question> questions = server.getQuestions();
        int newId = questions.stream().mapToInt(Question::getQuestionId).max().orElse(0) + 1;
        questions.add(new Question(newId, text, category, difficulty,
                Arrays.asList("A. " + a, "B. " + b, "C. " + c, "D. " + d),
                correct.toUpperCase()));
        server.saveQuestions();
        sendMessage("Question added (ID=" + newId + ").");
        handleAdmin();
    }

    private void adminRemoveQuestion() throws IOException {
        List<Question> questions = server.getQuestions();
        if (questions.isEmpty()) { sendMessage("No questions."); handleAdmin(); return; }

        sendMessage("--- Questions ---");
        for (Question q : questions) {
            sendMessage("[" + q.getQuestionId() + "] " + q.getText());
        }
        sendMessage("Enter question ID to remove:");
        String idStr = readLineAllowQuit();
        if (idStr == null) { handleAdmin(); return; }

        try {
            int id = Integer.parseInt(idStr.trim());
            boolean removed = questions.removeIf(q -> q.getQuestionId() == id);
            if (removed) {
                server.saveQuestions();
                sendMessage("Question " + id + " removed.");
            } else {
                sendMessage("Question ID not found.");
            }
        } catch (NumberFormatException e) {
            sendMessage("Invalid ID.");
        }
        handleAdmin();
    }

    private void adminViewScores() {
        List<ScoreEntry> all = server.getScores();
        if (all.isEmpty()) {
            sendMessage("No scores.");
        } else {
            sendMessage("--- All Scores ---");
            for (ScoreEntry e : all) {
                sendMessage(e.getUser().getUsername() + " | " + e.getScore()
                        + " | " + e.getGametype()
                        + " | " + e.getCorrectAnswers() + "/" + e.getTotalQuestions());
            }
        }

        sendMessage("--- Stats ---");
        long connectedNonAdminPlayers = server.getConnectedClients().keySet().stream()
                .filter(name -> {
                    User user = server.getUsers().get(name);
                    return user != null && !user.isAdmin();
                })
                .count();
        sendMessage("Total players connected: " + connectedNonAdminPlayers);

        int totalQuestionsPlayed = all.stream().mapToInt(ScoreEntry::getTotalQuestions).sum();
        int highestScore = all.stream().mapToInt(ScoreEntry::getScore).max().orElse(0);
        sendMessage("Total questions played: " + totalQuestionsPlayed);
        sendMessage("Highest score ever recorded: " + highestScore);


        Map<String, List<ScoreEntry>> multiplayerByRound = new HashMap<>();
        for (ScoreEntry e : all) {
            if (e.getUser() == null || e.getDate() == null) continue;
            if (!"multiplayer".equalsIgnoreCase(e.getGametype())) continue;
            String key = String.valueOf(e.getDate().getTime());
            multiplayerByRound.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        Map<String, Integer> wins = new HashMap<>();
        for (List<ScoreEntry> round : multiplayerByRound.values()) {
            int maxScore = round.stream().mapToInt(ScoreEntry::getScore).max().orElse(Integer.MIN_VALUE);
            for (ScoreEntry e : round) {
                if (e.getScore() == maxScore) {
                    String user = e.getUser().getUsername();
                    User player = server.getUsers().get(user);
                    if (player != null && !player.isAdmin()) {
                        wins.merge(user, 1, Integer::sum);
                    }
                }
            }
        }

        String top = wins.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse("N/A");
        int topWins = wins.getOrDefault(top, 0);
        sendMessage("Player with the most wins: " + top + ("N/A".equals(top) ? "" : " (" + topWins + " wins)"));

        showMenu();
    }

    private void adminKickPlayer() throws IOException {
        Map<String, ClientHandler> clients = server.getConnectedClients();
        sendMessage("--- Online Players ---");
        for (String u : clients.keySet()) {
            if (!u.equals(username)) sendMessage("  " + u);
        }
        sendMessage("Enter username to kick:");
        String target = readLineAllowQuit();
        if (target == null || target.trim().isEmpty()) { handleAdmin(); return; }
        target = target.trim();

        ClientHandler handler = clients.get(target);
        if (handler == null) {
            sendMessage("Player not found.");
        } else {
            handler.sendMessage("You have been kicked by admin.");
            handler.disconnect();
            sendMessage(target + " has been kicked.");
        }
        handleAdmin();
    }

    private String readLineAllowQuit() throws IOException {
        String value = in.readLine();
        if (value != null && value.trim().equals("-")) {
            throw new ClientQuitException();
        }
        return value;
    }

    private String tryReadLineWithTimeout(int timeoutMs) throws IOException {
        int previousTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(timeoutMs);
            String value = in.readLine();
            if (value != null && value.trim().equals("-")) {
                throw new ClientQuitException();
            }
            return value;
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
    }

    private String normalizeMultiplayerAnswer(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) return "";
        if (value.matches("[A-D]")) return value;
        char first = value.charAt(0);
        if (first >= 'A' && first <= 'D') return String.valueOf(first);
        return value;
    }

    private boolean waitForGameToStart(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (currentRoom != null && System.currentTimeMillis() < deadline) {
            if (server.isRoomInProgress(currentRoom)) {
                return true;
            }
            tryReadLineWithTimeout(120);
        }
        return currentRoom != null && server.isRoomInProgress(currentRoom);
    }

    public void sendMessage(String message) {
        out.println(message);
    }

    public void disconnect() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    private void cleanup() {
        if (currentRoom != null) {
            server.leaveRoom(currentRoom, username);
        }
        if (username != null) server.removeClient(username);
        try {
            if (in  != null) in.close();
            if (out != null) out.close();
            if (!socket.isClosed()) socket.close();
        } catch (IOException e) {
            System.err.println("Cleanup error: " + e.getMessage());
        }
    }
}
