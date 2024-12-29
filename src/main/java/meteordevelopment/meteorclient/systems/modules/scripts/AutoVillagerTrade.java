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

    private final Setting<BlockPos> inputStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("input-storage")
        .description("Coordinates of the chest containing the items given to the villager")
        .build()
    );

    private final Setting<BlockPos> outputStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("output-storage")
        .description("Coordinates of the chest containing the items received from the villager")
        .build()
    );

    private final Setting<BuyOrSell> buyOrSell = sgGeneral.add(new EnumSetting.Builder<BuyOrSell>()
        .name("buy-/-sell")
        .description("Whether to buy or sell the selected item")
        .defaultValue(BuyOrSell.BUY)
        .build()
    );


    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("Which item to buy/sell")
        .filter((item -> List.of(Items.EXPERIENCE_BOTTLE, Items.STONE).contains(item)))
        .defaultValue(Items.EXPERIENCE_BOTTLE)
        .build()
    );

    private final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
        .name("repeat")
        .description("Repeats until the module is deactivated")
        .build()
    );


    public boolean shouldStop = false;

    public AutoVillagerTrade() {
        super(Categories.Script,"auto-villager-trade","Automatically trade with villagers");
    }

    @EventHandler
    public void onGameLeft(GameLeftEvent event){
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
            }
            while (repeat.get() && !shouldStop);
            setModuleActive(this, false);
            ChatUtils.info("AutoVillagerTrade: DONE");
        });
    }

    private void trade() {
        ICustomGoalProcess goalProcess = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess();

        List<VillagerEntity> villagers = new ArrayList<>(getEntitiesByClass(mc, VillagerEntity.class).stream()
            .sorted(Comparator.comparingDouble(villager -> villager.squaredDistanceTo(Vec3d.of(mc.player.getBlockPos()))))
            .filter(this::villagerHasWantedProfession).toList());

        Item inputItem = buyOrSell.get() == BuyOrSell.BUY ? Items.EMERALD : targetItem.get(); // if we're buying items, we fetch emeralds. if we're selling items, we fetch the specified item
        Item outputItem = buyOrSell.get() == BuyOrSell.BUY ? targetItem.get() : Items.EMERALD; // if we're buying items, we store the target item. if we're selling items, we store emeralds

        openChest(mc, goalProcess, inputStorage.get());
        while(!shouldStop && getPlayerEmptySlotCount(mc) > 1 && containerHasItem(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == inputItem))) {
            takeItemStackFromContainer(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == inputItem));
            sleep(50);
        }
        closeScreen(mc);

        while (!shouldStop && !villagers.isEmpty()) {
            if (!playerHasMinTradeAmount(inputItem, outputItem)){
                break;
            }
            VillagerEntity villager = villagers.getFirst();
            baritoneGetNearBlock(goalProcess,villager.getBlockPos(), 2);
            tradeAllWithVillager(villager, villager.getBlockPos(), outputItem);
            closeScreen(mc);
            // recalculate closest Villager
            villagers.removeFirst();
            villagers = new ArrayList<>(villagers.stream().sorted(Comparator.comparingDouble(villagerEntity -> villagerEntity.squaredDistanceTo(Vec3d.of(mc.player.getBlockPos())))).toList());
        }

        openChest(mc, goalProcess, outputStorage.get());
        if (!playerInventoryHasItem(mc, outputItem)){
            shouldStop = true;
            closeScreen(mc);
            return;
        }
        while(!shouldStop && playerInventoryHasItem(mc, outputItem)){
            for (Slot slot : getContainerSlots(mc, PlayerInventory.class).stream().filter(slot -> slot.getStack().getItem() == outputItem).toList()){
                if (containerHasItem(mc, SimpleInventory.class, (itemStack -> itemStack.getItem() == Items.AIR))){
                    clickSlot(mc, slot.getStack(), slot.id, SlotActionType.QUICK_MOVE);
                    sleep(50);
                }
            }
        }
    }

    private void tradeAllWithVillager(VillagerEntity villager, BlockPos villagerPos, Item outputItem) {
        while (!shouldStop){
            lookAtBlockPos(mc, villagerPos);
            sleep(50);
            rightClickEntity(mc, villager);
            waitUntilTrue(() -> mc.currentScreen instanceof MerchantScreen);
            selectVillagerTrade(mc, buyOrSell.get(), targetItem.get());
            TradeOutputSlot tradeOutputSlot = (TradeOutputSlot) mc.player.currentScreenHandler.slots.stream().filter(slot -> slot instanceof TradeOutputSlot).findFirst().get();
            sleep(50);
            if(!slotIsItem(mc, tradeOutputSlot.id, outputItem)){
                // clicked the trade but the output is not the target item -> trade is locked or we can't afford it, this villager is done
                break;
            }
            clickSlot(mc,tradeOutputSlot.getStack(), tradeOutputSlot.id, SlotActionType.QUICK_MOVE);
            sleep(50);
            closeScreen(mc);
        }
    }

    private boolean villagerHasWantedProfession(VillagerEntity villager) {
        VillagerProfession profession = villager.getVillagerData().getProfession();
        if (profession == CLERIC) {
            return List.of(Items.EXPERIENCE_BOTTLE).contains(targetItem.get());
        }
        if (profession == MASON){
            return List.of(Items.STONE).contains(targetItem.get());
        }
        return false;
    }

    private boolean playerHasMinTradeAmount(Item inputItem, Item outputItem) {
        List<Slot> inputItemSlots = getPlayerInventorySlotsWithItem(mc, inputItem);
        int inputItemCount = inputItemSlots.stream().mapToInt(slot -> slot.getStack().getCount()).sum();
        if(outputItem == Items.EXPERIENCE_BOTTLE){
            return inputItemCount >= 3;
        }
        if (inputItem == Items.STONE){
            return inputItemCount >= 20;
        }
        return false;
    }
}
