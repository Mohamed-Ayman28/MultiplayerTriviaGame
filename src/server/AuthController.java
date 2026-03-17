package server;

import java.io.IOException;
import java.util.function.Consumer;
import models.User;

class AuthController {

    private final GameServer server;
    private final Consumer<String> sendMessage;
    private final ClientIO.LineReader readLineAllowQuit;
    private final Consumer<String> setUsername;
    private final Runnable showMenu;

    AuthController(
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

    void handleAuth(String input, ClientHandler handler) throws IOException {
        if (input.equalsIgnoreCase("login")) {
            sendMessage.accept("Enter username:");
            String uname = readLineAllowQuit.readLine();
            sendMessage.accept("Enter password:");
            String pass = readLineAllowQuit.readLine();

            if (!server.getUsers().containsKey(uname)) {
                sendMessage.accept("ERROR 404: Username not found.");
            } else if (!server.authenticateUser(uname, pass)) {
                sendMessage.accept("ERROR 401: Wrong password.");
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
            } else {
                server.registerUser(name, uname, pass);
                sendMessage.accept("Registered successfully! Please login.");
            }
        } else {
            sendMessage.accept("Invalid option. Type [login] or [register]:");
        }
    }

    boolean isAdmin(String username) {
        User user = username == null ? null : server.getUsers().get(username);
        return user != null && user.isAdmin();
    }
}