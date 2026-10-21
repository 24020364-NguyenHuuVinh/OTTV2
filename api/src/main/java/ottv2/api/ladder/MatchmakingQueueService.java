package ottv2.api.ladder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import ottv2.api.match.MatchJob;
import ottv2.api.room.RoomService;
import ottv2.api.room.RoomSession;

import java.security.SecureRandom;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MatchmakingQueueService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingQueueService.class);
    private static final MatchJob.MapConfiguration MAP = new MatchJob.MapConfiguration(8, 0, 0, 0);

    public record PvpQueueEntry(
            long userId,
            String username,
            String studentCode,
            String email,
            String displayName,
            int elo,
            String avatar,
            Instant enqueuedAt,
            String queueMode,
            String timeControlKey
    ) {}

    public record BotQueueEntry(
            long botId,
            long ownerId,
            String ownerUsername,
            String ownerStudentCode,
            String ownerEmail,
            String ownerDisplayName,
            String botName,
            long submissionId,
            int versionNo,
            String language,
            String artifactKey,
            String sourceSha256,
            int elo,
            String avatar,
            Instant enqueuedAt
    ) {
        MatchJob.ParticipantJob toJob() {
            return new MatchJob.ParticipantJob(botId, botName, ownerId, submissionId, language, artifactKey, sourceSha256);
        }
    }

    private final Map<Long, PvpQueueEntry> pvpQueue = new ConcurrentHashMap<>();
    private final Map<Long, BotQueueEntry> botQueue = new ConcurrentHashMap<>();
    private final Map<Long, PendingMatch> pendingMatches = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StringRedisTemplate redis;
    private final SimpMessagingTemplate messagingTemplate;
    private final RoomService roomService;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public MatchmakingQueueService(JdbcTemplate jdbc,
                                   TransactionTemplate transaction,
                                   @Autowired(required = false) StringRedisTemplate redis,
                                   @Autowired(required = false) SimpMessagingTemplate messagingTemplate,
                                   RoomService roomService,
                                   ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.redis = redis;
        this.messagingTemplate = messagingTemplate;
        this.roomService = roomService;
        this.mapper = mapper;
    }

    /**
     * Tham gia hàng đợi ghép trận
     */
    public Map<String, Object> join(long userId, String username, String displayName, String mode, Long botId) {
        return join(userId, username, displayName, mode, botId, "blitz_3_2");
    }

    public Map<String, Object> join(long userId, String username, String displayName, String mode, Long botId, String timeControlKey) {
        String cleanMode = mode != null ? mode.trim().toUpperCase() : "PVP";

        if ("PVP".equals(cleanMode) || "PVP_QUICK".equals(cleanMode)) {
            return joinPvpQueue(userId, username, displayName, cleanMode, timeControlKey);
        } else if ("BOT".equals(cleanMode)) {
            return joinBotQueue(userId, username, displayName, botId);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mode không hợp lệ. Chỉ hỗ trợ 'PVP', 'PVP_QUICK' hoặc 'BOT'.");
        }
    }

    /**
     * Rời khỏi hàng đợi ghép trận
     */
    public Map<String, Object> leave(long userId, String mode) {
        String cleanMode = mode != null ? mode.trim().toUpperCase() : null;
        boolean removedPvp = false;
        boolean removedBot = false;

        if (cleanMode == null || "PVP".equals(cleanMode) || "PVP_QUICK".equals(cleanMode)) {
            removedPvp = pvpQueue.remove(userId) != null;
        }

        if (cleanMode == null || "BOT".equals(cleanMode)) {
            Long targetBotId = null;
            for (Map.Entry<Long, BotQueueEntry> entry : botQueue.entrySet()) {
                if (entry.getValue().ownerId() == userId) {
                    targetBotId = entry.getKey();
                    break;
                }
            }
            if (targetBotId != null) {
                botQueue.remove(targetBotId);
                removedBot = true;
                if (redis != null) {
                    try {
                        long seasonId = activeSeason();
                        redis.opsForZSet().remove(LadderKeys.presence(seasonId), Long.toString(targetBotId));
                        redis.opsForHash().delete(LadderKeys.submissions(seasonId), Long.toString(targetBotId));
                    } catch (Exception ignored) {}
                }
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("status", "LEFT");
        res.put("removedPvp", removedPvp);
        res.put("removedBot", removedBot);
        res.put("message", "Đã rời khỏi hàng đợi thành công.");
        return res;
    }

    /**
     * Kiểm tra trạng thái hàng đợi hiện tại của user
     */
    public Map<String, Object> getStatus(long userId) {
        PvpQueueEntry pvp = pvpQueue.get(userId);
        BotQueueEntry bot = null;
        for (BotQueueEntry b : botQueue.values()) {
            if (b.ownerId() == userId) {
                bot = b;
                break;
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("inQueue", pvp != null || bot != null);
        res.put("inPvpQueue", pvp != null);
        res.put("inBotQueue", bot != null);
        PendingMatch pendingMatch = pendingMatches.get(userId);
        if (pendingMatch != null) {
            if (pendingMatch.createdAt().isBefore(Instant.now().minus(Duration.ofMinutes(2)))) {
                pendingMatches.remove(userId, pendingMatch);
            } else {
                res.put("matchFound", pendingMatch.event());
                pendingMatches.remove(userId, pendingMatch);
            }
        }
        if (pvp != null) {
            res.put("pvpWaitSeconds", Duration.between(pvp.enqueuedAt(), Instant.now()).toSeconds());
            res.put("pvpElo", pvp.elo());
            res.put("pvpMode", pvp.queueMode());
        }
        if (bot != null) {
            res.put("botWaitSeconds", Duration.between(bot.enqueuedAt(), Instant.now()).toSeconds());
            res.put("botId", bot.botId());
            res.put("botName", bot.botName());
            res.put("botElo", bot.elo());
        }
        return res;
    }

    // ==========================================
    // PVP QUEUE LOGIC
    // ==========================================

    private String normalizeTcKey(String tcKey) {
        if (tcKey == null || tcKey.isBlank()) return "blitz_3_2";
        String lower = tcKey.trim().toLowerCase();
        if ("rapid_5_3".equals(lower) || "5+3".equals(lower) || "rapid".equals(lower)) return "rapid_5_3";
        if ("bullet_1_1".equals(lower) || "1+1".equals(lower) || "bullet".equals(lower)) return "bullet_1_1";
        if ("auto".equals(lower) || "any".equals(lower) || "all".equals(lower)) return "auto";
        return "blitz_3_2";
    }

    private Map<String, Object> createTimeControlMap(String tcKey) {
        Map<String, Object> tc = new HashMap<>();
        switch (tcKey) {
            case "rapid_5_3" -> {
                tc.put("id", "5+3");
                tc.put("name", "🔥 Nhanh 5+3");
                tc.put("label", "Nhanh 5+3");
                tc.put("initialSeconds", 300);
                tc.put("incrementSeconds", 3);
                tc.put("baseMs", 300000);
                tc.put("incMs", 3000);
            }
            case "bullet_1_1" -> {
                tc.put("id", "1+1");
                tc.put("name", "🚀 Siêu Chớp 1+1");
                tc.put("label", "Siêu Chớp 1+1");
                tc.put("initialSeconds", 60);
                tc.put("incrementSeconds", 1);
                tc.put("baseMs", 60000);
                tc.put("incMs", 1000);
            }
            default -> {
                tc.put("id", "3+2");
                tc.put("name", "⚡ Chớp 3+2");
                tc.put("label", "Chớp 3+2");
                tc.put("initialSeconds", 180);
                tc.put("incrementSeconds", 2);
                tc.put("baseMs", 180000);
                tc.put("incMs", 2000);
            }
        }
        return tc;
    }

    private Map<String, Object> joinPvpQueue(long userId, String username, String displayName, String mode, String timeControlKey) {
        String cleanTcKey = normalizeTcKey(timeControlKey);

        // Nạp thông tin mới nhất từ DB
        Map<String, Object> userRow = jdbc.queryForMap(
                "SELECT student_code, email, display_name, COALESCE(pvp_elo, 1200) AS pvp_elo FROM users WHERE id = ?",
                userId
        );
        String sCode = (String) userRow.get("student_code");
        String em = (String) userRow.get("email");
        String dName = (String) userRow.get("display_name");
        int elo = ((Number) userRow.get("pvp_elo")).intValue();

        String finalUsername = (sCode != null && !sCode.isBlank()) ? sCode : (username != null ? username : em);
        String finalDisplayName = (dName != null && !dName.isBlank()) ? dName : (displayName != null ? displayName : finalUsername);
        String avatar = "https://api.dicebear.com/7.x/bottts/svg?seed=" + finalDisplayName;

        PvpQueueEntry self = new PvpQueueEntry(userId, finalUsername, sCode, em, finalDisplayName, elo, avatar, Instant.now(), mode, cleanTcKey);
        pvpQueue.put(userId, self);
        log.info("User {} (ID={}, ELO={}, Mode={}, TC={}) đã vào hàng đợi PvP", finalUsername, userId, elo, mode, cleanTcKey);

        // Thử ghép cặp ngay lập tức nếu có đối thủ thỏa mãn
        PvpMatchResult matched = tryMatchPvp(self);
        if (matched != null) {
            // This requester receives the match payload in this HTTP response;
            // retain the recoverable copy only for the opponent waiting in queue.
            pendingMatches.remove(userId);
            Map<String, Object> res = new HashMap<>();
            res.put("status", "MATCHED");
            res.put("type", "MATCH_FOUND");
            res.put("mode", matched.mode());
            res.put("matchId", matched.matchId());
            res.put("matchPublicId", matched.matchPublicId());
            res.put("roomCode", matched.roomCode());
            res.put("startsAt", matched.startsAt());
            res.put("countdownSeconds", 5);
            res.put("seed", matched.seed());
            res.put("timeControl", matched.timeControl());
            res.put("sideA", matched.sideA());
            res.put("sideB", matched.sideB());
            res.put("opponent", matched.sideB());
            res.put("isHost", true);
            res.put("isRanked", !"PVP_QUICK".equals(matched.mode()));
            res.put("message", "Đã tìm thấy đối thủ PvP!");
            return res;
        }

        Map<String, Object> res = new HashMap<>();
        res.put("status", "QUEUED");
        res.put("mode", mode);
        res.put("timeControl", cleanTcKey);
        res.put("elo", elo);
        res.put("message", "Đã tham gia hàng đợi ghép trận PvP. Đang tìm đối thủ...");
        return res;
    }

    private synchronized PvpMatchResult tryMatchPvp(PvpQueueEntry self) {
        if (!pvpQueue.containsKey(self.userId())) return null;

        boolean isSelfQuick = "PVP_QUICK".equalsIgnoreCase(self.queueMode());
        long waitedSeconds = Math.max(0, Duration.between(self.enqueuedAt(), Instant.now()).toSeconds());

        for (PvpQueueEntry candidate : pvpQueue.values()) {
            if (candidate.userId() == self.userId()) continue;

            // Kiểm tra thể thức thời gian: Không được ghép chéo 3m với 5m hay 1m
            String selfTc = self.timeControlKey() != null ? self.timeControlKey() : "blitz_3_2";
            String candTc = candidate.timeControlKey() != null ? candidate.timeControlKey() : "blitz_3_2";
            boolean isCompatibleTc = selfTc.equals(candTc) || "auto".equals(selfTc) || "auto".equals(candTc);
            if (!isCompatibleTc) {
                continue;
            }

            boolean isCandidateQuick = "PVP_QUICK".equalsIgnoreCase(candidate.queueMode());
            boolean sameMode = isSelfQuick == isCandidateQuick;
            if (!sameMode && waitedSeconds < 25) {
                // Ưu tiên ghép đúng cùng mode nếu chưa chờ quá 25s
                continue;
            }

            long candidateWaited = Math.max(0, Duration.between(candidate.enqueuedAt(), Instant.now()).toSeconds());
            long maxWait = Math.max(waitedSeconds, candidateWaited);

            // Cho PVP_QUICK: Cửa sổ ELO mở rộng nhanh: bắt đầu từ 250, sau 10s mở rộng mạnh, sau 15s không giới hạn ELO (ghép ngay)
            long window;
            if (isSelfQuick || isCandidateQuick) {
                window = maxWait >= 15 ? 10000 : Math.min(600, 250 + (maxWait / 3) * 60);
            } else {
                // PVP Ranked: Cửa sổ mở rộng từ 150 tới tối đa 400
                long maxWaitMinutes = maxWait / 60;
                window = Math.min(400, 150 + maxWaitMinutes * 50);
            }

            if (Math.abs(self.elo() - candidate.elo()) <= window) {
                // Ghép thành công!
                pvpQueue.remove(self.userId());
                pvpQueue.remove(candidate.userId());

                return executePvpMatch(self, candidate);
            }
        }
        return null;
    }

    private PvpMatchResult executePvpMatch(PvpQueueEntry a, PvpQueueEntry b) {
        String tcKey = "blitz_3_2";
        if (a.timeControlKey() != null && !"auto".equalsIgnoreCase(a.timeControlKey())) {
            tcKey = a.timeControlKey();
        } else if (b.timeControlKey() != null && !"auto".equalsIgnoreCase(b.timeControlKey())) {
            tcKey = b.timeControlKey();
        }

        Map<String, Object> finalTc = createTimeControlMap(tcKey);

        RoomSession room = roomService.createRoom(a.displayName(), finalTc);
        roomService.joinRoom(room.getCode(), b.displayName());

        long seed = random.nextLong();
        boolean isQuick = "PVP_QUICK".equalsIgnoreCase(a.queueMode()) || "PVP_QUICK".equalsIgnoreCase(b.queueMode());
        String matchMode = isQuick ? "PVP_QUICK" : "PVP";
        String sql = isQuick ? """
                INSERT INTO matches(match_type, seed, map_config, engine_ver, status)
                VALUES ('FRIENDLY', ?, ?::jsonb, '1', 'READY')
                RETURNING id
                """ : """
                INSERT INTO matches(match_type, seed, map_config, engine_ver, status)
                VALUES ('PVP', ?, ?::jsonb, '1', 'READY')
                RETURNING id
                """;

        Long matchId = jdbc.queryForObject(sql, Long.class, seed, writeJson(MAP));

        UUID publicId = jdbc.queryForObject("SELECT public_id FROM matches WHERE id=?", UUID.class, matchId);
        long startsAt = System.currentTimeMillis() + 5000L;

        Map<String, Object> event = new HashMap<>();
        event.put("type", "MATCH_FOUND");
        event.put("mode", matchMode);
        event.put("isRanked", !isQuick);
        event.put("matchId", matchId);
        event.put("matchPublicId", publicId != null ? publicId.toString() : UUID.randomUUID().toString());
        event.put("roomCode", room.getCode());
        event.put("startsAt", startsAt);
        event.put("countdownSeconds", 5);
        event.put("seed", seed);
        event.put("timeControl", finalTc);

        Map<String, Object> sideA = new HashMap<>();
        sideA.put("side", "A");
        sideA.put("userId", a.userId());
        sideA.put("username", a.username());
        sideA.put("studentCode", a.studentCode());
        sideA.put("email", a.email());
        sideA.put("displayName", a.displayName());
        sideA.put("name", a.displayName());
        sideA.put("elo", a.elo());
        sideA.put("avatar", a.avatar());
        event.put("sideA", sideA);

        Map<String, Object> sideB = new HashMap<>();
        sideB.put("side", "B");
        sideB.put("userId", b.userId());
        sideB.put("username", b.username());
        sideB.put("studentCode", b.studentCode());
        sideB.put("email", b.email());
        sideB.put("displayName", b.displayName());
        sideB.put("name", b.displayName());
        sideB.put("elo", b.elo());
        sideB.put("avatar", b.avatar());
        event.put("sideB", sideB);

        // Chuẩn bị payload cá nhân hóa: Bổ sung opponent và isHost cho từng bên
        Map<String, Object> eventForA = new HashMap<>(event);
        eventForA.put("opponent", sideB);
        eventForA.put("isHost", true);

        Map<String, Object> eventForB = new HashMap<>(event);
        eventForB.put("opponent", sideA);
        eventForB.put("isHost", false);

        // Keep a short-lived copy so clients can recover MATCH_FOUND if STOMP was
        // disconnected or had not finished subscribing when the pair was made.
        pendingMatches.put(a.userId(), new PendingMatch(eventForA, Instant.now()));
        pendingMatches.put(b.userId(), new PendingMatch(eventForB, Instant.now()));

        // Bắn sự kiện MATCH_FOUND tới kênh cá nhân của cả 2 người chơi (/topic/user/{username})
        broadcastToUser(a.username(), a.studentCode(), a.email(), eventForA);
        broadcastToUser(b.username(), b.studentCode(), b.email(), eventForB);

        // Bắn vào topic room
        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/room/" + room.getCode(), (Object) event);
        }

        log.info("PVP Match Found: {} vs {}, Mode={}, Room={}, MatchId={}", a.username(), b.username(), matchMode, room.getCode(), matchId);
        return new PvpMatchResult(matchId, publicId != null ? publicId.toString() : event.get("matchPublicId").toString(),
                room.getCode(), startsAt, seed, finalTc, sideA, sideB, matchMode);
    }

    private record PvpMatchResult(long matchId, String matchPublicId, String roomCode, long startsAt, long seed,
                                  Map<String, Object> timeControl, Map<String, Object> sideA, Map<String, Object> sideB,
                                  String mode) {}
    private record PendingMatch(Map<String, Object> event, Instant createdAt) {}

    // ==========================================
    // BOT QUEUE LOGIC
    // ==========================================

    private Map<String, Object> joinBotQueue(long userId, String username, String displayName, Long requestedBotId) {
        long seasonId = activeSeason();

        // 1. Tìm bot phù hợp
        String sql = """
                SELECT b.id AS bot_id, b.name AS bot_name, b.owner_id,
                       s.id AS submission_id, s.version_no, s.language, s.artifact_key, s.source_sha256,
                       COALESCE(r.elo, 1200) AS elo,
                       u.student_code, u.email, u.display_name
                FROM bots b
                JOIN users u ON u.id = b.owner_id
                JOIN submissions s ON s.id = b.active_submission_id
                LEFT JOIN bot_ratings r ON r.bot_id = b.id AND r.season_id = ?
                WHERE b.owner_id = ? AND b.ladder_enabled = TRUE
                  AND (b.deleted IS FALSE OR b.deleted IS NULL)
                  AND s.status IN ('COMPILED', 'ACTIVE')
                  AND s.artifact_key IS NOT NULL
                """ + (requestedBotId != null ? " AND b.id = ?" : " ORDER BY b.id DESC LIMIT 1");

        List<BotQueueEntry> entries = jdbc.query(sql, (rs, rowNum) -> {
            String sCode = rs.getString("student_code");
            String em = rs.getString("email");
            String dName = rs.getString("display_name");
            String bName = rs.getString("bot_name");
            String uName = (sCode != null && !sCode.isBlank()) ? sCode : (username != null ? username : em);
            String ownerName = (dName != null && !dName.isBlank()) ? dName : uName;
            String avatar = "https://api.dicebear.com/7.x/bottts/svg?seed=" + bName;

            return new BotQueueEntry(
                    rs.getLong("bot_id"),
                    rs.getLong("owner_id"),
                    uName,
                    sCode,
                    em,
                    ownerName,
                    bName,
                    rs.getLong("submission_id"),
                    rs.getInt("version_no"),
                    rs.getString("language"),
                    rs.getString("artifact_key"),
                    rs.getString("source_sha256").trim(),
                    rs.getInt("elo"),
                    avatar,
                    Instant.now()
            );
        }, requestedBotId != null ? new Object[]{seasonId, userId, requestedBotId} : new Object[]{seasonId, userId});

        if (entries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Không tìm thấy Bot hợp lệ nào có bản nộp mã nguồn đã biên dịch thành công để tham gia Ranked.");
        }

        BotQueueEntry self = entries.getFirst();
        botQueue.put(self.botId(), self);

        // Đồng bộ lên Redis Presence nếu có Redis
        if (redis != null) {
            try {
                redis.opsForZSet().add(LadderKeys.presence(seasonId), Long.toString(self.botId()), System.currentTimeMillis());
                redis.opsForHash().put(LadderKeys.submissions(seasonId), Long.toString(self.botId()), Long.toString(self.submissionId()));
                redis.opsForZSet().add(LadderKeys.ratings(seasonId), Long.toString(self.botId()), self.elo());
            } catch (Exception ignored) {}
        }

        log.info("Bot {} (ID={}, Owner={}, ELO={}) đã vào hàng đợi Bot Ranked", self.botName(), self.botId(), self.ownerUsername(), self.elo());

        // Thử ghép cặp ngay lập tức
        BotMatchResult matched = tryMatchBot(self, seasonId);
        if (matched != null) {
            // The joining owner receives this match directly in the HTTP response.
            pendingMatches.remove(userId);
            Map<String, Object> res = new HashMap<>();
            res.put("status", "MATCHED");
            res.put("type", "MATCH_FOUND");
            res.put("mode", "BOT");
            res.put("matchId", matched.matchId());
            res.put("matchPublicId", matched.matchPublicId());
            res.put("seriesId", matched.seriesId());
            res.put("startsAt", matched.startsAt());
            res.put("countdownSeconds", 3);
            res.put("seed", matched.seed());
            res.put("isRanked", true);
            res.put("sideA", matched.sideA());
            res.put("sideB", matched.sideB());
            res.put("message", "Đã tìm thấy đối thủ Bot!");
            return res;
        }

        Map<String, Object> res = new HashMap<>();
        res.put("status", "QUEUED");
        res.put("mode", "BOT");
        res.put("botId", self.botId());
        res.put("botName", self.botName());
        res.put("elo", self.elo());
        res.put("message", "Đã tham gia hàng đợi Bot Ranked. Đang tìm đối thủ...");
        return res;
    }

    private synchronized BotMatchResult tryMatchBot(BotQueueEntry self, long seasonId) {
        if (!botQueue.containsKey(self.botId())) return null;

        for (BotQueueEntry candidate : botQueue.values()) {
            if (candidate.botId() == self.botId()) continue;
            if (candidate.ownerId() == self.ownerId()) continue; // Chống tự đấu cùng chủ sở hữu

            long waitedMinutes = Math.max(0, Duration.between(self.enqueuedAt(), Instant.now()).toMinutes());
            long candidateWaitedMinutes = Math.max(0, Duration.between(candidate.enqueuedAt(), Instant.now()).toMinutes());
            long maxWait = Math.max(waitedMinutes, candidateWaitedMinutes);
            long window = Math.min(300, 150 + maxWait * 50);

            if (Math.abs(self.elo() - candidate.elo()) <= window) {
                // Ghép thành công!
                botQueue.remove(self.botId());
                botQueue.remove(candidate.botId());

                return executeBotMatch(self, candidate, seasonId);
            }
        }
        return null;
    }

    private BotMatchResult executeBotMatch(BotQueueEntry a, BotQueueEntry b, long seasonId) {
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO bot_ratings(bot_id, season_id) VALUES (?, ?), (?, ?) ON CONFLICT DO NOTHING",
                    a.botId(), seasonId, b.botId(), seasonId);

            long seed = random.nextLong();
            Long seriesId = jdbc.queryForObject("""
                    INSERT INTO ladder_series(season_id, bot_a_id, bot_b_id, submission_a_id, submission_b_id, seed)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, Long.class, seasonId, a.botId(), b.botId(), a.submissionId(), b.submissionId(), seed);

            Long matchId = jdbc.queryForObject("""
                    INSERT INTO matches(season_id, match_type, seed, map_config, engine_ver, status, ladder_series_id, series_game_no)
                    VALUES (?, 'LADDER', ?, ?::jsonb, '1', 'READY', ?, 1)
                    RETURNING id
                    """, Long.class, seasonId, seed, writeJson(MAP), seriesId);

            LocalDate month = jdbc.queryForObject("SELECT created_month FROM matches WHERE id=?",
                    (rs, row) -> rs.getDate(1).toLocalDate(), matchId);
            UUID publicId = jdbc.queryForObject("SELECT public_id FROM matches WHERE id=?", UUID.class, matchId);

            jdbc.update("""
                    INSERT INTO match_participants(match_id, created_month, side, bot_id, submission_id, elo_before, source_sha256, version_no)
                    VALUES (?, ?, 'A', ?, ?, ?, ?, ?),
                           (?, ?, 'B', ?, ?, ?, ?, ?)
                    """, matchId, Date.valueOf(month), a.botId(), a.submissionId(), a.elo(), a.sourceSha256(), a.versionNo(),
                    matchId, Date.valueOf(month), b.botId(), b.submissionId(), b.elo(), b.sourceSha256(), b.versionNo());

            MatchJob job = new MatchJob("MATCH_JOB", matchId, publicId.toString(), month.toString(), seed, MAP,
                    a.toJob(), b.toJob());
            jdbc.update("INSERT INTO outbox_events(topic, payload) VALUES ('match.ladder.q', ?::jsonb)", writeJson(job));

            long startsAt = System.currentTimeMillis() + 5000L;

            Map<String, Object> event = new HashMap<>();
            event.put("type", "MATCH_FOUND");
            event.put("mode", "BOT");
            event.put("matchId", matchId);
            event.put("matchPublicId", publicId.toString());
            event.put("seriesId", seriesId);
            event.put("startsAt", startsAt);
            event.put("countdownSeconds", 5);
            event.put("seed", seed);

            Map<String, Object> sideA = new HashMap<>();
            sideA.put("side", "A");
            sideA.put("userId", a.ownerId());
            sideA.put("username", a.ownerUsername());
            sideA.put("displayName", a.ownerDisplayName());
            sideA.put("botId", a.botId());
            sideA.put("botName", a.botName());
            sideA.put("name", a.botName() + " (" + a.ownerDisplayName() + ")");
            sideA.put("elo", a.elo());
            sideA.put("avatar", a.avatar());
            event.put("sideA", sideA);

            Map<String, Object> sideB = new HashMap<>();
            sideB.put("side", "B");
            sideB.put("userId", b.ownerId());
            sideB.put("username", b.ownerUsername());
            sideB.put("displayName", b.ownerDisplayName());
            sideB.put("botId", b.botId());
            sideB.put("botName", b.botName());
            sideB.put("name", b.botName() + " (" + b.ownerDisplayName() + ")");
            sideB.put("elo", b.elo());
            sideB.put("avatar", b.avatar());
            event.put("sideB", sideB);

            // Preserve a recoverable copy for each owner until their client confirms
            // it received the event through either STOMP or the status poll.
            pendingMatches.put(a.ownerId(), new PendingMatch(event, Instant.now()));
            pendingMatches.put(b.ownerId(), new PendingMatch(event, Instant.now()));

            broadcastToUser(a.ownerUsername(), a.ownerStudentCode(), a.ownerEmail(), event);
            broadcastToUser(b.ownerUsername(), b.ownerStudentCode(), b.ownerEmail(), event);

            log.info("BOT Match Found: {} vs {}, Series={}, MatchId={}", a.botName(), b.botName(), seriesId, matchId);
            return new BotMatchResult(matchId, publicId.toString(), seriesId, startsAt, seed, sideA, sideB);
        });
    }

    private record BotMatchResult(long matchId, String matchPublicId, long seriesId, long startsAt, long seed,
                                  Map<String, Object> sideA, Map<String, Object> sideB) {}

    // ==========================================
    // PERIODIC MATCHMAKING TICK (Every 2s)
    // ==========================================

    @Scheduled(fixedDelay = 2000)
    public void processQueues() {
        if (!pvpQueue.isEmpty()) {
            List<PvpQueueEntry> candidates = new ArrayList<>(pvpQueue.values());
            for (PvpQueueEntry entry : candidates) {
                tryMatchPvp(entry);
            }
        }

        if (!botQueue.isEmpty()) {
            long seasonId = activeSeason();
            List<BotQueueEntry> candidates = new ArrayList<>(botQueue.values());
            for (BotQueueEntry entry : candidates) {
                tryMatchBot(entry, seasonId);
            }
        }
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private void broadcastToUser(String username, String studentCode, String email, Map<String, Object> event) {
        if (messagingTemplate == null) return;
        try {
            if (username != null && !username.isBlank()) {
                messagingTemplate.convertAndSend("/topic/user/" + username, (Object) event);
            }
            if (studentCode != null && !studentCode.equalsIgnoreCase(username)) {
                messagingTemplate.convertAndSend("/topic/user/" + studentCode, (Object) event);
            }
            if (email != null) {
                String prefix = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
                if (!prefix.equalsIgnoreCase(username) && !prefix.equalsIgnoreCase(studentCode)) {
                    messagingTemplate.convertAndSend("/topic/user/" + prefix, (Object) event);
                }
            }
        } catch (Exception e) {
            log.warn("Lỗi khi bắn STOMP MATCH_FOUND tới user {}: {}", username, e.getMessage());
        }
    }

    private long activeSeason() {
        List<Long> ids = jdbc.query("""
                SELECT id FROM seasons WHERE status='ACTIVE' AND starts_at<=now() AND ends_at>now()
                ORDER BY starts_at LIMIT 1
                """, (rs, row) -> rs.getLong(1));
        return ids.isEmpty() ? 1L : ids.getFirst();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode matchmaking match json", e);
        }
    }
}
