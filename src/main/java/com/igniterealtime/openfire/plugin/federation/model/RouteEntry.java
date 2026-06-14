package com.igniterealtime.openfire.plugin.federation.model;

/**
 * One entry in the federation routing table.
 *
 * destination — the server we want to reach
 * nextHop     — the directly-connected peer we send to in order to reach it
 * hops        — total hop count from us to destination (1 = directly connected)
 * updatedAt   — epoch ms when this entry was last refreshed
 */
public record RouteEntry(
        String destination,
        String nextHop,
        int    hops,
        long   updatedAt
) {
    public RouteEntry(String destination, String nextHop, int hops) {
        this(destination, nextHop, hops, System.currentTimeMillis());
    }
}
