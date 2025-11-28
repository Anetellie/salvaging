package com.salvaging;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class SalvageCrewOverlay extends OverlayPanel
{
    private final SalvagingPlugin plugin;
    private final SalvagingConfig config;

    @Inject
    private SalvageCrewOverlay(SalvagingPlugin plugin, SalvagingConfig config)
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
        if (!config.showCrewPanel())
        {
            return null;
        }

        Map<String, Integer> crew = plugin.getCrewCatchCounts();

        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("Salvage crew")
                        .color(Color.WHITE)
                        .build()
        );

        if (crew == null || crew.isEmpty())
        {
            // Show panel even if nothing has happened yet
            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("No hooks yet.")
                            .build()
            );
            return super.render(graphics);
        }

        var sorted = crew.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed())
                .collect(Collectors.toList());

        for (Map.Entry<String, Integer> e : sorted)
        {
            String name = e.getKey();
            int count = e.getValue();
            double rate = plugin.getCrewRatePerHour(name);

            String right = Integer.toString(count);
            if (rate > 0)
            {
                right += String.format(" (%.1f/hr)", rate);
            }

            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left(name + ":")
                            .right(right)
                            .build()
            );
        }

        return super.render(graphics);
    }

}
