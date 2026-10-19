package com.igniterealtime.openfire.plugin.federation;

import com.igniterealtime.openfire.plugin.federation.model.PeerServer;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.SystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Applies the optional {@code <federation>} block of {@code conf/openfire.xml}: file settings, peers
 * and room settings, through the same methods the admin console uses. It only adds and updates what
 * the file declares and never writes the file. The format is described in the README.
 */
public class FederationFileConfig {

    private static final Logger Log = LoggerFactory.getLogger(FederationFileConfig.class);
    private static final int MAX_WARNINGS = 20;

    /** What one ingest changed, for the log and the admin console. */
    public record IngestResult(boolean blockPresent, int peersAdded, int peersUpdated,
                                int roomsUpdated, int mappingsRequested, int settingsUpdated,
                                List<String> warnings) {
        static final IngestResult EMPTY = new IngestResult(false, 0, 0, 0, 0, 0, List.of());
    }

    private volatile IngestResult lastResult = IngestResult.EMPTY;
    private volatile Long lastLoadedAtMillis = null;

    /** The config block's element name; unrelated to the protocol element that happens to share it. */
    private static final String CONFIG_ELEMENT = "federation";

    public Path configFile() {
        return JiveGlobals.getHomePath().resolve("conf").resolve("openfire.xml");
    }

    /** A {@link SAXReader} that refuses DOCTYPEs and external entities (CERT IDS17-J). */
    private static SAXReader hardenedReader() throws SAXException {
        SAXReader reader = new SAXReader();
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return reader;
    }

    public IngestResult lastResult() { return lastResult; }

    public Long lastLoadedAtMillis() { return lastLoadedAtMillis; }

    /** Parses {@code conf/openfire.xml} and applies its {@code <federation>} block, if any; never throws. */
    public IngestResult ingest(FederationManager manager) {
        File file = configFile().toFile();
        if (!file.isFile()) {
            Log.info("No openfire.xml found at {} — skipping file-based federation config", file);
            return remember(IngestResult.EMPTY);
        }

        Element fedEl;
        try {
            Document doc = hardenedReader().read(file);
            Element root = doc.getRootElement();
            fedEl = root == null ? null : root.element(CONFIG_ELEMENT);
        } catch (DocumentException | SAXException e) {
            Log.warn("Failed to parse {} for federation config", file, e);
            return remember(IngestResult.EMPTY);
        }

        if (fedEl == null) {
            Log.info("No <federation> block in {} — skipping file-based federation config", file);
            return remember(IngestResult.EMPTY);
        }

        List<String> warnings = new ArrayList<>();
        int settingsUpdated = applyFiles(manager, fedEl.element("files"), warnings);

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
                mappingsRequested, settingsUpdated, List.copyOf(warnings));
        Log.info("Ingested federation config from {}: {} peer(s) added, {} peer field(s) updated, "
                + "{} room(s) updated, {} mapping(s) requested, {} setting(s) updated{}",
                file, peersAdded, peersUpdated, roomsUpdated, mappingsRequested, settingsUpdated,
                warnings.isEmpty() ? "" : ", " + warnings.size() + " warning(s)");
        for (String w : warnings) Log.warn("federation config: {}", w);
        return remember(result);
    }

    private IngestResult remember(IngestResult r) {
        lastResult = r;
        lastLoadedAtMillis = System.currentTimeMillis();
        return r;
    }

    /** Applies the {@code <files>} element's attributes to the file relay settings; returns how many changed. */
    private int applyFiles(FederationManager manager, Element filesEl, List<String> warnings) {
        if (filesEl == null) return 0;
        int updated = 0;

        String enabledAttr = filesEl.attributeValue("enabled");
        if (enabledAttr != null) {
            boolean declared = Boolean.parseBoolean(enabledAttr.strip());
            if (FederationProperties.FILES_ENABLED.getValue() != declared) {
                FederationProperties.FILES_ENABLED.setValue(declared);
                updated++;
            }
        }

        updated += applyIntSetting(filesEl, "maxSizeMB", FederationProperties.FILES_MAX_MB, 1, warnings);
        updated += applyIntSetting(filesEl, "retentionDays", FederationProperties.FILES_RETENTION_DAYS, 1, warnings);

        String dirAttr = filesEl.attributeValue("storageDir");
        if (dirAttr != null) {
            String declared = dirAttr.strip();
            if (declared.isEmpty()) {
                warn(warnings, "<files> storageDir is empty, skipped");
            } else if (!Path.of(declared).isAbsolute()) {
                warn(warnings, "<files> storageDir='" + declared + "' must be a full path, skipped");
            } else if (!declared.equals(FederationProperties.FILES_STORAGE_DIR.getValue())) {
                String previous = FederationProperties.FILES_STORAGE_DIR.getValue();
                FederationProperties.FILES_STORAGE_DIR.setValue(declared);
                String moveError = manager.getFileRelay() != null
                        ? manager.getFileRelay().storageDirChanged() : null;
                if (moveError != null) {
                    FederationProperties.FILES_STORAGE_DIR.setValue(previous);
                    warn(warnings, "<files> storageDir='" + declared + "' rejected: " + moveError);
                } else {
                    updated++;
                }
            }
        }
        return updated;
    }

    /** Applies one integer attribute; returns 1 when it was present, valid and different from the stored value. */
    private int applyIntSetting(Element el, String attr, SystemProperty<Integer> prop,
                                int min, List<String> warnings) {
        String raw = el.attributeValue(attr);
        if (raw == null) return 0;
        int declared;
        try {
            declared = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            warn(warnings, "<files> " + attr + "='" + raw + "' is not an integer, skipped");
            return 0;
        }
        if (declared < min) {
            warn(warnings, "<files> " + attr + "=" + declared + " is below the minimum of " + min + ", skipped");
            return 0;
        }
        if (prop.getValue() == declared) return 0;
        prop.setValue(declared);
        return 1;
    }

    /** Applies one {@code <peer>}; returns {added, updated}, each 0 or 1. */
    private int[] applyPeer(FederationManager manager, Element peerEl, List<String> warnings) {
        String domain = normalizedDomain(peerEl.attributeValue("domain"));
        if (domain == null) {
            warn(warnings, "skipped <peer> with invalid domain '" + peerEl.attributeValue("domain") + "'");
            return new int[]{0, 0};
        }

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
                manager.applyLocalTrustChange(domain);
                updated = true;
            }
        }

        Element exposedEl = peerEl.element("exposedServers");
        if (exposedEl != null) {
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
                registry.getPeer(domain)
                        .filter(p -> p.getStatus() == PeerServer.Status.REACHABLE)
                        .ifPresent(p -> { manager.sendRoutingUpdate(domain); manager.sendRoomState(domain); });
                updated = true;
            }
        }

        return new int[]{added ? 1 : 0, updated ? 1 : 0};
    }

    /** Applies one {@code <room>}; returns {updated (0 or 1), mappings requested}. */
    private int[] applyRoom(FederationManager manager, Element roomEl, List<String> warnings) {
        String jid = normalizedJid(roomEl.attributeValue("jid"));
        if (jid == null) {
            warn(warnings, "skipped <room> with invalid jid '" + roomEl.attributeValue("jid") + "'");
            return new int[]{0, 0};
        }

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

        String filesAttr = roomEl.attributeValue("files");
        if (filesAttr != null) {
            boolean declared = Boolean.parseBoolean(filesAttr.strip());
            if (roomManager.isFilesEnabled(jid) != declared) {
                roomManager.setFilesEnabled(jid, declared);
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
                String remoteJid = normalizedJid(mapEl.attributeValue("remoteJid"));
                String remoteDomain = normalizedDomain(mapEl.attributeValue("remoteDomain"));
                if (remoteJid == null || remoteDomain == null) {
                    warn(warnings, "skipped <mapping> on " + jid + " with invalid remoteJid/remoteDomain");
                    continue;
                }
                if (roomManager.getMappingForLocal(jid, remoteDomain) != null) continue;
                if (!manager.roomSharedWith(jid, remoteDomain)) {
                    warn(warnings, "skipped mapping " + jid + " -> " + remoteJid
                            + ": room isn't shared (federated + visible) with " + remoteDomain + " yet");
                    continue;
                }
                manager.requestMapping(jid, remoteJid, remoteDomain);
                mappingsRequested++;
            }
        }

        return new int[]{updated ? 1 : 0, mappingsRequested};
    }

    private static void warn(List<String> warnings, String msg) {
        if (warnings.size() < MAX_WARNINGS) warnings.add(msg);
    }

    /** {@code raw} trimmed and lowercased, or null when the result is not a plausible domain. */
    private static String normalizedDomain(String raw) {
        if (raw == null) return null;
        String domain = raw.strip().toLowerCase(Locale.ROOT);
        return FederationNames.isPlausibleDomain(domain) ? domain : null;
    }

    /** {@code raw} trimmed, or null when the result is not a safe JID. */
    private static String normalizedJid(String raw) {
        if (raw == null) return null;
        String jid = raw.strip();
        return FederationNames.isSafeJid(jid) ? jid : null;
    }
}
