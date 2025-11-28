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

        // Only show when we're on a boat and user wants the panel
        if (!plugin.isOnBoat())
        {
            return null;
        }
        if (!config.showCrewPanel())
        {
            return null;
        }

        // --- TITLE ---
        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("Salvage crew")
                        .color(Color.WHITE)
                        .build()
        );

        // Hook stats (historical data)
        Map<String, Integer> crewHooks = plugin.getCrewCatchCounts();
        boolean haveHookStats = crewHooks != null && !crewHooks.isEmpty();

        // Live tracking (current NPCs)
        int trackedCrew = plugin.getCrewmates().size();
        long activeCrew = plugin.getCrewSalvaging().values().stream()
                .filter(Boolean::booleanValue)
                .count();

        // --- LIVE ACTIVITY: only show if we actually have tracked crew ---
        if (trackedCrew > 0)
        {
            Color crewColor = activeCrew > 0 ? Color.GREEN : Color.RED;

            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("Crew salvaging:")
                            .leftColor(crewColor)
                            .right(activeCrew + "/" + trackedCrew)
                            .rightColor(crewColor)
                            .build()
            );
        }
        else if (!haveHookStats)
        {
            // Only say "no crew" if we ALSO have no hook history
            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("No crew detected yet.")
                            .leftColor(Color.GRAY)
                            .build()
            );
        }

        // --- HOOK STATS: how many hooks each crew has gotten so far ---
        if (!haveHookStats)
        {
            // No hooks at all yet
            panelComponent.getChildren().add(
                    LineComponent.builder()
                            .left("No hooks yet.")
                            .build()
            );
            return super.render(graphics);
        }

        var sorted = crewHooks.entrySet().stream()
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
