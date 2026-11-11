package com.igniterealtime.openfire.plugin.federation.model;

/**
 * A negotiated pairing between a local MUC room and a room on {@code remoteDomain}. Only an
 * {@link State#ACTIVE} mapping carries traffic. Both ends hold the same {@code token}, minted on
 * accept, and it authorises later lifecycle requests. {@code activatedAt} is when the mapping last
 * became ACTIVE, in epoch milliseconds, or 0 when that is not known (a mapping from before 1.13.0).
 */
public record RoomMapping(
        String localRoomJid,
        String remoteRoomJid,
        String remoteDomain,
        State  state,
        String token,
        long   activatedAt
) {
    /** Lifecycle state of a mapping, as seen from this server. */
    public enum State {
        /** Requested by us and waiting for the remote to accept. */
        PENDING_OUT,
        /** Requested by the remote and waiting for our admin to accept. */
        PENDING_IN,
        /** Accepted by both ends; the only state that forwards traffic. */
        ACTIVE,
        /** Disabled by our admin. */
        DISABLED_LOCAL,
        /** Disabled by the remote admin. */
        DISABLED_REMOTE,
        /** Rejected by the remote. */
        REJECTED
    }

    public RoomMapping {
        if (state == null) state = State.PENDING_OUT;
        if (token == null) token = "";
    }

    /** A mapping whose activation time is not known. */
    public RoomMapping(String localRoomJid, String remoteRoomJid, String remoteDomain, State state, String token) {
        this(localRoomJid, remoteRoomJid, remoteDomain, state, token, 0L);
    }

    /** A new outgoing request that has no token yet. */
    public RoomMapping(String localRoomJid, String remoteRoomJid, String remoteDomain) {
        this(localRoomJid, remoteRoomJid, remoteDomain, State.PENDING_OUT, "");
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /** A copy of this mapping with a new state and token; becoming ACTIVE restarts its activation time. */
    public RoomMapping with(State newState, String newToken) {
        long activated = newState == State.ACTIVE && state != State.ACTIVE
                ? System.currentTimeMillis() : activatedAt;
        return new RoomMapping(localRoomJid, remoteRoomJid, remoteDomain, newState, newToken, activated);
    }

    /**
     * Whether a lifecycle request presenting {@code presented} speaks for this mapping. An empty
     * stored token is accepted, for mappings made before tokens (docs/protocol/04).
     */
    public boolean acceptsToken(String presented) {
        return token.isEmpty() || token.equals(presented);
    }
}
