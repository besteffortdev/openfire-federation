package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.util.SystemProperty;

/**
 * The plugin's Openfire system properties. Registering them lists each one, with its default, under
 * the Federation plugin in the admin console's System Properties page.
 */
public final class FederationProperties {

    /** Must equal plugin.xml's {@code <name>}, which Openfire uses to remove these properties on unload. */
    private static final String PLUGIN = "Federation";

    /** Registers a Boolean property, reusing one an earlier load left registered. */
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

    /** Registers an Integer property, reusing one an earlier load left registered. */
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

    /** Registers a String property, reusing one an earlier load left registered. */
    @SuppressWarnings("unchecked")
    private static SystemProperty<String> stringProp(String key, String defaultValue) {
        try {
            return SystemProperty.Builder.ofType(String.class)
                .setKey(key)
                .setPlugin(PLUGIN)
                .setDefaultValue(defaultValue)
                .setDynamic(true)
                .build();
        } catch (IllegalArgumentException alreadyRegistered) {
            return (SystemProperty<String>) SystemProperty.getProperty(key)
                .orElseThrow(() -> alreadyRegistered);
        }
    }

    /** Only configured peers may federate (default true); false accepts any server, registered as untrusted. */
    public static final SystemProperty<Boolean> PEER_ALLOWLIST =
        boolProp("plugin.federation.peerAllowlist", true, true);

    /** Whether remote users may join local rooms directly, without a mapping (default true). */
    public static final SystemProperty<Boolean> ALLOW_REMOTE_ROOM_TRAVERSAL =
        boolProp("plugin.federation.allowRemoteRoomTraversal", true, true);

    /** Whether 1:1 traffic to multi-hop peers is relayed over the overlay instead of native S2S (default true). */
    public static final SystemProperty<Boolean> DIRECT_MSG_RELAY =
        boolProp("plugin.federation.directMessageRelay", true, true);

    /** Whether subscribing to a multi-hop contact is followed by a presence probe over the overlay (default true). */
    public static final SystemProperty<Boolean> PROBE_ON_SUBSCRIBE =
        boolProp("plugin.federation.probeOnSubscribe", true, true);

    /** Whether to disable Openfire's server-wide S2S idle reaper; applied at start. */
    public static final SystemProperty<Boolean> DISABLE_S2S_IDLE =
        boolProp("plugin.federation.disableS2SIdle", true, false);

    /** Keepalive interval to reachable peers, in seconds. */
    public static final SystemProperty<Integer> KEEPALIVE_SECONDS =
        intProp("plugin.federation.keepaliveSeconds", 240, 30);

    /** The longest reconnect back-off for unreachable peers, in seconds. */
    public static final SystemProperty<Integer> RECONNECT_SECONDS =
        intProp("plugin.federation.reconnectSeconds", 30, 5);

    /** Mapping probe interval in seconds; 0 disables it, and values below 15 are raised to 15. */
    public static final SystemProperty<Integer> MAPPING_PING_SECONDS =
        intProp("plugin.federation.mappingPingSeconds", 30, 0);

    /** Comma-separated servers whose users may search this server's users (XEP-0055); blank allows none. */
    public static final SystemProperty<String> SEARCH_ALLOWED_SERVERS =
        stringProp("plugin.federation.search.allowedServers", "");

    /** Whether HTTP File Upload shares are relayed to the servers that deliver them (default true). */
    public static final SystemProperty<Boolean> FILES_ENABLED =
        boolProp("plugin.federation.files.enabled", true, true);

    /** Largest file the relay stages, sends or accepts, in megabytes. */
    public static final SystemProperty<Integer> FILES_MAX_MB =
        intProp("plugin.federation.files.maxSizeMB", 25, 1);

    /** Bytes per file chunk before base64, which adds a third; keep well under the S2S stanza size limit. */
    public static final SystemProperty<Integer> FILES_CHUNK_BYTES =
        intProp("plugin.federation.files.chunkBytes", 131072, 16384);

    /** Pause between chunks in milliseconds, so a large file cannot starve chat traffic on the link. */
    public static final SystemProperty<Integer> FILES_CHUNK_DELAY_MS =
        intProp("plugin.federation.files.chunkDelayMs", 20, 0);

    /** Days a relayed file is kept before it is purged. */
    public static final SystemProperty<Integer> FILES_RETENTION_DAYS =
        intProp("plugin.federation.files.retentionDays", 90, 1);

    /** Absolute path of the relay's storage directory; changing it moves the stored files. */
    public static final SystemProperty<String> FILES_STORAGE_DIR =
        stringProp("plugin.federation.files.storageDir", "/var/lib/openfire/federation-files");

    /** Base URL of our download endpoint in rewritten links; blank derives it from the domain and HTTP-bind port. */
    public static final SystemProperty<String> FILES_PUBLIC_URL =
        stringProp("plugin.federation.files.publicUrlBase", "");

    /** Extra comma-separated host names that identify this server's upload URLs. */
    public static final SystemProperty<String> FILES_EXTRA_LOCAL_HOSTS =
        stringProp("plugin.federation.files.extraLocalHosts", "");

    /** Path fragment that marks an upload URL; blank accepts any path on a local host. */
    public static final SystemProperty<String> FILES_UPLOAD_PATH_MARKER =
        stringProp("plugin.federation.files.uploadPathMarker", "/httpfileupload/");

    /** Extensions the relay stages and accepts, checked at both ends; blank allows nothing and {@code *} everything. */
    public static final SystemProperty<String> FILES_ALLOWED_EXTENSIONS =
        stringProp("plugin.federation.files.allowedExtensions",
            "jpg,jpeg,png,gif,webp,bmp,svg,heic,mp4,mov,webm,mkv,avi,mp3,m4a,wav,ogg,flac,"
          + "pdf,txt,csv,json,xml,doc,docx,xls,xlsx,ppt,pptx,odt,ods,odp,zip,rar,7z,tar,gz");

    /** Whether files are scanned with ClamAV at both ends (default false); a scan that cannot run blocks the file. */
    public static final SystemProperty<Boolean> FILES_AV_ENABLED =
        boolProp("plugin.federation.files.avEnabled", false, true);

    /** Host name of the clamd endpoint. */
    public static final SystemProperty<String> FILES_AV_HOST =
        stringProp("plugin.federation.files.avHost", "clamav");

    /** Port of the clamd endpoint. */
    public static final SystemProperty<Integer> FILES_AV_PORT =
        intProp("plugin.federation.files.avPort", 3310, 1);

    /** Connect and read timeout of one clamd scan, in milliseconds. */
    public static final SystemProperty<Integer> FILES_AV_TIMEOUT_MS =
        intProp("plugin.federation.files.avTimeoutMs", 30_000, 1_000);

    /** Days the scan and rejection logs keep an entry; pruned at start and every six hours. */
    public static final SystemProperty<Integer> FILES_LOG_RETENTION_DAYS =
        intProp("plugin.federation.files.logRetentionDays", 180, 1);

    /** Loads the class, whose static initialisers register every property. */
    public static void register() { }

    private FederationProperties() {}
}
