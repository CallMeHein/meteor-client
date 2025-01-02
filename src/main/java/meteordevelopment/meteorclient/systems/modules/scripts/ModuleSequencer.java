/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.fullyJoinedServer;
import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.setModuleActive;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Nuker;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class ModuleSequencer extends Module {
    private boolean shouldStop = false;
    private boolean moduleRunning = false;
    private boolean joining = false;

    public ModuleSequencer() {
        super(Categories.Script, "module-sequencer",
                "Automatically alternates between AutoVillagerTrade and AutoCobbleFarm");
        runInMainMenu = true; // prevent MeteorExecutor from aborting on game leave
    }

    @EventHandler
    public void onGameJoin(GameJoinedEvent event) {
        if (isActive()) {
            joining = true;
            MeteorExecutor.execute(() -> {
                while (!fullyJoinedServer(mc)) {
                    sleep(5000);
                }
                sleep(5000);
                joining = false;
            });
        }
    }

    @EventHandler
    public void onGameLeft(GameLeftEvent event) {
        if (isActive()) {
            AutoVillagerTrade villagerTrade = Modules.get().get(AutoVillagerTrade.class);
            AutoCobbleFarm cobbleFarm = Modules.get().get(AutoCobbleFarm.class);
            Nuker nuker = Modules.get().get(Nuker.class);

            if (villagerTrade.isActive())
                villagerTrade.toggle();
            if (cobbleFarm.isActive()) {
                if (nuker.isActive())
                    nuker.toggle();
                cobbleFarm.toggle();
            }
        }
    }

    @Override
    public void onDeactivate() {
        shouldStop = true;
        AutoVillagerTrade villagerTrade = Modules.get().get(AutoVillagerTrade.class);
        AutoCobbleFarm cobbleFarm = Modules.get().get(AutoCobbleFarm.class);

        if (villagerTrade.isActive())
            villagerTrade.toggle();
        if (cobbleFarm.isActive())
            cobbleFarm.toggle();
    }

    @Override
    public void onActivate() {
        shouldStop = false;
        moduleRunning = false;

        MeteorExecutor.execute(() -> {
            while (!shouldStop) {
                if (joining || !fullyJoinedServer(mc)) {
                    sleep(1000);
                    continue;
                }

                // Start AutoVillagerTrade
                if (!moduleRunning) {
                    ChatUtils.info("Starting AutoVillagerTrade...");
                    moduleRunning = true;
                    AutoVillagerTrade villagerTrade = Modules.get().get(AutoVillagerTrade.class);

                    // Ensure repeat is disabled
                    villagerTrade.repeat.set(false);
                    villagerTrade.toggle();

                    // Wait for AutoVillagerTrade to complete
                    while (villagerTrade.isActive() && !shouldStop) {
                        sleep(1000);
                    }
                    moduleRunning = false;
                    if (shouldStop) break;
                }

                // Start AutoCobbleFarm
                if (!moduleRunning) {
                    ChatUtils.info("Starting AutoCobbleFarm...");
                    moduleRunning = true;
                    AutoCobbleFarm cobbleFarm = Modules.get().get(AutoCobbleFarm.class);

                    // Ensure repeat is disabled
                    cobbleFarm.repeat.set(false);
                    cobbleFarm.toggle();

                    // Wait for AutoCobbleFarm to complete
                    while (cobbleFarm.isActive() && !shouldStop) {
                        sleep(1000);
                    }
                    moduleRunning = false;
                    if (shouldStop) break;
                }

                sleep(1000); // Small delay between cycles
            }

            if (shouldStop) {
                ChatUtils.info("ModuleSequencer stopped.");
                setModuleActive(this, false);
            }
        });
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
