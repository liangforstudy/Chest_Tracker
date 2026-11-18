package io.github.liangforstudy.entitytracker.mixin.client;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a container screen's slots start, to outline the items searched for (ContainerHighlight). */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {
    @Accessor("leftPos")
    int entitytracker$leftPos();

    @Accessor("topPos")
    int entitytracker$topPos();
}
