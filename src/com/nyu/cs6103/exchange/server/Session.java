package com.nyu.cs6103.exchange.server;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connected client, plus the thread that writes to it.
 *
 * Outbound messages go into a bounded queue drained by a dedicated writer
 * thread. Nothing else in the server ever writes to a socket directly. This
 * matters because a client on a slow link can take an arbitrarily long time to
 * accept bytes: if the matching thread wrote to sockets itself, one stalled
 * client would freeze the entire market for everyone.
 *
 * The queue is bounded rather than unbounded on purpose. A client that stops
 * reading entirely would otherwise make the server accumulate messages until
 * it runs out of memory. When the queue fills, that one session is dropped --
 * the correct trade-off, since a client too slow to keep up with market data
 * is not receiving a usable feed anyway.
 */
public class Session {

    private static final int OUTBOUND_CAPACITY = 512;
    private static final String POISON = "\u0000CLOSE";

    private final Socket socket;
    private final BlockingQueue<String> outbound = new ArrayBlockingQueue<>(OUTBOUND_CAPACITY);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final Thread writerThread;

    private volatile long userId = -1;
    private volatile String username = null;

    public Session(Socket socket) {
        this.socket = socket;
        this.writerThread = new Thread(this::writeLoop, "session-writer");
        this.writerThread.setDaemon(true);
        this.writerThread.start();
    }

    public boolean isAuthenticated() {
        return userId > 0;
    }

    public long userId() {
        return userId;
    }

    public String username() {
        return username;
    }

    public void authenticateAs(long id, String name) {
        this.userId = id;
        this.username = name;
    }

    /** Never blocks. Drops the session if the client has fallen too far behind. */
    public void send(String message) {
        if (!open.get()) {
            return;
        }
        if (!outbound.offer(message)) {
            System.err.println("session " + describe() + " too slow; dropping");
            close();
        }
    }

    private void writeLoop() {
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String msg = outbound.take();
                if (POISON.equals(msg)) {
                    break;
                }
                out.write(msg);
                out.write('\n');
                // Flush only when the queue has drained, so a burst of market
                // data becomes one write instead of one syscall per message.
                if (outbound.isEmpty()) {
                    out.flush();
                }
            }
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // client vanished; close() below cleans up
        } finally {
            closeSocketQuietly();
        }
    }

    public void close() {
        if (open.compareAndSet(true, false)) {
            if (!outbound.offer(POISON)) {
                closeSocketQuietly();
            }
        }
    }

    private void closeSocketQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    public boolean isOpen() {
        return open.get();
    }

    public String describe() {
        return (username != null ? username : "anon") + "@" + socket.getRemoteSocketAddress();
    }
}
