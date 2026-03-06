package server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Room Manager: maintain WebSocket sessions for each room ON THIS SERVER NODE.
 */
@Component
public class RoomManager {

    // room -> all WebSocket sessions in the room on this node
    private final ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    
    // userId -> user information (optional)
    private final ConcurrentHashMap<String, UserInfo> activeUsers = new ConcurrentHashMap<>();

    public void joinRoom(String roomId, WebSocketSession session, String userId) {
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        if (userId != null) {
            activeUsers.put(userId, new UserInfo(userId, roomId, session));
        }
    }

    public void leaveRoom(String roomId, WebSocketSession session, String userId) {
        Set<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                roomSessions.remove(roomId);
            }
        }
        if (userId != null) {
            activeUsers.remove(userId);
        }
    }

    public Set<WebSocketSession> getSessionsForRoom(String roomId) {
        return roomSessions.getOrDefault(roomId, ConcurrentHashMap.newKeySet());
    }

    public record UserInfo(String userId, String roomId, WebSocketSession session) {}
}
