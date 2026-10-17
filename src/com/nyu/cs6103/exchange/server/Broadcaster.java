package com.nyu.cs6103.exchange.server;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of live sessions and the fan-out point for market data.
 *
 * Handler threads add and remove sessions while the engine thread iterates
 * them to broadcast, so the backing collections are concurrent. A plain
 * HashSet here would throw ConcurrentModificationException the first time a
 * client disconnected during a busy market -- a bug that would only appear
 * under load, which is exactly the kind that survives casual testing.
 */
public class Broadcaster {

    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final Map<Long, Set<Session>> byUser = new ConcurrentHashMap<>();

    public void add(Session s) {
        sessions.add(s);
    }

    public void registerUser(Session s) {
        byUser.computeIfAbsent(s.userId(), k -> ConcurrentHashMap.newKeySet()).add(s);
    }

    public void remove(Session s) {
        sessions.remove(s);
        Set<Session> forUser = byUser.get(s.userId());
        if (forUser != null) {
            forUser.remove(s);
            if (forUser.isEmpty()) {
                byUser.remove(s.userId());
            }
        }
    }

    /** Public market data: everyone connected sees it. */
    public void broadcast(String message) {
        for (Session s : sessions) {
            if (s.isOpen()) {
                s.send(message);
            }
        }
    }

    /** Private data such as fills: only that user's sessions. */
    public void sendToUser(long userId, String message) {
        Collection<Session> targets = byUser.get(userId);
        if (targets == null) {
            return;
        }
        for (Session s : targets) {
            if (s.isOpen()) {
                s.send(message);
            }
        }
    }

    public int sessionCount() {
        return sessions.size();
    }

    public void closeAll() {
        for (Session s : sessions) {
            s.close();
        }
        sessions.clear();
        byUser.clear();
    }
}
