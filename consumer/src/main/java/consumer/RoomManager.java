package consumer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Room Manager: maintain WebSocket sessions for each room
 * 
 * - active rooms and their participants
 * - user session mapping
 * - thread-safe data structures
 */
@Component
public class RoomManager {

    // room -> all WebSocket sessions in the room
    private final ConcurrentHashMap<String, Set<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    
    // userId -> user information (optional, for tracking)
    private final ConcurrentHashMap<String, UserInfo> activeUsers = new ConcurrentHashMap<>();

    /**
     * user join room
     */
    public void joinRoom(String roomId, WebSocketSession session, String userId) {
        roomSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
        // record user information (if userId is not null)
        if (userId != null) {
            activeUsers.put(userId, new UserInfo(userId, roomId, session));
        }
    }

    /**
     * user leave room
     */
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

    /**
     * get all sessions in the room (for broadcasting)
     */
    public Set<WebSocketSession> getSessionsForRoom(String roomId) {
        return roomSessions.getOrDefault(roomId, ConcurrentHashMap.newKeySet());
    }

    /**
     * user information (optional)
     */
    public record UserInfo(String userId, String roomId, WebSocketSession session) {}
}
