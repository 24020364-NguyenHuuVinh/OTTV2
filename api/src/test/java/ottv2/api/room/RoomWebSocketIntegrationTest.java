package ottv2.api.room;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.ByteArrayMessageConverter;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "logging.level.org.springframework.web.socket=INFO",
                "logging.level.org.springframework.messaging=INFO"
        }
)
class RoomWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    private List<StompSession> activeSessions;

    @BeforeEach
    void setUp() {
        activeSessions = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : activeSessions) {
            try {
                if (session.isConnected()) {
                    session.disconnect();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private WebSocketStompClient createStompClient() {
        SockJsClient sockJsClient = new SockJsClient(List.of(new WebSocketTransport(new StandardWebSocketClient())));
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new CompositeMessageConverter(List.of(
                new MappingJackson2MessageConverter(),
                new StringMessageConverter(),
                new ByteArrayMessageConverter()
        )));
        return stompClient;
    }

    private StompSession connectClient(String url, BlockingQueue<Throwable> errors) throws Exception {
        WebSocketStompClient stompClient = createStompClient();
        StompSessionHandler sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public void handleException(StompSession session, StompCommand command, StompHeaders headers, byte[] payload, Throwable exception) {
                errors.add(exception);
            }

            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                errors.add(exception);
            }
        };

        StompSession session = stompClient.connectAsync(url, sessionHandler).get(5, TimeUnit.SECONDS);
        activeSessions.add(session);
        return session;
    }

    @Test
    void testWebSocketHandshakeAndValidAction() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession session = connectClient(url, errors);

        assertNotNull(session);
        assertTrue(session.isConnected(), "STOMP session should be connected");

        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();
        String roomCode = "test-action-1";

        session.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((Map<String, Object>) payload);
            }
        });

        // Send action with explicit application/json header
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/room/" + roomCode + "/action");
        headers.set("content-type", "application/json");

        String payload = "{\"type\":\"READY_CHANGE\",\"roomCode\":\"" + roomCode + "\",\"sender\":\"A\",\"ready\":true}";
        session.send(headers, payload.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> received = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "Should receive broadcasted message");
        assertEquals("READY_CHANGE", received.get("type"));
        assertEquals(roomCode, received.get("roomCode"));
        assertEquals("A", received.get("sender"));
        assertEquals(Boolean.TRUE, received.get("ready"));

        // Assert session is still connected (no 1002 close)
        assertTrue(session.isConnected(), "Session must remain connected after sending valid action");
        assertTrue(errors.isEmpty(), "No transport or STOMP errors should have occurred");
    }

    @Test
    void testMalformedActionKeepsConnectionAlive() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession session = connectClient(url, errors);

        assertTrue(session.isConnected());

        String roomCode = "test-malformed-2";
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();

        session.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((Map<String, Object>) payload);
            }
        });

        // Send intentionally malformed JSON
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/room/" + roomCode + "/action");
        headers.set("content-type", "application/json");
        session.send(headers, "{not-a-valid-json-string".getBytes(StandardCharsets.UTF_8));

        // Sleep briefly to let server process
        Thread.sleep(500);

        // Connection must still be alive!
        assertTrue(session.isConnected(), "Connection must stay alive even when client sends malformed JSON");
    }

    @Test
    void testMoveHandlingAndBroadcast() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();
        StompSession session = connectClient(url, errors);

        assertTrue(session.isConnected());

        String roomCode = "test-move-3";
        BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();

        session.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                messages.add((Map<String, Object>) payload);
            }
        });

        // Send move
        StompHeaders headers = new StompHeaders();
        headers.setDestination("/app/room/" + roomCode + "/move");
        headers.set("content-type", "application/json");

        String payload = "{\"type\":\"MAKE_MOVE\",\"roomCode\":\"" + roomCode + "\",\"sender\":\"A\",\"pieceId\":1,\"from\":[8,0],\"to\":[7,0]}";
        session.send(headers, payload.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> received = messages.poll(5, TimeUnit.SECONDS);
        assertNotNull(received, "Should receive move broadcast");
        assertEquals("MAKE_MOVE", received.get("type"));
        assertEquals(roomCode, received.get("roomCode"));
        assertEquals(1, received.get("pieceId"));

        assertTrue(session.isConnected(), "Session must remain connected after sending move");
    }

    @Test
    void testTwoPlayersMultiplayerBroadcast() throws Exception {
        String url = "http://localhost:" + port + "/api/ws";
        BlockingQueue<Throwable> hostErrors = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> guestErrors = new LinkedBlockingQueue<>();

        StompSession hostSession = connectClient(url, hostErrors);
        StompSession guestSession = connectClient(url, guestErrors);

        assertTrue(hostSession.isConnected());
        assertTrue(guestSession.isConnected());

        String roomCode = "test-pvp-999";
        BlockingQueue<Map<String, Object>> hostReceived = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> guestReceived = new LinkedBlockingQueue<>();

        hostSession.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                hostReceived.add((Map<String, Object>) payload);
            }
        });

        guestSession.subscribe("/topic/room/" + roomCode, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                guestReceived.add((Map<String, Object>) payload);
            }
        });

        // Guest sends PLAYER_JOINED
        StompHeaders guestHeaders = new StompHeaders();
        guestHeaders.setDestination("/app/room/" + roomCode + "/action");
        guestHeaders.set("content-type", "application/json");

        String joinPayload = "{\"type\":\"PLAYER_JOINED\",\"roomCode\":\"" + roomCode + "\",\"sender\":\"B\",\"senderName\":\"GuestUser\",\"ready\":true}";
        guestSession.send(guestHeaders, joinPayload.getBytes(StandardCharsets.UTF_8));

        // Both host and guest should receive the event
        Map<String, Object> msgForHost = hostReceived.poll(5, TimeUnit.SECONDS);
        Map<String, Object> msgForGuest = guestReceived.poll(5, TimeUnit.SECONDS);

        assertNotNull(msgForHost, "Host should receive PLAYER_JOINED event");
        assertEquals("PLAYER_JOINED", msgForHost.get("type"));
        assertEquals("GuestUser", msgForHost.get("senderName"));

        assertNotNull(msgForGuest, "Guest should receive PLAYER_JOINED event");
        assertEquals("PLAYER_JOINED", msgForGuest.get("type"));

        // Both sessions remain connected
        assertTrue(hostSession.isConnected(), "Host session must remain connected");
        assertTrue(guestSession.isConnected(), "Guest session must remain connected");
    }
}
