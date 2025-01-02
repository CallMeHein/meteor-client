/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;

import baritone.api.BaritoneAPI;
import baritone.api.process.ICustomGoalProcess;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.slot.TradeOutputSlot;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerProfession;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.*;
import static net.minecraft.village.VillagerProfession.CLERIC;
import static net.minecraft.village.VillagerProfession.MASON;

public class AutoVillagerTrade extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<BlockPos> stoneChest = sgGeneral.add(new BlockPosSetting.Builder()
            .name("stone-chest")
            .description("Coordinates of the chest containing stone")
            .build());

    private final Setting<BlockPos> emeraldChest = sgGeneral.add(new BlockPosSetting.Builder()
            .name("emerald-chest")
            .description("Coordinates of the chest containing emeralds")
            .build());

    private final Setting<BlockPos> xpChest = sgGeneral.add(new BlockPosSetting.Builder()
            .name("xp-chest")
            .description("Coordinates of the chest for storing XP bottles")
            .build());

    private final Setting<Integer> restockWaitTime = sgGeneral.add(new IntSetting.Builder()
            .name("restock-wait-time")
            .description("Time to wait for villagers to restock (in minutes)")
            .defaultValue(20)
            .min(1)
            .build());

    public final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
            .name("repeat")
            .description("Repeats until the module is deactivated")
            .build());

    public boolean shouldStop = false;
    private long lastTradeTime = 0;

    public AutoVillagerTrade() {
        super(Categories.Script, "auto-villager-trade",
                "Automatically trades stone for emeralds with masons, then emeralds for XP bottles with clerics");
    }

    @EventHandler
    public void onGameLeft(GameLeftEvent event) {
        setModuleActive(this, false);
    }

    @Override
    public void onDeactivate() {
        shouldStop = true;
    }

    @Override
    public void onActivate() {
        BaritoneAPI.getSettings().allowBreak.value = false;
        shouldStop = false;
        MeteorExecutor.execute(() -> {
            do {
                trade();
            } while (repeat.get() && !shouldStop);
            setModuleActive(this, false);
            ChatUtils.info("AutoVillagerTrade: DONE");
        });
    }

    private void trade() {
        ICustomGoalProcess goalProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();

        // Check if we need to wait for villager restock
        if (lastTradeTime != 0 && System.currentTimeMillis() - lastTradeTime < restockWaitTime.get() * 60 * 1000) {
            long waitTimeLeft = (restockWaitTime.get() * 60 * 1000) - (System.currentTimeMillis() - lastTradeTime);
            ChatUtils.info("Waiting for villagers to restock... " + (waitTimeLeft / 1000 / 60) + " minutes left");
            sleep(1000);
            return;
        }

        // First phase: Trade stone for emeralds with masons
        List<VillagerEntity> masons = new ArrayList<>(getEntitiesByClass(mc, VillagerEntity.class).stream()
                .sorted(Comparator
                        .comparingDouble(villager -> villager.squaredDistanceTo(Vec3d.of(mc.player.getBlockPos()))))
                .filter(villager -> villager.getVillagerData().getProfession() == MASON)
                .toList());

        boolean tradedWithAny = false;
        int masonIndex = 0;

        // Trade with all masons, refilling stone as needed
        while (!shouldStop && masonIndex < masons.size()) {
            // Get more stone if needed
            if (!hasMinStoneAmount()) {
                storeAllItems(goalProcess); // Store any items we have before getting more stone
                openChest(mc, goalProcess, stoneChest.get());
                ChatUtils.info("Checking stone chest...");
                if (!containerHasItem(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == Items.STONE))) {
                    ChatUtils.error("No stone found in chest!");
                    closeScreen(mc);
                    break;
                }

                int stoneTaken = 0;
                while (!shouldStop && getPlayerEmptySlotCount(mc) > 1 &&
                        containerHasItem(mc, SimpleInventory.class,
                                (itemStack -> itemStack.getItem() == Items.STONE))) {
                    takeItemStackFromContainer(mc, SimpleInventory.class,
                            (itemStack -> itemStack.getItem() == Items.STONE));
                    stoneTaken++;
                    sleep(50);
                }
                closeScreen(mc);
                ChatUtils.info("Took " + stoneTaken + " stone from chest");

                if (!hasMinStoneAmount()) {
                    ChatUtils.error("Not enough stone in chest!");
                    break;
                }
            }

            VillagerEntity mason = masons.get(masonIndex);
            baritoneGetNearBlock(goalProcess, mason.getBlockPos(), 2);
            boolean traded = tradeWithMason(mason);
            if (traded)
                tradedWithAny = true;
            closeScreen(mc);
            masonIndex++;
        }

        // Store any remaining items before proceeding
        storeAllItems(goalProcess);

        // Second phase: Trade emeralds for XP bottles with clerics
        List<VillagerEntity> clerics = new ArrayList<>(getEntitiesByClass(mc, VillagerEntity.class).stream()
                .sorted(Comparator
                        .comparingDouble(villager -> villager.squaredDistanceTo(Vec3d.of(mc.player.getBlockPos()))))
                .filter(villager -> villager.getVillagerData().getProfession() == CLERIC)
                .toList());

        int clericIndex = 0;

        // Trade with all clerics, refilling emeralds as needed
        while (!shouldStop && clericIndex < clerics.size()) {
            // Get more emeralds if needed
            if (!hasMinEmeraldAmount()) {
                storeAllItems(goalProcess); // Store any items we have before getting more emeralds
                openChest(mc, goalProcess, emeraldChest.get());
                while (!shouldStop && getPlayerEmptySlotCount(mc) > 1 &&
                        containerHasItem(mc, SimpleInventory.class,
                                (itemStack -> itemStack.getItem() == Items.EMERALD))) {
                    takeItemStackFromContainer(mc, SimpleInventory.class,
                            (itemStack -> itemStack.getItem() == Items.EMERALD));
                    sleep(50);
                }
                closeScreen(mc);

                if (!hasMinEmeraldAmount()) {
                    break;
                }
            }

            VillagerEntity cleric = clerics.get(clericIndex);
            baritoneGetNearBlock(goalProcess, cleric.getBlockPos(), 2);
            boolean traded = tradeWithCleric(cleric);
            if (traded)
                tradedWithAny = true;
            closeScreen(mc);
            clericIndex++;
        }

        // Store all remaining items at the end of the cycle
        storeAllItems(goalProcess);

        if (tradedWithAny) {
            lastTradeTime = System.currentTimeMillis();
        }

        // Deactivate if not repeating or if we should stop
        if (!repeat.get() || shouldStop) {
            ChatUtils.info("Trading round complete.");
            setModuleActive(this, false);
        }
    }

    private void storeAllItems(ICustomGoalProcess goalProcess) {
        // Store emeralds if any
        if (playerInventoryHasItem(mc, Items.EMERALD)) {
            openChest(mc, goalProcess, emeraldChest.get());
            while (!shouldStop && playerInventoryHasItem(mc, Items.EMERALD)) {
                for (Slot slot : getContainerSlots(mc, PlayerInventory.class).stream()
                        .filter(slot -> slot.getStack().getItem() == Items.EMERALD).toList()) {
                    if (containerHasItem(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == Items.AIR))) {
                        clickSlot(mc, slot.getStack(), slot.id, SlotActionType.QUICK_MOVE);
                        sleep(50);
                    }
                }
            }
            closeScreen(mc);
        }

        // Store XP bottles if any
        if (playerInventoryHasItem(mc, Items.EXPERIENCE_BOTTLE)) {
            openChest(mc, goalProcess, xpChest.get());
            while (!shouldStop && playerInventoryHasItem(mc, Items.EXPERIENCE_BOTTLE)) {
                for (Slot slot : getContainerSlots(mc, PlayerInventory.class).stream()
                        .filter(slot -> slot.getStack().getItem() == Items.EXPERIENCE_BOTTLE).toList()) {
                    if (containerHasItem(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == Items.AIR))) {
                        clickSlot(mc, slot.getStack(), slot.id, SlotActionType.QUICK_MOVE);
                        sleep(50);
                    }
                }
            }
            closeScreen(mc);
        }
    }

    private boolean tradeWithMason(VillagerEntity mason) {
        boolean traded = false;
        while (!shouldStop) {
            lookAtBlockPos(mc, mason.getBlockPos());
            sleep(50);
            rightClickEntity(mc, mason);
            waitUntilTrue(() -> mc.currentScreen instanceof MerchantScreen);
            selectVillagerTrade(mc, BuyOrSell.SELL, Items.STONE);
            TradeOutputSlot tradeOutputSlot = (TradeOutputSlot) mc.player.currentScreenHandler.slots.stream()
                    .filter(slot -> slot instanceof TradeOutputSlot).findFirst().get();
            sleep(50);
            if (!slotIsItem(mc, tradeOutputSlot.id, Items.EMERALD)) {
                break;
            }
            clickSlot(mc, tradeOutputSlot.getStack(), tradeOutputSlot.id, SlotActionType.QUICK_MOVE);
            traded = true;
            sleep(50);
            closeScreen(mc);
        }
        return traded;
    }

    private boolean tradeWithCleric(VillagerEntity cleric) {
        boolean traded = false;
        while (!shouldStop) {
            lookAtBlockPos(mc, cleric.getBlockPos());
            sleep(50);
            rightClickEntity(mc, cleric);
            waitUntilTrue(() -> mc.currentScreen instanceof MerchantScreen);
            selectVillagerTrade(mc, BuyOrSell.BUY, Items.EXPERIENCE_BOTTLE);
            TradeOutputSlot tradeOutputSlot = (TradeOutputSlot) mc.player.currentScreenHandler.slots.stream()
                    .filter(slot -> slot instanceof TradeOutputSlot).findFirst().get();
            sleep(50);
            if (!slotIsItem(mc, tradeOutputSlot.id, Items.EXPERIENCE_BOTTLE)) {
                break;
            }
            clickSlot(mc, tradeOutputSlot.getStack(), tradeOutputSlot.id, SlotActionType.QUICK_MOVE);
            traded = true;
            sleep(50);
            closeScreen(mc);
        }
        return traded;
    }

    private boolean hasMinStoneAmount() {
        List<Slot> stoneSlots = getPlayerInventorySlotsWithItem(mc, Items.STONE);
        int totalStone = stoneSlots.stream().mapToInt(slot -> slot.getStack().getCount()).sum();
        ChatUtils.info("Current stone amount: " + totalStone);
        return totalStone >= 20;
    }

    private boolean hasMinEmeraldAmount() {
        List<Slot> emeraldSlots = getPlayerInventorySlotsWithItem(mc, Items.EMERALD);
        return emeraldSlots.stream().mapToInt(slot -> slot.getStack().getCount()).sum() >= 3;
    }
}
