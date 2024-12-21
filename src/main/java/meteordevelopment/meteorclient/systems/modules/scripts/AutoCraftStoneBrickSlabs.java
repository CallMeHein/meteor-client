/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;

import baritone.api.BaritoneAPI;
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
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.*;

public class AutoCraftStoneBrickSlabs extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<BlockPos> emptyStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("empty-storage")
        .description("Coordinates of the Chest containing empty shulkers")
        .build()
    );

    private final Setting<BlockPos> stoneBricksStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("stone-brick-storage")
        .description("Coordinates of the Chest containing stone brick shulkers")
        .build()
    );

    private final Setting<BlockPos> stoneBrickSlabsStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("stone-brick-slab-storage")
        .description("Coordinates of the Chest containing stone brick slab shulkers")
        .build()
    );

    private final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
        .name("repeat")
        .description("repeats until the module is deactivated")
        .build()
    );

    private BlockPos stoneBrickShulkerPos;
    private BlockPos emptyShulkerPos;
    private BlockPos craftingTablePos;

    public AutoCraftStoneBrickSlabs() {
        super(Categories.Script, "auto-craft-stone-brick-slabs", "Automatically craft Stone Brick Slabs");
    }

    private boolean shouldStop = false;

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
        if (this.isActive() && playerInventoryHasItem(mc, Items.STONE_BRICKS)){
            ChatUtils.error("HighwayAutoCraft: Can't start the script if you're already carrying stone bricks, sorry");
            this.toggle();
            return;
        }
        if (this.isActive() && playerInventoryHasItem(mc, Items.STONE_BRICK_SLAB)){
            ChatUtils.error("HighwayAutoCraft: Can't start the script if you're already carrying stone brick slabs, sorry");
            this.toggle();
            return;
        }
        MeteorExecutor.execute(() -> {
            craftingTablePos = findClosestBlock(mc, mc.player.getBlockPos(), 20, Blocks.CRAFTING_TABLE);
            baritoneGetToBlock(BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess(), craftingTablePos);
            stoneBrickShulkerPos = mc.player.getBlockPos();
            emptyShulkerPos = stoneBrickShulkerPos.south(1);
            while (!shouldStop) {
                autoCraftStoneBrickSlabs();
                if (!repeat.get()){
                    break;
                }
            }
            setModuleActive(this, false);
        });
    }

    private void autoCraftStoneBrickSlabs() {
        ICustomGoalProcess goalProcess =  BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
        IBuilderProcess builderProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess();

        retrieveAndPlaceShulker(goalProcess, builderProcess, stoneBricksStorage.get(), stoneBrickShulkerPos);
        retrieveAndPlaceShulker(goalProcess, builderProcess, emptyStorage.get(), emptyShulkerPos);

        craftStoneBrickSlabs(goalProcess);

        breakAndPickupBlock(goalProcess, builderProcess, stoneBrickShulkerPos);
        breakAndPickupBlock(goalProcess, builderProcess, emptyShulkerPos);

        storeShulkers(goalProcess);
    }

    private void craftStoneBrickSlabs(ICustomGoalProcess goalProcess) {
        baritoneGetToBlock(goalProcess, craftingTablePos);
        while (true) {
            dumpStoneBrickSlabs(goalProcess);
            int keepEmptySlots = (int) Math.ceil(getPlayerEmptySlotCount(mc) / 2.0) + 1; // half, rounded up, + 1 because we might not fully use up all stone bricks
            extractItems(mc, Items.STONE_BRICKS, keepEmptySlots);
            closeScreen(mc);
            if (!playerInventoryHasItem(mc, Items.STONE_BRICKS)){
                break;
            }
            openCraftingTable(mc, goalProcess, craftingTablePos);
            while (getPlayerInventoryItemCount(mc, Items.STONE_BRICKS) >= 3) {
                craft(mc, Items.STONE_BRICK_SLAB, true);
                sleep(50);
            }
            closeScreen(mc);
        }
    }

    private void storeShulkers(ICustomGoalProcess goalProcess) {
        baritoneGetToBlock(goalProcess, stoneBrickSlabsStorage.get());
        openChest(mc, goalProcess, stoneBrickSlabsStorage.get());
        List<Slot> shulkers = getPlayerInventorySlotsWithItem(mc, Items.SHULKER_BOX);
        shulkers.forEach(slot -> {
            clickSlot(mc, slot.getStack(), slot.id, SlotActionType.QUICK_MOVE);
            sleep(100);
        });
        waitUntilTrue(() -> !playerInventoryHasItem(mc, Items.SHULKER_BOX));
        closeScreen(mc);
    }

    private void retrieveAndPlaceShulker(ICustomGoalProcess goalProcess, IBuilderProcess builderProcess, BlockPos storagePos, BlockPos shulkerPlacePos) {
        baritoneGetToBlock(goalProcess, storagePos);
        openChest(mc, goalProcess, storagePos);
        takeItemStackFromContainer(mc,new SimpleInventory(), (itemStack -> itemStack.getItem() == Items.SHULKER_BOX));
        waitUntilTrue(() -> playerInventoryHasItem(mc, Items.SHULKER_BOX));
        closeScreen(mc);
        baritonePlaceBlock(builderProcess, Blocks.SHULKER_BOX, shulkerPlacePos);
    }

    private void dumpStoneBrickSlabs(ICustomGoalProcess goalProcess){
        // fill initially empty shulker first
        openShulker(mc, goalProcess, emptyShulkerPos);
        while (playerInventoryHasItem(mc,Items.STONE_BRICK_SLAB) && containerHasItem(mc, new SimpleInventory(), (itemStack -> itemStack.getItem() == Items.AIR || (itemStack.getItem() == Items.STONE_BRICK_SLAB && itemStack.getCount() != 64)))){
            Slot stoneBrickSlabSlot = getPlayerInventorySlotsWithItem(mc, Items.STONE_BRICK_SLAB).getFirst();
            clickSlot(mc, stoneBrickSlabSlot.getStack(), stoneBrickSlabSlot.id, SlotActionType.QUICK_MOVE);
            sleep(50);
        }
        closeScreen(mc);

        // then the shulker containing stone bricks - keep it open for the next step, too
        openShulker(mc, goalProcess, stoneBrickShulkerPos);
        while (playerInventoryHasItem(mc,Items.STONE_BRICK_SLAB) && containerHasItem(mc, new SimpleInventory(), (itemStack -> itemStack.getItem() == Items.AIR || (itemStack.getItem() == Items.STONE_BRICK_SLAB && itemStack.getCount() != 64)))){
            Slot stoneBrickSlabSlot = getPlayerInventorySlotsWithItem(mc, Items.STONE_BRICK_SLAB).getFirst();
            clickSlot(mc, stoneBrickSlabSlot.getStack(), stoneBrickSlabSlot.id, SlotActionType.QUICK_MOVE);
            sleep(50);
        }
    }
}
