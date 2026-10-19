package com.igniterealtime.openfire.plugin.federation.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A MUC room tagged for federation. A local room has this server as its {@code originServer}; a
 * remote room has the server that advertised it. {@code visibleTo} lists the servers allowed to see
 * the room: empty means nobody, and {@code "*"} means every peer.
 */
public record FederatedRoom(
        String jid,
        String name,
        String description,
        String originServer,
        Set<String> visibleTo
) {
    /** Copies {@code visibleTo} into an unmodifiable set; null becomes an empty set. */
    public FederatedRoom {
        visibleTo = (visibleTo == null) ? Collections.emptySet()
                                        : Collections.unmodifiableSet(new LinkedHashSet<>(visibleTo));
    }

    /** A room with an empty visibility list, so nobody sees it until one is set. */
    public FederatedRoom(String jid, String name, String description, String originServer) {
        this(jid, name, description, originServer, Collections.emptySet());
    }

    /** A room whose visibility list is copied from any collection. */
    public FederatedRoom(String jid, String name, String description, String originServer,
                         Collection<String> visibleTo) {
        this(jid, name, description, originServer,
             visibleTo == null ? Collections.<String>emptySet() : new LinkedHashSet<>(visibleTo));
    }
}
