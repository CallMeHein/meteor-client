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

import static meteordevelopment.meteorclient.systems.modules.scripts.ScriptUtils.*;
import static meteordevelopment.meteorclient.systems.modules.scripts._AutoCraftSlabs.autoCraftSlabs;

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

    public AutoCraftStoneBrickSlabs() {
        super(Categories.Script, "auto-craft-stone-brick-slabs", "Automatically craft Stone Brick Slabs");
    }

    private final AtomicBoolean shouldStop = new AtomicBoolean(false);

    @SuppressWarnings("unused")
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
            autoCraftSlabs(mc,Items.STONE_BRICKS, Items.STONE_BRICK_SLAB, stoneBricksStorage.get(), stoneBrickSlabsStorage.get(), emptyStorage.get(), shouldStop, repeat.get());
            setModuleActive(this, false);
        });
    }
}
