package io.github.liangforstudy.entitytracker.gui;

import com.mojang.serialization.JsonOps;
import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.InventoryRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.RecordStatus;
import io.github.liangforstudy.entitytracker.search.ItemIndex;
import io.github.liangforstudy.entitytracker.track.Trash;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * One container in detail (CLAUDE.md 4.1e): where it is (converted from another dimension when
 * possible), its status and notes, and its last snapshot as an inventory grid. Tap a shulker or
 * bundle to look inside it; items matching the search are outlined. Vanilla widgets, 18px slots.
 */
public final class DetailScreen extends Screen {
    private static final int SLOT = 18;
    private static final int COLUMNS = 9;

    private final ContainerRecord record;
    private final Screen parent;
    private final String query;
    /** Shulkers/bundles opened inside the container, innermost last. */
    private final Deque<Level> path = new ArrayDeque<>();
    private Level top;
    private int selected = -1;
    private int gridX, gridY;
    /** Breadcrumb parts on screen: {x0, x1, depth}; depth 0 = the container itself. */
    private final List<int[]> crumbs = new ArrayList<>();
    private int crumbY;
    /**
     * Everything above the buttons scrolls when it doesn't fit (big GUI scale, long notes, a
     * double chest): mouse wheel, or drag on a touch screen. Content is clipped at viewBottom.
     */
    private int scroll, maxScroll, viewBottom;

    private record Level(String name, List<ItemStack> slots) {}

    public DetailScreen(ContainerRecord record, Screen parent, String query) {
        super(Text.literal(TrackerScreen.label(record)));
        this.record = record;
        this.parent = parent;
        this.query = query;
    }

    @Override
    protected void init() {
        if (top == null) top = new Level(TrackerScreen.label(record), decodeSlots());
        int buttonY = height - 26;
        viewBottom = buttonY - 4;
        boolean actions = !(record instanceof InventoryRecord);
        int count = actions ? 5 : 2;
        int w = Math.min(100, (width - 40) / count);
        int x = width / 2 - (w * count + 4 * (count - 1)) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.entitytracker.detail.back"), b -> back())
                .dimensions(x, buttonY, w, 20).build());
        x += w + 4;
        if (actions) {
            addDrawableChild(ButtonWidget.builder(Text.translatable("screen.entitytracker.detail.copy_xyz"),
                    b -> TrackerScreen.copyCoords(client, record, true)).dimensions(x, buttonY, w, 20).build());
            x += w + 4;
            addDrawableChild(ButtonWidget.builder(Text.translatable("screen.entitytracker.detail.copy_xz"),
                    b -> TrackerScreen.copyCoords(client, record, false))
                    .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("screen.entitytracker.detail.copy_xz_tip")))
                    .dimensions(x, buttonY, w, 20).build());
            x += w + 4;
            addDrawableChild(ButtonWidget.builder(record.isTrashed()
                    ? Text.translatable("screen.entitytracker.restore") : TrackerScreen.markLabel(record), b -> toggleTrash())
                    .dimensions(x, buttonY, w, 20).build());
            x += w + 4;
        }
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> client.setScreen(null))
                .dimensions(x, buttonY, w, 20).build());
    }

    private List<ItemStack> decodeSlots() {
        int size = Math.max(record.slotCount, 1);
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) slots.add(ItemStack.EMPTY);
        if (client == null || client.world == null) return slots;
        var ops = client.world.getRegistryManager().getOps(JsonOps.INSTANCE);
        for (ContainerRecord.SlotItem item : record.items) {
            ItemStack stack = ItemStack.CODEC.parse(ops, item.stack()).result().orElse(ItemStack.EMPTY);
            while (slots.size() <= item.slot()) slots.add(ItemStack.EMPTY);
            slots.set(item.slot(), stack);
        }
        return slots;
    }

    private Level current() {
        return path.isEmpty() ? top : path.peekLast();
    }

    /** The slots of a shulker or bundle, or null if the stack holds nothing to look into. */
    private static List<ItemStack> inside(ItemStack stack) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container != null) {
            DefaultedList<ItemStack> slots = DefaultedList.ofSize(27, ItemStack.EMPTY);
            container.copyTo(slots);
            return slots;
        }
        BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
            List<ItemStack> slots = new ArrayList<>();
            for (ItemStack s : bundle.iterate()) slots.add(s);
            return slots;
        }
        return null;
    }

    private void back() {
        if (!path.isEmpty()) {
            path.removeLast();
            selected = -1;
        } else {
            close();
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    private void toggleTrash() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank == null) return;
        if (record.isTrashed()) Trash.restore(bank, record);
        else Trash.trash(bank, record);
        clearAndInit();
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE && !path.isEmpty()) {
            path.removeLast(); // like a file explorer: Esc goes up one folder first
            selected = -1;
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (maxScroll == 0) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        scroll = Math.clamp(scroll - (int) Math.round(vertical * 16), 0, maxScroll);
        return true;
    }

    /** Dragging the content scrolls it, for touch screens (PojavLauncher) without a wheel. */
    @Override
    public boolean mouseDragged(Click click, double offsetX, double offsetY) {
        if (maxScroll > 0 && click.button() == 0 && click.y() < viewBottom) {
            scroll = Math.clamp(scroll - (int) Math.round(offsetY), 0, maxScroll);
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.y() < viewBottom && click.y() >= crumbY - 2 && click.y() < crumbY + 10) {
            for (int[] c : crumbs) {
                if (click.x() >= c[0] && click.x() < c[1]) {
                    while (path.size() > c[2]) path.removeLast();
                    selected = -1;
                    return true;
                }
            }
        }
        int slot = slotAt(click.x(), click.y());
        if (slot >= 0) {
            ItemStack stack = current().slots().get(slot);
            List<ItemStack> inner = stack.isEmpty() ? null : inside(stack);
            if (inner != null) {
                path.addLast(new Level(stack.getName().getString(), inner)); // step inside
                selected = -1;
            } else {
                selected = slot == selected ? -1 : slot; // tap to pin the tooltip (touch screens)
            }
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    private int slotAt(double mx, double my) {
        if (my >= viewBottom) return -1; // scrolled under the buttons
        List<ItemStack> slots = current().slots();
        for (int i = 0; i < slots.size(); i++) {
            int sx = gridX + (i % COLUMNS) * SLOT;
            int sy = gridY + (i / COLUMNS) * SLOT;
            if (mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT) return i;
        }
        return -1;
    }

    /** Darker than the vanilla backdrop, so grey text stays readable over a bright sky. */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = width / 2;
        int y = 8 - scroll;
        context.enableScissor(0, 0, width, viewBottom);
        context.drawItem(TrackerScreen.entityIcon(record.type), cx - textRenderer.getWidth(title) / 2 - 20, y - 4);
        context.drawCenteredTextWithShadow(textRenderer, title, cx, y, 0xFFFFFFFF);
        y += 14;
        for (String line : infoLines()) {
            context.drawCenteredTextWithShadow(textRenderer, line, cx, y, 0xFFCCCCCC);
            y += 11;
        }
        y = explanation(context, TrackerScreen.statusExplain(record), cx, y);
        List<TrackerScreen.Note> notes = TrackerScreen.notes(record);
        for (TrackerScreen.Note note : notes) {
            context.drawCenteredTextWithShadow(textRenderer, note.text(), cx, y, note.color());
            y += 11;
            y = explanation(context, note.explain(), cx, y);
        }

        // Breadcrumb (each part clickable, like a file explorer path), then the grid.
        y += 4;
        List<String> parts = new ArrayList<>();
        parts.add(Text.translatable("screen.entitytracker.detail.contents").getString());
        for (Level l : path) parts.add(l.name());
        String sep = " > ";
        int total = 0;
        for (int i = 0; i < parts.size(); i++) total += textRenderer.getWidth(parts.get(i)) + (i > 0 ? textRenderer.getWidth(sep) : 0);
        int px = cx - total / 2;
        crumbs.clear();
        crumbY = y;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                context.drawTextWithShadow(textRenderer, sep, px, y, 0xFF888888);
                px += textRenderer.getWidth(sep);
            }
            int w = textRenderer.getWidth(parts.get(i));
            boolean last = i == parts.size() - 1;
            boolean over = !last && mouseX >= px && mouseX < px + w && mouseY >= y - 2 && mouseY < y + 10;
            context.drawTextWithShadow(textRenderer, parts.get(i), px, y, last ? 0xFFFFD060 : over ? 0xFFFFFFFF : 0xFFC8A040);
            if (!last) crumbs.add(new int[]{px, px + w, i});
            px += w;
        }
        y += 12;

        List<ItemStack> slots = current().slots();
        int columns = Math.min(COLUMNS, Math.max(1, slots.size()));
        gridX = cx - columns * SLOT / 2;
        gridY = y;
        int hovered = slotAt(mouseX, mouseY);
        for (int i = 0; i < slots.size(); i++) {
            int sx = gridX + (i % COLUMNS) * SLOT;
            int sy = gridY + (i / COLUMNS) * SLOT;
            ItemStack stack = slots.get(i);
            boolean match = !query.isEmpty() && !stack.isEmpty() && ItemIndex.matchesDeep(stack, query, 0);
            context.fill(sx, sy, sx + SLOT, sy + SLOT, match ? 0xFFE8B04A : 0xFF373737); // border
            context.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, i == hovered || i == selected ? 0xFF9A9A9A : 0xFF8B8B8B);
            if (!stack.isEmpty()) {
                context.drawItem(stack, sx + 1, sy + 1);
                context.drawStackOverlay(textRenderer, stack, sx + 1, sy + 1);
            }
        }
        List<String> roles = path.isEmpty() ? slotRoles() : List.of();
        if (!roles.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, String.join(" \u00B7 ", roles), cx,
                    gridY + ((slots.size() + COLUMNS - 1) / COLUMNS) * SLOT + 3, 0xFFAAAAAA);
        }
        if (!record.hasSnapshot()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.entitytracker.never_opened"),
                    cx, gridY + SLOT + 4, 0xFFAAAAAA);
        }
        context.disableScissor();
        int contentBottom = gridY + ((slots.size() + COLUMNS - 1) / COLUMNS) * SLOT + (roles.isEmpty() ? 0 : 14) + 4;
        maxScroll = Math.max(0, contentBottom + scroll - viewBottom);
        if (scroll > maxScroll) scroll = maxScroll; // content got shorter (stepped out of a shulker)
        if (maxScroll > 0) {
            // Thin scrollbar on the right, so it's clear there's more below.
            int barH = Math.max(12, viewBottom * viewBottom / (viewBottom + maxScroll));
            int barY = (viewBottom - barH) * scroll / maxScroll;
            context.fill(width - 5, 0, width - 2, viewBottom, 0x40FFFFFF);
            context.fill(width - 5, barY, width - 2, barY + barH, 0xC0FFFFFF);
        }
        if (hovered >= 0 && !slots.get(hovered).isEmpty()) {
            context.drawItemTooltip(textRenderer, slots.get(hovered), mouseX, mouseY);
        } else if (selected >= 0 && selected < slots.size() && !slots.get(selected).isEmpty()) {
            // A tapped (pinned) tooltip stays next to its slot instead of following the mouse.
            context.drawItemTooltip(textRenderer, slots.get(selected),
                    gridX + (selected % COLUMNS) * SLOT + SLOT, gridY + (selected / COLUMNS) * SLOT);
        }
    }

    /** What each slot is for, left to right, for containers whose slots differ. */
    private List<String> slotRoles() {
        String type = record.type == null ? "" : record.type;
        if (type.endsWith("furnace") || type.endsWith("smoker")) {
            return List.of(t("input"), t("fuel"), t("output"));
        }
        if (type.endsWith("brewing_stand")) {
            return List.of(t("bottle"), t("bottle"), t("bottle"), t("ingredient"), t("fuel"));
        }
        return List.of();
    }

    private static String t(String role) {
        return Text.translatable("screen.entitytracker.slot." + role).getString();
    }

    /** Draws an explanation wrapped and dimmed under the line it explains; returns the next y. */
    private int explanation(DrawContext context, String text, int cx, int y) {
        if (text == null) return y;
        for (String para : text.split("\n")) {
            if (para.isEmpty()) continue;
            for (var line : textRenderer.wrapLines(Text.literal(para), Math.min(320, width - 40))) {
                context.drawCenteredTextWithShadow(textRenderer, line, cx, y, 0xFFB0B0B0);
                y += 10;
            }
        }
        return y + 2;
    }

    private List<String> infoLines() {
        List<String> lines = new ArrayList<>();
        if (record instanceof InventoryRecord) {
            lines.add(Text.translatable("screen.entitytracker.on_me_where").getString());
            return lines;
        }
        if (record instanceof BlockRecord b && b.global) {
            lines.add(TrackerScreen.where(record));
        } else {
            lines.add(TrackerScreen.place(record, true));
            String status = Text.translatable("screen.entitytracker.status." + TrackerScreen.statusId(record)).getString();
            boolean bad = record.status == RecordStatus.MISSING || record.status == RecordStatus.LIKELY_DESTROYED;
            lines.add((bad ? "⚠ " : "") + status + " · "
                    + Text.translatable("screen.entitytracker.seen_ago", TrackerScreen.age(record.lastSeen)).getString());
        }
        lines.add(record.hasSnapshot()
                ? Text.translatable("screen.entitytracker.detail.opened", TrackerScreen.age(record.lastOpened)).getString()
                : Text.translatable("screen.entitytracker.never_opened").getString());
        if (record instanceof EntityRecord e) {
            lines.add(Text.translatable("screen.entitytracker.detail.home", e.homeChunkX, e.homeChunkZ).getString());
        }
        if (record.isTrashed()) {
            lines.add(Text.translatable("screen.entitytracker.in_trash", TrackerScreen.age(record.trashedAt)).getString());
        }
        return lines;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
