package com.nyu.cs6103.exchange.client;

import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.Protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Client end of the socket, usable from Swing or headless.
 *
 * A dedicated listener thread reads inbound lines, because the server pushes
 * market data at any time rather than replying only when asked. Reading on the
 * Swing event thread would freeze the UI; polling would make updates late.
 *
 * Callbacks fire on the listener thread. A Swing caller must therefore hop to
 * the event dispatch thread before touching components, which TradingFrame
 * does with SwingUtilities.invokeLater.
 */
public class ServerConnection implements AutoCloseable {

    public interface Handler {
        default void onBook(BookSnapshot snapshot) { }
        default void onTrade(String symbol, long priceCents, long qty) { }
        default void onFill(long orderId, long qty, long priceCents, String status) { }
        default void onError(String reason) { }
        default void onOther(String rawLine) { }
        default void onDisconnected() { }
    }

    private final Socket socket;
    private final BufferedWriter out;
    private final BufferedReader in;
    private final Thread listener;
    private volatile boolean running = true;

    private volatile long userId = -1;
    private final CountDownLatch loginLatch = new CountDownLatch(1);
    private volatile String loginError = null;

    public ServerConnection(String host, int port, Handler handler) throws IOException {
        this.socket = new Socket(host, port);
        this.socket.setTcpNoDelay(true);
        this.out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.listener = new Thread(() -> readLoop(handler), "server-listener");
        this.listener.setDaemon(true);
        this.listener.start();
    }

    private void readLoop(Handler handler) {
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                dispatch(line, handler);
            }
        } catch (IOException e) {
            // socket closed
        } finally {
            running = false;
            loginLatch.countDown();
            handler.onDisconnected();
        }
    }

    private void dispatch(String line, Handler handler) {
        String[] f = Protocol.split(line);
        switch (f[0]) {
            case "BOOK" -> handler.onBook(Protocol.decodeBook(line));
            case "TRADE" -> handler.onTrade(f[1], Long.parseLong(f[2]), Long.parseLong(f[3]));
            case "FILL" -> handler.onFill(Long.parseLong(f[1]), Long.parseLong(f[2]),
                    Long.parseLong(f[3]), f[4]);
            case "OK" -> {
                if (f.length > 2 && "LOGIN".equals(f[1])) {
                    userId = Long.parseLong(f[2]);
                    loginLatch.countDown();
                }
                handler.onOther(line);
            }
            case "ERR" -> {
                loginError = f.length > 1 ? f[1] : "unknown error";
                loginLatch.countDown();
                handler.onError(loginError);
            }
            default -> handler.onOther(line);
        }
    }

    public void send(String message) throws IOException {
        synchronized (out) {
            out.write(message);
            out.write('\n');
            out.flush();
        }
    }

    public void register(String username, String password) throws IOException {
        send(Protocol.join("REGISTER", username, password));
    }

    /** Sends credentials and blocks until the server answers. */
    public boolean login(String username, String password, long timeoutMs)
            throws IOException, InterruptedException {
        send(Protocol.join("LOGIN", username, password));
        loginLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        return userId > 0;
    }

    public void order(String symbol, String side, long priceCents, long qty) throws IOException {
        send(Protocol.join("ORDER", symbol, side, priceCents, qty));
    }

    public void cancel(String symbol, long orderId) throws IOException {
        send(Protocol.join("CANCEL", symbol, orderId));
    }

    public long userId() {
        return userId;
    }

    public String loginError() {
        return loginError;
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
