package server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import models.ScoreEntry;
import models.User;

class AdminController {

    private final GameServer server;
    private final Supplier<String> usernameSupplier;
    private final Consumer<String> sendMessage;
    private final ClientIO.LineReader readLineAllowQuit;
    private final Runnable showMenu;

    AdminController(
            GameServer server,
            Supplier<String> usernameSupplier,
            Consumer<String> sendMessage,
            ClientIO.LineReader readLineAllowQuit,
            Runnable showMenu
    ) {
        this.server = server;
        this.usernameSupplier = usernameSupplier;
        this.sendMessage = sendMessage;
        this.readLineAllowQuit = readLineAllowQuit;
        this.showMenu = showMenu;
    }

    void handleAdmin() throws IOException {
        sendMessage.accept("=== ADMIN PANEL ===");
        sendMessage.accept("[1] View All Scores");
        sendMessage.accept("[2] Kick Player");
        sendMessage.accept("[0] Back");
        sendMessage.accept("Choose:");

        String input = readLineAllowQuit.readLine();
        if (input == null) {
            showMenu.run();
            return;
        }
        switch (input.trim()) {
            case "1":
                adminViewScores();
                break;
            case "2":
                adminKickPlayer();
                break;
            case "0":
                showMenu.run();
                break;
            default:
                handleAdmin();
                break;
        }
    }

    private void adminViewScores() {
        List<ScoreEntry> all = server.getScores();
        if (all.isEmpty()) {
            sendMessage.accept("No scores.");
        } else {
            sendMessage.accept("--- All Scores ---");
            for (ScoreEntry e : all) {
                sendMessage.accept(e.getUser().getUsername() + " | " + e.getScore()
                        + " | " + e.getGametype()
                        + " | " + e.getCorrectAnswers() + "/" + e.getTotalQuestions());
            }
        }

        sendMessage.accept("--- Stats ---");
        long connectedNonAdminPlayers = server.getConnectedClients().keySet().stream()
                .filter(name -> {
                    User user = server.getUsers().get(name);
                    return user != null && !user.isAdmin();
                })
                .count();
        sendMessage.accept("Total players connected: " + connectedNonAdminPlayers);

        int totalQuestionsPlayed = all.stream().mapToInt(ScoreEntry::getTotalQuestions).sum();
        int highestScore = all.stream().mapToInt(ScoreEntry::getScore).max().orElse(0);
        sendMessage.accept("Total questions played: " + totalQuestionsPlayed);
        sendMessage.accept("Highest score ever recorded: " + highestScore);

        Map<String, List<ScoreEntry>> multiplayerByRound = new HashMap<>();
        for (ScoreEntry e : all) {
            if (e.getUser() == null || e.getDate() == null) {
                continue;
            }
            if (!"multiplayer".equalsIgnoreCase(e.getGametype())) {
                continue;
            }
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
        sendMessage.accept("Player with the most wins: " + top + ("N/A".equals(top) ? "" : " (" + topWins + " wins)"));

        showMenu.run();
    }

    private void adminKickPlayer() throws IOException {
        String currentUsername = usernameSupplier.get();
        Map<String, ClientHandler> clients = server.getConnectedClients();
        sendMessage.accept("--- Online Players ---");
        for (String u : clients.keySet()) {
            if (!u.equals(currentUsername)) {
                sendMessage.accept("  " + u);
            }
        }
        sendMessage.accept("Enter username to kick:");
        String target = readLineAllowQuit.readLine();
        if (target == null || target.trim().isEmpty()) {
            handleAdmin();
            return;
        }
        target = target.trim();

        ClientHandler handler = clients.get(target);
        if (handler == null) {
            sendMessage.accept("Player not found.");
        } else {
            handler.sendMessage("You have been kicked by admin.");
            handler.disconnect();
            sendMessage.accept(target + " has been kicked.");
        }
        handleAdmin();
    }
}