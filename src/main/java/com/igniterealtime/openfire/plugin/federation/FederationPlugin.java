package com.igniterealtime.openfire.plugin.federation;

import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;

import java.io.File;

/**
 * Openfire plugin entry point.
 *
 * Openfire calls initializePlugin() when the plugin JAR is loaded (or hot-reloaded)
 * and destroyPlugin() when it is unloaded.  All work is delegated to FederationManager.
 */
public class FederationPlugin implements Plugin {

    private static volatile FederationPlugin instance;

    private FederationManager manager;

    /** Accessible from JSP pages: FederationPlugin.getInstance().getManager() */
    public static FederationPlugin getInstance() {
        return instance;
    }

    @Override
    public void initializePlugin(PluginManager pluginManager, File pluginDirectory) {
        instance = this;
        manager  = new FederationManager();
        manager.start();
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
}
