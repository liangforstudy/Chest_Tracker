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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * One container in detail (CLAUDE.md 4.1e): where it is (converted from another dimension when
 * possible), its status and notes, and its last snapshot as an inventory grid. Tap a shulker or
 * bundle to look inside it, or a book to read it; items matching the search are outlined.
 * Vanilla widgets, 18px slots.
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

    /**
     * Search matches by stack, worked out once rather than every frame: matching reads a book's
     * pages, which for a shelf of 100-page books is far too slow to repeat per frame.
     */
    private final Map<ItemStack, Boolean> matches = new IdentityHashMap<>();

    public DetailScreen(ContainerRecord record, Screen parent, String query) {
        super(Component.literal(TrackerScreen.label(record)));
        this.record = record;
        this.parent = parent;
        this.query = query;
    }

    @Override
    protected void init() {
        if (top == null) top = new Level(TrackerScreen.label(record), decodeSlots());
        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.builder(Component.translatable("screen.entitytracker.detail.back"), b -> back()).build());
        if (!(record instanceof InventoryRecord)) {
            buttons.add(Button.builder(Component.translatable("screen.entitytracker.detail.copy_xyz"),
                    b -> TrackerScreen.copyCoords(minecraft, record, true)).build());
            buttons.add(Button.builder(Component.translatable("screen.entitytracker.detail.copy_xz"),
                    b -> TrackerScreen.copyCoords(minecraft, record, false))
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.entitytracker.detail.copy_xz_tip")))
                    .build());
            buttons.add(Button.builder(record.isTrashed()
                    ? Component.translatable("screen.entitytracker.restore") : TrackerScreen.markLabel(record), b -> toggleTrash())
                    .build());
        }
        buttons.add(Button.builder(CommonComponents.GUI_DONE, b -> minecraft.setScreen(null)).build());
        viewBottom = ButtonBar.layout(buttons, font, width, height, 100) - 4;
        for (Button b : buttons) addRenderableWidget(b);
    }

    private List<ItemStack> decodeSlots() {
        int size = Math.max(record.slotCount, 1);
        List<ItemStack> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) slots.add(ItemStack.EMPTY);
        if (minecraft == null || minecraft.level == null) return slots;
        var ops = minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
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
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            NonNullList<ItemStack> slots = NonNullList.withSize(27, ItemStack.EMPTY);
            container.copyInto(slots);
            return slots;
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
            return new ArrayList<>(bundle.itemCopyStream().toList());
        }
        return null;
    }

    private void back() {
        if (!path.isEmpty()) {
            path.removeLast();
            selected = -1;
        } else {
            onClose();
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void toggleTrash() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank == null) return;
        if (record.isTrashed()) Trash.restore(bank, record);
        else Trash.trash(bank, record);
        rebuildWidgets();
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
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
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (maxScroll > 0 && click.button() == 0 && click.y() < viewBottom) {
            scroll = Math.clamp(scroll - (int) Math.round(offsetY), 0, maxScroll);
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
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
            BookViewScreen.BookAccess book = isBook(stack) ? BookViewScreen.BookAccess.fromItem(stack) : null;
            if (inner != null) {
                path.addLast(new Level(stack.getHoverName().getString(), inner)); // step inside
                selected = -1;
            } else if (book != null) {
                minecraft.setScreen(new Reader(book, this));
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
    public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cx = width / 2;
        int y = 8 - scroll;
        context.enableScissor(0, 0, width, viewBottom);
        context.renderItem(TrackerScreen.entityIcon(record.type), cx - font.width(title) / 2 - 20, y - 4);
        context.drawCenteredString(font, title, cx, y, 0xFFFFFFFF);
        y += 14;
        for (String line : infoLines()) {
            context.drawCenteredString(font, line, cx, y, 0xFFCCCCCC);
            y += 11;
        }
        y = explanation(context, TrackerScreen.statusExplain(record), cx, y);
        List<TrackerScreen.Note> notes = TrackerScreen.notes(record);
        for (TrackerScreen.Note note : notes) {
            context.drawCenteredString(font, note.text(), cx, y, note.color());
            y += 11;
            y = explanation(context, note.explain(), cx, y);
        }

        // Breadcrumb (each part clickable, like a file explorer path), then the grid.
        y += 4;
        List<String> parts = new ArrayList<>();
        parts.add(Component.translatable("screen.entitytracker.detail.contents").getString());
        for (Level l : path) parts.add(l.name());
        String sep = " > ";
        int total = 0;
        for (int i = 0; i < parts.size(); i++) total += font.width(parts.get(i)) + (i > 0 ? font.width(sep) : 0);
        int px = cx - total / 2;
        crumbs.clear();
        crumbY = y;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                context.drawString(font, sep, px, y, 0xFF888888);
                px += font.width(sep);
            }
            int w = font.width(parts.get(i));
            boolean last = i == parts.size() - 1;
            boolean over = !last && mouseX >= px && mouseX < px + w && mouseY >= y - 2 && mouseY < y + 10;
            context.drawString(font, parts.get(i), px, y, last ? 0xFFFFD060 : over ? 0xFFFFFFFF : 0xFFC8A040);
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
            boolean match = !query.isEmpty() && !stack.isEmpty()
                    && matches.computeIfAbsent(stack, st -> ItemIndex.matchesDeep(st, query, 0));
            context.fill(sx, sy, sx + SLOT, sy + SLOT, match ? 0xFFE8B04A : 0xFF373737); // border
            context.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, i == hovered || i == selected ? 0xFF9A9A9A : 0xFF8B8B8B);
            if (!stack.isEmpty()) {
                context.renderItem(stack, sx + 1, sy + 1);
                context.renderItemDecorations(font, stack, sx + 1, sy + 1);
            }
        }
        List<String> roles = path.isEmpty() ? slotRoles() : List.of();
        if (roles.isEmpty() && slots.stream().anyMatch(DetailScreen::isBook)) {
            roles = List.of(Component.translatable("screen.entitytracker.detail.read_hint").getString());
        }
        if (!roles.isEmpty()) {
            context.drawCenteredString(font, String.join(" \u00B7 ", roles), cx,
                    gridY + ((slots.size() + COLUMNS - 1) / COLUMNS) * SLOT + 3, 0xFFAAAAAA);
        }
        if (!record.hasSnapshot()) {
            context.drawCenteredString(font, Component.translatable("screen.entitytracker.never_opened"),
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
            context.setTooltipForNextFrame(font, slots.get(hovered), mouseX, mouseY);
        } else if (selected >= 0 && selected < slots.size() && !slots.get(selected).isEmpty()) {
            // A tapped (pinned) tooltip stays next to its slot instead of following the mouse.
            context.setTooltipForNextFrame(font, slots.get(selected),
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
        if (type.endsWith("armor_stand")) {
            return List.of(t("head"), t("chest"), t("legs"), t("feet"), t("main_hand"), t("off_hand"));
        }
        return List.of();
    }

    private static String t(String role) {
        return Component.translatable("screen.entitytracker.slot." + role).getString();
    }

    /** Draws an explanation wrapped and dimmed under the line it explains; returns the next y. */
    private int explanation(GuiGraphics context, String text, int cx, int y) {
        if (text == null) return y;
        for (String para : text.split("\n")) {
            if (para.isEmpty()) continue;
            for (var line : font.split(Component.literal(para), Math.min(320, width - 40))) {
                context.drawCenteredString(font, line, cx, y, 0xFFB0B0B0);
                y += 10;
            }
        }
        return y + 2;
    }

    private List<String> infoLines() {
        List<String> lines = new ArrayList<>();
        if (record instanceof InventoryRecord) {
            lines.add(Component.translatable("screen.entitytracker.on_me_where").getString());
            return lines;
        }
        if (record instanceof BlockRecord b && b.global) {
            lines.add(TrackerScreen.where(record));
        } else {
            lines.add(TrackerScreen.place(record, true));
            String status = Component.translatable("screen.entitytracker.status." + TrackerScreen.statusId(record)).getString();
            boolean bad = record.status == RecordStatus.MISSING || record.status == RecordStatus.LIKELY_DESTROYED;
            lines.add((bad ? "⚠ " : "") + status + " · "
                    + Component.translatable("screen.entitytracker.seen_ago", TrackerScreen.age(record.lastSeen)).getString());
        }
        lines.add(record.hasSnapshot()
                ? Component.translatable("screen.entitytracker.detail.opened", TrackerScreen.age(record.lastOpened)).getString()
                : Component.translatable("screen.entitytracker.never_opened").getString());
        if (record instanceof EntityRecord e) {
            lines.add(Component.translatable("screen.entitytracker.detail.home", e.homeChunkX, e.homeChunkZ).getString());
        }
        if (record.isTrashed()) {
            lines.add(Component.translatable("screen.entitytracker.in_trash", TrackerScreen.age(record.trashedAt)).getString());
        }
        return lines;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static boolean isBook(ItemStack stack) {
        return stack.has(DataComponents.WRITTEN_BOOK_CONTENT) || stack.has(DataComponents.WRITABLE_BOOK_CONTENT);
    }

    /** The vanilla book view, read-only, going back to the detail view when closed. */
    private static final class Reader extends BookViewScreen {
        private final Screen parent;

        Reader(BookViewScreen.BookAccess contents, Screen parent) {
            super(contents);
            this.parent = parent;
        }

        @Override
        public void onClose() {
            minecraft.setScreen(parent);
        }
    }
}
