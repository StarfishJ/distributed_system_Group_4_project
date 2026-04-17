package server;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import server.redis.PresenceRegistry;

/**
 * Periodically refreshes Redis presence TTL for rooms that still have local WebSocket sessions.
 */
@Component
@ConditionalOnProperty(name = "server.redis.enabled", havingValue = "true")
public class PresenceHeartbeat {

    private final RoomManager roomManager;
    private final ObjectProvider<PresenceRegistry> presenceRegistry;

    public PresenceHeartbeat(RoomManager roomManager, ObjectProvider<PresenceRegistry> presenceRegistry) {
        this.roomManager = roomManager;
        this.presenceRegistry = presenceRegistry;
    }

    @Scheduled(fixedDelayString = "${server.presence.heartbeat-interval-ms:60000}")
    public void refreshPresence() {
        PresenceRegistry pr = presenceRegistry.getIfAvailable();
        if (pr == null) return;
        List<String> rooms = new ArrayList<>(roomManager.getOccupiedRoomIds());
        if (!rooms.isEmpty()) {
            pr.touchRooms(rooms);
        }
    }
}
