package lookup;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import models.Question;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

public class LookupClient {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    private final String host;
    private final int port;
    private final Gson gson;

    public LookupClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.gson = new Gson();
    }

    public List<Question> fetchQuestions(String category, String difficulty, int count) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

                String safeCategory = category == null || category.trim().isEmpty() ? "any" : category.trim();
                String safeDifficulty = difficulty == null || difficulty.trim().isEmpty() ? "any" : difficulty.trim();
                int safeCount = Math.max(1, count);

                out.println("GET|" + safeCategory + "|" + safeDifficulty + "|" + safeCount);
                String response = in.readLine();
                if (response == null || response.trim().isEmpty()) {
                    return Collections.emptyList();
                }

                Type listType = new TypeToken<List<Question>>() {}.getType();
                List<Question> questions = gson.fromJson(response, listType);
                return questions != null ? questions : Collections.emptyList();
            }
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("LookupClient fetch error: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
