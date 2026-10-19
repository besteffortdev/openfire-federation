package com.igniterealtime.openfire.plugin.federation.model;

/**
 * A negotiated pairing between a local MUC room and a room on {@code remoteDomain}. Only an
 * {@link State#ACTIVE} mapping carries traffic. Both ends hold the same {@code token}, minted on
 * accept, and it authorises later lifecycle requests.
 */
public record RoomMapping(
        String localRoomJid,
        String remoteRoomJid,
        String remoteDomain,
        State  state,
        String token
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

    /** A new outgoing request that has no token yet. */
    public RoomMapping(String localRoomJid, String remoteRoomJid, String remoteDomain) {
        this(localRoomJid, remoteRoomJid, remoteDomain, State.PENDING_OUT, "");
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /** A copy of this mapping with a new state and token. */
    public RoomMapping with(State newState, String newToken) {
        return new RoomMapping(localRoomJid, remoteRoomJid, remoteDomain, newState, newToken);
    }
}
