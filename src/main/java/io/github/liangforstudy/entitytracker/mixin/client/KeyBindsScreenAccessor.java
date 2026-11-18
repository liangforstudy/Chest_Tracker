package io.github.liangforstudy.entitytracker.mixin.client;

import net.minecraft.client.gui.screens.options.controls.KeyBindsList;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The Key Binds list, to scroll it to this mod's keys when opened from the mod's settings. */
@Mixin(KeyBindsScreen.class)
public interface KeyBindsScreenAccessor {
    @Accessor("keyBindsList")
    KeyBindsList entitytracker$keyBindsList();
}
