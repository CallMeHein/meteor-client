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
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

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
        Map<String, Integer> shulkerItems = getShulkerItems(getFirstItemInPlayerInventory(mc, Items.SHULKER_BOX));

        System.out.println("Moving to place shulker");
        baritoneGetToBlock(goalProcess, shulkerPlacePos);

        System.out.println("Placing shulker");
        baritonePlaceBlock(builderProcess, Blocks.SHULKER_BOX, shulkerPlacePos);

        craftAllStoneBricks(shulkerItems);

        System.out.println("Breaking shulker");
        builderProcess.clearArea(shulkerPlacePos, shulkerPlacePos);
        baritoneWait(builderProcess);
        goalProcess.setGoalAndPath(new GoalBlock(shulkerPlacePos));
        baritoneWait(goalProcess);

        storeStoneBrickShulker(goalProcess);
    }

    private void craftAllStoneBricks(Map<String, Integer> shulkerItems) {
        while (shulkerItems.getOrDefault("minecraft:stone",0) > 0 || shulkerItems.getOrDefault("minecraft:air", 0) != 0) {
            System.out.println("Opening shulker");
            openShulker(mc, shulkerPlacePos);

            dumpStoneBricks(shulkerItems);
            extractStone(shulkerItems);

            System.out.println("Opening inventory");
            closeScreen(mc);
            setScreen(mc, new InventoryScreen(mc.player));

            System.out.println("Crafting stone bricks");
            while (!getPlayerInventorySlotsWithItem(mc, Items.STONE).isEmpty()) {
                craft(mc, Items.STONE_BRICKS, true);
            }
            mc.execute(() -> {
                mc.setScreen(null);
                mc.player.closeHandledScreen();
            });
        }

        // Dump final crafting batch
        System.out.println("Opening shulker");
        openShulker(mc, shulkerPlacePos);
        dumpStoneBricks(shulkerItems);
    }

    private void getStoneShulker(ICustomGoalProcess goalProcess) {
        System.out.println("Walking to stone storage");
        baritoneGetToBlock(goalProcess, stoneStorage.get());

        System.out.println("Opening stone storage");
        openChest(mc, stoneStorage.get());
        if (containerHasItem(mc, new SimpleInventory(), (itemStack -> isFullShulkerOfItem(itemStack, Items.STONE)))) {
            System.out.println("Taking out stone shulker");
            takeItemStackFromContainer(mc, new SimpleInventory(), (itemStack -> isFullShulkerOfItem(itemStack, Items.STONE)));
        }
        else {
            setModuleActive(this, false);
            shouldStop = true;
        }
        waitUntilTrue(() -> playerInventoryHasItem(mc, Items.SHULKER_BOX));
    }

    private void storeStoneBrickShulker(ICustomGoalProcess goalProcess) {
        System.out.println("Storing finished shulker");
        baritoneGetToBlock(goalProcess, stoneBricksStorage.get());
        openChest(mc, stoneBricksStorage.get());

        Slot shulkerSlot = filterVisibleSlotsByInventory(mc, PlayerInventory.class).filter(slot -> slot.getStack().getItem() == Items.SHULKER_BOX).toList().getFirst();
        clickSlot(mc, shulkerSlot.getStack(), shulkerSlot.id, SlotActionType.QUICK_MOVE);
    }

    private void dumpStoneBricks(Map<String, Integer> shulkerItems) {
        System.out.println("Dumping stone bricks into shulker");
        List<Slot> stoneBrickSlots = getPlayerInventorySlotsWithItem(mc, Items.STONE_BRICKS);
        if (!stoneBrickSlots.isEmpty()){
            stoneBrickSlots.forEach(slot -> {
                clickSlot(mc, slot.getStack(), slot.id, SlotActionType.QUICK_MOVE);
                shulkerItems.put("minecraft:stone_bricks",  shulkerItems.getOrDefault("minecraft:stone_bricks", 0) + slot.getStack().getCount());
                sleep(50);
            });
        }
    }

    private void extractStone(Map<String, Integer> shulkerItems) {
        System.out.println("Taking stone out of shulker");
        while (playerHasEmptySlots(mc) && containerHasItem(mc, new SimpleInventory(), (itemStack -> itemStack.getItem() == Items.STONE))) {
            takeItemStackFromContainer(mc, new SimpleInventory(), (itemStack -> itemStack.getItem() == Items.STONE));
            sleep(25);
        }
        int stoneCount = mc.player.getInventory().main.stream()
            .filter(itemStack -> itemStack.getItem() == Items.STONE)
            .mapToInt(ItemStack::getCount).sum();
        shulkerItems.put("minecraft:stone", shulkerItems.get("minecraft:stone") - stoneCount);
    }
}
