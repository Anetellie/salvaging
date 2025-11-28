package com.salvaging;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@PluginDescriptor(
        name = "Salvaging",
        description = "Shows salvaging statuses, cargo count and crew stats while Sailing.",
        tags = {"sailing", "salvage", "cargo"}
)
@Singleton
public class SalvagingPlugin extends Plugin
{
    // ---------- sailing/xp tracking ----------

    private static final int SAILING_XP_WINDOW_MINUTES = 5;

    private int gameTickCounter = 0; // still handy if we ever want tick-based things
    private Instant lastCrewXpChatTime = null;
    private Instant lastSailingXpTime = null;

    private boolean hadRecentCrewXpChat()
    {
        if (lastCrewXpChatTime == null)
        {
            return false;
        }

        long seconds = Duration.between(lastCrewXpChatTime, Instant.now()).getSeconds();
        return seconds <= 2;
    }

    // Animations we treat as "salvaging-related"
    private static final Set<Integer> SALVAGE_ANIMS = ImmutableSet.of(
            13584, // hook cast 1
            13577, // hook cast 2
            13599  // salvaging / hauling anim
    );

    // All possible crew names we care about
    private static final Set<String> CREW_NAMES = Set.of(
            "Jobless Jim",
            "Ex-Captain Siad",
            "Ada",
            "Adventurer",
            "Oarswoman",
            "Olga"
    );

    // Cargo hold widget ids
    private static final int CARGOHOLD_GROUP_ID   = 943;
    private static final int CARGOHOLD_USED_CHILD = 4;
    private static final int CARGOHOLD_CAP_CHILD  = 5;
    private static final int CARGOHOLD_ITEMS_CHILD = 8;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private SalvagingConfig config;

    @Inject
    private SalvagingOverlay statusOverlay;

    @Inject
    private SalvageCargoOverlay cargoOverlay;

    @Inject
    private SalvageCrewOverlay crewOverlay;

    @Inject
    private SalvageTimingOverlay timingOverlay;

    // --------- state ---------

    @Getter
    private boolean onBoat = false;

    @Getter
    private boolean active;       // "any salvaging animation"

    @Getter
    private boolean salvaging;    // specifically animation 13599

    @Getter
    private boolean statusKnown;

    private int cargoUsed = 0;
    private int cargoCapacity = 0;

    private static class CrewStats
    {
        int count;
        Instant first;
        Instant last;
    }

    private final Map<String, CrewStats> crewStats = new HashMap<>();

    private int crewTotalSalvages = 0;
    private double crewAvgIntervalSeconds = 0.0;
    private Instant lastCrewSalvageTime = null;

    // --------- config ---------

    @Provides
    SalvagingConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(SalvagingConfig.class);
    }

    // --------- lifecycle ---------

    @Override
    protected void startUp()
    {
        resetAllStats();
        overlayManager.add(statusOverlay);
        overlayManager.add(cargoOverlay);
        overlayManager.add(crewOverlay);
        overlayManager.add(timingOverlay);
    }

    @Override
    protected void shutDown()
    {
        overlayManager.remove(statusOverlay);
        overlayManager.remove(cargoOverlay);
        overlayManager.remove(crewOverlay);
        overlayManager.remove(timingOverlay);
        resetAllStats();
    }

    private void resetAllStats()
    {
        active = false;
        salvaging = false;
        statusKnown = false;

        cargoUsed = 0;
        cargoCapacity = 0;

        crewStats.clear();
        crewTotalSalvages = 0;
        crewAvgIntervalSeconds = 0.0;
        lastCrewSalvageTime = null;

        lastSailingXpTime = null;
        lastCrewXpChatTime = null;
        gameTickCounter = 0;

        onBoat = false;
    }

    // --------- helpers ----------

    /**
     * onBoat is true if we've had any Sailing XP within the XP window.
     * If the window is 0 or negative, any Sailing XP keeps us "on boat"
     * until logout or reset.
     */
    private void updateOnBoatFlag()
    {
        if (lastSailingXpTime == null)
        {
            onBoat = false;
            return;
        }

        int windowMinutes = SAILING_XP_WINDOW_MINUTES;

        if (windowMinutes <= 0)
        {
            onBoat = true;
            return;
        }

        Duration since = Duration.between(lastSailingXpTime, Instant.now());
        onBoat = since.toMinutes() < windowMinutes;
    }

    private boolean isNearLocalPlayer(NPC npc)
    {
        Player me = client.getLocalPlayer();
        if (me == null || npc == null)
        {
            return false;
        }

        WorldPoint myLoc = WorldPoint.fromLocalInstance(client, me.getLocalLocation());
        WorldPoint npcLoc = npc.getWorldLocation();

        if (myLoc == null || npcLoc == null)
        {
            return false;
        }

        // Same-boat-ish radius; tweak if needed
        return myLoc.distanceTo(npcLoc) <= 8;
    }

    private boolean isMyCrew(NPC npc)
    {
        if (npc == null)
        {
            return false;
        }

        String raw = npc.getName();
        if (raw == null)
        {
            return false;
        }

        String name = Text.removeTags(raw).trim();
        // Only care that the NPC is one of the known crew names
        return CREW_NAMES.contains(name);
    }

    private void updateCargoFromWidget()
    {
        Widget universe = client.getWidget(CARGOHOLD_GROUP_ID, 0);
        if (universe == null || universe.isHidden())
        {
            return;
        }

        boolean parsedText = false;

        Widget usedWidget = client.getWidget(CARGOHOLD_GROUP_ID, CARGOHOLD_USED_CHILD);
        Widget capWidget  = client.getWidget(CARGOHOLD_GROUP_ID, CARGOHOLD_CAP_CHILD);

        if (usedWidget != null && capWidget != null)
        {
            try
            {
                int used = Integer.parseInt(Text.removeTags(usedWidget.getText()).trim());
                int cap  = Integer.parseInt(Text.removeTags(capWidget.getText()).trim());

                if (cap > 0)
                {
                    cargoUsed = used;
                    cargoCapacity = cap;
                    parsedText = true;
                }
            }
            catch (NumberFormatException ignored)
            {
                // fall back to counting items
            }
        }

        Widget itemsContainer = client.getWidget(CARGOHOLD_GROUP_ID, CARGOHOLD_ITEMS_CHILD);
        if (itemsContainer != null)
        {
            int sum = 0;

            Widget[] children = itemsContainer.getDynamicChildren();
            if (children == null)
            {
                children = itemsContainer.getChildren();
            }

            if (children != null)
            {
                for (Widget child : children)
                {
                    sum += child.getItemQuantity();
                }
            }

            if (sum > 0)
            {
                cargoUsed = sum;

                if (!parsedText && cargoCapacity == 0)
                {
                    int override = config.cargoCapacityOverride();
                    if (override > 0)
                    {
                        cargoCapacity = override;
                    }
                }
            }
        }
    }

    // --------- getters for overlays ----------

    int getCargoUsed()
    {
        return cargoUsed;
    }

    int getCargoMax()
    {
        int override = config.cargoCapacityOverride();
        if (override > 0)
        {
            return override;
        }
        return cargoCapacity;
    }

    Map<String, Integer> getCrewCatchCounts()
    {
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, CrewStats> e : crewStats.entrySet())
        {
            out.put(e.getKey(), e.getValue().count);
        }
        return out;
    }

    double getCrewRatePerHour(String name)
    {
        CrewStats cs = crewStats.get(name);
        if (cs == null || cs.count <= 1 || cs.first == null)
        {
            return 0;
        }

        Duration d = Duration.between(cs.first, Instant.now());
        double hours = d.toMillis() / 3_600_000.0;
        if (hours <= 0)
        {
            return 0;
        }
        return cs.count / hours;
    }

    public int getCrewTotalSalvages()
    {
        return crewTotalSalvages;
    }

    public double getCrewAverageIntervalSeconds()
    {
        return crewAvgIntervalSeconds > 0.0 ? crewAvgIntervalSeconds : 0.0;
    }

    public int getCrewSecondsSinceLastSalvage()
    {
        if (lastCrewSalvageTime == null)
        {
            return -1;
        }
        return (int) Duration.between(lastCrewSalvageTime, Instant.now()).getSeconds();
    }

    // --------- event handlers ----------

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (event.getSkill() != Skill.SAILING)
        {
            return;
        }

        // Any change in Sailing XP means we're (almost certainly) on a ship
        lastSailingXpTime = Instant.now();
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        if (event.getActor() != client.getLocalPlayer())
        {
            return;
        }

        int anim = event.getActor().getAnimation();
        boolean nowActive = SALVAGE_ANIMS.contains(anim);

        active = nowActive;
        statusKnown = true;
        salvaging = (anim == 13599); // “salvaging” animation

        // Not strictly needed for onBoat anymore, but cheap to keep updated
        updateOnBoatFlag();
    }

    /**
     * Track crew hooks from NPC overhead text:
     * "Managed to hook some salvage! I'll put it in the cargo hold."
     */
    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event)
    {
        Actor actor = event.getActor();
        if (!(actor instanceof NPC))
        {
            return;
        }

        NPC npc = (NPC) actor;

        // Only our crew members (by name)
        if (!isMyCrew(npc))
        {
            return;
        }

        String rawText = event.getOverheadText();
        if (rawText == null)
        {
            return;
        }

        String text = Text.removeTags(rawText);

        if (!text.startsWith("Managed to hook some salvage"))
        {
            return;
        }

        // Must be on our boat AND must have had the crew-XP chat message very recently
        if (!onBoat || !hadRecentCrewXpChat())
        {
            return;
        }

        // --- per-crew stats ---
        String crewName = Text.removeTags(npc.getName()).trim();
        CrewStats cs = crewStats.computeIfAbsent(crewName, k -> new CrewStats());
        Instant now = Instant.now();

        cs.count++;
        if (cs.first == null)
        {
            cs.first = now;
        }
        cs.last = now;

        // --- global crew timing ---
        crewTotalSalvages++;

        if (lastCrewSalvageTime != null)
        {
            double delta = Duration.between(lastCrewSalvageTime, now).toMillis() / 1000.0;

            if (crewAvgIntervalSeconds <= 0.0)
            {
                crewAvgIntervalSeconds = delta;
            }
            else
            {
                crewAvgIntervalSeconds =
                        (crewAvgIntervalSeconds * (crewTotalSalvages - 1) + delta) / crewTotalSalvages;
            }
        }

        lastCrewSalvageTime = now;

        // --- cargo estimate: +1 per crew hook ---
        int max = getCargoMax();
        if (max > 0)
        {
            cargoUsed = Math.min(cargoUsed + 1, max);
        }
        else
        {
            cargoUsed++;
        }
    }


    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() == CARGOHOLD_GROUP_ID)
        {
            updateCargoFromWidget();
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        gameTickCounter++;

        updateOnBoatFlag();

        Widget universe = client.getWidget(CARGOHOLD_GROUP_ID, 0);
        if (universe != null && !universe.isHidden())
        {
            updateCargoFromWidget();
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.GAMEMESSAGE
                && event.getType() != ChatMessageType.SPAM
                && event.getType() != ChatMessageType.MESBOX)
        {
            return;
        }

        String clean = Text.removeTags(event.getMessage());

        // crew XP line from Sailing
        if (clean.equals("You gain some experience by watching your crew work."))
        {
            lastCrewXpChatTime = Instant.now();
        }

        String msg = clean.toLowerCase();

        // cargo full sync
        if (msg.contains("cargo hold") && msg.contains("full"))
        {
            int max = getCargoMax();
            if (max > 0)
            {
                cargoUsed = max;
            }
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        switch (event.getGameState())
        {
            case LOGIN_SCREEN:
            case HOPPING:
                resetAllStats();
                break;
            default:
                break;
        }
    }
}
