package server;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.time.Instant;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Reactive WebSocket handler: same business logic as FastChatServer
 * (validate JSON, JOIN/TEXT/LEAVE, echo). WebFlux keeps one thread per connection
 * only when work is done; otherwise non-blocking, so high concurrency with fewer threads.
 */
public class ChatWebSocketHandler implements WebSocketHandler {
    private static final Logger logger = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private final MessagePublisher publisher;
    private final RoomManager roomManager;
    private final ConcurrentHashMap<String, Set<String>> roomToJoinedUsers = new ConcurrentHashMap<>();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    public ChatWebSocketHandler(MessagePublisher publisher, RoomManager roomManager) {
        this.publisher = publisher;
        this.roomManager = roomManager;
    }

    private Set<String> joinedUsersForRoom(String roomId) {
        return roomToJoinedUsers.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
    }

    /** Extract roomId from path /chat/1 -> "1". */
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

        // Register this session for broadcast
        if (roomId != null) {
            roomManager.joinRoom(roomId, session);
        }

        // Clean up on close
        session.closeStatus()
                .doFinally(signal -> {
                    if (roomId != null) {
                        roomManager.leaveRoom(roomId, session);
                    }
                })
                .subscribe();

        return session.receive()
                .flatMap(msg -> {
                    String payload = msg.getPayloadAsText();
                    String serverTimestamp = Instant.now().toString();

                    // PHASE 1: Synchronous state check on Netty thread (nanoseconds).
                    // JSON parsing + JOIN/LEAVE state mutation happens HERE, before
                    // any concurrent I/O. This guarantees correct ordering without
                    // needing flatMap(..., 1).
                    StringBuilder batch = new StringBuilder();
                    java.util.List<PublishTask> publishTasks = new java.util.ArrayList<>();
                    for (String line : payload.split("\n")) {
                        if (line.isEmpty()) continue;
                        ProcessResult result = validateAndUpdateState(session, roomId, line, serverTimestamp);
                        if (result.responseJson != null) {
                            if (batch.length() > 0) batch.append("\n");
                            batch.append(result.responseJson);
                        }
                        if (result.publishTask != null) {
                            publishTasks.add(result.publishTask);
                        }
                    }

                    // PHASE 2: Offload only the blocking RabbitMQ publish to boundedElastic.
                    // State is already consistent, so this can safely run concurrently.
                    String batchStr = batch.toString();
                    if (publishTasks.isEmpty()) {
                        if (batchStr.isEmpty()) return Mono.empty();
                        return session.send(Mono.just(session.textMessage(batchStr)));
                    }
                    return Mono.fromCallable(() -> {
                        for (PublishTask task : publishTasks) {
                            publisher.publishMessage(task.roomId, task.message);
                        }
                        return batchStr;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(finalBatch -> {
                        if (finalBatch.isEmpty()) return Mono.empty();
                        return session.send(Mono.just(session.textMessage(finalBatch)));
                    });
                }, 256) // High concurrency: Phase 1 sync guarantees state correctness
                .then();
    }

    /** Holds a pending RabbitMQ publish. */
    private record PublishTask(String roomId, ClientMessage message) {}

    /** Result of synchronous validation + state update. */
    private record ProcessResult(String responseJson, PublishTask publishTask) {}

    /**
     * SYNCHRONOUS: Parse JSON, validate fields, update JOIN/LEAVE state.
     * Does NOT touch RabbitMQ. Safe to call on Netty thread.
     */
    private ProcessResult validateAndUpdateState(WebSocketSession session, String roomId, String message, String serverTimestamp) {
        String userId = null, username = null, msgContent = null, timestamp = null, messageType = null;

        try (JsonParser p = JSON_FACTORY.createParser(message)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return new ProcessResult(buildErrorJson("invalid JSON"), null);
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
                    default -> p.skipChildren();
                }
            }
        } catch (IOException e) {
            return new ProcessResult(buildErrorJson("invalid JSON"), null);
        }

        if (userId == null || userId.isEmpty()) return new ProcessResult(buildErrorJson("userId missing"), null);
        if (username == null || username.length() < 3) return new ProcessResult(buildErrorJson("username invalid"), null);
        if (msgContent == null || msgContent.isEmpty()) return new ProcessResult(buildErrorJson("message missing"), null);
        if (timestamp == null || !isValidTimestampFast(timestamp)) return new ProcessResult(buildErrorJson("invalid timestamp"), null);
        if (messageType == null) return new ProcessResult(buildErrorJson("messageType missing"), null);

        Set<String> joined = roomId != null ? joinedUsersForRoom(roomId) : null;
        if ("JOIN".equals(messageType)) {
            if (joined != null) {
                joined.add(userId);
            }
        } else {
            if (roomId == null || joined == null || !joined.contains(userId)) {
                return new ProcessResult(buildErrorJson("user not in room"), null);
            }
            if ("LEAVE".equals(messageType)) joined.remove(userId);
        }

        if (roomId == null) {
            return new ProcessResult(buildErrorJson("invalid path"), null);
        }

        // Build the message for async publish (no I/O here)
        String clientIp = session.getHandshakeInfo().getRemoteAddress() != null
                ? session.getHandshakeInfo().getRemoteAddress().getHostString() : "unknown";
        String serverId;
        try {
            serverId = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            serverId = "server-1";
        }
        ClientMessage msg = new ClientMessage(
                UUID.randomUUID().toString(),
                roomId,
                userId,
                username,
                msgContent,
                timestamp,
                messageType,
                serverId,
                clientIp
        );

        String ackJson = buildAckJson(msg.messageId(), messageType, roomId, serverTimestamp);
        return new ProcessResult(ackJson, new PublishTask(roomId, msg));
    }

    /** Lightweight delivery acknowledgement — NOT a full echo. */
    private static String buildAckJson(String messageId, String messageType, String roomId, String serverTimestamp) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"status\":\"QUEUED\"")
                .append(",\"messageId\":\"").append(messageId).append("\"")
                .append(",\"messageType\":\"").append(messageType).append("\"")
                .append(",\"roomId\":\"").append(roomId).append("\"")
                .append(",\"serverTimestamp\":\"").append(serverTimestamp).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String buildErrorJson(String message) {
        return "{\"status\":\"ERROR\",\"message\":\"" + escapeJson(message) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static boolean isValidTimestampFast(String ts) {
        if (ts == null || ts.length() < 19) return false;
        return ts.charAt(4) == '-' && ts.charAt(7) == '-' && ts.charAt(10) == 'T'
                && ts.charAt(13) == ':' && ts.charAt(16) == ':';
    }
}
