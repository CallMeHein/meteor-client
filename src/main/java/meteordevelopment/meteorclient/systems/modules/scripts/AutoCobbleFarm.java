/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.ICustomGoalProcess;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AutoTotem;
import meteordevelopment.meteorclient.systems.modules.combat.KillAura;
import meteordevelopment.meteorclient.systems.modules.misc.InventoryTweaks;
import meteordevelopment.meteorclient.systems.modules.player.AutoEat;
import meteordevelopment.meteorclient.systems.modules.player.AutoMend;
import meteordevelopment.meteorclient.systems.modules.player.AutoTool;
import meteordevelopment.meteorclient.systems.modules.world.Nuker;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.setModuleActive;
import static net.minecraft.block.Blocks.COBBLESTONE;
import static net.minecraft.enchantment.Enchantments.MENDING;
import static net.minecraft.entity.EntityType.*;

public class AutoCobbleFarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<BlockPos> cobbleGen = sgGeneral.add(new BlockPosSetting.Builder()
        .name("cobble-generator")
        .description("BlockPos of the cobblestone generator")
        .build()
    );

    private final Setting<BlockPos> expFarm = sgGeneral.add(new BlockPosSetting.Builder()
        .name("exp-farm")
        .description("BlockPos of the EXP Farm")
        .build()
    );

    private boolean shouldStop = false;
    private BlockPos baritoneGoal;
    private boolean joining = false;


    public AutoCobbleFarm() {
        super(Categories.Script, "auto-cobble-farm", "Automatically farm cobble with nuker and mend pickaxes");
        runInMainMenu = true; // prevent MeteorExecutor from aborting on game leave
    }

    @EventHandler
    public void onGameJoin(GameJoinedEvent event){
        if (isActive()){
            joining = true;
            MeteorExecutor.execute(() -> {
                while (!fullyJoinedServer()){
                    sleep(5000);
                }
                sleep(5000);
                ICustomGoalProcess goalProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
                if (baritoneGoal != null) {
                    goalProcess.setGoalAndPath(new GoalBlock(baritoneGoal));
                }
                joining = false;
            });
        }
    }


    @EventHandler
    public void onGameLeft(GameLeftEvent event){
        if (isActive()) {
            ICustomGoalProcess goalProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
            GoalBlock goal = (GoalBlock) goalProcess.getGoal();
            if (goal != null) {
                baritoneGoal = goal.getGoalPos();
            }
        }
    }

    @Override
    public void onDeactivate() {
        shouldStop = true;
    }

    @Override
    public void onActivate() {
        shouldStop = false;
        Settings settings = BaritoneAPI.getSettings();
        settings.allowBreak.value = false;
        settings.allowPlace.value = false;
        // Modules
        AutoEat autoEat = Modules.get().get(AutoEat.class);
        AutoMend autoMend = Modules.get().get(AutoMend.class);
        AutoTool autoTool = Modules.get().get(AutoTool.class);
        AutoTotem autoTotem = Modules.get().get(AutoTotem.class);
        InventoryTweaks inventoryTweaks = Modules.get().get(InventoryTweaks.class);
        KillAura killAura = Modules.get().get(KillAura.class);
        Nuker nuker = Modules.get().get(Nuker.class);

        MeteorExecutor.execute(() -> {
            validateConditions();
            if (shouldStop) {
                setModuleActive(this, false);
                return;
            };
            setModuleActive(autoEat, true);
            ICustomGoalProcess goalProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
            while (true) {
                // get pickaxes out of offhand slot
                setupAutoTotem(autoTotem);

                // go to cobble gen spot
                goalProcess.setGoalAndPath(new GoalBlock(cobbleGen.get().getX(), cobbleGen.get().getY(), cobbleGen.get().getZ()));
                waitForArrival(goalProcess);
                if (shouldStop) break;

                // unlock offhand slot
                setModuleActive(autoTotem, false);

                // start mining
                setupAutoTool(autoTool);
                setupNuker(nuker);
                waitForPickaxeDurability(autoTool, PickaxeDurability.LOW);
                if (shouldStop) break;

                // stop mining
                setModuleActive(nuker, false);
                setModuleActive(autoTool, false);

                // go to exp farm spot
                goalProcess.setGoalAndPath(new GoalBlock(expFarm.get().getX(), expFarm.get().getY(), expFarm.get().getZ()));
                waitForArrival(goalProcess);
                if (shouldStop) break;

                // disable AutoDrop because it interferes with KillAura
                setModuleActive(inventoryTweaks, false);

                // start mending
                setupAutoMend(autoMend);
                setupKillAura(killAura);

                waitForPickaxeDurability(autoTool, PickaxeDurability.FULL);
                if (shouldStop) break;

                setModuleActive(inventoryTweaks, true);
                setModuleActive(killAura, false);
                setModuleActive(autoMend, false);
            }
            setModuleActive(this, false);
        });
    }

    private void validateConditions() {
        List<Integer> coordinates = List.of(cobbleGen.get().getX(), cobbleGen.get().getY(), cobbleGen.get().getZ(), expFarm.get().getX(), expFarm.get().getY(), expFarm.get().getZ());
        if (coordinates.stream().anyMatch(coord -> coord == 0)){
            ChatUtils.error("AutoCobbleFarm: Some coordinates are 0, forgot to set them?");
            shouldStop = true;
            return;
        }
        List<ItemStack> inventory = mc.player.getInventory().main;
        List<ItemStack> offhand = mc.player.getInventory().offHand;
        List<ItemStack> hotbar = inventory.stream().filter(itemStack -> inventory.indexOf(itemStack) <= 8).toList(); // index 0 to 8 are the hotbar
        if (hotbar.stream().noneMatch(itemStack ->(itemStack.getItem() instanceof SwordItem) && !itemStack.getEnchantments().getEnchantments().contains(MENDING))) {
            ChatUtils.error("AutoCobbleFarm: No sword with mending found in hotbar");
            shouldStop = true;
            return;
        }

        if (hotbar.stream().noneMatch(itemStack -> itemStack.getComponents().contains(Registries.DATA_COMPONENT_TYPE.get(Identifier.of("minecraft:food"))))){
            ChatUtils.error("AutoCobbleFarm: No food found in hotbar");
            shouldStop = true;
            return;
        }

        if (Stream.concat(inventory.stream(), offhand.stream()).noneMatch(itemStack -> itemStack.getItem() instanceof PickaxeItem && !itemStack.getEnchantments().getEnchantments().contains(MENDING))){
            ChatUtils.error("AutoCobbleFarm: No pickaxes with mending found in inventory");
            shouldStop = true;
            return;
        }

        if (Stream.concat(inventory.stream(), offhand.stream()).noneMatch(itemStack -> itemStack.getItem() == Items.TOTEM_OF_UNDYING)){
            ChatUtils.error("AutoCobbleFarm: No totem found in inventory");
            shouldStop = true;
            return;
        }
    }

    private void waitForArrival(ICustomGoalProcess goalProcess) {
        while (joining || (goalProcess.isActive() && !shouldStop) || !fullyJoinedServer()) {
            sleep(1000);
        }
    }

    private void setupAutoTotem(AutoTotem autoTotem) {
        autoTotem.mode.set(AutoTotem.Mode.Strict);
        setModuleActive(autoTotem, true);
    }

    private void setupAutoTool(AutoTool autoTool) {
        autoTool.fromInventory.set(true);
        autoTool.antiBreak.set(true);
        autoTool.breakDurability.set(5);
        setModuleActive(autoTool,true);
    }

    private void setupNuker(Nuker nuker) {
        nuker.shape.set(Nuker.Shape.Sphere);
        nuker.mode.set(Nuker.Mode.All);
        nuker.range.set(5.0);
        nuker.delay.set(4);
        nuker.maxBlocksPerTick.set(1);
        nuker.sortMode.set(Nuker.SortMode.Closest);
        nuker.swingHand.set(false);
        nuker.packetMine.set(true);
        nuker.rotate.set(false);
        nuker.listMode.set(Nuker.ListMode.Whitelist);
        nuker.whitelist.set(List.of(COBBLESTONE));
        setModuleActive(nuker, true);
    }

    private void setupAutoMend(AutoMend autoMend) {
        autoMend.blacklist.set(List.of(Items.DIAMOND_SWORD, Items.NETHERITE_SWORD, Items.ELYTRA));
        autoMend.force.set(true);
        autoMend.autoDisable.set(false);
        setModuleActive(autoMend, true);
    }

    private void setupKillAura(KillAura killAura) {
        killAura.weapon.set(KillAura.Weapon.Sword);
        killAura.autoSwitch.set(true);
        killAura.entities.set(Set.of(ZOMBIE, SKELETON, SPIDER, CREEPER, DROWNED, WITCH, ZOMBIE_VILLAGER));
        setModuleActive(killAura, true);
    }

    private void waitForPickaxeDurability(AutoTool autoTool, PickaxeDurability pickaxeDurability) {
        while(!shouldStop) {
            if (!fullyJoinedServer()){
                sleep(500);
                continue;
            }
            List<ItemStack> items = new ArrayList<>(mc.player.getInventory().main);
            items.addAll(mc.player.getInventory().offHand);
            List<ItemStack> pickaxes = items.stream().filter(item -> item.getItem() instanceof PickaxeItem).toList();
            boolean done = pickaxes.stream().allMatch(pickaxe ->
                switch (pickaxeDurability) {
                    case LOW -> (0.99 - ((double) pickaxe.getDamage() / (double) pickaxe.getMaxDamage())) * 100 <= autoTool.breakDurability.get();
                    case FULL -> pickaxe.getDamage() == 0;
                }
            );
            if (done) {
                break;
            }
            System.out.println("Waiting for pickaxe durability " + pickaxeDurability.toString());
            sleep(5000);
        }
    }

    private enum PickaxeDurability {
        LOW,
        FULL
    }

    private void sleep(int ms){
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean fullyJoinedServer() {
        ClientPlayerEntity player = mc.player;
        if (player == null) return false;
        PlayerInventory inventory = player.getInventory();
        if (inventory == null) return false;
        return inventory.main.stream().anyMatch(itemStack -> itemStack.getItem() != Items.AIR); // if we have any non-air item, we're out of the queue
    }
}
