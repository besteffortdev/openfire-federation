package com.igniterealtime.openfire.plugin.federation.protocol;

import java.util.Set;

/**
 * Which of a room's mappings a hub sends a relayed payload on to. Kept apart from
 * {@link FederationIQHandler#fanOutToOtherMappings} so the rules can be exercised without a server.
 */
final class RoomFanOut {

    private RoomFanOut() {}

    /**
     * Why the hub does not send the payload to the mapping on {@code spoke}, or null to send it.
     *
     * @param fromDomain  the neighbour the payload arrived from
     * @param viaServers  the relay trail it arrived with
     * @param hubs        the {@link FederationStanzaFactory#ATTR_HUBS} trail it arrived with
     * @param payloadHome the home server of the user it is about, or empty if unknown
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
