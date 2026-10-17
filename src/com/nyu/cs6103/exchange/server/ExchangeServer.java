package com.nyu.cs6103.exchange.server;

import com.nyu.cs6103.exchange.common.BookSnapshot;
import com.nyu.cs6103.exchange.common.MatchResult;
import com.nyu.cs6103.exchange.common.Order;
import com.nyu.cs6103.exchange.common.Protocol;
import com.nyu.cs6103.exchange.common.Trade;
import com.nyu.cs6103.exchange.server.db.Db;
import com.nyu.cs6103.exchange.server.db.TradeDao;
import com.nyu.cs6103.exchange.server.db.UserDao;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Ties the whole server together.
 *
 * Thread inventory, which is the part worth understanding:
 *   1  accept thread            -- blocks in accept(), hands sockets to the pool
 *   N  client handler threads   -- one per connection, read and parse only
 *   1  matching thread          -- sole owner of every OrderBook
 *   1  persistence thread       -- settles trades in arrival order
 *   N  session writer threads   -- one per connection, drains an outbound queue
 *
 * Persistence runs on its own single thread rather than inline on the matching
 * thread. Disk I/O is orders of magnitude slower than an in-memory match, so
 * settling inline would let database latency dictate matching throughput.
 * Keeping it to exactly one thread preserves the order trades were matched in,
 * which a general thread pool would not.
 */
public class ExchangeServer implements AutoCloseable {

    public static final List<String> SYMBOLS = List.of("AAPL", "MSFT", "NVDA");
    private static final int SNAPSHOT_DEPTH = 5;

    private final int port;
    private final Db db;
    private final UserDao users;
    private final TradeDao trades;
    private final Broadcaster broadcaster = new Broadcaster();
    private final MatchingEngine engine;

    private final ExecutorService clientPool =
            Executors.newFixedThreadPool(32, r -> {
                Thread t = new Thread(r, "client-handler");
                t.setDaemon(true);
                return t;
            });
    private final ExecutorService persistence =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "persistence");
                t.setDaemon(true);
                return t;
            });

    private ServerSocket serverSocket;
    private Thread engineThread;
    private Thread acceptThread;
    private volatile boolean running = false;

    public ExchangeServer(int port, Db db) throws SQLException {
        this.port = port;
        this.db = db;
        this.db.initSchema();
        this.users = new UserDao(db);
        this.trades = new TradeDao(db);
        this.engine = new MatchingEngine(SYMBOLS, new EngineListener());
    }

    /** Binds, starts every thread, and returns the actual bound port. */
    public int start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;

        engineThread = new Thread(engine, "matching-engine");
        engineThread.start();

        acceptThread = new Thread(this::acceptLoop, "accept");
        acceptThread.start();

        int bound = serverSocket.getLocalPort();
        System.out.println("exchange listening on port " + bound
                + " symbols=" + String.join(",", SYMBOLS));
        return bound;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Session session = new Session(socket);
                broadcaster.add(session);
                clientPool.execute(new ClientHandler(
                        socket, session, engine, broadcaster, users, trades, SYMBOLS));
            } catch (IOException e) {
                if (running) {
                    System.err.println("accept failed: " + e.getMessage());
                }
            }
        }
    }

    /** Receives engine callbacks on the matching thread. Must not block. */
    private class EngineListener implements MatchingEngine.Listener {

        @Override
        public void onMatch(MatchResult result) {
            // Publish immediately; persist off-thread.
            for (Trade t : result.trades()) {
                broadcaster.broadcast(Protocol.encodeTrade(t));
            }
            for (Order o : result.touchedOrders()) {
                if (o.filled() > 0) {
                    broadcaster.sendToUser(o.userId(), Protocol.encodeFill(
                            o.id(), o.filled(), o.priceCents(), o.status().name()));
                }
            }
            persistence.execute(() -> {
                try {
                    trades.settle(result.trades(), result.touchedOrders());
                } catch (SQLException e) {
                    System.err.println("SETTLEMENT FAILED: " + e.getMessage());
                }
            });
        }

        @Override
        public void onCancelled(Order order) {
            // Keep the DB order history consistent with the in-memory book.
            persistence.execute(() -> {
                try {
                    trades.markCancelled(order);
                } catch (SQLException e) {
                    System.err.println("cancel persist failed: " + e.getMessage());
                }
            });
        }

        @Override
        public void onBookChanged(BookSnapshot snapshot) {
            broadcaster.broadcast(Protocol.encodeBook(snapshot));
        }

        @Override
        public void onRejected(Order order, String reason) {
            broadcaster.sendToUser(order.userId(), Protocol.error(reason));
        }
    }

    @Override
    public void close() {
        running = false;
        engine.shutdown();
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // shutting down anyway
        }
        broadcaster.closeAll();
        clientPool.shutdownNow();
        persistence.shutdown();
        try {
            persistence.awaitTermination(3, TimeUnit.SECONDS);
            if (engineThread != null) {
                engineThread.join(2000);
            }
            if (acceptThread != null) {
                acceptThread.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public MatchingEngine engine() {
        return engine;
    }

    public TradeDao trades() {
        return trades;
    }

    /** Waits for the persistence queue to drain; used by tests and shutdown. */
    public void awaitSettlement() {
        try {
            persistence.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("settlement did not drain", e);
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5555;
        ExchangeServer server = new ExchangeServer(port, Db.file("./exchange-data"));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        Thread.currentThread().join();
    }
}
