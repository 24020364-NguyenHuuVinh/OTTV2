package ottv2.api.room;

import java.time.Instant;

public class RoomSession {
    public enum Status {
        WAITING,
        PLAYING,
        FINISHED
    }

    private final String code;
    private final String hostName;
    private String guestName;
    private Status status;
    private final Object timeControl;
    private final Instant createdAt;
    private Instant joinedAt;
    private volatile Instant lastActiveAt;
    private boolean hostReady;
    private boolean guestReady;

    public RoomSession(String code, String hostName, Object timeControl) {
        this.code = code;
        this.hostName = hostName != null && !hostName.isBlank() ? hostName : "Chủ Phòng";
        this.timeControl = timeControl;
        this.status = Status.WAITING;
        this.createdAt = Instant.now();
        this.lastActiveAt = this.createdAt;
        this.hostReady = true;
        this.guestReady = false;
    }

    public String getCode() {
        return code;
    }

    public String getHostName() {
        return hostName;
    }

    public String getGuestName() {
        return guestName;
    }

    public void setGuestName(String guestName) {
        this.guestName = guestName;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Object getTimeControl() {
        return timeControl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void updateLastActive() {
        this.lastActiveAt = Instant.now();
    }

    public boolean isHostReady() {
        return hostReady;
    }

    public void setHostReady(boolean hostReady) {
        this.hostReady = hostReady;
    }

    public boolean isGuestReady() {
        return guestReady;
    }

    public void setGuestReady(boolean guestReady) {
        this.guestReady = guestReady;
    }

    public boolean isFull() {
        return guestName != null && !guestName.isBlank();
    }
}
