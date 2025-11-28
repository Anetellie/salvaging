package net.runelite.client.plugins.salvaging;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("salvaging")
public interface SalvagingConfig extends Config
{
    @ConfigSection(
            name = "Overlays",
            description = "Toggle individual overlays",
            position = 0
    )
    String overlaysSection = "overlaysSection";

    @ConfigSection(
            name = "Cargo (soon)",
            description = "Cargo overlay behaviour",
            position = 1
    )
    String cargoSection = "cargoSection";

    @ConfigSection(
            name = "Credits",
            description = "cerdit and dedications",
            position = 99
    )
    String creditsSection = "creditsSection";


    @ConfigItem(
            keyName = "showStatusOverlay",
            name = "Show status",
            description = "Show the simple status overlay.",
            position = 0,
            section = overlaysSection
    )
    default boolean showStatusOverlay()
    {
        return true;
    }

    @ConfigItem(
            keyName = "showCargoOverlay",
            name = "Show cargo overlay",
            description = "Show current cargo / max cargo in a panel.",
            position = 1,
            section = overlaysSection
    )
    default boolean showCargoOverlay()
    {
        return true;
    }

    @ConfigItem(
            keyName = "showCrewPanel",
            name = "Show crew panel",
            description = "Show per-crew salvage counts and rates.",
            position = 2,
            section = overlaysSection
    )
    default boolean showCrewPanel()
    {
        return true;
    }

    @ConfigItem(
            keyName = "showTimingPanel",
            name = "Show timing panel",
            description = "Show salvage timing / average interval.",
            position = 3,
            section = overlaysSection
    )
    default boolean showTimingPanel()
    {
        return true;
    }


    @ConfigItem(
            keyName = "highlightCargoWhenFull",
            name = "Highlight cargo text when full",
            description = "Make the cargo text red when your hold is full.",
            position = 0,
            section = cargoSection
    )
    default boolean highlightCargoWhenFull()
    {
        return true;
    }

    @Alpha
    @ConfigItem(
            keyName = "cargoFullColor",
            name = "Cargo full text color (later)",
            description = "Color used for cargo text when full.",
            position = 1,
            section = cargoSection
    )
    default Color cargoFullColor()
    {
        return new Color(255, 0, 0, 180);
    }

    @ConfigItem(
            keyName = "cargoCapacityOverride",
            name = "Cargo capacity override (later)",
            description = "If > 0, use this as max cargo instead of auto-detection.",
            position = 2,
            section = cargoSection
    )
    default int cargoCapacityOverride()
    {
        return 0;
    }


    @ConfigItem(
            keyName = "dedicationText",
            name = "Dedication",
            description = "Just a little text at the bottom.",
            position = 0,
            section = creditsSection
    )
    default String dedicationText()
    {
        return "Dedicated to the community";
    }
}
