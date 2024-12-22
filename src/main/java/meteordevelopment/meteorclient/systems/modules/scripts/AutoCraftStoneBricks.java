/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;
import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.ICustomGoalProcess;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.BlockPosSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.*;

public class AutoCraftStoneBricks extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final Setting<BlockPos> stoneStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("stone-storage")
        .description("Coordinates of the Chest containing stone shulkers")
        .build()
    );

    private final Setting<BlockPos> stoneBricksStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("stone-brick-storage")
        .description("Coordinates of the Chest containing stone brick shulkers")
        .build()
    );

    private final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
        .name("repeat")
        .description("repeats until the module is deactivated")
        .build()
    );

    private BlockPos shulkerPlacePos;

    public AutoCraftStoneBricks() {
        super(Categories.Script, "auto-craft-stone-bricks", "Automatically craft Stone Bricks");
    }

    private boolean shouldStop = false;

    @SuppressWarnings("unused")
    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        setModuleActive(this, false);
    }

     @Override
     public void onDeactivate(){
        shouldStop = true;
     }

    @Override
    public void onActivate() {
        shouldStop = false;
        if (this.isActive() && playerInventoryHasItem(mc, Items.SHULKER_BOX)){
            ChatUtils.error("HighwayAutoCraft: Can't start the script if you're already carrying a shulker box, sorry");
            this.toggle();
            return;
        }
        if (this.isActive() && playerInventoryHasItem(mc, Items.STONE)){
            ChatUtils.error("HighwayAutoCraft: Can't start the script if you're already carrying stone, sorry");
            this.toggle();
            return;
        }
        if (this.isActive() && playerInventoryHasItem(mc, Items.STONE_BRICKS)){
            ChatUtils.error("HighwayAutoCraft: Can't start the script if you're already carrying stone bricks, sorry");
            this.toggle();
            return;
        }
        MeteorExecutor.execute(() -> {
            shulkerPlacePos = mc.player.getBlockPos();
            while (!shouldStop) {
                autoCraftStoneBricks();
                if (!repeat.get()){
                    break;
                }
            }
            setModuleActive(this, false);
        });
    }

    private void autoCraftStoneBricks() {
        ICustomGoalProcess goalProcess =  BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
        IBuilderProcess builderProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess();

        getStoneShulker(goalProcess);
        if (shouldStop){
            return;
        }

        baritoneGetToBlock(goalProcess, shulkerPlacePos);
        baritonePlaceBlock(builderProcess, Blocks.SHULKER_BOX, shulkerPlacePos);

        craftAllStoneBricks(goalProcess);

        builderProcess.clearArea(shulkerPlacePos, shulkerPlacePos);
        baritoneWait(builderProcess);
        goalProcess.setGoalAndPath(new GoalBlock(shulkerPlacePos));
        baritoneWait(goalProcess);

        storeStoneBrickShulker(goalProcess);
    }

    private void craftAllStoneBricks(ICustomGoalProcess goalProcess) {
        while (true) {
            openShulker(mc, goalProcess, shulkerPlacePos);

            dumpStoneBricks();
            extractItems(mc, Items.STONE, 0);
            if (getPlayerInventoryItemCount(mc, Items.STONE) == 0){
                break;
            }

            closeScreen(mc);
            setScreen(mc, new InventoryScreen(mc.player));

            while (!getPlayerInventorySlotsWithItem(mc, Items.STONE).isEmpty()) {
                craft(mc, Items.STONE_BRICKS, true);
            }
            closeScreen(mc);
        }

        // Dump final crafting batch
        openShulker(mc, goalProcess, shulkerPlacePos);
        dumpStoneBricks();
    }

    private void getStoneShulker(ICustomGoalProcess goalProcess) {
        baritoneGetToBlock(goalProcess, stoneStorage.get());

        openChest(mc, goalProcess, stoneStorage.get());
        takeItemStackFromContainer(mc, new SimpleInventory(), (itemStack -> isFullShulkerOfItem(itemStack, Items.STONE)));
        waitUntilTrue(() -> playerInventoryHasItem(mc, Items.SHULKER_BOX));
    }

    private void storeStoneBrickShulker(ICustomGoalProcess goalProcess) {
        baritoneGetToBlock(goalProcess, stoneBricksStorage.get());
        openChest(mc, goalProcess, stoneBricksStorage.get());

        Slot shulkerSlot = filterVisibleSlotsByInventory(mc, PlayerInventory.class).filter(slot -> slot.getStack().getItem() == Items.SHULKER_BOX).toList().getFirst();
        clickSlot(mc, shulkerSlot.getStack(), shulkerSlot.id, SlotActionType.QUICK_MOVE);
    }

    private void dumpStoneBricks() {
        List<Slot> stoneBrickSlots = getPlayerInventorySlotsWithItem(mc, Items.STONE_BRICKS);
        if (!stoneBrickSlots.isEmpty()){
            stoneBrickSlots.forEach(slot -> {
                clickSlot(mc, slot.getStack(), slot.id, SlotActionType.QUICK_MOVE);
                sleep(50);
            });
        }
    }
}
