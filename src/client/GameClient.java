package client;

import java.io.*;
import java.net.Socket;

public class GameClient {

    private final String host;
    private final int port;

    public GameClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        try (Socket socket = new Socket(host, port)) {
            System.out.println("Connected to server at " + host + ":" + port);

            BufferedReader serverIn  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter    serverOut = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader userIn    = new BufferedReader(new InputStreamReader(System.in));

            // read server responses in a thread 
            Thread readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = serverIn.readLine()) != null) {
                        System.out.println(line);
                    }
                } catch (IOException e) {
                    System.out.println("Disconnected from server.");
                }
            });
            readerThread.setDaemon(true);
            readerThread.start();

            // reads user input in a separate thread
            String input;
            while ((input = userIn.readLine()) != null) {
                serverOut.println(input);
                if (input.trim().equals("-")) {
                    break;
                }
            }

        } catch (IOException e) {
            System.err.println("Could not connect to server: " + e.getMessage());
        }
    }
}
