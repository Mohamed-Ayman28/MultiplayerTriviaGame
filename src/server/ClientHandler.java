package server;

import java.io.*;
import java.net.Socket;
import java.util.*;
import models.*;

public class ClientHandler implements Runnable {

    private static class ClientQuitException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private final Socket   socket;
    private final GameServer server;
    private final AuthController authController;
    private final AdminController adminController;
    private final LeaderboardController leaderboardController;
    private final SinglePlayerController singlePlayerController;
    private final MultiplayerController multiplayerController;
    private BufferedReader in;
    private PrintWriter out;
    private ClientIO io;
    private String  username;
    private String  currentRoom; 

    public ClientHandler(Socket socket, GameServer server) {
        this.socket = socket;
        this.server = server;
        this.authController = new AuthController(
            server,
            this::sendMessage,
            this::readLineAllowQuit,
            this::setUsername,
            this::showMenu
        );
        this.adminController = new AdminController(
                server,
                () -> username,
                this::sendMessage,
                this::readLineAllowQuit,
                this::showMenu
        );
        this.leaderboardController = new LeaderboardController(
            server,
            () -> username,
            this::sendMessage,
            this::showMenu
        );
        this.singlePlayerController = new SinglePlayerController(
            server,
            () -> username,
            this::sendMessage,
            this::readLineAllowQuit,
            this::timedRead,
            this::throwClientQuit,
            this::showMenu
        );
        this.multiplayerController = new MultiplayerController(
            server,
            () -> username,
            () -> currentRoom,
            this::setCurrentRoom,
            this::sendMessage,
            this::readLineAllowQuit,
            this::tryReadLineWithTimeout,
            this::showMenu
        );
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            out = new PrintWriter(socket.getOutputStream(), true);
            io = new ClientIO(socket, in, this::sendMessage, this::checkQuitInput);

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
                    authController.handleAuth(input, this);
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
            case "4": leaderboardController.showLeaderboard(); break;
            case "5":
                if (authController.isAdmin(username)) adminController.handleAdmin();
                else sendMessage("Access denied.");
                break;
            default:
                showMenu();
        }
    }


    private void handleSinglePlayer() throws IOException {
        singlePlayerController.handleSinglePlayer();
    }

    private void handleRandomTrivia() throws IOException {
        singlePlayerController.handleRandomTrivia();
    }

    private String timedRead(long timeoutMs, List<Integer> warnings, int totalSecs) {
        return io.timedRead(timeoutMs, warnings, totalSecs);
    }


    private void handleMultiplayer() throws IOException {
        multiplayerController.handleMultiplayer();
    }

    private String readLineAllowQuit() throws IOException {
        return io.readLineAllowQuit();
    }

    private String tryReadLineWithTimeout(int timeoutMs) throws IOException {
        return io.tryReadLineWithTimeout(timeoutMs);
    }

    private void setUsername(String username) {
        this.username = username;
    }

    private void setCurrentRoom(String currentRoom) {
        this.currentRoom = currentRoom;
    }

    private void throwClientQuit() throws IOException {
        throw new ClientQuitException();
    }

    private void checkQuitInput(String value) throws IOException {
        if (value != null && value.trim().equals("-")) {
            throw new ClientQuitException();
        }
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
