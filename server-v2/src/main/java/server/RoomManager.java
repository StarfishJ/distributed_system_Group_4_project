package server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Room Manager: maintain WebSocket sessions for each room.
 * Thread-safe via ConcurrentHashMap + ConcurrentHashMap.newKeySet().
 */
@Component
public class RoomManager {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();

    public void joinRoom(String roomId, WebSocketSession session) {
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void leaveRoom(String roomId, WebSocketSession session) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
    }

    public Set<WebSocketSession> getSessionsForRoom(String roomId) {
        return roomSessions.getOrDefault(roomId, ConcurrentHashMap.newKeySet());
    }
}
