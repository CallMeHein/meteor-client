/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.baritoneGoto;
import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.fullyJoinedServer;
import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.setModuleActive;
import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.sleep;

import baritone.api.BaritoneAPI;
import baritone.api.process.ICustomGoalProcess;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
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
import net.minecraft.util.math.BlockPos;

public class ModuleSequencer extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<BlockPos> home = sgGeneral.add(new BlockPosSetting.Builder()
        .name("home")
        .description("Location to return to between modules")
        .build()
    );

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

            setModuleActive(villagerTrade, false);
            if (cobbleFarm.isActive()) {
                setModuleActive(nuker, false);
                setModuleActive(cobbleFarm, false);
            }
        }
    }

    @Override
    public void onDeactivate() {
        shouldStop = true;
        AutoVillagerTrade villagerTrade = Modules.get().get(AutoVillagerTrade.class);
        AutoCobbleFarm cobbleFarm = Modules.get().get(AutoCobbleFarm.class);

        setModuleActive(villagerTrade, false);
        setModuleActive(cobbleFarm, false);
    }

    @Override
    public void onActivate() {
        shouldStop = false;
        moduleRunning = false;
        ICustomGoalProcess goalProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();

        MeteorExecutor.execute(() -> {

            while (!shouldStop) {
                if (joining || !fullyJoinedServer(mc)) {
                    sleep(1000);
                    continue;
                }

                // Return home when starting
                ChatUtils.info("Returning home...");
                baritoneGoto(goalProcess, home.get());

                // Start AutoVillagerTrade
                if (!moduleRunning) {
                    ChatUtils.info("Starting AutoVillagerTrade...");
                    moduleRunning = true;
                    AutoVillagerTrade villagerTrade = Modules.get().get(AutoVillagerTrade.class);

                    // Ensure repeat is disabled
                    villagerTrade.repeat.set(false);
                    setModuleActive(villagerTrade, true);

                    // Wait for AutoVillagerTrade to complete
                    while (villagerTrade.isActive() && !shouldStop) {
                        sleep(1000);
                    }
                    moduleRunning = false;
                    if (shouldStop) break;

                }

                // Return home after AutoVillagerTrade
                ChatUtils.info("Returning home...");
                baritoneGoto(goalProcess, home.get());

                // Start AutoCobbleFarm
                if (!moduleRunning) {
                    ChatUtils.info("Starting AutoCobbleFarm...");
                    moduleRunning = true;
                    AutoCobbleFarm cobbleFarm = Modules.get().get(AutoCobbleFarm.class);

                    // Ensure repeat is disabled
                    cobbleFarm.repeat.set(false);
                    setModuleActive(cobbleFarm, true);

                    // Wait for AutoCobbleFarm to complete
                    while (cobbleFarm.isActive() && !shouldStop) {
                        sleep(1000);
                    }
                    moduleRunning = false;
                    if (shouldStop) break;

                }

                // Return home after AutoCobbleFarm
                ChatUtils.info("Returning home...");
                baritoneGoto(goalProcess, home.get());

                sleep(1000); // Small delay between cycles
            }

            if (shouldStop) {
                ChatUtils.info("ModuleSequencer stopped.");
                setModuleActive(this, false);
            }
        });
    }
}
