package com.igniterealtime.openfire.plugin.federation.model;

/**
 * A bilateral pairing between a local MUC room and a remote MUC room.
 *
 * A mapping is a negotiated, revocable relationship (since 1.3.46): the requester stores it
 * {@link State#PENDING_OUT} and sends a request; the remote stores {@link State#PENDING_IN}
 * (or auto-accepts) and, on accept, mints a {@link #token} shared by both ends. Only
 * {@link State#ACTIVE} mappings forward MUC traffic. Either end can {@link State#DISABLED_LOCAL
 * disable} a mapping (the other sees {@link State#DISABLED_REMOTE} — "disabled by peer").
 */
public record RoomMapping(
        String localRoomJid,   // room@conference.local-domain
        String remoteRoomJid,  // room@conference.remote-domain
        String remoteDomain,   // the XMPP domain that owns the remote room
        State  state,
        String token           // shared per-mapping consent token (empty until accepted / legacy)
) {
    public enum State {
        PENDING_OUT,      // we requested it; awaiting the remote's accept
        PENDING_IN,       // the remote requested it; awaiting our accept
        ACTIVE,           // accepted on both ends — the only state that forwards
        DISABLED_LOCAL,   // we disabled it
        DISABLED_REMOTE,  // the remote disabled it ("disabled by peer")
        REJECTED          // the remote rejected our request
    }

    public RoomMapping {
        if (state == null) state = State.PENDING_OUT;
        if (token == null) token = "";
    }

    /** Convenience constructor for a freshly-requested (outgoing, untokenised) mapping. */
    public RoomMapping(String localRoomJid, String remoteRoomJid, String remoteDomain) {
        this(localRoomJid, remoteRoomJid, remoteDomain, State.PENDING_OUT, "");
    }

    public boolean isActive() {
        return state == State.ACTIVE;
    }

    /** Returns a copy of this mapping with a new state and token. */
    public RoomMapping with(State newState, String newToken) {
        return new RoomMapping(localRoomJid, remoteRoomJid, remoteDomain, newState, newToken);
    }
}
