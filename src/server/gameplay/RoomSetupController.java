package server.gameplay;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import server.core.GameServer;
import server.io.ClientIO;

class RoomSetupController {

    enum Outcome {
        RETURN_TO_MENU,
        RETURN_TO_LOBBY
    }

    private final GameServer server;
    private final Supplier<String> usernameSupplier;
    private final Supplier<String> currentRoomSupplier;
    private final Consumer<String> sendMessage;
    private final ClientIO.LineReader readLineAllowQuit;
    private final Runnable showMenu;

    RoomSetupController(
            GameServer server,
            Supplier<String> usernameSupplier,
            Supplier<String> currentRoomSupplier,
            Consumer<String> sendMessage,
            ClientIO.LineReader readLineAllowQuit,
            Runnable showMenu
    ) {
        this.server = server;
        this.usernameSupplier = usernameSupplier;
        this.currentRoomSupplier = currentRoomSupplier;
        this.sendMessage = sendMessage;
        this.readLineAllowQuit = readLineAllowQuit;
        this.showMenu = showMenu;
    }

    Outcome configureRoomGame() throws IOException {
        String currentRoom = currentRoomSupplier.get();
        List<String> players = server.getGameRooms().get(currentRoom);
        if (players == null || players.isEmpty()) {
            sendMessage.accept("Room not found.");
            showMenu.run();
            return Outcome.RETURN_TO_MENU;
        }

        sendMessage.accept("Enter category (or 'any'). Available: " + String.join(", ", server.getAvailableCategories()));
        String category = readLineAllowQuit.readLine();
        if (category == null) {
            category = "any";
        }

        sendMessage.accept("Enter difficulty (easy/medium/hard or 'any'):");
        String difficulty = readLineAllowQuit.readLine();
        if (difficulty == null || difficulty.trim().isEmpty()) {
            difficulty = "any";
        }

        int defaultCount = server.getConfig() != null ? server.getConfig().getDefaultQuestionCount() : 5;
        sendMessage.accept("Enter number of questions (default " + defaultCount + "):");
        String qCountStr = readLineAllowQuit.readLine();
        int qCount = defaultCount;
        if (qCountStr != null && !qCountStr.trim().isEmpty()) {
            try {
                qCount = Integer.parseInt(qCountStr.trim());
            } catch (NumberFormatException e) {
                sendMessage.accept("Invalid number. Using default " + defaultCount + ".");
                qCount = defaultCount;
            }
        }

        sendMessage.accept("Enable team-vs-team mode? (yes/no)");
        String teamMode = readLineAllowQuit.readLine();
        boolean enableTeams = teamMode != null && teamMode.trim().equalsIgnoreCase("yes");

        String teamA = null;
        String teamB = null;
        Map<String, String> teamByUser = null;

        if (enableTeams) {
            if (players.size() < 2) {
                sendMessage.accept("At least 2 players required for teams.");
                return Outcome.RETURN_TO_LOBBY;
            }

            sendMessage.accept("Enter Team A name:");
            teamA = readLineAllowQuit.readLine();
            sendMessage.accept("Enter Team B name:");
            teamB = readLineAllowQuit.readLine();

            if (teamA == null || teamB == null || teamA.trim().isEmpty() || teamB.trim().isEmpty()) {
                sendMessage.accept("Team names are required.");
                return Outcome.RETURN_TO_LOBBY;
            }
            if (teamA.trim().equalsIgnoreCase(teamB.trim())) {
                sendMessage.accept("Team names must be unique.");
                return Outcome.RETURN_TO_LOBBY;
            }

            teamByUser = new HashMap<>();
            sendMessage.accept("Assign players to teams. Type A or B for each player:");
            for (String p : players) {
                while (true) {
                    sendMessage.accept("Player " + p + " -> team (A/B):");
                    String t = readLineAllowQuit.readLine();
                    if (t == null) {
                        sendMessage.accept("Cancelled.");
                        return Outcome.RETURN_TO_LOBBY;
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
                    sendMessage.accept("Invalid input. Please enter A or B.");
                }
            }
        }

        boolean ok = server.configureRoomGame(currentRoom, category, difficulty, qCount, teamA, teamB, teamByUser);
        if (!ok) {
            sendMessage.accept("Invalid setup. Team mode requires unique team names, an even player count, equal players per team, and valid team assignments.");
        } else {
            sendMessage.accept("Game setup updated successfully.");
            String username = usernameSupplier.get();
            server.broadcastToRoomExcept(currentRoom, "Host updated game setup.", username);
        }
        return Outcome.RETURN_TO_LOBBY;
    }
}