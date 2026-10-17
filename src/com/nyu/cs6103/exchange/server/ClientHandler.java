package com.nyu.cs6103.exchange.server;

import com.nyu.cs6103.exchange.common.Order;
import com.nyu.cs6103.exchange.common.Protocol;
import com.nyu.cs6103.exchange.common.Side;
import com.nyu.cs6103.exchange.server.db.TradeDao;
import com.nyu.cs6103.exchange.server.db.UserDao;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Reads one client's command stream and turns it into engine commands.
 *
 * One instance runs per connected client on a pool thread. It never touches an
 * OrderBook: every mutating request becomes a queued command, so this thread
 * returns to reading immediately and slow matching cannot back up the network
 * layer.
 */
public class ClientHandler implements Runnable {

    private static final long STARTING_CASH_CENTS = 1_000_000_00L;

    private final Socket socket;
    private final Session session;
    private final MatchingEngine engine;
    private final Broadcaster broadcaster;
    private final UserDao users;
    private final TradeDao trades;
    private final List<String> symbols;

    public ClientHandler(Socket socket, Session session, MatchingEngine engine,
                         Broadcaster broadcaster, UserDao users, TradeDao trades,
                         List<String> symbols) {
        this.socket = socket;
        this.session = session;
        this.engine = engine;
        this.broadcaster = broadcaster;
        this.users = users;
        this.trades = trades;
        this.symbols = symbols;
    }

    @Override
    public void run() {
        session.send(Protocol.join("WELCOME", String.join(",", symbols)));
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (!dispatch(line.trim())) {
                    break;
                }
            }
        } catch (IOException e) {
            // client disconnected mid-read; fall through to cleanup
        } finally {
            broadcaster.remove(session);
            session.close();
        }
    }

    /** Returns false when the connection should be closed. */
    private boolean dispatch(String line) {
        String[] f = Protocol.split(line);
        try {
            switch (f[0].toUpperCase()) {
                case "REGISTER" -> handleRegister(f);
                case "LOGIN" -> handleLogin(f);
                case "ORDER" -> handleOrder(f);
                case "CANCEL" -> handleCancel(f);
                case "QUIT" -> {
                    return false;
                }
                default -> session.send(Protocol.error("unknown command " + f[0]));
            }
        } catch (IllegalArgumentException e) {
            session.send(Protocol.error(e.getMessage()));
        } catch (SQLException e) {
            session.send(Protocol.error("database unavailable"));
            System.err.println("db error for " + session.describe() + ": " + e.getMessage());
        }
        return true;
    }

    private void handleRegister(String[] f) throws SQLException {
        require(f.length == 3, "usage: REGISTER|username|password");
        Protocol.validateField(f[1], "username");
        Protocol.validateField(f[2], "password");
        try {
            UserDao.User u = users.register(f[1], f[2].toCharArray(), STARTING_CASH_CENTS);
            for (String symbol : symbols) {
                trades.seedHolding(u.id(), symbol, 1_000);
            }
            session.send(Protocol.join("OK", "REGISTER", u.id()));
        } catch (SQLException e) {
            session.send(Protocol.error("username already taken"));
        }
    }

    private void handleLogin(String[] f) throws SQLException {
        require(f.length == 3, "usage: LOGIN|username|password");
        Optional<UserDao.User> u = users.authenticate(f[1], f[2].toCharArray());
        if (u.isEmpty()) {
            // Deliberately identical for a bad username and a bad password, so
            // the response cannot be used to enumerate valid accounts.
            session.send(Protocol.error("invalid credentials"));
            return;
        }
        session.authenticateAs(u.get().id(), u.get().username());
        broadcaster.registerUser(session);
        session.send(Protocol.join("OK", "LOGIN", u.get().id(), u.get().cashCents()));
    }

    private void handleOrder(String[] f) throws SQLException {
        requireAuth();
        require(f.length == 5, "usage: ORDER|symbol|BUY or SELL|priceCents|quantity");

        String symbol = f[1];
        require(engine.knowsSymbol(symbol), "unknown symbol " + symbol);
        Side side = parseSide(f[2]);
        long price = parseLong(f[3], "price");
        long qty = parseLong(f[4], "quantity");

        preTradeCheck(symbol, side, price, qty);

        Order order = new Order(session.userId(), symbol, side, price, qty);
        trades.insertOrder(order);
        session.send(Protocol.join("ACCEPTED", order.id()));
        engine.submit(order);
    }

    /**
     * Refuse an order the account could not settle, before it reaches the
     * book. Without this, a matched trade could fail during settlement, which
     * would mean unwinding an execution other users have already been told
     * about. Checking up front keeps every printed trade final.
     */
    private void preTradeCheck(String symbol, Side side, long price, long qty)
            throws SQLException {
        if (side == Side.BUY) {
            long needed = price * qty;
            long cash = trades.cashOf(session.userId());
            require(cash >= needed, "insufficient funds: need " + needed + " have " + cash);
        } else {
            long held = trades.holdingOf(session.userId(), symbol);
            require(held >= qty, "insufficient shares: need " + qty + " have " + held);
        }
    }

    private void handleCancel(String[] f) {
        requireAuth();
        require(f.length == 3, "usage: CANCEL|symbol|orderId");
        engine.cancel(f[1], parseLong(f[2], "orderId"), session.userId());
    }

    // ---- small helpers ----

    private void requireAuth() {
        require(session.isAuthenticated(), "not logged in");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Side parseSide(String raw) {
        try {
            return Side.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("side must be BUY or SELL");
        }
    }

    private static long parseLong(String raw, String what) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a whole number");
        }
    }
}
