package server;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import models.ScoreEntry;

class LeaderboardController {

    private final GameServer server;
    private final Supplier<String> usernameSupplier;
    private final Consumer<String> sendMessage;
    private final Runnable showMenu;

    LeaderboardController(
            GameServer server,
            Supplier<String> usernameSupplier,
            Consumer<String> sendMessage,
            Runnable showMenu
    ) {
        this.server = server;
        this.usernameSupplier = usernameSupplier;
        this.sendMessage = sendMessage;
        this.showMenu = showMenu;
    }

    void showLeaderboard() {
        String username = usernameSupplier.get();
        List<ScoreEntry> all = server.getScores();
        if (all.isEmpty()) {
            sendMessage.accept("No scores yet.");
            showMenu.run();
            return;
        }

        List<ScoreEntry> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> b.getScore() - a.getScore());

        sendMessage.accept("=== LEADERBOARD (Top 10) ===");
        int limit = Math.min(10, sorted.size());
        for (int i = 0; i < limit; i++) {
            ScoreEntry e = sorted.get(i);
            sendMessage.accept((i + 1) + ". " + e.getUser().getUsername()
                    + " | " + e.getScore() + " pts"
                    + " | " + e.getGametype()
                    + " | " + e.getCorrectAnswers() + "/" + e.getTotalQuestions());
        }

        sendMessage.accept("=== YOUR LAST GAMES ===");
        List<ScoreEntry> mine = new ArrayList<>();
        for (ScoreEntry e : all) {
            if (e.getUser() != null && username != null && username.equals(e.getUser().getUsername())) {
                mine.add(e);
            }
        }
        mine.sort((a, b) -> b.getDate().compareTo(a.getDate()));
        if (mine.isEmpty()) {
            sendMessage.accept("No history for user " + username + ".");
        } else {
            int mineLimit = Math.min(5, mine.size());
            for (int i = 0; i < mineLimit; i++) {
                ScoreEntry e = mine.get(i);
                sendMessage.accept((i + 1) + ". " + e.getGametype()
                        + " | " + e.getScore() + " pts"
                        + " | " + e.getCorrectAnswers() + "/" + e.getTotalQuestions()
                        + " | " + e.getDate());
            }
        }
        showMenu.run();
    }
}