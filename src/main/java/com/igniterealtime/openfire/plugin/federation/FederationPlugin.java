package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;

import java.io.File;

/**
 * Openfire entry point for the federation plugin. Openfire calls it when the plugin jar is loaded
 * or unloaded, and it hands the whole lifecycle to {@link FederationManager}.
 */
public class FederationPlugin implements Plugin {

    private static volatile FederationPlugin instance;

    private FederationManager manager;

    /** The loaded plugin instance, or null while the plugin is not loaded. */
    public static FederationPlugin getInstance() {
        return instance;
    }

    /** Starts the manager; a failed start is undone before the failure is passed on to Openfire. */
    @Override
    public void initializePlugin(PluginManager pluginManager, File pluginDirectory) {
        instance = this;
        manager  = new FederationManager();
        try {
            manager.start();
        } catch (RuntimeException | Error e) {
            // Openfire keeps a plugin whose start failed registered until it is unloaded, hooks and all.
            rollBack(e);
            throw e;
        }
    }

    @Override
    public void destroyPlugin() {
        if (manager != null) {
            manager.stop();
        }
        instance = null;
    }

    public FederationManager getManager() {
        return manager;
    }

    /** Removes whatever the failed start had registered; a failure here is kept on {@code cause}. */
    private void rollBack(Throwable cause) {
        try {
            manager.stop();
        } catch (RuntimeException | Error cleanup) {
            cause.addSuppressed(cleanup);
        }
        manager  = null;
        instance = null;
    }
}
