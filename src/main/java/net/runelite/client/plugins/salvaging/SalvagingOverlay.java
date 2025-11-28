package net.runelite.client.plugins.salvaging;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.TitleComponent;

class SalvagingOverlay extends OverlayPanel
{
    private final Client client;
    private final SalvagingPlugin plugin;
    private final SalvagingConfig config;

    @Inject
    private SalvagingOverlay(Client client, SalvagingPlugin plugin, SalvagingConfig config)
    {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
        this.client = client;
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();

        if (!config.showStatusOverlay() || !plugin.isOnBoat())
        {
            return null;
        }

        if (!plugin.isStatusKnown())
        {
            return null;
        }

        boolean active = plugin.isActive();
        boolean salvaging = plugin.isSalvaging();

        String text;
        Color color;

        if (!active)
        {
            text = "IDLE";
            color = Color.RED;
        }
        else if (salvaging)
        {
            text = "Cleaning";
            color = Color.GREEN;
        }
        else
        {
            text = "Salvaging";
            color = Color.GREEN;
        }

        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text(text)
                        .color(color)
                        .build()
        );

        return super.render(graphics);
    }
}
