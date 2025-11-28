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
import net.runelite.api.Skill;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
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
    // ---------- sailing / xp tracking ----------

    private static final int SAILING_XP_WINDOW_MINUTES = 5;

    // Crystal extractor
    private static final String CRYSTAL_MESSAGE =
            "Your crystal extractor has harvested a crystal mote!";
    private static final int CRYSTAL_COOLDOWN_SECONDS = 60;

    // Cargo full message from crew
    private static final String CARGO_FULL_CREW_MESSAGE =
            "Your crewmate on the salvaging hook cannot salvage as the cargo hold is full.";

    private int gameTickCounter = 0;
    private Instant lastCrewXpChatTime = null;
    private Instant lastSailingXpTime = null;
    private Instant lastCrystalHarvestTime = null;

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
            13576,
            13577,
            13583,
            13584,
            13599  // main salvaging / hauling anim
    );

    // All possible crew names we care about
    private static final Set<String> CREW_NAMES = Set.of(
            "Jobless Jim",
            "Ex-Captain Siad",
            "Adventurer Ada",
            "Cabin Boy Jenkins",
            "Oarswoman Olga",
            "Jittery Jim",
            "Bosun Zarah",
            "Jolly Jim",
            "Spotter Virginia",
            "Sailor Jakob"
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
    private final Map<Actor, Boolean> crewSalvaging = new HashMap<>();

    @Getter
    private final Map<Actor, Integer> crewIdleTicks = new HashMap<>();

    @Getter
    private final Set<Actor> crewmates = new java.util.HashSet<>();

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

    @Getter
    private boolean cargoFull = false;

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

    // --------- animation / crew helpers ---------

    private boolean isAnimationSalvaging(int anim)
    {
        return SALVAGE_ANIMS.contains(anim);
    }

    private boolean isCrewmateName(String name)
    {
        if (name == null)
        {
            return false;
        }

        String clean = Text.removeTags(name).trim();
        return CREW_NAMES.contains(clean);
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
        return CREW_NAMES.contains(name);
    }

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
        cargoFull = false;

        crewStats.clear();
        crewTotalSalvages = 0;
        crewAvgIntervalSeconds = 0.0;
        lastCrewSalvageTime = null;

        lastSailingXpTime = null;
        lastCrewXpChatTime = null;
        lastCrystalHarvestTime = null;

        gameTickCounter = 0;
        onBoat = false;

        crewmates.clear();
        crewSalvaging.clear();
        crewIdleTicks.clear();
    }

    // --------- helpers ----------

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

    private void recomputeCargoFull()
    {
        int max = getCargoMax();
        if (max > 0)
        {
            cargoFull = cargoUsed >= max;
        }
        // if we don't know max, cargoFull can only be forced true by chat
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

        recomputeCargoFull();
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

    /**
     * Safe cargo-full check for overlays: uses numbers if we know them,
     * otherwise falls back to the boolean flag.
     */
    public boolean isCargoReallyFull()
    {
        int max = getCargoMax();
        if (max > 0)
        {
            return cargoUsed >= max;
        }
        return cargoFull;
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

    // -------- crystal cooldown helpers --------

    /**
     * Returns remaining crystal cooldown in seconds:
     * -1 if we never saw the message yet,
     *  0 if it's ready,
     * >0 while on cooldown.
     */
    public int getCrystalCooldownSecondsRemaining()
    {
        if (lastCrystalHarvestTime == null)
        {
            return -1;
        }

        long elapsed = Duration.between(lastCrystalHarvestTime, Instant.now()).getSeconds();
        long remaining = CRYSTAL_COOLDOWN_SECONDS - elapsed;

        return (int) Math.max(0, remaining);
    }

    public boolean isCrystalOnCooldown()
    {
        int left = getCrystalCooldownSecondsRemaining();
        return left > 0;
    }

    // --------- event handlers ----------

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        if (event.getSkill() != Skill.SAILING)
        {
            return;
        }

        lastSailingXpTime = Instant.now();
    }

    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        Actor actor = event.getActor();
        if (actor == null)
        {
            return;
        }

        // Match Drekk's plugin: ignore top-level world view
        if (actor.getWorldView().isTopLevel())
        {
            return;
        }

        int anim = actor.getAnimation();

        // Player logic
        if (actor == client.getLocalPlayer())
        {
            boolean nowActive = SALVAGE_ANIMS.contains(anim);

            active = nowActive;
            statusKnown = true;
            salvaging = (anim == 13599);

            updateOnBoatFlag();
        }
        // Crew logic
        else if (crewmates.contains(actor))
        {
            if (isAnimationSalvaging(anim))
            {
                crewSalvaging.put(actor, true);
                crewIdleTicks.put(actor, 0);
            }
            else
            {
                crewSalvaging.put(actor, false);
                crewIdleTicks.put(actor, 0);
            }
        }
    }

    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event)
    {
        Actor actor = event.getActor();
        if (!(actor instanceof NPC))
        {
            return;
        }

        NPC npc = (NPC) actor;

        // Only our crew, on our world view
        if (!isMyCrew(npc)
                || npc.getWorldView() != client.getLocalPlayer().getWorldView())
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

        if (!onBoat || !hadRecentCrewXpChat())
        {
            return;
        }

        String crewName = Text.removeTags(npc.getName()).trim();
        CrewStats cs = crewStats.computeIfAbsent(crewName, k -> new CrewStats());
        Instant now = Instant.now();

        cs.count++;
        if (cs.first == null)
        {
            cs.first = now;
        }
        cs.last = now;

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

        int max = getCargoMax();
        if (max > 0)
        {
            cargoUsed = Math.min(cargoUsed + 1, max);
        }
        else
        {
            cargoUsed++;
        }

        recomputeCargoFull();
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

        // Track idle ticks for crew
        for (Actor crew : crewmates)
        {
            int currentIdle = crewIdleTicks.getOrDefault(crew, 0);
            boolean working = crewSalvaging.getOrDefault(crew, false);

            if (!working && currentIdle < 10)
            {
                crewIdleTicks.put(crew, currentIdle + 1);
            }
        }

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
        String msgLower = clean.toLowerCase();

        // crew XP line from Sailing
        if (clean.equals("You gain some experience by watching your crew work."))
        {
            lastCrewXpChatTime = Instant.now();
        }

        // Specific cargo-full line from crewmate
        if (clean.equals(CARGO_FULL_CREW_MESSAGE))
        {
            cargoFull = true;
            int max = getCargoMax();
            if (max > 0)
            {
                cargoUsed = max;
            }
            return;
        }

        // generic cargo full detection (other messages mentioning cargo full)
        if (msgLower.contains("cargo hold") && msgLower.contains("full"))
        {
            cargoFull = true;
            int max = getCargoMax();
            if (max > 0)
            {
                cargoUsed = max;
            }
        }

        // crystal extractor harvested
        if (clean.equals(CRYSTAL_MESSAGE))
        {
            lastCrystalHarvestTime = Instant.now();
        }
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned event)
    {
        NPC npc = event.getNpc();

        // Only our named crew, same world view as us
        if (!isCrewmateName(npc.getName())
                || npc.getWorldView() != client.getLocalPlayer().getWorldView())
        {
            return;
        }

        if (crewmates.add(npc))
        {
            crewSalvaging.put(npc, false);
            crewIdleTicks.put(npc, 0);
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned event)
    {
        NPC npc = event.getNpc();
        crewmates.remove(npc);
        crewSalvaging.remove(npc);
        crewIdleTicks.remove(npc);
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
