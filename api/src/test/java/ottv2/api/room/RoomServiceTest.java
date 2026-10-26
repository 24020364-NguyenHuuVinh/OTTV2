package ottv2.api.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoomServiceTest {

    private RoomService roomService;
    private RoomController roomController;

    @BeforeEach
    void setUp() {
        roomService = new RoomService();
        roomController = new RoomController(roomService);
    }

    @Test
    void testCreateRoomReturns6DigitCode() {
        RoomSession room = roomService.createRoom("Player1", Map.of("id", "3+2"));
        assertNotNull(room);
        assertNotNull(room.getCode());
        assertEquals(6, room.getCode().length());
        assertTrue(room.getCode().matches("\\d{6}"));
        assertEquals("Player1", room.getHostName());
        assertEquals(RoomSession.Status.WAITING, room.getStatus());
        assertFalse(room.isFull());
    }

    @Test
    void testJoinRoomSuccess() {
        RoomSession room = roomService.createRoom("HostPlayer", null);
        String code = room.getCode();

        RoomSession joined = roomService.joinRoom(code, "GuestPlayer");
        assertNotNull(joined);
        assertEquals("GuestPlayer", joined.getGuestName());
        assertEquals(RoomSession.Status.PLAYING, joined.getStatus());
        assertTrue(joined.isFull());
    }

    @Test
    void testJoinRoomNotFound() {
        assertThrows(RoomNotFoundException.class, () -> roomService.joinRoom("999999", "Guest"));
    }

    @Test
    void testJoinRoomAlreadyFull() {
        RoomSession room = roomService.createRoom("HostPlayer", null);
        String code = room.getCode();

        roomService.joinRoom(code, "GuestPlayer1");
        assertThrows(RoomFullException.class, () -> roomService.joinRoom(code, "GuestPlayer2"));
    }

    @Test
    void testControllerCreateAndJoinApi() {
        ResponseEntity<?> createRes = roomController.createRoom(Map.of("hostName", "Master"));
        assertEquals(HttpStatus.CREATED, createRes.getStatusCode());
        Map<?, ?> createBody = (Map<?, ?>) createRes.getBody();
        assertNotNull(createBody);
        String code = String.valueOf(createBody.get("code"));
        assertEquals(6, code.length());

        // Join room
        ResponseEntity<?> joinRes = roomController.joinRoom(code, Map.of("guestName", "Challenger"));
        assertEquals(HttpStatus.OK, joinRes.getStatusCode());

        // Join room when full -> 400
        ResponseEntity<?> joinResFull = roomController.joinRoom(code, Map.of("guestName", "ThirdWheel"));
        assertEquals(HttpStatus.BAD_REQUEST, joinResFull.getStatusCode());

        // Join non-existent room -> 404
        ResponseEntity<?> joinResNotFound = roomController.joinRoom("000000", Map.of("guestName", "Ghost"));
        assertEquals(HttpStatus.NOT_FOUND, joinResNotFound.getStatusCode());
    }

    @Test
    void testStompHandlers() {
        RoomSession room = roomService.createRoom("Player1", null);
        roomService.joinRoom(room.getCode(), "Player2");
        String code = room.getCode();

        Map<String, Object> moveData = new java.util.HashMap<>(Map.of(
                "sender", "A",
                "playerToken", room.getHostPlayerToken(),
                "from", new int[]{0, 0},
                "to", new int[]{1, 0}
        ));
        Map<String, Object> result = roomController.handleMove(code, moveData);
        assertNotNull(result);
        assertEquals("A", result.get("sender"));
        assertNull(result.get("playerToken"), "playerToken should be stripped before broadcast");

        Map<String, Object> actionData = new java.util.HashMap<>(Map.of(
                "type", "READY",
                "sender", "A",
                "playerToken", room.getHostPlayerToken()
        ));
        Map<String, Object> actionResult = roomController.handleAction(code, actionData);
        assertNotNull(actionResult);
        assertEquals("READY", actionResult.get("type"));
        assertNull(actionResult.get("playerToken"), "playerToken should be stripped before broadcast");

        // Unauthorized move from non-existent room or invalid token
        Map<String, Object> unauthorizedMove = Map.of(
                "sender", "A",
                "playerToken", "invalid-token",
                "from", new int[]{0, 0},
                "to", new int[]{1, 0}
        );
        assertNull(roomController.handleMove(code, unauthorizedMove));

        // Unauthorized action
        Map<String, Object> unauthorizedAction = Map.of(
                "type", "READY",
                "sender", "A",
                "playerToken", "wrong-token"
        );
        assertNull(roomController.handleAction(code, unauthorizedAction));
    }

    @Test
    void testJoinRoomCreatesMatchesRecordWhenJdbcPresent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        RoomController controllerWithJdbc = new RoomController(roomService, null, null, jdbc);

        UUID publicId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("INSERT INTO matches"), eq(Long.class), any(), any(), any(), any(), any(), any()))
                .thenReturn(12345L);
        when(jdbc.queryForObject(contains("SELECT public_id FROM matches WHERE id = ?"), eq(UUID.class), eq(12345L)))
                .thenReturn(publicId);

        ResponseEntity<?> createRes = controllerWithJdbc.createRoom(Map.of(
                "hostName", "HostGamer",
                "userId", 10L,
                "username", "host10",
                "isRanked", true
        ));
        assertEquals(HttpStatus.CREATED, createRes.getStatusCode());
        String code = String.valueOf(((Map<?, ?>) createRes.getBody()).get("code"));

        ResponseEntity<?> joinRes = controllerWithJdbc.joinRoom(code, Map.of(
                "guestName", "GuestChallenger",
                "userId", 20L,
                "username", "guest20"
        ));
        assertEquals(HttpStatus.OK, joinRes.getStatusCode());
        Map<?, ?> joinBody = (Map<?, ?>) joinRes.getBody();
        assertNotNull(joinBody);
        assertEquals(12345L, joinBody.get("matchId"));
        assertEquals(publicId.toString(), joinBody.get("matchPublicId"));

        // Verify matches row was created with match_type = 'PVP' (2 distinct users)
        verify(jdbc).queryForObject(contains("INSERT INTO matches"), eq(Long.class),
                eq("PVP"), anyLong(), anyString(), eq(10L), eq(20L), eq(code));

        // Verify getRoom also returns matchId and matchPublicId
        ResponseEntity<?> getRes = controllerWithJdbc.getRoom(code);
        assertEquals(HttpStatus.OK, getRes.getStatusCode());
        Map<?, ?> getBody = (Map<?, ?>) getRes.getBody();
        assertEquals(12345L, getBody.get("matchId"));
        assertEquals(publicId.toString(), getBody.get("matchPublicId"));
    }
}
