/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;

import baritone.api.pathing.goals.*;
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
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.listener.ServerPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.village.TradeOffer;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.BuyOrSell.BUY;
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

    static void baritoneGetNearBlock(ICustomGoalProcess goalProcess, BlockPos pos, int range){
        goalProcess.setGoalAndPath(new GoalNear(pos, range));
        baritoneWait(goalProcess);
    }

    static void baritonePlaceBlock(IBuilderProcess builderProcess, Block block, BlockPos blockPos){
        ISchematic placeBlock = new PlaceStructureSchematic(block);
        builderProcess.build("_", placeBlock, blockPos);
        baritoneWait(builderProcess);
    }

    static void baritoneGoto(ICustomGoalProcess goalProcess, BlockPos pos){
        goalProcess.setGoalAndPath(new GoalBlock(pos));
        baritoneWait(goalProcess);
    }

    static void baritoneClearArea(IBuilderProcess builderProcess, BlockPos pos1, BlockPos pos2){
        builderProcess.clearArea(pos1, pos2);
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

        // stolen from Altoclef idk, it works
        @Override
        public BlockState desiredState(int x, int y, int z, BlockState blockState, List<BlockState> available) {
            if (x == 0 && y == 0 && z == 0) {
                if (!available.isEmpty()) {
                    for (BlockState possible : available) {
                        if (possible == null) continue;
                        if (Arrays.asList(toPlace).contains(possible.getBlock())) {
                            return possible;
                        }
                    }
                }
            }
            return blockState;
        }
    }

    //
    // Interactions
    //

    static void extractItems(MinecraftClient mc, Item target, int keepEmptySlots) {
        while (getPlayerEmptySlotCount(mc) > keepEmptySlots && containerHasItem(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == target))) {
            takeItemStackFromContainer(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == target));
            sleep(25);
        }
    }

    static void rightClickBlock(MinecraftClient mc, BlockPos position) {
        ClientPlayerInteractionManager interactionManager = mc.interactionManager;
        assert interactionManager != null;
        mc.execute(() ->
            interactionManager.interactBlock(
                mc.player,
                MAIN_HAND,
                new BlockHitResult(
                    Vec3d.ofCenter(position),
                    Direction.UP,
                    position,
                    false
                )
            )
        );
    }

    static void rightClickEntity(MinecraftClient mc, Entity entity) {
        mc.execute(() -> {
            PlayerInteractEntityC2SPacket interactAtPacket = PlayerInteractEntityC2SPacket.interactAt(
                entity,
            false,
                Hand.MAIN_HAND,
                entity.getBlockPos().toCenterPos()
            );

            PlayerInteractEntityC2SPacket interactPacket = PlayerInteractEntityC2SPacket.interact(
                entity,
                false,
                Hand.MAIN_HAND
            );
            mc.getNetworkHandler().sendPacket(interactAtPacket);
            mc.getNetworkHandler().sendPacket(interactPacket);
        });
    }

    static void takeItemStackFromContainer(MinecraftClient mc, Class<? extends Inventory> targetContainer, Predicate<ItemStack> isTargetItem) {
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

    static void openShulker(MinecraftClient mc, ICustomGoalProcess goalProcess, BlockPos shulkerPos) {
        System.out.println("Opening shulker");
        openContainer(mc, goalProcess,shulkerPos, ShulkerBoxScreen.class);
    }

    static void openChest(MinecraftClient mc, ICustomGoalProcess goalProcess, BlockPos chestPos) {
        System.out.println("Opening chest");
        openContainer(mc, goalProcess,chestPos, GenericContainerScreen.class);
    }

    static void openCraftingTable(MinecraftClient mc, ICustomGoalProcess goalProcess, BlockPos craftingTablePos){
        System.out.println("Opening crafting table");
        openContainer(mc, goalProcess,craftingTablePos, CraftingScreen.class);
    }

    static void openContainer(MinecraftClient mc, ICustomGoalProcess goalProcess, BlockPos pos, Class<? extends Screen> screen){
        baritoneGetToBlock(goalProcess, pos);
        while (mc.currentScreen == null || mc.currentScreen.getClass() != screen) {
            lookAtBlockPos(mc, pos);
            rightClickBlock(mc, pos);
            sleep(500);
        }
    }

    static void lookAtBlockPos(MinecraftClient mc, BlockPos targetPos) {
        Vec3d playerPos = mc.player.getPos();
        Vec3d targetVec = Vec3d.ofCenter(targetPos);
        Vec3d diff = targetVec.subtract(playerPos);

        double yaw = Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0;
        double pitch = Math.toDegrees(Math.atan2(diff.y, Math.sqrt(diff.x * diff.x + diff.z * diff.z)));

        mc.player.setYaw((float) yaw);
        mc.player.setPitch((float) pitch);
    }

    static void craft(MinecraftClient mc, Item item, boolean craftAll){
        int craftingResultSlotId = getCraftingResultSlotIndex(mc);
        RecipeEntry<CraftingRecipe> recipe = (RecipeEntry<CraftingRecipe>) mc.world.getRecipeManager().get(Identifier.of(item.toString().split(":")[0], item.toString().split(":")[1])).get();
        mc.interactionManager.clickRecipe(mc.player.currentScreenHandler.syncId, recipe,craftAll);
        waitUntilTrue(() -> slotIsItem(mc, craftingResultSlotId, item), 100);
        clickSlot(mc, mc.player.currentScreenHandler.slots.get(craftingResultSlotId).getStack(),craftingResultSlotId, SlotActionType.QUICK_MOVE);
        waitUntilTrue(() -> slotIsItem(mc, craftingResultSlotId, Items.AIR), 100);
    }

    static void breakAndPickupBlock(ICustomGoalProcess goalProcess, IBuilderProcess builderProcess, BlockPos pos){
        baritoneClearArea(builderProcess, pos, pos);
        baritoneGoto(goalProcess, pos);
    }

    static void selectVillagerTrade(MinecraftClient mc, BuyOrSell buyOrSell, Item targetItem) {
        MerchantScreen merchantScreen = (MerchantScreen) mc.currentScreen;
        MerchantScreenHandler merchantScreenHandler = merchantScreen.getScreenHandler();
        List<TradeOffer> tradeOffers = merchantScreenHandler.getRecipes();
        for (int i = 0; i < tradeOffers.size(); i++) {
            TradeOffer offer = tradeOffers.get(i);
            Item villagerWants = buyOrSell == BUY ? offer.getSellItem().getItem() : offer.getDisplayedFirstBuyItem().getItem();
            if (villagerWants == targetItem) {
                int onScreenIndex = i;
                // trade screen only shows 7/10 of the trades, if we need one of the last 3 trades we scroll down and adjust the index accordingly
                if (i > 7){
                    onScreenIndex -= 3;
                    merchantScreen.mouseScrolled(0,0,0,-3);
                    sleep(250);
                }
                getTradeOfferWidgets(merchantScreen)[onScreenIndex].onPress();
                break;
            }
        }
    }

    private static ButtonWidget[] getTradeOfferWidgets(MerchantScreen merchantScreen) {
        Field offersField;
        try {
            offersField = MerchantScreen.class.getDeclaredField("offers");
            offersField.setAccessible(true);
        } catch (NoSuchFieldException ignored) {
            try {
                offersField = MerchantScreen.class.getDeclaredField("field_19162");
                offersField.setAccessible(true);
            } catch (NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            return (ButtonWidget[]) offersField.get(merchantScreen);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    //
    // Data
    //

    static boolean containerHasItem(MinecraftClient mc, Class<? extends Inventory> targetContainer, Predicate<ItemStack> isTargetItem){
        List<Slot> containerInventory = getContainerSlots(mc, targetContainer);
        return containerInventory.stream().anyMatch(slot -> isTargetItem.test(slot.inventory.getStack(slot.id)));
    }

    static List<Slot> getContainerSlots(MinecraftClient mc, Class<? extends Inventory> targetInventory) {
        ScreenHandler screen = mc.player.currentScreenHandler;
        return screen.slots.stream().filter(slot -> slot.inventory.getClass() == targetInventory).toList();
    }

    static boolean isFullShulkerOfItem(ItemStack itemStack, Item targetItem) {
        return itemStack.getItem() == Items.SHULKER_BOX &&  getShulkerItems(itemStack).get(targetItem.toString()) == 64 * 9 * 3;
    }

    static int getPlayerInventoryItemCount(MinecraftClient mc, Item item){
        return mc.player.getInventory().main.stream().mapToInt(itemStack -> itemStack.getItem() == item ? itemStack.getCount() : 0).sum();
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

    static boolean playerInventoryHasItem(MinecraftClient mc, Item item) {
        return mc.player.getInventory().main.stream().anyMatch(itemStack -> itemStack.getItem() == item);
    }

    static boolean slotIsItem(MinecraftClient mc, int slotId, Item item) {
        return mc.player.currentScreenHandler.getSlot(slotId).getStack().getItem() == item;
    }

    static BlockPos findClosestBlock(MinecraftClient mc, BlockPos searchOrigin, int searchRadius, Block targetBlock) {
        BlockPos closestPos = null;
        double closestDistance = Double.MAX_VALUE;

        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -searchRadius; y <= searchRadius; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos currentPos = searchOrigin.add(x, y, z);
                    if (mc.world.getBlockState(currentPos).isOf(targetBlock)) {
                        double distance = searchOrigin.getSquaredDistance(currentPos);
                        if (distance < closestDistance) {
                            closestDistance = distance;
                            closestPos = currentPos;
                        }
                    }
                }
            }
        }
        return closestPos;
    }

    static <T extends Entity> List<T> getEntitiesByClass(MinecraftClient mc, Class<T> targetEntity) {
        List<Entity> entities = new ArrayList<>();
        mc.world.getEntities().forEach(entities::add);
        return (List<T>) entities.stream().filter(entity -> entity.getClass() == targetEntity).toList();
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
        waitUntilTrue(() -> mc.currentScreen == null || mc.currentScreen instanceof GameMenuScreen); // if game is not focused, it defaults to GameMenuScreen
        System.out.println("Closed screen");
    }

    static void setScreen(MinecraftClient mc, Screen screen) {
        mc.execute(() -> mc.setScreen(screen));
        waitUntilTrue(() -> mc.currentScreen.getClass() == screen.getClass());
    }

    public static boolean fullyJoinedServer(MinecraftClient mc) {
        if (mc.player == null) return false;
        PlayerInventory inventory = mc.player.getInventory();
        if (inventory == null) return false;
        return inventory.main.stream().anyMatch(itemStack -> itemStack.getItem() != Items.AIR);
    }

    enum BuyOrSell {
        BUY,
        SELL
    }
}
