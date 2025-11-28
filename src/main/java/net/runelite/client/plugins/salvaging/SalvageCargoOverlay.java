package net.runelite.client.plugins.salvaging;

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

public class SalvageCargoOverlay extends OverlayPanel
{

    private static final int CARGO_GROUP_ID = 943;

    private final Client client;
    private final SalvagingPlugin plugin;
    private final SalvagingConfig config;

    @Inject
    private SalvageCargoOverlay(Client client, SalvagingPlugin plugin, SalvagingConfig config)
    {
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

        //  If the cargo window is open, override with *real* widget text

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
                // try next child
            }
        }

        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("Cargo")
                        .color(Color.WHITE)
                        .build()
        );

        String rightText = (max > 0) ? (used + " / " + max) : Integer.toString(used);

        Color rightColor = Color.WHITE;
        if (max > 0 && config.highlightCargoWhenFull() && used >= max)
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
