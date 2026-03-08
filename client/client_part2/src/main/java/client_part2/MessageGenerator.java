package client_part2;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Single dedicated thread that generates all messages and places them in a thread-safe
 * queue/buffer. Sending (worker) threads only take from the queue—they never wait for
 * this generator.
 *
 * 3.1 Message Generation (500,000 total):
 * - userId: random 1–100000, username: "user{userId}", message: random from 50 pre-defined
 * - roomId: random 1–20, messageType: 90% TEXT, 5% JOIN, 5% LEAVE, timestamp: ISO-8601
 *
 * Client enforces JOIN-before-TEXT/LEAVE per room (per-room set of joined userIds) so that
 * server receives valid sequences; server should still validate and reject invalid messages as a safety net.
 */
public class MessageGenerator implements Runnable {

    private static final int NUM_ROOMS = ClientConfig.getNumRooms();

    private final BlockingQueue<ClientMessage> singleQueue;
    private final List<BlockingQueue<ClientMessage>> workerQueues;
    private final List<List<BlockingQueue<ClientMessage>>> queuesByRoomIndex; // derived from workerQueues
    private final int totalMessages;
    private int[] messagesPerWorker = null; // when non-null, put exactly messagesPerWorker[i] into workerQueues[i]

    /** Per-room collection of joined userIds; O(1) add/remove/random-pick for TEXT/LEAVE. */
    private final List<RandomSet> joinedUsersByRoom;

    private static final String[] MESSAGES = {
            "Hello!", "How are you?", "Good morning", "Good night", "See you later",
            "What's up?", "Nice to meet you", "Thank you", "You're welcome", "No problem",
            "Sounds good", "Let me know", "I agree", "That's right", "Exactly",
            "Awesome!", "Great job", "Well done", "Keep it up", "Good luck",
            "Take care", "Have a good day", "Catch you later", "Talk soon", "Bye",
            "Sure thing", "Got it", "Understood", "Makes sense", "I see",
            "Interesting", "Tell me more", "Really?", "No way", "Wow",
            "Cool", "Nice", "Perfect", "Excellent", "Amazing",
            "Not bad", "Could be better", "Let's do it", "Why not?", "Maybe",
            "I think so", "Probably", "Definitely", "Absolutely", "Of course"
    };

    private static List<RandomSet> createJoinedState() {
        List<RandomSet> list = new ArrayList<>(NUM_ROOMS);
        for (int i = 0; i < NUM_ROOMS; i++) list.add(new RandomSet());
        return list;
    }

    private static List<List<BlockingQueue<ClientMessage>>> buildRoomIndex(List<BlockingQueue<ClientMessage>> allQueues) {
        if (allQueues == null || allQueues.isEmpty()) return null;
        List<List<BlockingQueue<ClientMessage>>> map = new ArrayList<>(NUM_ROOMS);
        for (int i = 0; i < NUM_ROOMS; i++) {
            List<BlockingQueue<ClientMessage>> roomQueues = new ArrayList<>();
            // Map each room to at least one queue.
            // If rooms > queues, multiple rooms share a queue.
            // If queues > rooms, multiple queues per room (sharding).
            if (allQueues.size() >= NUM_ROOMS) {
                // Original logic: distribute all queues among rooms
                for (int j = 0; j < allQueues.size(); j++) {
                    if (j % NUM_ROOMS == i) {
                        roomQueues.add(allQueues.get(j));
                    }
                }
            } else {
                // More rooms than queues: each room gets exactly one queue
                roomQueues.add(allQueues.get(i % allQueues.size()));
            }
            map.add(roomQueues);
        }
        return map;
    }

    /**
     * Set-like structure with O(1) add, remove, and random element.
     * Uses ArrayList + HashMap(element -> index); remove swaps with last for O(1).
     */
    private static final class RandomSet {
        private final List<String> list = new ArrayList<>();
        private final Map<String, Integer> indexMap = new HashMap<>();

        boolean isEmpty() { return list.isEmpty(); }

        void add(String s) {
            if (indexMap.containsKey(s)) return;
            indexMap.put(s, list.size());
            list.add(s);
        }

        void remove(String s) {
            Integer i = indexMap.remove(s);
            if (i == null) return;
            int lastIdx = list.size() - 1;
            if (i != lastIdx) {
                String last = list.get(lastIdx);
                list.set(i, last);
                indexMap.put(last, i);
            }
            list.remove(lastIdx);
        }

        /** O(1) random element; null if empty. */
        String pickRandom() {
            if (list.isEmpty()) return null;
            return list.get(ThreadLocalRandom.current().nextInt(list.size()));
        }
    }

    /** Single queue: all messages go to one queue (e.g. all workers use same room). */
    public MessageGenerator(BlockingQueue<ClientMessage> queue, int totalMessages) {
        this.singleQueue = queue;
        this.workerQueues = null;
        this.queuesByRoomIndex = null;
        this.totalMessages = totalMessages;
        this.joinedUsersByRoom = createJoinedState();
    }

    /** Per-worker queues (sharded): messages distributed by user hash to preserve order per-user per-room. */
    public MessageGenerator(List<BlockingQueue<ClientMessage>> workerQueues, int totalMessages) {
        this.singleQueue = null;
        this.workerQueues = workerQueues;
        this.queuesByRoomIndex = buildRoomIndex(workerQueues);
        this.totalMessages = totalMessages;
        this.joinedUsersByRoom = createJoinedState();
    }

    /** Warmup: Fill each worker queue with exact count. messagesPerWorker[i] -> workerQueues[i]. */
    public MessageGenerator(List<BlockingQueue<ClientMessage>> workerQueues, int[] messagesPerWorker) {
        this.singleQueue = null;
        this.workerQueues = workerQueues;
        this.queuesByRoomIndex = buildRoomIndex(workerQueues);
        int sum = 0;
        for (int c : messagesPerWorker) sum += c;
        this.totalMessages = sum;
        this.messagesPerWorker = messagesPerWorker;
        this.joinedUsersByRoom = createJoinedState();
    }

    @Override
    public void run() {
        try {
            if (workerQueues != null) {
                System.out.println("[Generator] Started, will put " + totalMessages + " messages into " + workerQueues.size() + " worker queues.");
            }
            if (messagesPerWorker != null) {
                // Warmup: generate messages and shard by (roomId, userId) to ensure order
                // This ensures JOIN -> TEXT -> LEAVE sequences for the same user stay ordered
                int totalWarmupMessages = 0;
                for (int c : messagesPerWorker) totalWarmupMessages += c;
                
                for (int i = 0; i < totalWarmupMessages; i++) {
                    ClientMessage msg = generateMessage();
                    int roomIndex = Integer.parseInt(msg.getRoomId()) - 1;
                    List<BlockingQueue<ClientMessage>> roomQueues = queuesByRoomIndex.get(roomIndex);
                    int shard = Math.abs(msg.getUserId().hashCode()) % roomQueues.size();
                    roomQueues.get(shard).put(msg);
                }
            } else {
                // Random load generation for Main
                for (int i = 0; i < totalMessages; i++) {
                    ClientMessage msg = generateMessage();
                    if (workerQueues != null) {
                        int roomIndex = Integer.parseInt(msg.getRoomId()) - 1;
                        List<BlockingQueue<ClientMessage>> candidates = queuesByRoomIndex.get(roomIndex);
                        // Shard by UserID to ensure single-consumer per user-in-room, preventing ordering races
                        int shard = Math.abs(msg.getUserId().hashCode()) % candidates.size();
                        candidates.get(shard).put(msg);
                    } else {
                        singleQueue.put(msg);
                    }
                }
            }
            if (workerQueues != null) {
                System.out.println("[Generator] Done, put " + totalMessages + " messages into " + workerQueues.size() + " worker queues.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("MessageGenerator interrupted: " + e.getMessage());
        }
    }

    private ClientMessage generateMessage() {
        return generateMessageForRoom(ThreadLocalRandom.current().nextInt(NUM_ROOMS) + 1);
    }

    private ClientMessage generateMessageForRoom(int roomId) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int roomIndex = roomId - 1;
        RandomSet joined = joinedUsersByRoom.get(roomIndex);

        String userId;
        String messageType;

        if (joined.isEmpty()) {
            userId = String.valueOf(rnd.nextInt(1, 100_001));
            joined.add(userId);
            messageType = "JOIN";
        } else {
            int r = rnd.nextInt(100);
            if (r < 90) {
                messageType = "TEXT";
                userId = joined.pickRandom();
            } else if (r < 95) {
                messageType = "JOIN";
                String existingInOther = pickUserIdFromOtherRoom(roomIndex);
                userId = (existingInOther != null && rnd.nextBoolean()) ? existingInOther : String.valueOf(rnd.nextInt(1, 100_001));
                joined.add(userId);
            } else {
                messageType = "LEAVE";
                userId = joined.pickRandom();
                joined.remove(userId);
            }
        }

        ClientMessage msg = new ClientMessage();
        msg.setUserId(userId);
        msg.setUsername("user" + userId);
        msg.setMessage(MESSAGES[rnd.nextInt(MESSAGES.length)]);
        msg.setRoomId(String.valueOf(roomId));
        msg.setMessageType(messageType);
        msg.setTimestamp(Instant.now().toString());
        // PRE-SERIALIZE (Extreme optimization: offload JSON build to generator thread)
        msg.setSerializedJson(msg.toJson());
        return msg;
    }

    private String pickUserIdFromOtherRoom(int currentRoomIndex) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int otherRoom = rnd.nextInt(NUM_ROOMS - 1);
        if (otherRoom >= currentRoomIndex) otherRoom++;
        return joinedUsersByRoom.get(otherRoom).pickRandom();
    }
}
