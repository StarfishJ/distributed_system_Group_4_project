package consumer;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * WebSocket Broadcaster: broadcast message to all clients in the room
 * - Broadcast to all connected clients in room
 * - Acknowledge message after successful broadcast
 */
@Component
public class MessageBroadcaster {

    private final RoomManager roomManager;

    public MessageBroadcaster(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    /**
     * broadcast message to all clients in the room
     * requirements: acknowledge message after successful broadcast
     * 
     * strategy: return true if at least one client received successfully, avoid duplicate messages when some fail
     * - successful session: message delivered
     * - failed session: removed from RoomManager, will not receive duplicate messages
     * - return false if all clients failed or no clients, retry NACK
     */
    public Mono<Boolean> broadcastToRoom(String roomId, String messageJson) {
        Set<WebSocketSession> sessions = roomManager.getSessionsForRoom(roomId);
        
        if (sessions.isEmpty()) {
            return Mono.just(false);
        }

        // send to all sessions concurrently, count success and failure
        return Flux.fromIterable(sessions)
                .flatMap(session -> 
                    session.send(Mono.just(session.textMessage(messageJson)))
                            .then(Mono.just(true)) // return true if send successful
                            .onErrorResume(e -> {
                                // send failed, remove the session (to prevent subsequent retries from sending to disconnected clients)
                                roomManager.leaveRoom(roomId, session, null);
                                return Mono.just(false); // return false if send failed
                            })
                )
                .collectList()
                .map(results -> {
                    // return true if at least one client received successfully, avoid duplicate messages when some fail
                    // return false if all clients failed or no clients, retry NACK
                    return results.stream().anyMatch(Boolean::booleanValue);
                })
                .onErrorReturn(false);
    }
}
