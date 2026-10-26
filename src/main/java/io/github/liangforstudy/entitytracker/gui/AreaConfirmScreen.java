package io.github.liangforstudy.entitytracker.gui;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.track.Trash;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Confirm list for "mark everything in this area destroyed": blocks and entities in separate
 * groups, everything ticked, tap a row to untick it. Nothing changes until Confirm.
 */
public final class AreaConfirmScreen extends Screen {
    private final List<ContainerRecord> blocks = new ArrayList<>();
    private final List<ContainerRecord> entities = new ArrayList<>();
    private final Set<ContainerRecord> unticked = new HashSet<>();
    private final Trash.Area area;
    private Button confirm;

    public AreaConfirmScreen(List<ContainerRecord> candidates, Trash.Area area) {
        super(Component.translatable("screen.entitytracker.area.title"));
        this.area = area;
        for (ContainerRecord r : candidates) (r instanceof BlockRecord ? blocks : entities).add(r);
    }

    @Override
    protected void init() {
        CheckList list = new CheckList(minecraft, width, height - 84, 40);
        list.add(Component.translatable("screen.entitytracker.area.blocks", blocks.size()), blocks);
        list.add(Component.translatable("screen.entitytracker.area.entities", entities.size()), entities);
        addRenderableWidget(list);

        confirm = addRenderableWidget(Button.builder(Component.empty(), b -> apply())
                .bounds(width / 2 - 154, height - 28, 150, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 + 4, height - 28, 150, 20).build());
        updateConfirm();
    }

    private List<ContainerRecord> ticked() {
        List<ContainerRecord> out = new ArrayList<>();
        for (ContainerRecord r : blocks) if (!unticked.contains(r)) out.add(r);
        for (ContainerRecord r : entities) if (!unticked.contains(r)) out.add(r);
        return out;
    }

    private void updateConfirm() {
        int n = ticked().size();
        confirm.setMessage(Component.translatable("screen.entitytracker.area.confirm", n));
        confirm.active = n > 0;
    }

    private void apply() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank != null) {
            List<ContainerRecord> chosen = ticked();
            Trash.trashArea(bank, chosen);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.translatable("screen.entitytracker.area.done", chosen.size()), false);
            }
        }
        onClose();
    }

    /** Darker than the vanilla backdrop, so grey text stays readable over a bright sky. */
    @Override
    public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        context.drawCenteredString(font, Component.translatable("screen.entitytracker.area.size",
                area.maxX() - area.minX() + 1, area.maxY() - area.minY() + 1, area.maxZ() - area.minZ() + 1,
                area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ()), width / 2, 22, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private final class CheckList extends ObjectSelectionList<CheckList.Entry> {
        CheckList(Minecraft client, int width, int height, int y) {
            super(client, width, height, y, 26);
        }

        void add(Component header, List<ContainerRecord> records) {
            addEntry(new io.github.liangforstudy.entitytracker.gui.AreaConfirmScreen.CheckList.Entry(header, null));
            for (ContainerRecord r : records) addEntry(new io.github.liangforstudy.entitytracker.gui.AreaConfirmScreen.CheckList.Entry(null, r));
        }

        @Override
        public int getRowWidth() {
            return Math.min(360, width - 24);
        }

        final class Entry extends ObjectSelectionList.Entry<io.github.liangforstudy.entitytracker.gui.AreaConfirmScreen.CheckList.Entry> {
            private final Component header;
            private final ContainerRecord record;
            private final ItemStack icon;

            Entry(Component header, ContainerRecord record) {
                this.header = header;
                this.record = record;
                this.icon = record == null ? ItemStack.EMPTY : TrackerScreen.entityIcon(record.type);
            }

            @Override
            public void renderContent(GuiGraphics context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX();
                int y = getContentY();
                if (record == null) {
                    context.drawString(font, header, x + 2, y + 9, 0xFFFFD060);
                    return;
                }
                boolean on = !unticked.contains(record);
                context.drawString(font, on ? "[x]" : "[ ]", x + 2, y + 9, on ? 0xFFFFFFFF : 0xFF888888);
                context.renderItem(icon, x + 22, y + 3);
                context.drawString(font, TrackerScreen.label(record), x + 42, y + 3, on ? 0xFFFFFFFF : 0xFF888888);
                context.drawString(font, TrackerScreen.where(record), x + 42, y + 14,
                        on ? TrackerScreen.statusColor(record.status) : 0xFF666666);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
                if (record == null) return false;
                if (!unticked.remove(record)) unticked.add(record);
                updateConfirm();
                return true;
            }

            @Override
            public Component getNarration() {
                return record == null ? header : Component.literal(TrackerScreen.label(record));
            }
        }
    }
}
