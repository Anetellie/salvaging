package com.salvaging;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class SalvageTimingOverlay extends OverlayPanel
{
    private final SalvagingPlugin plugin;
    private final SalvagingConfig config;

    @Inject
    private SalvageTimingOverlay(SalvagingPlugin plugin, SalvagingConfig config)
    {
        super(plugin);
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();
        if (!plugin.isOnBoat())
        {
            return null;
        }
        if (!config.showTimingPanel())
        {
            return null;
        }

        int total = plugin.getCrewTotalSalvages();
        double avgSeconds = plugin.getCrewAverageIntervalSeconds();
        int sinceLast = plugin.getCrewSecondsSinceLastSalvage();

        if (total == 0)
        {
            // Nothing to show yet
            return null;
        }

        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("Salvage timing")
                        .color(Color.WHITE)
                        .build()
        );

        panelComponent.getChildren().add(
                LineComponent.builder()
                        .left("Total salvages:")
                        .right(Integer.toString(total))
                        .build()
        );

        panelComponent.getChildren().add(
                LineComponent.builder()
                        .left("Avg between hooks:")
                        .right(String.format("%.1fs", avgSeconds))
                        .build()
        );

        if (sinceLast >= 0)
        {
            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("Last salvage:")
                            .right(sinceLast + "s ago")
                            .build()
            );
        }

        return super.render(graphics);
    }
}
