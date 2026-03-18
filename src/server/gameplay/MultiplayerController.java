package server.gameplay;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import server.core.GameServer;
import server.io.ClientIO;

public class MultiplayerController {

    @FunctionalInterface
    public interface TimeoutLineReader {
        String read(int timeoutMs) throws IOException;
    }

    private final GameServer server;
    private final Supplier<String> usernameSupplier;
    private final Supplier<String> currentRoomSupplier;
    private final Consumer<String> currentRoomSetter;
    private final Consumer<String> sendMessage;
    private final ClientIO.LineReader readLineAllowQuit;
    private final TimeoutLineReader tryReadLineWithTimeout;
    private final Runnable showMenu;
    private final RoomSetupController roomSetupController;

    public MultiplayerController(
            GameServer server,
            Supplier<String> usernameSupplier,
            Supplier<String> currentRoomSupplier,
            Consumer<String> currentRoomSetter,
            Consumer<String> sendMessage,
            ClientIO.LineReader readLineAllowQuit,
            TimeoutLineReader tryReadLineWithTimeout,
            Runnable showMenu
    ) {
        this.server = server;
        this.usernameSupplier = usernameSupplier;
        this.currentRoomSupplier = currentRoomSupplier;
        this.currentRoomSetter = currentRoomSetter;
        this.sendMessage = sendMessage;
        this.readLineAllowQuit = readLineAllowQuit;
        this.tryReadLineWithTimeout = tryReadLineWithTimeout;
        this.showMenu = showMenu;
        this.roomSetupController = new RoomSetupController(
            server,
            usernameSupplier,
            currentRoomSupplier,
            sendMessage,
            readLineAllowQuit,
            showMenu
        );
    }

    public void handleMultiplayer() throws IOException {
        showMultiplayerMenu();
        String input = readLineAllowQuit.readLine();
        if (input == null) {
            return;
        }
        if (input.trim().equals("-")) {
            showMenu.run();
            return;
        }
        switch (input.trim()) {
            case "1":
                createRoom();
                break;
            case "2":
                joinRoom();
                break;
            case "3":
                joinPublicRoom();
                break;
            case "4":
                listRooms();
                handleMultiplayer();
                break;
            case "0":
                showMenu.run();
                break;
            default:
                handleMultiplayer();
                break;
        }
    }

    private void showMultiplayerMenu() {
        sendMessage.accept("=== MULTIPLAYER ===");
        sendMessage.accept("[1] Create Room");
        sendMessage.accept("[2] Join Room");
        sendMessage.accept("[3] Join Public Room");
        sendMessage.accept("[4] List Rooms");
        sendMessage.accept("[0] Back");
        sendMessage.accept("Choose:");
    }

    private void joinPublicRoom() throws IOException {
        String username = usernameSupplier.get();
        String currentRoom = server.joinOrCreatePublicRoom(username);
        currentRoomSetter.accept(currentRoom);

        server.broadcastToRoomExcept(currentRoom, username + " joined the public room.", username);
        sendMessage.accept("Joined public room '" + currentRoom + "'. Waiting for auto-start...");
        server.tryAutoStartPublicRoom(currentRoom);
        handleRoomLobby();
    }

    private void createRoom() throws IOException {
        String username = usernameSupplier.get();

        sendMessage.accept("Enter room name:");
        String roomName = readLineAllowQuit.readLine();
        if (roomName == null || roomName.trim().isEmpty()) {
            showMenu.run();
            return;
        }
        roomName = roomName.trim();

        if (!server.createRoom(roomName, username)) {
            sendMessage.accept("Room '" + roomName + "' already exists.");
            handleMultiplayer();
            return;
        }

        currentRoomSetter.accept(roomName);
        sendMessage.accept("Room '" + roomName + "' created. You are the host.");
        handleRoomLobby();
    }

    private void joinRoom() throws IOException {
        String username = usernameSupplier.get();

        listRooms();
        sendMessage.accept("Enter room name to join:");
        String roomName = readLineAllowQuit.readLine();
        if (roomName == null || roomName.trim().isEmpty()) {
            showMenu.run();
            return;
        }
        roomName = roomName.trim();

        if (!server.joinRoom(roomName, username)) {
            sendMessage.accept("Cannot join room '" + roomName + "' (full, in progress, or not found).");
            handleMultiplayer();
            return;
        }

        currentRoomSetter.accept(roomName);
        server.broadcastToRoomExcept(roomName, username + " joined the room.", username);
        sendMessage.accept("Joined room '" + roomName + "'. Waiting for host to start...");
        handleRoomLobby();
    }

    private void listRooms() {
        Map<String, List<String>> rooms = server.getGameRooms();
        if (rooms.isEmpty()) {
            sendMessage.accept("No rooms available.");
            return;
        }
        sendMessage.accept("--- Available Rooms ---");
        for (Map.Entry<String, List<String>> e : rooms.entrySet()) {
            String status = server.isRoomInProgress(e.getKey()) ? "[IN PROGRESS]" : "[WAITING]";
            sendMessage.accept(e.getKey() + " | Players: " + e.getValue().size()
                    + " | Host: " + server.getRoomHost(e.getKey()) + " " + status);
        }
    }

    private void handleRoomLobby() throws IOException {
        String currentRoom = currentRoomSupplier.get();
        String username = usernameSupplier.get();
        if (currentRoom == null || username == null) {
            showMenu.run();
            return;
        }

        if (server.isPublicRoom(currentRoom)) {
            handlePublicRoomLobby();
            return;
        }

        boolean isHost = username.equals(server.getRoomHost(currentRoom));

        if (isHost) {
            sendMessage.accept("=== LOBBY: " + currentRoom + " (you are host) ===");
            sendMessage.accept("[1] Start Game");
            sendMessage.accept("[2] Configure Game (category/difficulty/questions/teams)");
            sendMessage.accept("[3] Show Players");
            sendMessage.accept("[0] Leave Room");
            sendMessage.accept("Choose:");

            String input = readLineAllowQuit.readLine();
            if (input == null) {
                leaveCurrentRoom();
                return;
            }
            switch (input.trim()) {
                case "1":
                    List<String> players = server.getGameRooms().get(currentRoom);
                    int minPlayers = server.getConfig() != null ? server.getConfig().getMinPlayers() : 2;
                    if (players == null || players.size() < minPlayers) {
                        sendMessage.accept("Need at least " + minPlayers + " players to start.");
                        handleRoomLobby();
                    } else {
                        String room = currentRoom;
                        new Thread(() -> server.startMultiplayerGame(room)).start();
                        if (waitForGameToStart(5000)) {
                            waitForGameEnd();
                        } else {
                            sendMessage.accept("Game did not start. Please try again.");
                            handleRoomLobby();
                        }
                    }
                    break;
                case "2":
                    if (roomSetupController.configureRoomGame() == RoomSetupController.Outcome.RETURN_TO_LOBBY) {
                        handleRoomLobby();
                    }
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
                    break;
            }
        } else {
            sendMessage.accept("=== LOBBY: " + currentRoom + " ===");
            sendMessage.accept("[0] Leave Room  (or wait for host to start)");

            while (true) {
                if (server.isRoomInProgress(currentRoom)) {
                    waitForGameEnd();
                    break;
                }
                String line = tryReadLineWithTimeout.read(300);
                if (line != null && line.trim().equals("0")) {
                    leaveCurrentRoom();
                    return;
                }
            }
        }
    }

    private void handlePublicRoomLobby() throws IOException {
        String currentRoom = currentRoomSupplier.get();
        if (currentRoom == null) {
            showMenu.run();
            return;
        }

        sendMessage.accept("=== PUBLIC LOBBY: " + currentRoom + " ===");
        sendMessage.accept("Waiting for enough players. Type [0] to leave.");

        while (true) {
            if (server.isRoomInProgress(currentRoom)) {
                waitForGameEnd();
                return;
            }

            server.tryAutoStartPublicRoom(currentRoom);

            String line = tryReadLineWithTimeout.read(300);
            if (line != null && line.trim().equals("0")) {
                leaveCurrentRoom();
                return;
            }
        }
    }

    private void waitForGameEnd() throws IOException {
        String currentRoom = currentRoomSupplier.get();
        String username = usernameSupplier.get();
        while (currentRoom != null && server.isRoomInProgress(currentRoom)) {
            String line = tryReadLineWithTimeout.read(200);
            if (line == null) {
                currentRoom = currentRoomSupplier.get();
                continue;
            }

            if (line.trim().isEmpty()) {
                currentRoom = currentRoomSupplier.get();
                continue;
            }

            String ans = server.normalizeAnswerToken(line);
            if (!ans.matches("[A-D]")) {
                sendMessage.accept("Invalid answer format. Use only A/B/C/D.");
                currentRoom = currentRoomSupplier.get();
                continue;
            }

            GameServer.SubmitResult submitResult = server.submitAnswer(currentRoom, username, ans);
            switch (submitResult) {
                case ACCEPTED:
                    sendMessage.accept("Answer locked: " + ans);
                    break;
                case QUESTION_CLOSED:
                    sendMessage.accept("No active question right now. Wait for the next question.");
                    break;
                case DUPLICATE_ANSWER:
                    sendMessage.accept("You already answered this question. First answer is counted.");
                    break;
                case NO_ACTIVE_GAME:
                    sendMessage.accept("No active game in this room.");
                    break;
                case INVALID_ANSWER:
                default:
                    sendMessage.accept("Invalid answer format. Use only A/B/C/D.");
                    break;
            }
            currentRoom = currentRoomSupplier.get();
        }
        currentRoomSetter.accept(null);
        showMenu.run();
    }

    private void showCurrentRoomPlayers() {
        String currentRoom = currentRoomSupplier.get();
        List<String> players = server.getGameRooms().get(currentRoom);
        if (players == null || players.isEmpty()) {
            sendMessage.accept("Room is empty.");
            return;
        }
        sendMessage.accept("Players in room " + currentRoom + ":");
        for (String p : players) {
            sendMessage.accept("- " + p);
        }
    }

    private void leaveCurrentRoom() {
        String currentRoom = currentRoomSupplier.get();
        String username = usernameSupplier.get();
        if (currentRoom != null) {
            server.broadcastToRoomExcept(currentRoom, username + " left the room.", username);
            server.leaveRoom(currentRoom, username);
            sendMessage.accept("You left the room.");
            currentRoomSetter.accept(null);
        }
        showMenu.run();
    }

    private boolean waitForGameToStart(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String currentRoom = currentRoomSupplier.get();
        while (currentRoom != null && System.currentTimeMillis() < deadline) {
            if (server.isRoomInProgress(currentRoom)) {
                return true;
            }
            tryReadLineWithTimeout.read(120);
            currentRoom = currentRoomSupplier.get();
        }
        return currentRoom != null && server.isRoomInProgress(currentRoom);
    }
}