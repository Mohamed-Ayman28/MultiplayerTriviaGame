package server;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.function.Consumer;

class ClientIO {

    @FunctionalInterface
    interface LineReader {
        String readLine() throws IOException;
    }

    @FunctionalInterface
    interface QuitChecker {
        void check(String value) throws IOException;
    }

    private final Socket socket;
    private final BufferedReader in;
    private final Consumer<String> sendMessage;
    private final QuitChecker quitChecker;

    ClientIO(Socket socket, BufferedReader in, Consumer<String> sendMessage, QuitChecker quitChecker) {
        this.socket = socket;
        this.in = in;
        this.sendMessage = sendMessage;
        this.quitChecker = quitChecker;
    }

    String readLineAllowQuit() throws IOException {
        String value = in.readLine();
        quitChecker.check(value);
        return value;
    }

    String tryReadLineWithTimeout(int timeoutMs) throws IOException {
        int previousTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(timeoutMs);
            String value = in.readLine();
            quitChecker.check(value);
            return value;
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
    }

    String timedRead(long timeoutMs, List<Integer> warnings, int totalSecs) {
        final String[] result = {null};
        final boolean[] done = {false};

        Thread reader = new Thread(() -> {
            try {
                result[0] = in.readLine();
            } catch (IOException ignored) {
            }
            done[0] = true;
        });
        reader.setDaemon(true);
        reader.start();

        long start = System.currentTimeMillis();

        for (int w : warnings) {
            long warnAt = timeoutMs - (w * 1000L);
            while (!done[0] && (System.currentTimeMillis() - start) < warnAt) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
            if (!done[0]) {
                sendMessage.accept("[!] " + w + " seconds remaining!");
            }
        }

        while (!done[0] && (System.currentTimeMillis() - start) < timeoutMs) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }

        reader.interrupt();
        return result[0];
    }
}