package com.salvaging;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

class SalvageCargoOverlay extends OverlayPanel
{
    // Cargo group so we can read real numbers when the window is open
    private static final int CARGO_GROUP_ID = 943;

    private final Client client;
    private final SalvagingPlugin plugin;
    private final SalvagingConfig config;

    @Inject
    private SalvageCargoOverlay(Client client, SalvagingPlugin plugin, SalvagingConfig config)
    {
        super(plugin);
        this.client = client;
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
        if (!config.showCargoOverlay())
        {
            return null;
        }

        int used = plugin.getCargoUsed();
        int max = plugin.getCargoMax();

        // --- If the cargo window is open, try to override with the actual "X / Y" text ---
        for (int child = 0; child < 30; child++)
        {
            Widget w = client.getWidget(CARGO_GROUP_ID, child);
            if (w == null || w.isHidden())
            {
                continue;
            }

            String text = w.getText();
            if (text == null)
            {
                continue;
            }

            String stripped = text.trim();
            int slashIdx = stripped.indexOf('/');
            if (slashIdx <= 0 || slashIdx >= stripped.length() - 1)
            {
                continue;
            }

            String left = stripped.substring(0, slashIdx).trim();
            String right = stripped.substring(slashIdx + 1).trim();

            try
            {
                int parsedUsed = Integer.parseInt(left);
                int parsedMax = Integer.parseInt(right);

                if (parsedUsed >= 0 && parsedMax > 0 && parsedUsed <= parsedMax)
                {
                    used = parsedUsed;
                    max = parsedMax;
                }
            }
            catch (NumberFormatException ignored)
            {
                // just skip this child
            }
        }

        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("Cargo")
                        .color(Color.WHITE)
                        .build()
        );

        boolean cargoFullFlag = plugin.isCargoReallyFull();

        String rightText;
        Color rightColor = Color.WHITE;

        if (max > 0)
        {
            // We know the max
            if (cargoFullFlag)
            {
                rightText = "FULL";
            }
            else
            {
                rightText = used + " / " + max;
            }
        }
        else
        {
            // Max is unknown (haven't opened cargo yet, or couldn't parse)
            if (cargoFullFlag)
            {
                rightText = "FULL";
            }
            else
            {
                // your requested behavior: "unknown cargo"
                // rendered as "Cargo: Unknown"
                rightText = "Unknown";
                rightColor = Color.GRAY;
            }
        }

        if (cargoFullFlag && config.highlightCargoWhenFull())
        {
            rightColor = config.cargoFullColor();
        }

        panelComponent.getChildren().add(
                LineComponent.builder()
                        .left("Cargo:")
                        .right(rightText)
                        .rightColor(rightColor)
                        .build()
        );

        return super.render(graphics);
    }
}
