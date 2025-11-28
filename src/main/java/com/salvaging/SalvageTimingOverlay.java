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

        // Crystal hook cooldown (-1 = never seen, 0 = ready, >0 = seconds left)
        int crystalRemaining = plugin.getCrystalCooldownSecondsRemaining();

        // If we have neither salvage data nor crystal info, hide the panel
        if (total == 0 && crystalRemaining < 0)
        {
            return null;
        }

        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("Salvage timing")
                        .color(Color.WHITE)
                        .build()
        );

        // --- Crew salvage timing (only if we actually have any salvages) ---
        if (total > 0)
        {
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
        }

        // --- Crystal extractor cooldown (if we've seen at least one proc) ---
        if (crystalRemaining >= 0)
        {
            String rightText;
            Color rightColor;

            if (crystalRemaining == 0)
            {
                rightText = "READY";
                rightColor = Color.GREEN;
            }
            else
            {
                rightText = crystalRemaining + "s";
                rightColor = Color.YELLOW;
            }

            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("Crystal hook:")
                            .right(rightText)
                            .rightColor(rightColor)
                            .build()
            );
        }

        return super.render(graphics);
    }
}
