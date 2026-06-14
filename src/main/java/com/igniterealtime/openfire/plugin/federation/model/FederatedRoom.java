package com.igniterealtime.openfire.plugin.federation.model;

/**
 * Describes a MUC room that has been tagged as federatable.
 *
 * For local rooms, originServer == the local XMPP domain.
 * For remote rooms, originServer == the peer domain that advertised it.
 */
public record FederatedRoom(
        String jid,
        String name,
        String description,
        String originServer
) {}
