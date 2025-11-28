package com.salvaging;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.salvaging.SalvagingPlugin;

public class SalvagingPluginTest
{
    public static void main(String[] args) throws Exception
    {
        // Load your Salvaging plugin into the external plugin manager
        ExternalPluginManager.loadBuiltin(SalvagingPlugin.class);

        // Start RuneLite
        RuneLite.main(args);
    }
}
