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
     * Opt-in/-out peer trust gate. When true, only admin-approved peers may drive federation.
     * Defaults to true (secure by default); existing peers are grandfathered as approved.
     */
    public static final SystemProperty<Boolean> PEER_ALLOWLIST = SystemProperty.Builder.ofType(Boolean.class)
        .setKey("plugin.federation.peerAllowlist")
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
