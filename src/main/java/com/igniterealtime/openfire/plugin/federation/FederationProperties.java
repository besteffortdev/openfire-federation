package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.util.SystemProperty;

/**
 * Central registry of the plugin's Openfire {@link SystemProperty} definitions.
 *
 * Registering them (rather than reading raw keys via {@code JiveGlobals.getXProperty(key, default)})
 * makes every property show up in <em>Admin Console → Server → System Properties</em> grouped under
 * the <strong>Federation</strong> plugin — with its default, a type-appropriate editor (a checkbox
 * for booleans), and automatic removal when the plugin is unloaded.
 *
 * The keys are identical to the ones the rest of the plugin already reads, so registration is
 * backwards compatible: existing values are picked up unchanged.
 */
public final class FederationProperties {

    /**
     * Plugin attribution for the registry. MUST equal plugin.xml's {@code <name>} ("Federation"):
     * on unload, PluginManager calls {@code SystemProperty.removePropertiesForPlugin(metadata.getName())}
     * with that display name — registering under any other string (e.g. the lowercase directory
     * name) means unload removes nothing and the next hot-reload's re-registration throws
     * "A SystemProperty already exists", killing the plugin load.
     */
    private static final String PLUGIN = "Federation";

    /**
     * Registers a Boolean property, adopting the already-registered instance when a previous
     * (or failed) load left it behind in Openfire's JVM-global registry — a duplicate
     * registration must never prevent the plugin from loading.
     */
    @SuppressWarnings("unchecked")
    private static SystemProperty<Boolean> boolProp(String key, boolean defaultValue, boolean dynamic) {
        try {
            return SystemProperty.Builder.ofType(Boolean.class)
                .setKey(key)
                .setPlugin(PLUGIN)
                .setDefaultValue(defaultValue)
                .setDynamic(dynamic)
                .build();
        } catch (IllegalArgumentException alreadyRegistered) {
            return (SystemProperty<Boolean>) SystemProperty.getProperty(key)
                .orElseThrow(() -> alreadyRegistered);
        }
    }

    /** Integer twin of {@link #boolProp}; also survives a stale registration. */
    @SuppressWarnings("unchecked")
    private static SystemProperty<Integer> intProp(String key, int defaultValue, int minValue) {
        try {
            return SystemProperty.Builder.ofType(Integer.class)
                .setKey(key)
                .setPlugin(PLUGIN)
                .setDefaultValue(defaultValue)
                .setMinValue(minValue)
                .setDynamic(true)
                .build();
        } catch (IllegalArgumentException alreadyRegistered) {
            return (SystemProperty<Integer>) SystemProperty.getProperty(key)
                .orElseThrow(() -> alreadyRegistered);
        }
    }

    /**
     * Peer trust gate (secure by default). When true, only configured peers
     * (see {@link PeerRegistry#isApproved}) may drive federation; actions from any other server are
     * rejected. NOTE: with this on, both ends must explicitly add each other — auto-registration of
     * unknown peers is suppressed. Set to false for open federation (accept any server that connects).
     */
    public static final SystemProperty<Boolean> PEER_ALLOWLIST =
        boolProp("plugin.federation.peerAllowlist", true, true);

    /**
     * Remote-room traversal gate (ON by default — permissive). When true, a user on another server
     * may traverse the federation network to join or address a local MUC room directly over S2S,
     * even when no federation room mapping exists — which is what lets users reach ad-hoc / private
     * rooms that are never advertised. Federation's own virtual occupants are injected locally
     * (never as real S2S presence) and are unaffected either way. Set false to lock rooms down so
     * they are only reachable through an explicit federation mapping.
     */
    public static final SystemProperty<Boolean> ALLOW_REMOTE_ROOM_TRAVERSAL =
        boolProp("plugin.federation.allowRemoteRoomTraversal", true, true);

    /**
     * 1:1 federation relay (on by default). When true, an outbound 1:1 message addressed to a user on
     * an overlay-reachable peer domain is relayed hop-by-hop through the federation overlay (and native
     * S2S delivery is suppressed) instead of requiring a direct S2S link. Replies route back the same
     * way. Set false to fall back to Openfire's native S2S behaviour for 1:1 messages.
     */
    public static final SystemProperty<Boolean> DIRECT_MSG_RELAY =
        boolProp("plugin.federation.directMessageRelay", true, true);

    /**
     * Publish this server's online-user directory to federation peers (OFF by default — privacy).
     * When true, the set of currently-logged-in users is gossiped across the overlay so peers can
     * show who is reachable here in their Routing Table view. Untrusted peers never receive it.
     * Servers that leave this off can still send and receive 1:1 federated messages by typed JID.
     */
    public static final SystemProperty<Boolean> DIRECTORY_PUBLISH =
        boolProp("plugin.federation.directoryPublish", false, true);

    /**
     * Advertise this server's connected clients to peers as XEP-0048 bookmarks (OFF by default).
     * When true, the set of currently-logged-in users is pushed across the overlay and injected into
     * each peer user's bookmark storage (as {@code <url>} bookmarks), so they appear in a normal chat
     * client. Untrusted peers never receive it. Independent of the user-directory gossip.
     */
    public static final SystemProperty<Boolean> BOOKMARK_PUSH =
        boolProp("plugin.federation.bookmarkPush", false, true);

    /**
     * Probe a multi-hop contact's presence over the overlay when a local user subscribes to them
     * mid-session (ON by default). Openfire auto-probes a freshly-approved contact via native S2S,
     * which leaks past the federation interceptor and fails for a multi-hop peer (no direct link),
     * so the contact's presence is never pulled. With this on, we re-issue that probe over the
     * overlay (mirroring the login-time probe). Set false to fall back to Openfire's native probe.
     */
    public static final SystemProperty<Boolean> PROBE_ON_SUBSCRIBE =
        boolProp("plugin.federation.probeOnSubscribe", true, true);

    /** Disable Openfire's server-wide S2S idle reaper on startup (applied at start; needs restart). */
    public static final SystemProperty<Boolean> DISABLE_S2S_IDLE =
        boolProp("plugin.federation.disableS2SIdle", true, false);

    // Interval properties are kept as plain second-integers because S2SMonitor reads them with
    // JiveGlobals.getIntProperty under the same key — registering them as Integer guarantees the
    // stored string ("240") stays byte-compatible with that reader.

    /** Keepalive ping interval (seconds) to reachable peers. */
    public static final SystemProperty<Integer> KEEPALIVE_SECONDS =
        intProp("plugin.federation.keepaliveSeconds", 240, 30);

    /** Reconnect back-off cap (seconds) for UNREACHABLE peers. */
    public static final SystemProperty<Integer> RECONNECT_SECONDS =
        intProp("plugin.federation.reconnectSeconds", 30, 5);

    /** End-to-end mapping probe interval (seconds); 0 disables (effective values below 15 are clamped). */
    public static final SystemProperty<Integer> MAPPING_PING_SECONDS =
        intProp("plugin.federation.mappingPingSeconds", 60, 0);

    /** Touching this class triggers the static field initialisers above, registering every property. */
    public static void register() { /* no-op; class load does the work */ }

    private FederationProperties() {}
}
