package ottv2.api.match;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class MatchDtos {
    private MatchDtos() { }

    public record CreateMatchRequest(@NotNull Long submissionAId, @NotNull Long submissionBId) { }
    public record MatchResponse(UUID id, String status, Long seed, String winnerSide,
                                String endReason, Integer totalTurns, Instant queuedAt,
                                Instant finishedAt, Participant sideA, Participant sideB) { }
    public record Participant(long botId, String botName, long submissionId, String fault, String sha256, Integer versionNo) {
        public Participant(long botId, String botName, long submissionId, String fault) {
            this(botId, botName, submissionId, fault, null, null);
        }
    }
    public record PlayableSubmission(long id, long botId, String botName, int versionNo,
                                     String language, String status) { }
}
