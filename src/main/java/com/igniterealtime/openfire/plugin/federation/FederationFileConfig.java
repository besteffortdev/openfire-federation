package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads a declarative {@code <federation>} block from Openfire's own {@code conf/openfire.xml} (if
 * present) and upserts its contents — configured peers, per-room federation/sharing/visibility/
 * mappings — into the JiveGlobals-backed DB through the same public methods the admin console uses.
 *
 * Read-only: {@code openfire.xml} is never written to. Safe to re-run (called once from
 * {@link FederationManager#start()} and again any time from the admin console's "Reload" button) —
 * it only adds peers/mappings that don't exist yet and updates fields the file actually declares;
 * anything present in the DB but absent from the file is never touched or removed.
 *
 * <pre>{@code
 * <jive>
 *   <federation>
 *     <peers>
 *       <peer domain="2502-xmpp.example.net" untrusted="false"/>
 *       <peer domain="2506-xmpp.example.net" untrusted="true">
 *         <exposedServers><server>2505-xmpp.example.net</server></exposedServers>
 *       </peer>
 *     </peers>
 *     <rooms>
 *       <room jid="team@conference.2501-xmpp.example.net" federated="true" autoAccept="true">
 *         <visibleTo><server>2502-xmpp.example.net</server></visibleTo>  <!-- or <server>*</server> for all -->
 *         <mappings>
 *           <mapping remoteJid="team@conference.2502-xmpp.example.net" remoteDomain="2502-xmpp.example.net"/>
 *         </mappings>
 *       </room>
 *     </rooms>
 *   </federation>
 * </jive>
 * }</pre>
 */
public class FederationFileConfig {

    private static final Logger Log = LoggerFactory.getLogger(FederationFileConfig.class);
    private static final int MAX_WARNINGS = 20;

    public record IngestResult(boolean blockPresent, int peersAdded, int peersUpdated,
                                int roomsUpdated, int mappingsRequested, List<String> warnings) {
        static final IngestResult EMPTY = new IngestResult(false, 0, 0, 0, 0, List.of());
    }

    private volatile IngestResult lastResult = IngestResult.EMPTY;
    private volatile Long lastLoadedAtMillis = null;

    public Path configFile() {
        return JiveGlobals.getHomePath().resolve("conf").resolve("openfire.xml");
    }

    public IngestResult lastResult() { return lastResult; }

    public Long lastLoadedAtMillis() { return lastLoadedAtMillis; }

    /** Parses {@code conf/openfire.xml} and upserts any {@code <federation>} block found. Never throws. */
    public IngestResult ingest(FederationManager manager) {
        File file = configFile().toFile();
        if (!file.isFile()) {
            Log.info("No openfire.xml found at {} — skipping file-based federation config", file);
            return remember(IngestResult.EMPTY);
        }

        Element fedEl;
        try {
            Document doc = new SAXReader().read(file);
            Element root = doc.getRootElement();
            fedEl = root == null ? null : root.element("federation");
        } catch (Exception e) {
            Log.warn("Failed to parse {} for federation config: {}", file, e.getMessage());
            return remember(IngestResult.EMPTY);
        }

        if (fedEl == null) {
            Log.info("No <federation> block in {} — skipping file-based federation config", file);
            return remember(IngestResult.EMPTY);
        }

        List<String> warnings = new ArrayList<>();
        int peersAdded = 0, peersUpdated = 0;
        Element peersEl = fedEl.element("peers");
        if (peersEl != null) {
            for (Element peerEl : peersEl.elements("peer")) {
                int[] counts = applyPeer(manager, peerEl, warnings);
                peersAdded += counts[0];
                peersUpdated += counts[1];
            }
        }

        int roomsUpdated = 0, mappingsRequested = 0;
        Element roomsEl = fedEl.element("rooms");
        if (roomsEl != null) {
            for (Element roomEl : roomsEl.elements("room")) {
                int[] counts = applyRoom(manager, roomEl, warnings);
                roomsUpdated += counts[0];
                mappingsRequested += counts[1];
            }
        }

        IngestResult result = new IngestResult(true, peersAdded, peersUpdated, roomsUpdated,
                mappingsRequested, List.copyOf(warnings));
        Log.info("Ingested federation config from {}: {} peer(s) added, {} peer field(s) updated, "
                + "{} room(s) updated, {} mapping(s) requested{}",
                file, peersAdded, peersUpdated, roomsUpdated, mappingsRequested,
                warnings.isEmpty() ? "" : ", " + warnings.size() + " warning(s)");
        for (String w : warnings) Log.warn("federation config: {}", w);
        return remember(result);
    }

    private IngestResult remember(IngestResult r) {
        lastResult = r;
        lastLoadedAtMillis = System.currentTimeMillis();
        return r;
    }

    // ── peers ────────────────────────────────────────────────────────────────

    /** Returns {addedCount, updatedCount} (each 0 or 1). */
    private int[] applyPeer(FederationManager manager, Element peerEl, List<String> warnings) {
        String rawDomain = peerEl.attributeValue("domain");
        if (!isPlausibleDomain(rawDomain)) {
            warn(warnings, "skipped <peer> with invalid domain '" + rawDomain + "'");
            return new int[]{0, 0};
        }
        String domain = rawDomain.strip().toLowerCase();

        PeerRegistry registry = manager.getPeerRegistry();
        boolean added = false, updated = false;
        if (!registry.contains(domain)) {
            manager.addPeer(domain);
            added = true;
        }

        String untrustedAttr = peerEl.attributeValue("untrusted");
        if (untrustedAttr != null) {
            boolean declared = Boolean.parseBoolean(untrustedAttr.strip());
            if (registry.isUntrusted(domain) != declared) {
                registry.setUntrusted(domain, declared);
                // Trust is negotiated per-link: announce the new stance to the peer and let it
                // block/clear against the remote's last-known stance — same as the admin console's
                // add-peer and set-untrusted actions (both follow setUntrusted with this call).
                manager.applyLocalTrustChange(domain);
                updated = true;
            }
        }

        Element exposedEl = peerEl.element("exposedServers");
        if (exposedEl != null) {
            // Constrain to the legitimate candidate set, same defense-in-depth filter the admin
            // console's set-exposed-servers action applies (drops the peer's own domain / anything
            // only reachable via the peer itself, so the persisted set stays meaningful).
            Set<String> allowed = manager.exposableServers(domain);
            Set<String> declared = new LinkedHashSet<>();
            for (Element s : exposedEl.elements("server")) {
                String v = s.getTextTrim().toLowerCase();
                if (!v.isEmpty() && allowed.contains(v)) declared.add(v);
                else if (!v.isEmpty()) warn(warnings, "peer " + domain + ": exposedServers entry '" + v
                        + "' is not a valid exposure target, skipped");
            }
            if (!declared.equals(registry.getExposedServers(domain))) {
                registry.setExposedServers(domain, declared);
                // Push the updated exposure (routes + room state) immediately if the link is up.
                registry.getPeer(domain)
                        .filter(p -> p.getStatus() == PeerServer.Status.REACHABLE)
                        .ifPresent(p -> { manager.sendRoutingUpdate(domain); manager.sendRoomState(domain); });
                updated = true;
            }
        }

        return new int[]{added ? 1 : 0, updated ? 1 : 0};
    }

    // ── rooms ────────────────────────────────────────────────────────────────

    /** Returns {roomUpdatedCount (0/1), mappingsRequestedCount}. */
    private int[] applyRoom(FederationManager manager, Element roomEl, List<String> warnings) {
        String rawJid = roomEl.attributeValue("jid");
        if (!isSafeFederationJid(rawJid)) {
            warn(warnings, "skipped <room> with invalid jid '" + rawJid + "'");
            return new int[]{0, 0};
        }
        String jid = rawJid.strip();

        FederatedRoomManager roomManager = manager.getRoomManager();
        boolean updated = false;

        String federatedAttr = roomEl.attributeValue("federated");
        if (federatedAttr != null) {
            boolean declared = Boolean.parseBoolean(federatedAttr.strip());
            if (roomManager.isFederated(jid) != declared) {
                manager.setRoomFederated(jid, declared);
                updated = true;
            }
        }

        String autoAcceptAttr = roomEl.attributeValue("autoAccept");
        if (autoAcceptAttr != null) {
            boolean declared = Boolean.parseBoolean(autoAcceptAttr.strip());
            if (roomManager.isAutoAccept(jid) != declared) {
                roomManager.setAutoAccept(jid, declared);
                updated = true;
            }
        }

        Element visibleEl = roomEl.element("visibleTo");
        if (visibleEl != null) {
            Set<String> declared = new LinkedHashSet<>();
            for (Element s : visibleEl.elements("server")) {
                String v = s.getTextTrim();
                if (v.equals(FederatedRoomManager.VISIBLE_ALL)) {
                    declared.clear();
                    declared.add(FederatedRoomManager.VISIBLE_ALL);
                    break;
                }
                if (!v.isEmpty()) declared.add(v.toLowerCase());
            }
            if (!declared.equals(roomManager.getRoomVisibility(jid))) {
                manager.setRoomVisibility(jid, declared);
                updated = true;
            }
        }

        int mappingsRequested = 0;
        Element mappingsEl = roomEl.element("mappings");
        if (mappingsEl != null) {
            for (Element mapEl : mappingsEl.elements("mapping")) {
                String rawRemoteJid = mapEl.attributeValue("remoteJid");
                String rawRemoteDomain = mapEl.attributeValue("remoteDomain");
                if (!isSafeFederationJid(rawRemoteJid) || !isPlausibleDomain(rawRemoteDomain)) {
                    warn(warnings, "skipped <mapping> on " + jid + " with invalid remoteJid/remoteDomain");
                    continue;
                }
                String remoteDomain = rawRemoteDomain.strip().toLowerCase();
                if (roomManager.getMappingForLocal(jid, remoteDomain) != null) continue;   // already mapped
                if (!manager.roomSharedWith(jid, remoteDomain)) {
                    warn(warnings, "skipped mapping " + jid + " -> " + rawRemoteJid
                            + ": room isn't shared (federated + visible) with " + remoteDomain + " yet");
                    continue;
                }
                manager.requestMapping(jid, rawRemoteJid.strip(), remoteDomain);
                mappingsRequested++;
            }
        }

        return new int[]{updated ? 1 : 0, mappingsRequested};
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void warn(List<String> warnings, String msg) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(msg);
    }

    /** Same character-class rule as {@code FederationApiServlet.isPlausibleDomain} (case-insensitive here). */
    private static boolean isPlausibleDomain(String d) {
        if (d == null) return false;
        d = d.strip();
        if (d.isEmpty() || d.length() > 253) return false;
        for (int i = 0; i < d.length(); i++) {
            char c = d.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                       || c == '.' || c == '-' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** Same character-class rule as {@code FederationIQHandler.isSafeFederationJid}. */
    private static boolean isSafeFederationJid(String jid) {
        if (jid == null) return false;
        jid = jid.strip();
        if (jid.isEmpty() || jid.length() > 3071) return false;   // RFC 7622 max length
        for (int i = 0; i < jid.length(); i++) {
            char c = jid.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
            if (c == '"' || c == '\'' || c == '<' || c == '>' || c == '&' || c == '\\' || c == ' ') return false;
        }
        return jid.indexOf('@') > 0 || jid.indexOf('.') > 0;
    }
}
