package server.gameplay;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import models.Question;
import models.ScoreEntry;
import models.User;
import server.core.GameServer;

public class SinglePlayerController {

    @FunctionalInterface
    public interface LineReader {
        String readLine() throws IOException;
    }

    @FunctionalInterface
    public interface TimedReader {
        String read(long timeoutMs, List<Integer> warnings, int totalSecs);
    }

    @FunctionalInterface
    public interface QuitAction {
        void onQuit() throws IOException;
    }

    private final GameServer server;
    private final Supplier<String> usernameSupplier;
    private final Consumer<String> sendMessage;
    private final LineReader readLineAllowQuit;
    private final TimedReader timedRead;
    private final QuitAction quitAction;
    private final Runnable showMenu;

    public SinglePlayerController(
            GameServer server,
            Supplier<String> usernameSupplier,
            Consumer<String> sendMessage,
            LineReader readLineAllowQuit,
            TimedReader timedRead,
            QuitAction quitAction,
            Runnable showMenu
    ) {
        this.server = server;
        this.usernameSupplier = usernameSupplier;
        this.sendMessage = sendMessage;
        this.readLineAllowQuit = readLineAllowQuit;
        this.timedRead = timedRead;
        this.quitAction = quitAction;
        this.showMenu = showMenu;
    }

    public void handleSinglePlayer() throws IOException {
        sendMessage.accept("Enter category (or 'any'). Available: " + String.join(", ", server.getAvailableCategories()));
        String category = readLineAllowQuit.readLine();
        if (category == null || category.trim().equals("-")) {
            showMenu.run();
            return;
        }

        sendMessage.accept("Enter difficulty (easy/medium/hard or 'any'):");
        String difficulty = readLineAllowQuit.readLine();
        if (difficulty == null || difficulty.trim().equals("-")) {
            showMenu.run();
            return;
        }

        playSinglePlayer(category.trim(), difficulty.trim(), "singleplayer", "=== SINGLE PLAYER ===");
    }

    public void handleRandomTrivia() throws IOException {
        playSinglePlayer("any", "any", "random-trivia", "=== RANDOM TRIVIA ===");
    }

    private void playSinglePlayer(String category, String difficulty, String gameType, String title) throws IOException {
        int qTime = server.getConfig() != null ? server.getConfig().getQuestionTime() : 15;
        int qCount = server.getConfig() != null ? server.getConfig().getDefaultQuestionCount() : 5;
        List<Integer> warnings = server.getConfig() != null
                ? server.getConfig().getWarningTimes() : Arrays.asList(10, 5);

        List<Question> gameQuestions = server.fetchQuestionsForGame(category, difficulty, qCount);
        if (gameQuestions.isEmpty()) {
            sendMessage.accept("No questions available.");
            showMenu.run();
            return;
        }

        sendMessage.accept(title);
        sendMessage.accept("Category: " + category + " | Difficulty: " + difficulty);
        sendMessage.accept("You have " + qTime + " seconds per question. " + gameQuestions.size() + " questions total.");

        int score = 0;
        int correct = 0;

        for (int i = 0; i < gameQuestions.size(); i++) {
            Question q = gameQuestions.get(i);
            sendMessage.accept("--- Question " + (i + 1) + "/" + gameQuestions.size()
                    + " [" + q.getDifficultyLevel() + "] ---");
            sendMessage.accept(q.getText());
            for (String choice : q.getChoices()) {
                sendMessage.accept(choice);
            }
            sendMessage.accept("You have " + qTime + " seconds. Enter A/B/C/D:");

            String answer = timedRead.read(qTime * 1000L, warnings, qTime);

            if ("-".equals(answer != null ? answer.trim() : null)) {
                quitAction.onQuit();
            }

            if (answer == null || answer.isEmpty()) {
                sendMessage.accept("Time's up! No answer given.");
            } else if (answer.equalsIgnoreCase(q.getCorrectAnswer())) {
                sendMessage.accept("CORRECT! +10 points");
                score += 10;
                correct += 1;
            } else {
                sendMessage.accept("WRONG! Correct answer was: " + q.getCorrectAnswer());
            }
        }

        sendMessage.accept("=== GAME OVER ===");
        sendMessage.accept("Score: " + score + " | Correct: " + correct + "/" + gameQuestions.size());

        String username = usernameSupplier.get();
        User user = username == null ? null : server.getUsers().get(username);
        if (user != null) {
            server.addScore(new ScoreEntry(user, score, new Date(), gameType,
                    gameQuestions.size(), correct));
        }

        showMenu.run();
    }
}