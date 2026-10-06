package com.igniterealtime.openfire.plugin.federation.model;

/**
 * One entry in the federation routing table.
 *
 * destination — the server we want to reach
 * nextHop     — the directly-connected peer we send to in order to reach it
 * hops        — total hop count from us to destination (1 = directly connected)
 * updatedAt   — epoch ms when this entry was last refreshed
 * edge        — the path was learned across an UNTRUSTED edge somewhere upstream: either the
 *               advertiser flagged it so, or (on our own table) it was learned from an untrusted peer.
 *               A route without the flag through a trusted neighbour is preferred over any route with
 *               it, so an untrusted edge can never out-bid the trusted mesh by advertising a small hop
 *               count. See FederationRoutingTable.updateFromPeer.
 */
public record RouteEntry(
        String  destination,
        String  nextHop,
        int     hops,
        long    updatedAt,
        boolean edge
) {
    public RouteEntry(String destination, String nextHop, int hops) {
        this(destination, nextHop, hops, System.currentTimeMillis(), false);
    }

    public RouteEntry(String destination, String nextHop, int hops, boolean edge) {
        this(destination, nextHop, hops, System.currentTimeMillis(), edge);
    }
}
