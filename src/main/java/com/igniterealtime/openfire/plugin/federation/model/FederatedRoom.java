package com.igniterealtime.openfire.plugin.federation.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Describes a MUC room that has been tagged as federatable.
 *
 * For local rooms, originServer == the local XMPP domain.
 * For remote rooms, originServer == the peer domain that advertised it.
 *
 * {@code visibleTo} is the per-room visibility ACL: the set of destination server domains allowed to
 * see this room's advertisement. An EMPTY set means "visible to all peers" (the default). The set
 * travels with the advertisement on the wire so every relay can enforce it (see
 * {@code FederationManager.roomVisibleAtHop}).
 */
public record FederatedRoom(
        String jid,
        String name,
        String description,
        String originServer,
        Set<String> visibleTo
) {
    /** Normalises a null visibleTo to an empty (all-peers) set. */
    public FederatedRoom {
        visibleTo = (visibleTo == null) ? Collections.emptySet()
                                        : Collections.unmodifiableSet(new LinkedHashSet<>(visibleTo));
    }

    /** Convenience constructor for rooms with no visibility restriction (visible to all). */
    public FederatedRoom(String jid, String name, String description, String originServer) {
        this(jid, name, description, originServer, Collections.emptySet());
    }

    /** Convenience overload accepting any collection for the visibility ACL. */
    public FederatedRoom(String jid, String name, String description, String originServer,
                         Collection<String> visibleTo) {
        this(jid, name, description, originServer,
             visibleTo == null ? Collections.<String>emptySet() : new LinkedHashSet<>(visibleTo));
    }
}
