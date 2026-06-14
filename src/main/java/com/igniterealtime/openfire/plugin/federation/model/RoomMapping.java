package com.igniterealtime.openfire.plugin.federation.model;

/**
 * A confirmed bilateral pairing between a local MUC room and a remote MUC room.
 *
 * When an admin on this server maps localRoomJid → remoteRoomJid, a room-mapping
 * stanza is sent to the remote server so both sides store the same relationship.
 * The interceptor uses this to route MUC messages to the correct remote room.
 */
public record RoomMapping(
        String localRoomJid,   // room@conference.local-domain
        String remoteRoomJid,  // room@conference.remote-domain
        String remoteDomain    // the XMPP domain that owns the remote room
) {}
