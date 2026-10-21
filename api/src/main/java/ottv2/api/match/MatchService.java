package ottv2.api.match;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ottv2.api.match.MatchDtos.CreateMatchRequest;
import ottv2.api.match.MatchDtos.MatchResponse;
import ottv2.api.match.MatchDtos.Participant;
import ottv2.api.match.MatchDtos.PlayableSubmission;
import ottv2.api.submission.SubmissionDtos.Language;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MatchService {
    private static final MatchJob.MapConfiguration DEFAULT_MAP = new MatchJob.MapConfiguration(8, 0, 0, 0);
    private static final String MATCH_SELECT = """
            SELECT m.public_id, m.status, m.seed, m.winner_side, m.end_reason, m.total_turns,
                   m.queued_at, m.finished_at, m.created_month,
                   a.bot_id AS a_bot_id, ba.name AS a_bot_name, a.submission_id AS a_submission_id, a.fault AS a_fault,
                   a.source_sha256 AS a_sha, a.version_no AS a_ver,
                   b.bot_id AS b_bot_id, bb.name AS b_bot_name, b.submission_id AS b_submission_id, b.fault AS b_fault,
                   b.source_sha256 AS b_sha, b.version_no AS b_ver
            FROM matches m
            JOIN match_participants a ON a.match_id=m.id AND a.created_month=m.created_month AND a.side='A'
            JOIN bots ba ON ba.id=a.bot_id
            JOIN match_participants b ON b.match_id=m.id AND b.created_month=m.created_month AND b.side='B'
            JOIN bots bb ON bb.id=b.bot_id
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public MatchService(JdbcTemplate jdbc, TransactionTemplate transaction, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.mapper = mapper;
    }

    public MatchResponse create(long ownerId, CreateMatchRequest request) {
        return transaction.execute(status -> {
            ParticipantJobData sideA = readySubmission(request.submissionAId());
            ParticipantJobData sideB = readySubmission(request.submissionBId());
            if (sideA.botId() == sideB.botId()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A bot cannot play against itself");
            }
            if (sideA.ownerId() != ownerId && sideB.ownerId() != ownerId) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "At least one submission must belong to you");
            }

            long seed = random.nextLong();
            String mapJson = writeJson(DEFAULT_MAP);
            MatchInsert inserted = jdbc.queryForObject("""
                    INSERT INTO matches(match_type, seed, map_config, engine_ver, status)
                    VALUES ('FRIENDLY', ?, ?::jsonb, '1', 'QUEUED')
                    RETURNING id, public_id, created_month, queued_at
                    """, (rs, row) -> new MatchInsert(rs.getLong("id"), rs.getObject("public_id", UUID.class),
                    rs.getDate("created_month").toLocalDate(), rs.getTimestamp("queued_at").toInstant()), seed, mapJson);

            jdbc.update("""
                    INSERT INTO match_participants(match_id, created_month, side, bot_id, submission_id, source_sha256, version_no)
                    VALUES (?, ?, 'A', ?, ?, ?, ?), (?, ?, 'B', ?, ?, ?, ?)
                    """, inserted.id(), Date.valueOf(inserted.createdMonth()), sideA.botId(), sideA.submissionId(), sideA.sourceSha256(), sideA.versionNo(),
                    inserted.id(), Date.valueOf(inserted.createdMonth()), sideB.botId(), sideB.submissionId(), sideB.sourceSha256(), sideB.versionNo());

            MatchJob job = new MatchJob("MATCH_JOB", inserted.id(), inserted.publicId().toString(),
                    inserted.createdMonth().toString(), seed, DEFAULT_MAP,
                    sideA.toJob(), sideB.toJob());
            jdbc.update("INSERT INTO outbox_events(topic, payload) VALUES ('match.ladder.q', ?::jsonb)", writeJson(job));
            return response(inserted.id(), inserted.createdMonth());
        });
    }

    public List<MatchResponse> list(long ownerId) {
        return jdbc.query(MATCH_SELECT + """
                WHERE EXISTS (SELECT 1 FROM match_participants mine JOIN bots owned ON owned.id=mine.bot_id
                              WHERE mine.match_id=m.id AND mine.created_month=m.created_month AND owned.owner_id=?)
                ORDER BY m.queued_at DESC LIMIT 100
                """, (rs, row) -> map(rs), ownerId);
    }

    public List<MatchResponse> listLadder(long ownerId) {
        return jdbc.query(MATCH_SELECT + """
                WHERE m.match_type='LADDER' AND EXISTS (
                  SELECT 1 FROM match_participants mine JOIN bots owned ON owned.id=mine.bot_id
                  WHERE mine.match_id=m.id AND mine.created_month=m.created_month AND owned.owner_id=?
                )
                ORDER BY m.queued_at DESC LIMIT 100
                """, (rs, row) -> map(rs), ownerId);
    }

    public List<PlayableSubmission> playableSubmissions() {
        return jdbc.query("""
                SELECT s.id, b.id AS bot_id, b.name AS bot_name, s.version_no, s.language, s.status
                FROM submissions s JOIN bots b ON b.id=s.bot_id
                WHERE s.status IN ('COMPILED','ACTIVE') AND s.artifact_key IS NOT NULL AND b.ladder_enabled=TRUE
                ORDER BY b.name, s.version_no DESC LIMIT 500
                """, (rs, row) -> new PlayableSubmission(rs.getLong("id"), rs.getLong("bot_id"),
                rs.getString("bot_name"), rs.getInt("version_no"), rs.getString("language"), rs.getString("status")));
    }

    public MatchResponse get(long ownerId, UUID publicId) {
        try {
            return jdbc.queryForObject(MATCH_SELECT + """
                    WHERE m.public_id=? AND EXISTS (
                      SELECT 1 FROM match_participants mine JOIN bots owned ON owned.id=mine.bot_id
                      WHERE mine.match_id=m.id AND mine.created_month=m.created_month AND owned.owner_id=?)
                    """, (rs, row) -> map(rs), publicId, ownerId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found");
        }
    }

    public record ReplayInfo(String storageKey, String replayJson) { }

    public ReplayInfo getReplayInfo(String matchIdentifier) {
        if (matchIdentifier == null || matchIdentifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dữ liệu Replay chưa sẵn sàng hoặc đã bị xóa");
        }
        String sql = """
                SELECT r.storage_key, r.replay_data::text AS replay_json
                FROM matches m
                LEFT JOIN replays r ON r.match_id = m.id AND r.created_month = m.created_month
                WHERE (m.public_id::text = ? OR m.id::text = ?)
                LIMIT 1
                """;
        List<ReplayInfo> list = jdbc.query(sql, (rs, rowNum) ->
                new ReplayInfo(rs.getString("storage_key"), rs.getString("replay_json")),
                matchIdentifier.trim(), matchIdentifier.trim());
        if (!list.isEmpty() && (list.get(0).storageKey() != null || list.get(0).replayJson() != null)) {
            return list.get(0);
        }

        // Fallback: Kiểm tra replay trong bảng user_matches theo replay_id hoặc id
        try {
            String pvpSql = """
                    SELECT replay_id, replay_data::text AS replay_json
                    FROM user_matches
                    WHERE (replay_id = ? OR id::text = ?) AND replay_data IS NOT NULL
                    ORDER BY id DESC LIMIT 1
                    """;
            List<ReplayInfo> pvpList = jdbc.query(pvpSql, (rs, rowNum) ->
                    new ReplayInfo(rs.getString("replay_id"), rs.getString("replay_json")),
                    matchIdentifier.trim(), matchIdentifier.trim());
            if (!pvpList.isEmpty() && pvpList.get(0).replayJson() != null) {
                return pvpList.get(0);
            }
        } catch (Exception ignored) { }

        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Dữ liệu Replay chưa sẵn sàng hoặc đã bị xóa");
    }

    public String replayKey(long ownerId, UUID publicId) {
        return privateObjectKey(ownerId, publicId, null);
    }

    public String botLogKey(long ownerId, UUID publicId, String side) {
        if (!"A".equals(side) && !"B".equals(side)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Side must be A or B");
        }
        return privateObjectKey(ownerId, publicId, side);
    }

    private String privateObjectKey(long ownerId, UUID publicId, String side) {
        String sql = """
                SELECT r.storage_key, r.bot_a_log_key, r.bot_b_log_key,
                    EXISTS (SELECT 1 FROM match_participants p JOIN bots b ON b.id=p.bot_id
                            WHERE p.match_id=m.id AND p.created_month=m.created_month AND p.side='A' AND b.owner_id=?) AS owns_a,
                    EXISTS (SELECT 1 FROM match_participants p JOIN bots b ON b.id=p.bot_id
                            WHERE p.match_id=m.id AND p.created_month=m.created_month AND p.side='B' AND b.owner_id=?) AS owns_b
                FROM matches m JOIN replays r ON r.match_id=m.id AND r.created_month=m.created_month
                WHERE m.public_id=? AND EXISTS (
                  SELECT 1 FROM match_participants p JOIN bots b ON b.id=p.bot_id
                  WHERE p.match_id=m.id AND p.created_month=m.created_month AND b.owner_id=?
                )
                """;
        try {
            return jdbc.queryForObject(sql, (rs, row) -> {
                if (side == null) return rs.getString("storage_key");
                if (!rs.getBoolean("owns_" + side.toLowerCase())) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot log not found");
                }
                String key = rs.getString("bot_" + side.toLowerCase() + "_log_key");
                if (key == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bot log not found");
                return key;
            }, ownerId, ownerId, publicId, ownerId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Replay or log not found");
        }
    }

    private ParticipantJobData readySubmission(long submissionId) {
        try {
            return jdbc.queryForObject("""
                    SELECT b.id AS bot_id, b.name, b.owner_id, s.id AS submission_id, s.version_no,
                           s.language, s.artifact_key, s.source_sha256
                    FROM submissions s JOIN bots b ON b.id=s.bot_id
                    WHERE s.id=? AND s.status IN ('COMPILED','ACTIVE') AND s.artifact_key IS NOT NULL
                      AND b.ladder_enabled=TRUE
                    """, (rs, row) -> new ParticipantJobData(rs.getLong("bot_id"), rs.getString("name"),
                    rs.getLong("owner_id"), rs.getLong("submission_id"), rs.getInt("version_no"), Language.valueOf(rs.getString("language")),
                    rs.getString("artifact_key"), rs.getString("source_sha256").trim()), submissionId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both submissions must be compiled and playable");
        }
    }

    private MatchResponse response(long id, java.time.LocalDate createdMonth) {
        return jdbc.queryForObject(MATCH_SELECT + " WHERE m.id=? AND m.created_month=?",
                (rs, row) -> map(rs), id, Date.valueOf(createdMonth));
    }

    private static MatchResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new MatchResponse(rs.getObject("public_id", UUID.class), rs.getString("status"), rs.getLong("seed"),
                rs.getString("winner_side"), rs.getString("end_reason"), (Integer) rs.getObject("total_turns"),
                rs.getTimestamp("queued_at").toInstant(), finished == null ? null : finished.toInstant(),
                new Participant(rs.getLong("a_bot_id"), rs.getString("a_bot_name"), rs.getLong("a_submission_id"), rs.getString("a_fault"),
                        rs.getString("a_sha"), (Integer) rs.getObject("a_ver")),
                new Participant(rs.getLong("b_bot_id"), rs.getString("b_bot_name"), rs.getLong("b_submission_id"), rs.getString("b_fault"),
                        rs.getString("b_sha"), (Integer) rs.getObject("b_ver")));
    }

    private String writeJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not encode match job", e); }
    }

    private record MatchInsert(long id, UUID publicId, java.time.LocalDate createdMonth, Instant queuedAt) { }
    private record ParticipantJobData(long botId, String botName, long ownerId, long submissionId, int versionNo,
                                      Language language, String artifactKey, String sourceSha256) {
        MatchJob.ParticipantJob toJob() {
            return new MatchJob.ParticipantJob(botId, botName, ownerId, submissionId,
                    language.name(), artifactKey, sourceSha256);
        }
    }
}
