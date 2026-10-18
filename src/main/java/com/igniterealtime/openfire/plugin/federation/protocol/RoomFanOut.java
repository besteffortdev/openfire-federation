package com.igniterealtime.openfire.plugin.federation.protocol;

import java.util.Set;

/**
 * Decides which of a room's mappings a hub relays a payload to. Kept apart from
 * {@link FederationIQHandler} so the rules can be unit-tested without a running server.
 */
final class RoomFanOut {

    private RoomFanOut() {}

    /**
     * Why the hub must not relay the payload to the mapping on {@code spoke}, or null to relay it.
     * {@code hubs} is the {@link FederationStanzaFactory#ATTR_HUBS} trail the payload arrived with.
     */
    static String skipReason(String spoke, String fromDomain, Set<String> viaServers, String hubs,
                             String payloadHome) {
        if (spoke.equals(fromDomain))                          return "is direct sender";
        if (viaServers.contains(spoke))                        return "in via trail";
        if (spoke.equals(payloadHome))                         return "is payload home";
        if (FederationStanzaFactory.viaContains(hubs, spoke))  return "already a hub for it";
        return null;
    }
}
