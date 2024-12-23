/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;

import baritone.api.BaritoneAPI;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.ICustomGoalProcess;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.*;

public class _AutoCraftSlabs {
    public static void autoCraftSlabs(
        MinecraftClient mc,
        Item fullBlockItem,
        Item slabItem,
        BlockPos fullBlockShulkerStorage,
        BlockPos slabShulkerStorage,
        BlockPos emptyShulkerStorage,
        AtomicBoolean shouldStop,
        boolean repeat
    ) {
        BlockPos craftingTablePos = findClosestBlock(mc, mc.player.getBlockPos(), 20, Blocks.CRAFTING_TABLE);
        BlockPos placedFullBlockShulkerPos = craftingTablePos.south(1);
        BlockPos placedEmptyShulkerPos = placedFullBlockShulkerPos.south(1);
        ICustomGoalProcess goalProcess =  BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();
        IBuilderProcess builderProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getBuilderProcess();

        do {
            retrieveAndPlaceShulker(mc, goalProcess, builderProcess, fullBlockShulkerStorage, placedFullBlockShulkerPos);
            retrieveAndPlaceShulker(mc, goalProcess, builderProcess, emptyShulkerStorage, placedEmptyShulkerPos);

            craftSlabs(mc, goalProcess, fullBlockItem, slabItem, craftingTablePos, placedEmptyShulkerPos, placedFullBlockShulkerPos);

            breakAndPickupBlock(goalProcess, builderProcess, placedFullBlockShulkerPos);
            breakAndPickupBlock(goalProcess, builderProcess, placedEmptyShulkerPos);

            storeShulkers(mc, goalProcess, slabShulkerStorage);
        } while (!shouldStop.get() && repeat);
    }

    public static void craftSlabs(
        MinecraftClient mc,
        ICustomGoalProcess goalProcess,
        Item fullBlockItem,
        Item slabItem,
        BlockPos craftingTablePos,
        BlockPos placedEmptyShulkerPos,
        BlockPos placedFullBlockShulkerPos
    ){
        baritoneGetToBlock(goalProcess, craftingTablePos);
        while (true) {
            dumpSlabs(mc, goalProcess, slabItem, placedEmptyShulkerPos, placedFullBlockShulkerPos);
            int keepEmptySlots = (int) Math.ceil(getPlayerEmptySlotCount(mc) / 2.0) + 1; // half, rounded up, + 1 because we might not fully use up all full blocks
            extractItems(mc, fullBlockItem, keepEmptySlots);
            closeScreen(mc);
            if (!playerInventoryHasItem(mc, fullBlockItem)){
                break;
            }
            openCraftingTable(mc, goalProcess, craftingTablePos);
            while (getPlayerInventoryItemCount(mc, fullBlockItem) >= 3) {
                craft(mc, slabItem, true);
                sleep(50);
            }
            closeScreen(mc);
        }
    }

    public static void storeShulkers(MinecraftClient mc, ICustomGoalProcess goalProcess, BlockPos slabShulkerStorage) {
        baritoneGetNearBlock(goalProcess, slabShulkerStorage, 2);
        openChest(mc, goalProcess, slabShulkerStorage);
        List<Slot> shulkers = getPlayerInventorySlotsWithItem(mc, Items.SHULKER_BOX);
        shulkers.forEach(slot -> {
            clickSlot(mc, slot.getStack(), slot.id, SlotActionType.QUICK_MOVE);
            sleep(100);
        });
        waitUntilTrue(() -> !playerInventoryHasItem(mc, Items.SHULKER_BOX));
        closeScreen(mc);
    }

    public static void retrieveAndPlaceShulker(MinecraftClient mc, ICustomGoalProcess goalProcess, IBuilderProcess builderProcess, BlockPos storagePos, BlockPos shulkerPlacePos) {
        baritoneGetNearBlock(goalProcess, storagePos, 2);
        openChest(mc, goalProcess, storagePos);
        takeItemStackFromContainer(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == Items.SHULKER_BOX));
        waitUntilTrue(() -> playerInventoryHasItem(mc, Items.SHULKER_BOX));
        closeScreen(mc);
        baritonePlaceBlock(builderProcess, Blocks.SHULKER_BOX, shulkerPlacePos);
    }

    public static void dumpSlabs(
        MinecraftClient mc,
        ICustomGoalProcess goalProcess,
        Item slabItem,
        BlockPos placedEmptyShulkerPos,
        BlockPos placedFullBlockShulkerPos
    ){
        // fill initially empty shulker first
        dumpSlabsInShulker(mc, goalProcess, slabItem, placedEmptyShulkerPos);
        closeScreen(mc);

        // then the shulker containing full blocks - keep it open for the next step, too
        dumpSlabsInShulker(mc, goalProcess, slabItem, placedFullBlockShulkerPos);
    }

    private static void dumpSlabsInShulker(MinecraftClient mc, ICustomGoalProcess goalProcess, Item slabItem, BlockPos placedEmptyShulkerPos) {
        openShulker(mc, goalProcess, placedEmptyShulkerPos);
        while (playerInventoryHasItem(mc, slabItem) && containerHasItem(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == Items.AIR || (itemStack.getItem() == slabItem && itemStack.getCount() != 64)))){
            Slot slabSlot = getPlayerInventorySlotsWithItem(mc, slabItem).getFirst();
            clickSlot(mc, slabSlot.getStack(), slabSlot.id, SlotActionType.QUICK_MOVE);
            sleep(50);
        }
    }
}
