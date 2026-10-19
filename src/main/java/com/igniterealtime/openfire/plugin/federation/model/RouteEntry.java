package com.igniterealtime.openfire.plugin.federation.model;

/**
 * One routing-table entry: {@code destination} is reached through the directly connected peer
 * {@code nextHop} in {@code hops} hops. {@code edge} marks a path that crosses an untrusted peer;
 * such a path never outranks one through trusted peers. {@code updatedAt} is in epoch milliseconds.
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
