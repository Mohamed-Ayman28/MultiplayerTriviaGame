package lookup;

import com.google.gson.Gson;
import models.Question;
import utils.JsonLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class LookupServer {

    private final int port;
    private final Gson gson;
    private final ExecutorService pool;
    private final List<Question> questions;

    public LookupServer(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid lookup port: " + port);
        }
        this.port = port;
        this.gson = new Gson();
        this.pool = Executors.newFixedThreadPool(16);
        this.questions = new JsonLoader().loadQuestions();
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            System.out.println("=== Lookup Server started on port " + port + " ===");
            while (true) {
                Socket socket = serverSocket.accept();
                pool.submit(() -> handleClient(socket));
            }
        } catch (Exception e) {
            System.err.println("Lookup server error: " + e.getMessage());
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {

            String line = in.readLine();
            if (line == null) {
                out.println("[]");
                return;
            }

            String[] parts = line.split("\\|", -1);
            if (parts.length < 4 || !"GET".equalsIgnoreCase(parts[0])) {
                out.println("[]");
                return;
            }

            String category = parts[1];
            String difficulty = parts[2];
            int count;
            try {
                count = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                out.println("[]");
                return;
            }

            if (count <= 0) {
                out.println("[]");
                return;
            }

            List<Question> filtered = filterQuestions(this.questions, category, difficulty);
            Collections.shuffle(filtered);
            int limit = Math.min(Math.max(1, count), filtered.size());
            List<Question> result = filtered.subList(0, limit);
            out.println(gson.toJson(result));
        } catch (IOException e) {
            System.err.println("LookupServer client error: " + e.getMessage());
        }
    }

    private List<Question> filterQuestions(List<Question> questions, String category, String difficulty) {
        if (questions == null) {
            return new ArrayList<>();
        }

        String c = normalize(category);
        String d = normalize(difficulty);

        return questions.stream()
                .filter(q -> "any".equals(c) || normalize(q.getCategory()).equals(c))
                .filter(q -> "any".equals(d) || normalize(q.getDifficultyLevel()).equals(d))
                .collect(Collectors.toList());
    }

    private String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "any";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
