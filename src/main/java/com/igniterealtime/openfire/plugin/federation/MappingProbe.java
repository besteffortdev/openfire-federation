package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.RoomMapping;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationSender;
import com.igniterealtime.openfire.plugin.federation.protocol.FederationStanzaFactory;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.igniterealtime.openfire.plugin.federation.FederationNames.localDomain;

/**
 * End-to-end probes of mapped servers (mapping-ping, docs/protocol/04). A server that misses
 * {@link #MISS_LIMIT} pings in a row while a route to it exists counts as broken, and the occupants
 * reached through it are evicted until it answers again.
 */
public final class MappingProbe {

    private static final Logger Log = LoggerFactory.getLogger(MappingProbe.class);

    /** Unanswered pings in a row after which a mapped path counts as broken. */
    static final int MISS_LIMIT = 3;

    /** Domains that have answered a probe at least once; persisted so break detection survives a restart. */
    private static final String PROBE_CAPABLE_KEY = "plugin.federation.probeCapableDomains";
    private static final int MAX_PROBE_CAPABLE = 1024;

    /** Probe bookkeeping for one mapped domain. */
    private static final class State {
        volatile boolean everPonged = false;
        volatile int     missed     = 0;
        volatile boolean broken     = false;
        volatile long    lastPingAt = 0;
        volatile long    lastPongAt = 0;
        volatile long    lastRttMs  = -1;
    }

    private final FederationManager manager;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private final Set<String> probeCapable = ConcurrentHashMap.newKeySet();
    private volatile boolean probeCapableLoaded = false;

    MappingProbe(FederationManager manager) {
        this.manager = manager;
    }

    private Set<String> probeCapable() {
        if (!probeCapableLoaded) {
            synchronized (probeCapable) {
                if (!probeCapableLoaded) {
                    String csv = JiveGlobals.getProperty(PROBE_CAPABLE_KEY, "");
                    for (String d : csv.split(",")) {
                        if (!d.isBlank()) probeCapable.add(d.trim());
                    }
                    probeCapableLoaded = true;
                }
            }
        }
        return probeCapable;
    }

    /** Records that a routable domain answers mapping probes, which enables break detection toward it. */
    public void markProbeCapable(String domain) {
        if (domain == null || domain.isEmpty()) return;
        // Persisted, so bounded and limited to servers we route to: a peer can claim any origin.
        if (manager.getRoutingTable().findNextHop(domain).isEmpty()) return;
        if (probeCapable().size() >= MAX_PROBE_CAPABLE && !probeCapable().contains(domain)) return;
        if (probeCapable().add(domain)) {
            JiveGlobals.setProperty(PROBE_CAPABLE_KEY, String.join(",", probeCapable));
        }
    }

    /** Whether the mapped domain has stopped answering probes, although a route may still exist. */
    public boolean isPathBroken(String remoteDomain) {
        State st = states.get(remoteDomain);
        return st != null && st.broken;
    }

    /** Round trip of the newest pong from the domain in milliseconds, or -1 if none this run. */
    public long roundTripMs(String remoteDomain) {
        State st = states.get(remoteDomain);
        return st == null ? -1 : st.lastRttMs;
    }

    /** Seconds since the newest pong from the domain, or -1 if none this run. */
    public long pongAgeSecs(String remoteDomain) {
        State st = states.get(remoteDomain);
        return st == null || st.lastPongAt == 0 ? -1
             : (System.currentTimeMillis() - st.lastPongAt) / 1000;
    }

    /** Sends one mapping-ping to every routable domain we hold an ACTIVE mapping with, flagging broken paths. */
    public void sendPings() {
        Set<String> mapped = new LinkedHashSet<>();
        for (List<RoomMapping> mappings : manager.getRoomManager().getLocalMappings().values()) {
            for (RoomMapping m : mappings) {
                if (m.isActive()) mapped.add(m.remoteDomain());
            }
        }
        states.keySet().retainAll(mapped);
        String localDomain = localDomain();
        for (String dest : mapped) {
            String nextHop = manager.getRoutingTable().findNextHop(dest).orElse(null);
            if (nextHop == null) continue;
            State st = states.computeIfAbsent(dest, k -> new State());
            if ((st.everPonged || probeCapable().contains(dest)) && !st.broken && st.missed >= MISS_LIMIT) {
                st.broken = true;
                onPathBroken(dest);
            }
            st.missed++;
            long now = System.currentTimeMillis();
            st.lastPingAt = now;
            FederationSender.send("mapping-ping", dest, () -> FederationStanzaFactory.mappingPing(
                    nextHop, dest, localDomain, "", Long.toString(now)));
        }
    }

    /** Records a pong from a mapped domain and re-syncs occupants if its path had been broken. */
    public void onPong(String remoteDomain, String echoedTs) {
        // Only domains we probe have state; a pong for any other is unsolicited.
        State st = states.get(remoteDomain);
        if (st == null) return;
        boolean wasBroken = st.broken;
        st.everPonged = true;
        markProbeCapable(remoteDomain);
        long now = System.currentTimeMillis();
        long sentAt = sentAtOf(echoedTs, st.lastPingAt);
        if (sentAt > 0 && sentAt <= now) st.lastRttMs = now - sentAt;
        st.lastPongAt = now;
        st.missed = 0;
        st.broken = false;
        if (wasBroken) {
            Log.info("Federation mapping path to {} responds again — re-syncing occupants", remoteDomain);
            manager.getOccupantSync().resyncMappedDestinations(Set.of(remoteDomain));
        }
    }

    /** The send time a pong echoes, or {@code fallback} when it echoes none or not a number. */
    private static long sentAtOf(String echoedTs, long fallback) {
        if (echoedTs == null || echoedTs.isEmpty()) return fallback;
        try {
            return Long.parseLong(echoedTs);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** A mapped domain stopped answering although a route exists: evicts its occupants. */
    private void onPathBroken(String remoteDomain) {
        Log.warn("Federation mapping path to {} is broken end-to-end ({} pings unanswered while a route "
               + "exists) — an intermediate server may be denying this traffic; evicting its occupants",
                 remoteDomain, MISS_LIMIT);
        for (List<RoomMapping> mappings : manager.getRoomManager().getLocalMappings().values()) {
            for (RoomMapping m : mappings) {
                if (m.isActive() && m.remoteDomain().equals(remoteDomain)) {
                    manager.getOccupantSync().evictForInactiveMapping(m.localRoomJid(), remoteDomain);
                }
            }
        }
    }
}
