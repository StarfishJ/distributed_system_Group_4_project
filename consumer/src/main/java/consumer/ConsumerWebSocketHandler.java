package consumer;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import reactor.core.publisher.Mono;

/**
 * Consumer WebSocket Handler: receive client messages and publish to queue
 * 
 * architecture flow:
 * 1. client connects to Consumer WebSocket
 * 2. Consumer receives message and publishes to RabbitMQ queue
 * 3. Consumer consumes from queue and broadcasts to all clients in the room
 * 4. after broadcast, acknowledge message
 */
public class ConsumerWebSocketHandler implements WebSocketHandler {

    private final RoomManager roomManager;
    private final MessagePublisher messagePublisher;
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private final ConcurrentHashMap<String, Set<String>> roomToJoinedUsers = new ConcurrentHashMap<>();
    
    // Idempotency: track recently-seen messageIds per user.
    // Bounded LRU (max 1000 per user) to prevent unbounded memory growth.
    private final ConcurrentHashMap<String, Set<String>> clientMessageIds = new ConcurrentHashMap<>();
    private static final int MAX_SEEN_IDS = 1000;

    private Set<String> seenIdsForUser(String userId) {
        return clientMessageIds.computeIfAbsent(userId, k -> {
            // LRU set backed by a LinkedHashMap: evicts oldest entry when capacity exceeded
            Map<String, Boolean> lru = new LinkedHashMap<>(MAX_SEEN_IDS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_SEEN_IDS;
                }
            };
            return Collections.synchronizedSet(Collections.newSetFromMap(lru));
        });
    }

    public ConsumerWebSocketHandler(RoomManager roomManager, MessagePublisher messagePublisher) {
        this.roomManager = roomManager;
        this.messagePublisher = messagePublisher;
    }

    private Set<String> joinedUsersForRoom(String roomId) {
        return roomToJoinedUsers.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
    }

    private static String roomIdFromPath(String path) {
        String prefix = "/chat/";
        if (path == null || !path.startsWith(prefix)) return null;
        String idStr = path.substring(prefix.length()).split("/")[0].trim();
        try {
            int rid = Integer.parseInt(idStr);
            if (rid >= 1 && rid <= 20) return idStr;
        } catch (NumberFormatException ignored) { }
        return null;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        String roomId = roomIdFromPath(path);
        
        if (roomId == null) {
            return session.close();
        }

        // initial registration to RoomManager (userId will be updated in JOIN message)
        roomManager.joinRoom(roomId, session, null);

        // listen for connection close, clean up session
        session.closeStatus()
                .doOnNext(status -> {
                    roomManager.leaveRoom(roomId, session, null);
                })
                .subscribe();

        // receive client messages and publish to queue
        return session.receive()
                .flatMap(msg -> {
                    String payload = msg.getPayloadAsText();
                    String serverTimestamp = Instant.now().toString();
                    for (String line : payload.split("\n")) {
                        if (line.isEmpty()) continue;
                        processSingleMessage(session, roomId, line, serverTimestamp);
                    }
                    return Mono.empty(); // do not reply to client, wait for Consumer to broadcast
                })
                .then();
    }

    private void processSingleMessage(WebSocketSession session, String roomId, String message, String serverTimestamp) {
        String userId = null, username = null, msgContent = null, timestamp = null, messageType = null;
        String clientMessageId = null; // client provided messageId (if exists)

        try (JsonParser p = JSON_FACTORY.createParser(message)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = p.currentName();
                p.nextToken();
                if (fieldName == null) continue;
                switch (fieldName) {
                    case "userId" -> userId = p.getValueAsString();
                    case "username" -> username = p.getValueAsString();
                    case "message" -> msgContent = p.getValueAsString();
                    case "timestamp" -> timestamp = p.getValueAsString();
                    case "messageType" -> messageType = p.getValueAsString();
                    case "messageId" -> clientMessageId = p.getValueAsString(); // 客户端提供的 messageId
                    default -> p.skipChildren();
                }
            }
        } catch (IOException e) {
            return;
        }

        if (userId == null || userId.isEmpty()) return;
        if (username == null || username.length() < 3) return;
        if (msgContent == null || msgContent.isEmpty()) return;
        if (timestamp == null) return;
        if (messageType == null) return;

        // idempotency check: if client provided messageId, check if it is duplicate
        if (clientMessageId != null && !clientMessageId.isEmpty()) {
            Set<String> seenIds = seenIdsForUser(userId); // bounded LRU, max 1000 per user
            if (!seenIds.add(clientMessageId)) {
                // add() returns false if already present → duplicate, ignore
                return;
            }
        }

        Set<String> joined = joinedUsersForRoom(roomId);
        if ("JOIN".equals(messageType)) {
            joined.add(userId);
            // update userId in RoomManager for subsequent broadcast
            roomManager.joinRoom(roomId, session, userId);
        } else {
            if (!joined.contains(userId)) {
                return; // user not in room, ignore message
            }
            if ("LEAVE".equals(messageType)) {
                joined.remove(userId);
                roomManager.leaveRoom(roomId, session, userId);
            }
        }

        if (roomId == null) {
            return;
        }

        // publish to RabbitMQ queue (do not reply to client)
        String clientIp = session.getHandshakeInfo().getRemoteAddress() != null
                ? session.getHandshakeInfo().getRemoteAddress().getHostString() : "unknown";
        String serverId;
        try {
            serverId = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            serverId = "consumer-1";
        }
        
        // use client provided messageId (if exists), otherwise use server generated UUID
        // idempotency check and queue message use the same ID, avoid conflict
        String messageId = (clientMessageId != null && !clientMessageId.isEmpty()) 
                ? clientMessageId 
                : UUID.randomUUID().toString();
        
        ClientMessage msg = new ClientMessage(
                messageId,
                roomId,
                userId,
                username,
                msgContent,
                timestamp,
                messageType,
                serverId,
                clientIp
        );
        messagePublisher.publishMessage(roomId, msg);
        // do not reply to client, wait for Consumer to consume from queue and broadcast
    }
}
