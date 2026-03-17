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

    void showScoreHistory() {
        String username = usernameSupplier.get();
        List<ScoreEntry> all = server.getScores();

        sendMessage.accept("=== SCORE HISTORY ===");
        List<ScoreEntry> mine = new ArrayList<>();
        for (ScoreEntry e : all) {
            if (e.getUser() != null && username != null && username.equals(e.getUser().getUsername())) {
                mine.add(e);
            }
        }

        mine.sort((a, b) -> {
            if (a.getDate() == null && b.getDate() == null) return 0;
            if (a.getDate() == null) return 1;
            if (b.getDate() == null) return -1;
            return b.getDate().compareTo(a.getDate());
        });

        if (mine.isEmpty()) {
            sendMessage.accept("No history for user " + username + ".");
        } else {
            for (int i = 0; i < mine.size(); i++) {
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