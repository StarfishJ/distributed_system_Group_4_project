package server;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Broadcasts a message to all WebSocket sessions in a room.
 * Returns true if at least one session received the message.
 */
@Component
public class MessageBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(MessageBroadcaster.class);

    private final RoomManager roomManager;

    public MessageBroadcaster(RoomManager roomManager) {
        this.roomManager = roomManager;
    }

    public Mono<Boolean> broadcastToRoom(String roomId, String messageJson) {
        Set<WebSocketSession> sessions = roomManager.getSessionsForRoom(roomId);

        if (sessions.isEmpty()) {
            return Mono.just(false);
        }

        return Flux.fromIterable(sessions)
                .flatMap(session ->
                    session.send(Mono.just(session.textMessage(messageJson)))
                            .then(Mono.just(true))
                            .onErrorResume(e -> {
                                log.debug("Broadcast failed to session {}, removing", session.getId());
                                roomManager.leaveRoom(roomId, session);
                                return Mono.just(false);
                            })
                )
                .collectList()
                .map(results -> results.stream().anyMatch(Boolean::booleanValue))
                .onErrorReturn(false);
    }
}
