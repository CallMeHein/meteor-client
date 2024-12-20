/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;

import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.schematic.AbstractSchematic;
import baritone.api.schematic.ISchematic;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static net.minecraft.util.Hand.MAIN_HAND;

public class ScriptUtils {
    //
    // Meteor
    //

    static void setModuleActive(Module module, boolean active){
        if (module.isActive() != active){
            module.toggle();
        }
    }

    //
    // Baritone
    //

    static void baritoneGetToBlock(ICustomGoalProcess goalProcess, BlockPos pos) {
        goalProcess.setGoalAndPath(new GoalGetToBlock(pos));
        baritoneWait(goalProcess);
    }

    static void baritonePlaceBlock(IBuilderProcess builderProcess, Block block, BlockPos blockPos){
        ISchematic placeBlock = new PlaceStructureSchematic(block);
        builderProcess.build("_", placeBlock, blockPos);
        baritoneWait(builderProcess);
    }

    static void baritoneWait(IBaritoneProcess process){
        while (process.isActive()){
            sleep(100);
        }
    }

    static class PlaceStructureSchematic extends AbstractSchematic {
        private final Block[] toPlace;

        public PlaceStructureSchematic(Block... blocksToPlace) {
            super(1, 1, 1);
            this.toPlace = blocksToPlace;
        }

        @Override
        public BlockState desiredState(int x, int y, int z, BlockState blockState, List<BlockState> available) {
            if (x == 0 && y == 0 && z == 0) {
                // Place!!
                if (!available.isEmpty()) {
                    for (BlockState possible : available) {
                        if (possible == null) continue;
                        if (Arrays.asList(toPlace).contains(possible.getBlock())) {
                            return possible;
                        }
                    }
                }
            }
            // Don't care.
            return blockState;
        }
    }

    //
    // Interactions
    //

    static void rightClick(MinecraftClient mc, BlockPos position) {
        ClientPlayerInteractionManager interactionManager = mc.interactionManager;
        assert interactionManager != null;
        mc.execute(() ->
            interactionManager.interactBlock(mc.player, MAIN_HAND, new BlockHitResult(
                Vec3d.ofCenter(position),
                Direction.UP,
                position,
                false
            )));
    }

    static void takeItemStackFromContainer(MinecraftClient mc, Inventory targetContainer, Predicate<ItemStack> isTargetItem) {
        List<Slot> containerInventory = getContainerSlots(mc, targetContainer);
        AtomicReference<ItemStack> item = new AtomicReference<>();
        int chestSlotId = containerInventory.stream().filter(slot -> {
            if(isTargetItem.test(slot.inventory.getStack(slot.id))){
                item.set(slot.inventory.getStack(slot.id));
                return true;
            }
            return false;
        }).toList().getFirst().id;
        clickSlot(mc, item.get(), chestSlotId, SlotActionType.QUICK_MOVE);
    }

    static void clickSlot(MinecraftClient mc, ItemStack itemStack, int slotId, SlotActionType action) {
        ScreenHandler screenHandler = mc.player.currentScreenHandler;
        ClickSlotC2SPacket clickPacket = new ClickSlotC2SPacket(
            screenHandler.syncId,
            screenHandler.getRevision(),
            slotId,
            0,
            action,
            itemStack,
            new Int2ObjectArrayMap<>()
        );
        sendPacket(mc, clickPacket);
    }

    static void sendPacket(MinecraftClient mc, Packet<ServerPlayPacketListener> packet) {
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();
        assert networkHandler != null;
        networkHandler.sendPacket(packet);
    }

    static void openShulker(MinecraftClient mc, BlockPos shulkerPos) {
        rightClick(mc, shulkerPos);
        waitUntilTrue(() -> mc.currentScreen instanceof ShulkerBoxScreen);
    }

    static void openChest(MinecraftClient mc, BlockPos chestPos) {
        rightClick(mc, chestPos);
        waitUntilTrue(() -> mc.currentScreen instanceof GenericContainerScreen);
    }

    static void craft(MinecraftClient mc, Item item, boolean craftAll){
        int craftingResultSlotId = getCraftingResultSlotIndex(mc);
        RecipeEntry<CraftingRecipe> recipe = (RecipeEntry<CraftingRecipe>) mc.world.getRecipeManager().get(Identifier.of(item.toString().split(":")[0], item.toString().split(":")[1])).get();
        mc.interactionManager.clickRecipe(mc.player.currentScreenHandler.syncId, recipe,craftAll);
        waitUntilTrue(() -> slotIsItem(mc, craftingResultSlotId, item), 100);
        clickSlot(mc, mc.player.currentScreenHandler.slots.get(craftingResultSlotId).getStack(),craftingResultSlotId, SlotActionType.QUICK_MOVE);
        waitUntilTrue(() -> slotIsItem(mc, craftingResultSlotId, Items.AIR), 100);
    }

    //
    // Data
    //

    static boolean containerHasItem(MinecraftClient mc, Inventory targetContainer, Predicate<ItemStack> isTargetItem){
        List<Slot> containerInventory = getContainerSlots(mc, targetContainer);
        return containerInventory.stream().anyMatch(slot -> isTargetItem.test(slot.inventory.getStack(slot.id)));
    }

    static List<Slot> getContainerSlots(MinecraftClient mc, Inventory targetContainer) {
        ScreenHandler screen = mc.player.currentScreenHandler;
        List<Slot> containerInventory = screen.slots.stream().filter(slot -> slot.inventory.getClass() == targetContainer.getClass()).toList();
        return containerInventory;
    }

    static boolean isFullShulkerOfItem(ItemStack itemStack, Item targetItem) {
        return itemStack.getItem() == Items.SHULKER_BOX &&  getShulkerItems(itemStack).get(targetItem.toString()) == 64 * 9 * 3;
    }

    static Map<String, Integer> getShulkerItems(ItemStack shulker) {
        Optional<? extends ContainerComponent> containerComponent = shulker.getComponentChanges().get(DataComponentTypes.CONTAINER);
        Map<String ,Integer> items = new HashMap<>();
        if (containerComponent != null){
            containerComponent.get().iterateNonEmpty().forEach(itemStack -> {
                if (items.containsKey(itemStack.getItem().toString())){
                    items.put(itemStack.getItem().toString(), items.get(itemStack.getItem().toString()) + itemStack.getCount());
                }
                else {
                    items.put(itemStack.getItem().toString(), itemStack.getCount());
                }
            });
        }
        return items;
    }

    static Stream<Slot> filterVisibleSlotsByInventory(MinecraftClient mc, Class<? extends Inventory> inventory){
        return mc.player.currentScreenHandler.slots.stream().filter(slot -> slot.inventory.getClass() == inventory);
    }

    static int getCraftingResultSlotIndex(MinecraftClient mc){
        return filterVisibleSlotsByInventory(mc, CraftingResultInventory.class).findFirst().get().id;
    }

    static List<Slot> getPlayerInventorySlotsWithItem(MinecraftClient mc, Item item){
        return mc.player.currentScreenHandler.slots.stream().filter(slot -> slot.inventory instanceof PlayerInventory && slot.inventory.getStack(slot.getIndex()).getItem() == item).toList();
    }

    static int getPlayerEmptySlotCount(MinecraftClient mc){
        return (int) mc.player.getInventory().main.stream().filter(itemStack -> itemStack.getItem() == Items.AIR).count();
    }

    static boolean playerHasEmptySlots(MinecraftClient mc) {
        return getPlayerEmptySlotCount(mc) > 0;
    }

    static boolean playerInventoryHasItem(MinecraftClient mc, Item item) {
        return mc.player.getInventory().main.stream().anyMatch(itemStack -> itemStack.getItem() == item);
    }

    static ItemStack getFirstItemInPlayerInventory(MinecraftClient mc, Item item) {
        return mc.player.getInventory().main.stream().filter(itemStack -> itemStack.getItem() == item).toList().getFirst();
    }

    static boolean slotIsItem(MinecraftClient mc, int slotId, Item item) {
        return mc.player.currentScreenHandler.getSlot(slotId).getStack().getItem() == item;
    }

    //
    // Flow control
    //

    static void sleep(int ms){
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    static void waitUntilTrue(Supplier<Boolean> condition){
        waitUntilTrue(condition, 250);
    }

    static void waitUntilTrue(Supplier<Boolean> condition, int pollingRateMs){
        do {
            sleep(pollingRateMs);
        } while (!condition.get());
    }

    static void closeScreen(MinecraftClient mc) {
        mc.execute(() -> {
            mc.setScreen(null);
            mc.player.closeHandledScreen();
        });
    }

    static void setScreen(MinecraftClient mc, Screen screen) {
        mc.execute(() -> {
            mc.setScreen(screen);
        });
        waitUntilTrue(() -> mc.currentScreen.getClass() == screen.getClass());
    }
}
