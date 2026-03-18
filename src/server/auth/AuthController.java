package server.auth;

import java.io.IOException;
import java.util.function.Consumer;
import models.User;
import server.core.ClientHandler;
import server.core.GameServer;
import server.io.ClientIO;

public class AuthController {

    private final GameServer server;
    private final Consumer<String> sendMessage;
    private final ClientIO.LineReader readLineAllowQuit;
    private final Consumer<String> setUsername;
    private final Runnable showMenu;

    public AuthController(
            GameServer server,
            Consumer<String> sendMessage,
            ClientIO.LineReader readLineAllowQuit,
            Consumer<String> setUsername,
            Runnable showMenu
    ) {
        this.server = server;
        this.sendMessage = sendMessage;
        this.readLineAllowQuit = readLineAllowQuit;
        this.setUsername = setUsername;
        this.showMenu = showMenu;
    }

    public void handleAuth(String input, ClientHandler handler) throws IOException {
        if (input.equalsIgnoreCase("login")) {
            sendMessage.accept("Enter username:");
            String uname = readLineAllowQuit.readLine();
            sendMessage.accept("Enter password:");
            String pass = readLineAllowQuit.readLine();

            if (!server.getUsers().containsKey(uname)) {
                sendMessage.accept("ERROR 404: Username not found.");
                promptForAuth();
            } else if (!server.authenticateUser(uname, pass)) {
                sendMessage.accept("ERROR 401: Wrong password.");
                promptForAuth();
            } else {
                setUsername.accept(uname);
                server.addClient(uname, handler);
                sendMessage.accept("Login successfully! Welcome, " + uname);
                showMenu.run();
            }

        } else if (input.equalsIgnoreCase("register")) {
            sendMessage.accept("Enter name:");
            String name = readLineAllowQuit.readLine();
            sendMessage.accept("Enter username:");
            String uname = readLineAllowQuit.readLine();
            sendMessage.accept("Enter password:");
            String pass = readLineAllowQuit.readLine();

            if (server.getUsers().containsKey(uname)) {
                sendMessage.accept("ERROR 409: Username already taken please choose another username.");
                promptForAuth();
            } else {
                server.registerUser(name, uname, pass);
                sendMessage.accept("Registered successfully! Please login.");
                promptForAuth();
            }
        } else {
            promptForAuthWithPrefix("Invalid option.");
        }
    }

    private void promptForAuth() {
        sendMessage.accept("Type [login] or [register]:");
    }

    private void promptForAuthWithPrefix(String prefix) {
        sendMessage.accept(prefix + " Type [login] or [register]:");
    }

    public boolean isAdmin(String username) {
        User user = username == null ? null : server.getUsers().get(username);
        return user != null && user.isAdmin();
    }
}