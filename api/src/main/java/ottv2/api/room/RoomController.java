package ottv2.api.room;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
public class RoomController {

    private static final Logger log = LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public RoomController(RoomService roomService,
                          @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
                          @Autowired(required = false) ObjectMapper objectMapper) {
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public RoomController(RoomService roomService) {
        this(roomService, null, null);
    }

    @PostMapping("/api/rooms/create")
    public ResponseEntity<?> createRoom(@RequestBody(required = false) Map<String, Object> body) {
        String hostName = body != null && body.get("hostName") != null ? String.valueOf(body.get("hostName")) : "Chủ Phòng";
        Object timeControl = body != null ? body.get("timeControl") : null;

        RoomSession room = roomService.createRoom(hostName, timeControl);

        Map<String, Object> response = new HashMap<>();
        response.put("code", room.getCode());
        response.put("roomCode", room.getCode());
        response.put("status", room.getStatus().name());
        response.put("isHost", true);
        response.put("hostName", room.getHostName());
        response.put("createdAt", room.getCreatedAt().toString());
        if (room.getTimeControl() != null) {
            response.put("timeControl", room.getTimeControl());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/rooms/join/{code}")
    public ResponseEntity<?> joinRoom(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
        String guestName = body != null && body.get("guestName") != null ? String.valueOf(body.get("guestName")) : "Khách";
        try {
            RoomSession room = roomService.joinRoom(code, guestName);

            // Bắn (broadcast) sự kiện MATCH_START vào topic /topic/room/{code}
            // để Chủ phòng đang chờ tự động nhận diện và chuyển sang màn hình bàn cờ cùng lúc với Khách
            if (messagingTemplate != null) {
                Map<String, Object> wsEvent = new HashMap<>();
                wsEvent.put("type", "MATCH_START");
                wsEvent.put("roomCode", room.getCode());
                wsEvent.put("status", "PLAYING");
                wsEvent.put("hostName", room.getHostName());
                wsEvent.put("guestName", room.getGuestName());
                wsEvent.put("sender", "SERVER");
                wsEvent.put("ready", true);
                if (room.getTimeControl() != null) {
                    wsEvent.put("timeControl", room.getTimeControl());
                }
                messagingTemplate.convertAndSend("/topic/room/" + code, (Object) wsEvent);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("code", room.getCode());
            response.put("roomCode", room.getCode());
            response.put("status", room.getStatus().name());
            response.put("isHost", false);
            response.put("hostName", room.getHostName());
            response.put("guestName", room.getGuestName());
            if (room.getTimeControl() != null) {
                response.put("timeControl", room.getTimeControl());
            }
            return ResponseEntity.ok(response);
        } catch (RoomNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "ROOM_NOT_FOUND",
                    "message", "Mã phòng không tồn tại hoặc đã hết hạn!"
            ));
        } catch (RoomFullException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "error", "ROOM_FULL",
                    "message", "Phòng đã đủ 2 người chơi!"
            ));
        }
    }

    @GetMapping("/api/rooms/{code}")
    public ResponseEntity<?> getRoom(@PathVariable String code) {
        RoomSession room = roomService.getRoom(code);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "ROOM_NOT_FOUND",
                    "message", "Mã phòng không tồn tại!"
            ));
        }
        Map<String, Object> response = new HashMap<>();
        response.put("code", room.getCode());
        response.put("roomCode", room.getCode());
        response.put("status", room.getStatus().name());
        response.put("hostName", room.getHostName());
        response.put("guestName", room.getGuestName() != null ? room.getGuestName() : "");
        if (room.getTimeControl() != null) {
            response.put("timeControl", room.getTimeControl());
        }
        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(Object rawPayload) {
        if (rawPayload == null) {
            return new HashMap<>();
        }
        if (rawPayload instanceof Map) {
            return (Map<String, Object>) rawPayload;
        }
        try {
            String str;
            if (rawPayload instanceof byte[] bytes) {
                str = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            } else if (rawPayload instanceof String s) {
                str = s.trim();
            } else {
                return objectMapper.convertValue(rawPayload, new TypeReference<Map<String, Object>>() {});
            }

            if (str.startsWith("{") && str.endsWith("}")) {
                return objectMapper.readValue(str, new TypeReference<Map<String, Object>>() {});
            }

            // If str is a quoted string: e.g. "\"{\\\"type\\\":...}\"" or "\"eyJ...\""
            if (str.startsWith("\"") && str.endsWith("\"")) {
                String inner = objectMapper.readValue(str, String.class);
                if (inner.startsWith("{") && inner.endsWith("}")) {
                    return objectMapper.readValue(inner, new TypeReference<Map<String, Object>>() {});
                }
                // Try Base64 decode
                try {
                    byte[] decoded = java.util.Base64.getDecoder().decode(inner);
                    String decodedStr = new String(decoded, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (decodedStr.startsWith("{") && decodedStr.endsWith("}")) {
                        return objectMapper.readValue(decodedStr, new TypeReference<Map<String, Object>>() {});
                    }
                } catch (Exception ignored) {
                }
            }

            return objectMapper.readValue(str, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse STOMP payload as Map: {}", e.getMessage());
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("raw", rawPayload.toString());
            return fallback;
        }
    }

    // STOMP Message Handler: Lắng nghe ở @MessageMapping("/room/{code}/move") và broadcast lại nước đi cho cả 2 người qua @SendTo("/topic/room/{code}")
    @MessageMapping("/room/{code}/move")
    @SendTo("/topic/room/{code}")
    public Map<String, Object> handleMove(@DestinationVariable String code, @Payload(required = false) Object rawPayload) {
        try {
            RoomSession room = roomService.getRoom(code);
            if (room != null) {
                room.updateLastActive();
            }
            Map<String, Object> moveData = parsePayload(rawPayload);
            log.info("[STOMP MOVE] Room {}: pieceId={}, from={}, to={}", code, moveData.get("pieceId"), moveData.get("from"), moveData.get("to"));
            return moveData;
        } catch (Exception e) {
            log.error("Lỗi khi xử lý STOMP move phòng {}: {}", code, e.getMessage(), e);
            return Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
        }
    }

    // STOMP Message Handler: Lắng nghe các action khác (ready, clock_sync, restart, chat, leave)
    @MessageMapping("/room/{code}/action")
    @SendTo("/topic/room/{code}")
    public Map<String, Object> handleAction(@DestinationVariable String code, @Payload(required = false) Object rawPayload) {
        try {
            RoomSession room = roomService.getRoom(code);
            if (room != null) {
                room.updateLastActive();
            }
            Map<String, Object> actionData = parsePayload(rawPayload);
            String actionType = actionData.get("type") != null ? String.valueOf(actionData.get("type")) : "";
            log.info("[STOMP ACTION] Room {}: type={}, sender={}", code, actionType, actionData.get("sender"));

            if ("PLAYER_LEFT".equalsIgnoreCase(actionType) && room != null) {
                String sender = actionData.get("sender") != null ? String.valueOf(actionData.get("sender")).trim().toUpperCase() : "";
                if ("A".equals(sender) || "B".equals(sender)) {
                    room.setPlayerLeft(sender);
                }
            }
            return actionData;
        } catch (Exception e) {
            log.error("Lỗi khi xử lý STOMP action phòng {}: {}", code, e.getMessage(), e);
            return Map.of("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
        }
    }

    // Bắt và xử lý tập trung mọi ngoại lệ STOMP trong controller để không bị ngắt kết nối WebSocket session
    @MessageExceptionHandler
    public void handleMessageException(Exception e) {
        log.error("Unhandled WebSocket message error: {}", e.getMessage(), e);
    }
}
