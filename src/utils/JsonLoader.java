package utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import models.*;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonLoader {

    private static final String USERS_FILE = "users.json";
    private static final String QUESTIONS_FILE = "questions.json";
    private static final String SCORES_FILE = "scores.json";
    private static final String CONFIG_FILE = "config.json";

    private final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            .create();

    private Path resolveReadPath(String fileName) {
        Path preferred = Paths.get("data", fileName);
        if (Files.exists(preferred)) return preferred;

        Path fallback = Paths.get("src", "data", fileName);
        if (Files.exists(fallback)) return fallback;
        return preferred;
    }

    private Path resolveWritePath(String fileName) throws IOException {
        Path preferred = Paths.get("data", fileName);
        if (Files.exists(preferred) || Files.exists(preferred.getParent())) return preferred;

        Path fallback = Paths.get("src", "data", fileName);
        if (Files.exists(fallback) || Files.exists(fallback.getParent())) return fallback;

        Files.createDirectories(preferred.getParent());
        return preferred;
    }


    public Map<String, User> loadUsers() {
        Path path = resolveReadPath(USERS_FILE);
        if (!Files.exists(path)) return new LinkedHashMap<>();
        try (Reader r = Files.newBufferedReader(path)) {
            Type t = new TypeToken<List<User>>(){}.getType();
            List<User> list = gson.fromJson(r, t);
            Map<String, User> map = new LinkedHashMap<>();
            if (list != null) {
                for (User u : list) map.put(u.getUsername(), u);
            }
            return map;
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("Failed to load users: " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }


    public List<Question> loadQuestions() {
        Path path = resolveReadPath(QUESTIONS_FILE);
        if (!Files.exists(path)) return new ArrayList<>();
        try (Reader r = Files.newBufferedReader(path)) {
            Type t = new TypeToken<List<Question>>(){}.getType();
            List<Question> list = gson.fromJson(r, t);
            return list != null ? list : new ArrayList<>();
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("Failed to load questions: " + e.getMessage());
            return new ArrayList<>();
        }
    }


    public List<ScoreEntry> loadScores() {
        Path path = resolveReadPath(SCORES_FILE);
        if (!Files.exists(path)) return new ArrayList<>();
        try (Reader r = Files.newBufferedReader(path)) {
            Type t = new TypeToken<List<ScoreEntry>>(){}.getType();
            List<ScoreEntry> list = gson.fromJson(r, t);
            return list != null ? list : new ArrayList<>();
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("Failed to load scores: " + e.getMessage());
            return new ArrayList<>();
        }
    }


    public Config loadConfig() {
        Path path = resolveReadPath(CONFIG_FILE);
        if (!Files.exists(path)) return null;
        try (Reader r = Files.newBufferedReader(path)) {
            return gson.fromJson(r, Config.class);
        } catch (IOException | JsonSyntaxException e) {
            System.err.println("Failed to load config: " + e.getMessage());
            return null;
        }
    }

    public void saveScores(List<ScoreEntry> scores) {  
        try (Writer w = Files.newBufferedWriter(resolveWritePath(SCORES_FILE))) {
            gson.toJson(scores, w);
        } catch (IOException e) {
            System.err.println("Failed to save scores: " + e.getMessage());
        }
    }

    public void saveUsers(Map<String, User> users) {
        try (Writer w = Files.newBufferedWriter(resolveWritePath(USERS_FILE))) {
            gson.toJson(new ArrayList<>(users.values()), w);
        } catch (IOException e) {
            System.err.println("Failed to save users: " + e.getMessage());
        }
    }
}
