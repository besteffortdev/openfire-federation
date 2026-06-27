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

    /** Plugin canonical name (the plugins/ directory name) used to attribute properties. */
    private static final String PLUGIN = "federation";

    /**
     * Peer trust gate (secure by default). When true, only configured peers
     * (see {@link PeerRegistry#isApproved}) may drive federation; actions from any other server are
     * rejected. NOTE: with this on, both ends must explicitly add each other — auto-registration of
     * unknown peers is suppressed. Set to false for open federation (accept any server that connects).
     */
    public static final SystemProperty<Boolean> PEER_ALLOWLIST = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.federation.peerAllowlist")
        .setPlugin(PLUGIN)
        .setDefaultValue(true)
        .setDynamic(true)
        .build();

    /**
     * Force-federation gate (on by default). When true, a remote user cannot join or address a
     * local MUC room directly over a raw S2S connection — they must go through a federation room
     * mapping. Federation's own virtual occupants are injected locally (never as real S2S presence)
     * and are unaffected. Set false to allow direct cross-server room access alongside federation.
     */
    public static final SystemProperty<Boolean> BLOCK_DIRECT_MUC = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.federation.directRemoteMucBlocked")
        .setPlugin(PLUGIN)
        .setDefaultValue(true)
        .setDynamic(true)
        .build();

    /**
     * 1:1 federation relay (on by default). When true, an outbound 1:1 message addressed to a user on
     * an overlay-reachable peer domain is relayed hop-by-hop through the federation overlay (and native
     * S2S delivery is suppressed) instead of requiring a direct S2S link. Replies route back the same
     * way. Set false to fall back to Openfire's native S2S behaviour for 1:1 messages.
     */
    public static final SystemProperty<Boolean> DIRECT_MSG_RELAY = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.federation.directMessageRelay")
        .setPlugin(PLUGIN)
        .setDefaultValue(true)
        .setDynamic(true)
        .build();

    /**
     * Publish this server's online-user directory to federation peers (OFF by default — privacy).
     * When true, the set of currently-logged-in users is gossiped across the overlay so peers can
     * show who is reachable here in their Routing Table view. Untrusted peers never receive it.
     * Servers that leave this off can still send and receive 1:1 federated messages by typed JID.
     */
    public static final SystemProperty<Boolean> DIRECTORY_PUBLISH = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.federation.directoryPublish")
        .setPlugin(PLUGIN)
        .setDefaultValue(false)
        .setDynamic(true)
        .build();

    /**
     * Advertise this server's connected clients to peers as XEP-0048 bookmarks (OFF by default).
     * When true, the set of currently-logged-in users is pushed across the overlay and injected into
     * each peer user's bookmark storage (as {@code <url>} bookmarks), so they appear in a normal chat
     * client. Untrusted peers never receive it. Independent of the user-directory gossip.
     */
    public static final SystemProperty<Boolean> BOOKMARK_PUSH = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.federation.bookmarkPush")
        .setPlugin(PLUGIN)
        .setDefaultValue(false)
        .setDynamic(true)
        .build();

    /**
     * Probe a multi-hop contact's presence over the overlay when a local user subscribes to them
     * mid-session (ON by default). Openfire auto-probes a freshly-approved contact via native S2S,
     * which leaks past the federation interceptor and fails for a multi-hop peer (no direct link),
     * so the contact's presence is never pulled. With this on, we re-issue that probe over the
     * overlay (mirroring the login-time probe). Set false to fall back to Openfire's native probe.
     */
    public static final SystemProperty<Boolean> PROBE_ON_SUBSCRIBE = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.federation.probeOnSubscribe")
        .setPlugin(PLUGIN)
        .setDefaultValue(true)
        .setDynamic(true)
        .build();

    /** Disable Openfire's server-wide S2S idle reaper on startup (applied at start; needs restart). */
    public static final SystemProperty<Boolean> DISABLE_S2S_IDLE = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.federation.disableS2SIdle")
        .setPlugin(PLUGIN)
        .setDefaultValue(true)
        .setDynamic(false)
        .build();

    // Keepalive / reconnect are kept as plain second-integers because S2SMonitor reads them with
    // JiveGlobals.getIntProperty under the same key — registering them as Integer guarantees the
    // stored string ("240") stays byte-compatible with that reader.

    /** Keepalive ping interval (seconds) to reachable peers. */
    public static final SystemProperty<Integer> KEEPALIVE_SECONDS = SystemProperty.Builder.ofType(Integer.class)
        .setKey("plugin.federation.keepaliveSeconds")
        .setPlugin(PLUGIN)
        .setDefaultValue(240)
        .setMinValue(30)
        .setDynamic(true)
        .build();

    /** Reconnect back-off cap (seconds) for UNREACHABLE peers. */
    public static final SystemProperty<Integer> RECONNECT_SECONDS = SystemProperty.Builder.ofType(Integer.class)
        .setKey("plugin.federation.reconnectSeconds")
        .setPlugin(PLUGIN)
        .setDefaultValue(30)
        .setMinValue(5)
        .setDynamic(true)
        .build();

    /** Touching this class triggers the static field initialisers above, registering every property. */
    public static void register() { /* no-op; class load does the work */ }

    private FederationProperties() {}
}
