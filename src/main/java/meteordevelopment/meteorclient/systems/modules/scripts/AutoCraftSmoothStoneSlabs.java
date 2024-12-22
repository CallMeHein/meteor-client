/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.scripts;

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
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.atomic.AtomicBoolean;

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.playerInventoryHasItem;
import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.setModuleActive;
import static meteordevelopment.meteorclient.systems.modules.scripts._AutoCraftSlabs.autoCraftSlabs;

public class AutoCraftSmoothStoneSlabs extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<BlockPos> emptyStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("empty-storage")
        .description("Coordinates of the Chest containing empty shulkers")
        .build()
    );

    private final Setting<BlockPos> smoothStoneStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("smooth-stone-storage")
        .description("Coordinates of the Chest containing smooth stone shulkers")
        .build()
    );

    private final Setting<BlockPos> smoothStoneSlabsStorage = sgGeneral.add(new BlockPosSetting.Builder()
        .name("smooth-stone-slab-storage")
        .description("Coordinates of the Chest containing smooth stone slab shulkers")
        .build()
    );

    private final Setting<Boolean> repeat = sgGeneral.add(new BoolSetting.Builder()
        .name("repeat")
        .description("repeats until the module is deactivated")
        .build()
    );

    public AutoCraftSmoothStoneSlabs() {
        super(Categories.Script, "auto-craft-smooth-stone-slabs", "Automatically craft Smooth Stone Slabs");
    }

    private AtomicBoolean shouldStop = new AtomicBoolean(false);

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        setModuleActive(this, false);
    }

    @Override
    public void onDeactivate(){
        shouldStop.set(true);
    }

    @Override
    public void onActivate() {
        shouldStop.set(false);
        if (this.isActive() && playerInventoryHasItem(mc, Items.SHULKER_BOX)){
            ChatUtils.error("HighwayAutoCraft: Can't start the script if you're already carrying a shulker box, sorry");
            this.toggle();
            return;
        }
        if (this.isActive() && playerInventoryHasItem(mc, Items.SMOOTH_STONE)){
            ChatUtils.error("HighwayAutoCraft: Can't start the script if you're already carrying smooth stone, sorry");
            this.toggle();
            return;
        }
        if (this.isActive() && playerInventoryHasItem(mc, Items.SMOOTH_STONE_SLAB)){
            ChatUtils.error("HighwayAutoCraft: Can't start the script if you're already carrying smooth stone slabs, sorry");
            this.toggle();
            return;
        }
        MeteorExecutor.execute(() -> {
            autoCraftSlabs(mc,Items.SMOOTH_STONE, Items.SMOOTH_STONE_SLAB, smoothStoneStorage.get(), smoothStoneSlabsStorage.get(), emptyStorage.get(), shouldStop, repeat.get());
            setModuleActive(this, false);
        });
    }
}
