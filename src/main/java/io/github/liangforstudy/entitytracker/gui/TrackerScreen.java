package io.github.liangforstudy.entitytracker.gui;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.Settings;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.InventoryRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.RecordStatus;
import io.github.liangforstudy.entitytracker.search.Fuzzy;
import io.github.liangforstudy.entitytracker.search.ItemIndex;
import io.github.liangforstudy.entitytracker.search.ItemQuery;
import io.github.liangforstudy.entitytracker.search.Tally;
import io.github.liangforstudy.entitytracker.search.TallyReport;
import io.github.liangforstudy.entitytracker.trace.Tracers;
import io.github.liangforstudy.entitytracker.track.Trash;
import org.lwjgl.glfw.GLFW;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Search screen over everything remembered. Empty search lists containers; typing lists matching
 * items (nested ones included). Vanilla widgets only, rows tall enough to tap (PojavLauncher).
 */
public final class TrackerScreen extends Screen {
    private static final int ROW_HEIGHT = 46; // four lines: what / which container / where+status / notes
    private static final int MAX_ROWS = 500;

    private EditBox search;
    private @Nullable String pendingTooltip;
    /** Keys of selected containers (Ctrl/Shift-click, or taps in Select mode). */
    private final Set<String> selection = new LinkedHashSet<>();
    private boolean selectMode;
    private int lastToggled = -1;
    // Normal bar / bulk bar buttons; shown and hidden in place so the list keeps its scroll.
    private Button viewButton, destroyedButton, selectButton, doneButton;
    /** In the trash: one button per kind, the open one greyed out. */
    private final List<Button> kindButtons = new ArrayList<>();
    private Button bulkPrimary, bulkDelete, selectAllButton, cancelButton;
    /** "Trace n" for the selection; "Tracers (n)" while anything is traced. */
    private Button bulkTrace, tracersButton;
    private int tooltipX, tooltipY;
    private ResultList list;
    private boolean showDestroyed = true;
    /** The results shown are close matches, not exact ones. */
    private boolean fuzzy;
    /** How many of the searched item the memory holds, when the search is an item ("totems"). */
    private @Nullable Component tallyHint;
    /** Containers the current search hasn't looked at yet. */
    private int indexing;
    /** The search being worked through, a few milliseconds per frame; null when finished. */
    private @Nullable SearchJob job;
    private boolean waitingForBank;
    /** false = remembered containers, true = the trash. */
    private boolean trashView;
    /** Which part of the trash is open. Kept apart so broken blocks don't bury entities. */
    private TrashKind trashKind = TrashKind.ENTITIES;
    /**
     * The grid: every item the memory holds at a glance, one slot per item type with its total
     * (shulker and bundle contents counted, see {@link Tally}). Tapping one lists where it is.
     */
    private boolean gridView;
    private Button gridButton;
    private List<GridCell> grid = List.of();
    private int gridScroll, gridMaxScroll, gridBottom, gridRevision = -1;
    /** The list's place to go back to once the rebuilt list is long enough (-1: none). */
    private double pendingScroll = -1;
    private @Nullable Row pendingSelected;
    /** The next rebuild shows a different list (the trash, another part of it): start at the top. */
    private boolean freshList;
    private int gridPressed = -1;
    private boolean gridDragged;
    /** The hovered cell's tooltip, worked out once per cell rather than every frame. */
    private int tooltipCell = -1;
    private List<net.minecraft.util.FormattedCharSequence> cellTooltip = List.of();
    private static final int CELL = 20;

    /** {@code shown}: the total, plus what may be gone while "Destroyed: shown". */
    private record GridCell(String id, ItemStack stack, long shown) {}

    /** The trash's three views: storage entities, villagers and traders, and blocks. */
    public enum TrashKind {
        ENTITIES("screen.entitytracker.trash.entities"),
        TRADES("screen.entitytracker.trash.trades"),
        BLOCKS("screen.entitytracker.trash.blocks");

        final String key;

        TrashKind(String key) {
            this.key = key;
        }

        Stream<? extends ContainerRecord> trashed(MemoryBank bank) {
            Stream<? extends ContainerRecord> all = this == TRADES ? bank.traders()
                    : bank.containers().filter(r -> (r instanceof BlockRecord) == (this == BLOCKS));
            return all.filter(ContainerRecord::isTrashed);
        }
    }
    /** The open row menu (right-click or the row's "⋮"), empty when closed. */
    private final List<Button> menu = new ArrayList<>();
    private int menuX, menuY, menuW, menuH;

    public TrackerScreen() {
        super(Component.translatable("screen.entitytracker.title"));
    }

    /** Opens straight into one part of the trash (the Trades screen's Trash button). */
    public static TrackerScreen trash(TrashKind kind) {
        TrackerScreen screen = new TrackerScreen();
        screen.trashView = true;
        screen.trashKind = kind;
        return screen;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(300, width - 32);
        String previous = search != null ? search.getValue() : "";
        search = new EditBox(font, (width - fieldWidth) / 2, 24, fieldWidth, 20,
                Component.translatable("screen.entitytracker.search"));
        search.setHint(Component.translatable("screen.entitytracker.search"));
        search.setValue(previous);
        search.setResponder(q -> {
            // A new search starts at its top.
            pendingScroll = -1;
            pendingSelected = null;
            refresh();
        });
        addRenderableWidget(search);

        // Coming back from a detail view (or a resize) rebuilds the list: keep its place and the
        // container that was picked, so checking 400 chests one by one doesn't start over each time.
        if (list != null && !freshList) {
            pendingScroll = list.scrollAmount();
            ResultList.Entry picked = list.getSelected();
            pendingSelected = picked == null ? null : picked.row;
        }
        freshList = false;
        list = new ResultList(minecraft, width, height - 30 - LIST_Y, LIST_Y);
        addRenderableWidget(list);
        for (Button tab : ScreenTabs.create(minecraft, width, false)) addRenderableWidget(tab);

        // Bottom bar: views and filters, or bulk actions while something is selected.
        menu.clear();
        // "Trash (n)" in the list, "<- Back to list" in the trash, which has a button per kind.
        viewButton = addRenderableWidget(Button.builder(trashLabel(), b -> {
            trashView = !trashView;
            clearSelection();
            freshList = true;
            rebuildWidgets();
        }).build());
        kindButtons.clear();
        for (TrashKind kind : TrashKind.values()) {
            Button button = addRenderableWidget(Button.builder(kindLabel(kind), b -> {
                trashKind = kind;
                clearSelection();
                freshList = true;
                rebuildWidgets();
            }).build());
            button.active = kind != trashKind;
            kindButtons.add(button);
        }
        gridButton = addRenderableWidget(Button.builder(gridLabel(), b -> {
            gridView = !gridView;
            gridScroll = 0;
            b.setMessage(gridLabel());
            refresh();
        }).build());
        destroyedButton = addRenderableWidget(Button.builder(destroyedLabel(), b -> {
            showDestroyed = !showDestroyed;
            b.setMessage(destroyedLabel());
            updateBar();
            refresh();
        }).build());
        selectButton = addRenderableWidget(Button.builder(Component.translatable("screen.entitytracker.select"), b -> {
            selectMode = true;
            updateBar();
        }).build());
        doneButton = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).build());
        bulkPrimary = addRenderableWidget(Button.builder(Component.empty(), b -> bulkPrimary()).build());
        bulkDelete = addRenderableWidget(Button.builder(Component.empty(), b -> bulkDelete()).build());
        bulkTrace = addRenderableWidget(Button.builder(Component.empty(), b -> {
            List<ContainerRecord> records = selectedRecords();
            if (records.isEmpty()) return;
            clearSelection();
            trace(records);
        }).build());
        tracersButton = addRenderableWidget(Button.builder(Component.empty(), this::openTracersMenu).build());
        selectAllButton = addRenderableWidget(Button.builder(Component.translatable("screen.entitytracker.select_all"), b -> {
            for (ResultList.Entry e : list.children()) {
                if (!(e.row.record() instanceof InventoryRecord)) selection.add(e.row.record().key());
            }
            selectMode = true;
            updateBar();
        }).build());
        cancelButton = addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> {
            clearSelection();
            updateBar();
        }).build());
        updateBar();

        setInitialFocus(search);
        refresh();
    }

    /** "Trash (3)" in the list, "<- Back to list" in the trash: a place to go, not a mode. */
    private Component trashLabel() {
        if (trashView) return Component.translatable("screen.entitytracker.trash_back");
        return Component.translatable("screen.entitytracker.trash_open", totalTrashed());
    }

    /** Everything in the trash, all three kinds. */
    public static long totalTrashed() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank == null) return 0;
        long n = 0;
        for (TrashKind kind : TrashKind.values()) n += kind.trashed(bank).count();
        return n;
    }

    /** "Entities (3)", "Trades (1)", "Blocks (9)". */
    private static Component kindLabel(TrashKind kind) {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        return Component.translatable(kind.key, bank == null ? 0 : kind.trashed(bank).count());
    }

    /** Names the view the button goes to: "Grid" in the list, "List" in the grid. */
    private Component gridLabel() {
        return Component.translatable(gridView ? "screen.entitytracker.grid.to_list" : "screen.entitytracker.grid.to_grid");
    }

    private boolean showingGrid() {
        return gridView && !trashView;
    }

    private Component destroyedLabel() {
        return Component.translatable(showDestroyed ? "screen.entitytracker.destroyed_shown"
                : "screen.entitytracker.destroyed_hidden");
    }

    /** Lays out whichever bottom bar applies: views and filters, or bulk actions. */
    private void updateBar() {
        boolean bulk = selectMode || !selection.isEmpty();
        List<Button> shown = new ArrayList<>();
        for (Button b : List.of(viewButton, gridButton, destroyedButton, selectButton, doneButton, bulkPrimary, bulkDelete,
                bulkTrace, tracersButton, selectAllButton, cancelButton)) {
            b.visible = false;
        }
        for (Button b : kindButtons) b.visible = false;
        if (bulk) {
            int n = selection.size();
            bulkPrimary.setMessage(Component.translatable(trashView ? "screen.entitytracker.bulk.restore"
                    : "screen.entitytracker.bulk.mark", n));
            bulkPrimary.active = n > 0;
            shown.add(bulkPrimary);
            if (trashView) {
                bulkDelete.setMessage(Component.translatable("screen.entitytracker.bulk.delete", n));
                bulkDelete.active = n > 0;
                shown.add(bulkDelete);
            } else {
                bulkTrace.setMessage(Component.translatable("screen.entitytracker.bulk.trace", n));
                bulkTrace.active = n > 0;
                shown.add(bulkTrace);
            }
            shown.add(selectAllButton);
            shown.add(cancelButton);
        } else {
            shown.add(viewButton);
            if (trashView) {
                shown.addAll(kindButtons);
            } else {
                shown.add(gridButton);
                shown.add(destroyedButton);
            }
            if (!showingGrid()) shown.add(selectButton); // selecting is for containers, in the list
            if (!trashView && Tracers.count() > 0) {
                tracersButton.setMessage(Component.translatable("screen.entitytracker.tracers", Tracers.count()));
                shown.add(tracersButton);
            }
            shown.add(doneButton);
        }
        for (Button b : shown) b.visible = true;
        // The list ends above the buttons, which take two rows on a narrow screen
        int top = ButtonBar.layout(shown, font, width, height, 120);
        list.updateSizeAndPosition(width, top - 4 - LIST_Y, LIST_Y);
        list.visible = !showingGrid();
        gridBottom = top - 4;
    }

    private void clearSelection() {
        selection.clear();
        selectMode = false;
        lastToggled = -1;
    }

    private List<ContainerRecord> selectedRecords() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        List<ContainerRecord> out = new ArrayList<>();
        if (bank == null) return out;
        for (String key : selection) {
            ContainerRecord r = bank.getByKey(key);
            if (r != null) out.add(r);
        }
        return out;
    }

    /** Mark destroyed (one undoable action) or, in the trash view, restore the selection. */
    private void bulkPrimary() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        List<ContainerRecord> records = selectedRecords();
        if (bank == null || records.isEmpty()) return;
        if (trashView) {
            for (ContainerRecord r : records) Trash.restore(bank, r);
        } else {
            Trash.trashArea(bank, records);
            Messages.chat(Component.translatable("screen.entitytracker.area.done", records.size()));
        }
        clearSelection();
        refresh();
        updateBar();
    }

    private void bulkDelete() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        List<ContainerRecord> records = selectedRecords();
        if (bank == null || records.isEmpty()) return;
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                for (ContainerRecord r : records) Trash.deletePermanently(bank, r);
                clearSelection();
            }
            minecraft.setScreen(this);
        }, Component.translatable("screen.entitytracker.bulk.delete_confirm", records.size()),
                Component.translatable("screen.entitytracker.bulk.delete_body")));
    }

    /** Opens the action menu for a container at the given screen position. */
    private void openMenu(ContainerRecord record, double x, double y) {
        closeMenu();
        if (record instanceof InventoryRecord) return; // nothing to mark destroyed
        List<Button.Builder> items = new ArrayList<>();
        if (trashView) {
            items.add(Button.builder(Component.translatable("screen.entitytracker.restore"), b -> act(() -> {
                MemoryBank bank = EntityTrackerClient.banks().bank();
                if (bank != null) Trash.restore(bank, record);
            })));
            items.add(Button.builder(Component.translatable("screen.entitytracker.delete"), b -> {
                closeMenu();
                confirmDelete(record);
            }));
        } else {
            boolean hasExplanations = statusExplain(record) != null || notes(record).stream().anyMatch(n -> n.explain() != null);
            if (hasExplanations) {
                items.add(Button.builder(Component.translatable("screen.entitytracker.show_warnings"), b -> {
                    closeMenu();
                    minecraft.setScreen(new DetailScreen(record, this, ""));
                }));
            }
            if (Tracers.isTraced(record)) {
                items.add(Button.builder(Component.translatable("screen.entitytracker.untrace"),
                        b -> act(() -> Tracers.remove(record))));
            } else {
                items.add(Button.builder(Component.translatable("screen.entitytracker.trace"), b -> {
                    closeMenu();
                    trace(List.of(record));
                }));
            }
            items.add(Button.builder(markLabel(record), b -> act(() -> {
                MemoryBank bank = EntityTrackerClient.banks().bank();
                if (bank != null) Trash.trash(bank, record);
            })));
        }
        showMenu(items, x, y);
    }

    /** Traces these containers and goes back to the game, where the lines are. */
    void trace(List<ContainerRecord> records) {
        Tracers.add(records);
        Messages.chat(Component.translatable("screen.entitytracker.tracers.started",
                records.size() == 1 ? label(records.get(0)) : String.valueOf(records.size())));
        minecraft.setScreen(null);
    }

    /** The tracers' own menu, from the "Tracers (n)" button: hide or show, clear. */
    private void openTracersMenu(Button from) {
        closeMenu();
        List<Button.Builder> items = new ArrayList<>();
        items.add(Button.builder(Component.translatable(Tracers.hidden() ? "screen.entitytracker.tracers.show"
                : "screen.entitytracker.tracers.hide"), b -> act(Tracers::toggle)));
        items.add(Button.builder(Component.translatable("screen.entitytracker.tracers.clear"), b -> act(Tracers::clear)));
        // Above the button, since it sits in the bottom bar.
        showMenu(items, from.getX(), from.getY() - items.size() * 20 - 2);
    }

    private void showMenu(List<Button.Builder> items, double x, double y) {
        menuW = 120;
        menuH = items.size() * 20;
        menuX = (int) Math.min(x, width - menuW - 4);
        menuY = (int) Math.min(y, height - menuH - 4);
        for (int i = 0; i < items.size(); i++) {
            menu.add(addRenderableWidget(items.get(i).bounds(menuX, menuY + i * 20, menuW, 20).build()));
        }
    }

    private void act(Runnable action) {
        closeMenu();
        action.run();
        refresh();
    }

    private void closeMenu() {
        for (Button b : menu) removeWidget(b);
        menu.clear();
    }

    private boolean insideMenu(double x, double y) {
        return x >= menuX && x < menuX + menuW && y >= menuY && y < menuY + menuH;
    }

    /** While a search is still waiting for containers to be indexed, try again every tick. */
    @Override
    public void tick() {
        super.tick();
        // The bank just finished loading while the screen was open. (A search in progress
        // carries on by itself in render, a few milliseconds per frame.)
        if (waitingForBank && EntityTrackerClient.banks().bank() != null) refreshKeepingScroll();
        waitingForBank = EntityTrackerClient.banks().bank() == null;
        // The grid follows the memory while it's open (a container opened, an entity moved).
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (showingGrid() && bank != null && bank.revision() != gridRevision) refreshGrid();
    }

    private void refreshKeepingScroll() {
        double scroll = list.scrollAmount();
        refresh();
        list.setScrollAmount(scroll);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        input = CloseKey.asEscape(this, minecraft, input);
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            // Esc closes an open menu, then clears the search, and only then closes the screen.
            if (!menu.isEmpty()) {
                closeMenu();
                return true;
            }
            if (selectMode || !selection.isEmpty()) {
                clearSelection();
                updateBar();
                return true;
            }
            if (!search.getValue().isEmpty()) {
                search.setValue("");
                return true;
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!menu.isEmpty()) {
            if (insideMenu(click.x(), click.y())) {
                // Only the menu's own buttons: the screen's other widgets come first in its
                // children, so the list or a button under the menu would take the click.
                for (Button b : List.copyOf(menu)) {
                    if (b.isMouseOver(click.x(), click.y())) return b.mouseClicked(click, doubled);
                }
                return true;
            }
            closeMenu(); // a click anywhere else just closes the menu
            return true;
        }
        if (showingGrid() && click.button() == 0 && gridCellAt(click.x(), click.y()) >= 0) {
            // Acted on at release, so a drag (scrolling on a touch screen) doesn't open anything.
            gridPressed = gridCellAt(click.x(), click.y());
            gridDragged = false;
            return true;
        }
        if (click.button() == 1 && !showingGrid()) {
            ResultList.Entry entry = list.entryAt(click.x(), click.y());
            if (entry != null) {
                list.setSelected(entry);
                openMenu(entry.row.record(), click.x(), click.y());
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    /** A tap on a grid cell (pressed and released on it, no drag) lists where that item is. */
    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (gridPressed >= 0) {
            int pressed = gridPressed;
            gridPressed = -1;
            if (!gridDragged && showingGrid() && gridCellAt(click.x(), click.y()) == pressed) {
                String name = grid.get(pressed).stack().getHoverName().getString();
                gridView = false;
                gridButton.setMessage(gridLabel());
                search.setValue(name); // searches the list for it
                updateBar();
            }
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (showingGrid()) {
            gridScroll = Math.clamp(gridScroll - (int) Math.round(vertical * CELL), 0, gridMaxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    /** Dragging the grid scrolls it, for touch screens (PojavLauncher) without a wheel. */
    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (showingGrid() && click.button() == 0 && click.y() >= LIST_Y && click.y() < gridBottom) {
            gridDragged |= Math.abs(offsetY) > 0.5;
            gridScroll = Math.clamp(gridScroll - (int) Math.round(offsetY), 0, gridMaxScroll);
            return true;
        }
        return super.mouseDragged(click, offsetX, offsetY);
    }

    private void confirmDelete(ContainerRecord record) {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank == null || record == null) return;
        minecraft.setScreen(new ConfirmScreen(yes -> {
            if (yes) Trash.deletePermanently(bank, record);
            minecraft.setScreen(this);
        }, Component.translatable("screen.entitytracker.delete_confirm"),
                Component.translatable("screen.entitytracker.delete_confirm_body", label(record))));
    }

    /**
     * Starts a new search and shows whatever it finds within this frame's budget; the rest
     * follows over the next frames (see {@link SearchJob}), so the nearest results appear at once
     * and typing never waits for a big base to be searched to the end.
     */
    private void refresh() {
        if (viewButton != null) {
            viewButton.setMessage(trashLabel());
            for (TrashKind kind : TrashKind.values()) kindButtons.get(kind.ordinal()).setMessage(kindLabel(kind));
            updateBar(); // "Trash (n)" grows with n
        }
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.startsWith("@")) query = query.substring(1); // @head:Notch until full filters (4.6)
        fuzzy = false;
        MemoryBank bank = EntityTrackerClient.banks().bank();
        tallyHint = bank == null || trashView || query.isEmpty() ? null : TallyReport.hint(query, bank);
        if (showingGrid()) {
            // The grid needs no search through containers: it comes from the tally.
            job = null;
            indexing = 0;
            list.setRows(List.of());
            refreshGrid();
            return;
        }
        job = new SearchJob(query, records(), namedItems(query));
        list.setRows(List.of());
        continueSearch();
    }

    /**
     * The items a search names by alias or exact name ("totems", "gapples"), which count as exact
     * matches although their text doesn't contain the words typed.
     */
    private static Set<Item> namedItems(String query) {
        if (query.isEmpty()) return Set.of();
        ItemQuery.Result result = ItemQuery.resolve(query);
        if (result.kind() != ItemQuery.Kind.ALIAS && result.kind() != ItemQuery.Kind.EXACT) return Set.of();
        Set<Item> items = new HashSet<>();
        for (String id : result.ids()) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed != null) BuiltInRegistries.ITEM.getOptional(parsed).ifPresent(items::add);
        }
        return items;
    }

    /**
     * One cell per item type the memory holds, most first, narrowed to the search: item names, and
     * players' words like "gapples" or "totems" (see {@link ItemQuery}).
     */
    private void refreshGrid() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        tooltipCell = -1;
        if (bank == null) {
            grid = List.of();
            gridRevision = -1;
            return;
        }
        String query = search.getValue().trim().toLowerCase(Locale.ROOT);
        Set<Item> named = namedItems(query);
        List<GridCell> cells = new ArrayList<>();
        Tally.of(bank).items.forEach((id, count) -> {
            long shown = count.total + (showDestroyed ? count.maybeGone : 0);
            if (shown <= 0) return;
            Identifier parsed = Identifier.tryParse(id);
            Item item = parsed == null ? null : BuiltInRegistries.ITEM.getOptional(parsed).orElse(null);
            if (item == null || item == Items.AIR) return;
            ItemStack stack = new ItemStack(item);
            if (!query.isEmpty() && !named.contains(item)
                    && !stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) return;
            cells.add(new GridCell(id, stack, shown));
        });
        cells.sort(Comparator.comparingLong(GridCell::shown).reversed()
                .thenComparing(c -> c.stack().getHoverName().getString()));
        grid = cells;
        gridRevision = bank.revision();
    }

    private int gridColumns() {
        return Math.max(1, Math.min(24, (width - 24) / CELL));
    }

    private int gridCellAt(double mx, double my) {
        if (my < LIST_Y || my >= gridBottom) return -1;
        int cols = gridColumns();
        int left = (width - cols * CELL) / 2;
        if (mx < left || mx >= left + cols * CELL) return -1;
        int i = (int) ((my - LIST_Y + gridScroll) / CELL) * cols + (int) ((mx - left) / CELL);
        return i < grid.size() ? i : -1;
    }

    /** 3456 stays as it is; bigger numbers are shortened so they fit under the slot: 12k, 1.2M. */
    private static String shortCount(long n) {
        if (n < 10_000) return Long.toString(n);
        if (n < 1_000_000) return n / 1000 + "k";
        return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
    }

    private void renderGrid(GuiGraphics context, int mouseX, int mouseY) {
        int cols = gridColumns();
        int left = (width - cols * CELL) / 2;
        int rows = (grid.size() + cols - 1) / cols;
        gridMaxScroll = Math.max(0, rows * CELL - (gridBottom - LIST_Y));
        gridScroll = Math.clamp(gridScroll, 0, gridMaxScroll);
        int hovered = menu.isEmpty() ? gridCellAt(mouseX, mouseY) : -1;
        context.enableScissor(0, LIST_Y, width, gridBottom);
        for (int i = 0; i < grid.size(); i++) {
            int x = left + (i % cols) * CELL + 1;
            int y = LIST_Y + (i / cols) * CELL - gridScroll + 1;
            if (y + CELL < LIST_Y || y > gridBottom) continue;
            GridCell cell = grid.get(i);
            context.fill(x, y, x + 18, y + 18, i == hovered ? 0x60FFFFFF : 0x28FFFFFF);
            context.renderItem(cell.stack(), x + 1, y + 1);
            context.renderItemDecorations(font, cell.stack(), x + 1, y + 1, shortCount(cell.shown()));
        }
        context.disableScissor();
        if (gridMaxScroll > 0) {
            int view = gridBottom - LIST_Y;
            int barH = Math.max(12, view * view / (view + gridMaxScroll));
            int barY = LIST_Y + (view - barH) * gridScroll / gridMaxScroll;
            context.fill(width - 5, LIST_Y, width - 2, gridBottom, 0x40FFFFFF);
            context.fill(width - 5, barY, width - 2, barY + barH, 0xC0FFFFFF);
        }
        if (hovered < 0) return;
        if (hovered != tooltipCell) {
            MemoryBank bank = EntityTrackerClient.banks().bank();
            List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
            GridCell cell = grid.get(hovered);
            lines.add(cell.stack().getHoverName().getVisualOrderText());
            if (bank != null) lines.addAll(font.split(TallyReport.line(cell.id(), bank), 220));
            lines.add(Component.translatable("screen.entitytracker.grid.tap").getVisualOrderText());
            cellTooltip = lines;
            tooltipCell = hovered;
        }
        context.setTooltipForNextFrame(cellTooltip, mouseX, mouseY);
    }

    /** Runs the current search for one frame's budget and puts what it found in the list. */
    private void continueSearch() {
        if (job == null) return;
        long start = System.nanoTime();
        job.run(start + Settings.searchBudgetNanos);
        if (!job.exact.isEmpty()) {
            if (fuzzy) {
                // The first exact match: close matches shown so far make way for it.
                list.setRows(job.exact);
                fuzzy = false;
            } else if (job.exact.size() > list.children().size()) {
                list.addRows(job.exact.subList(list.children().size(), job.exact.size()));
            }
        } else if (job.close.size() > list.children().size()) {
            list.addRows(job.close.subList(list.children().size(), job.close.size()));
            fuzzy = true;
        }
        indexing = job.done ? 0 : job.remaining();
        restorePlace(job.done);
        if (job.done) job = null;
        long ms = (System.nanoTime() - start) / 1_000_000;
        if (ms > 16) EntityTrackerClient.LOGGER.warn("Tracker search step took {} ms (longer than a frame)", ms);
    }

    /**
     * After the list was rebuilt: the picked row is selected again as soon as it's back, and the
     * scroll put back once the search has refilled the list far enough (or has finished).
     */
    private void restorePlace(boolean searchDone) {
        if (pendingSelected != null) {
            for (ResultList.Entry e : list.children()) {
                // The same container, and the same item in it (rows are rebuilt, so not by identity).
                if (e.row.record() == pendingSelected.record() && (pendingSelected.item() == null
                        || e.row.item() != null && e.row.item().stack() == pendingSelected.item().stack()
                        && e.row.item().path().equals(pendingSelected.item().path()))) {
                    list.setSelected(e);
                    pendingSelected = null;
                    break;
                }
            }
        }
        if (pendingScroll >= 0 && (searchDone || list.maxScrollAmount() >= pendingScroll)) {
            list.setScrollAmount(pendingScroll);
            pendingScroll = -1;
        }
        if (searchDone) pendingSelected = null;
    }

    /** "close matches" next to the search field, when the results are fuzzy ones. */
    static void drawFuzzyNote(GuiGraphics context, net.minecraft.client.gui.Font tr, EditBox field) {
        String note = Component.translatable("screen.entitytracker.close_matches").getString();
        context.drawString(tr, note, field.getX() + field.getWidth() - tr.width(note) - 6,
                field.getY() + 6, 0xFFE8B04A);
    }

    /** What the list is searching through: "On me" first, then containers nearest first. */
    private List<ContainerRecord> records() {
        List<ContainerRecord> records = new ArrayList<>();
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank == null) return records;
        // Your own inventory first, so "it was on me all along" is the first thing you see.
        InventoryRecord onMe = trashView ? null : InventoryRecord.current();
        if (onMe != null) records.add(onMe);
        Stream<? extends ContainerRecord> shown = trashView ? trashKind.trashed(bank)
                : bank.containers().filter(r -> !r.isTrashed() && (showDestroyed || r.status != RecordStatus.LIKELY_DESTROYED));
        records.addAll(shown
                .sorted(Comparator.comparingDouble(TrackerScreen::sortDistance))
                .toList());
        return records;
    }

    /**
     * One search, worked through container by container (nearest first) and item by item until
     * the time budget runs out, then carried on next frame. Results come in order, so they're
     * only ever added at the bottom of the list. Until an exact match turns up, close matches
     * ("diamnd" -> diamond) are collected on the way, so a typo shows results straight away
     * instead of after a second pass over everything.
     */
    private static final class SearchJob {
        final String query;
        final String[] queryWords;
        final Set<Item> namedItems;
        final List<ContainerRecord> records;
        final List<Row> exact = new ArrayList<>();
        final List<Row> close = new ArrayList<>();
        int next;
        /** Where to carry on inside records.get(next) (a big container can take several frames). */
        int nextItem;
        boolean done;

        SearchJob(String query, List<ContainerRecord> records, Set<Item> namedItems) {
            this.query = query;
            this.namedItems = namedItems;
            this.queryWords = Fuzzy.words(query);
            this.records = records;
        }

        /**
         * Book pages already searched, by identity: copies of one book share their text, so a
         * wall of the same 100-page book is read once per search, not once per copy.
         */
        final java.util.IdentityHashMap<String, Boolean> books = new java.util.IdentityHashMap<>();

        boolean bookMatches(String text) {
            if (text.isEmpty()) return false;
            return books.computeIfAbsent(text, t -> t.contains(query));
        }

        /** Containers not looked at yet. */
        int remaining() {
            return records.size() - next;
        }

        void run(long deadline) {
            ItemIndex index = EntityTrackerClient.index();
            while (next < records.size() && exact.size() < MAX_ROWS) {
                if (System.nanoTime() >= deadline) return;
                ContainerRecord record = records.get(next);
                if (query.isEmpty()) {
                    // Top-level stacks from the record itself: opening the screen indexes nothing.
                    exact.add(new Row(record, null, record.items.size()));
                } else {
                    // Not indexed yet: index it slot by slot within the budget; carry on next frame.
                    List<ItemIndex.IndexedItem> items = record instanceof InventoryRecord
                            ? index.items(record) : index.itemsWithin(record, deadline);
                    if (items == null) return;
                    for (; nextItem < items.size(); nextItem++) {
                        if ((nextItem & 31) == 31 && System.nanoTime() >= deadline) return;
                        ItemIndex.IndexedItem item = items.get(nextItem);
                        if (item.searchText().contains(query) || bookMatches(item.bookText())
                                || namedItems.contains(item.stack().getItem())) {
                            exact.add(new Row(record, item, 0));
                        } else if (exact.isEmpty() && close.size() < MAX_ROWS
                                && Fuzzy.matches(item.searchText(), item.words(), queryWords)) {
                            close.add(new Row(record, item, 0));
                        }
                    }
                }
                next++;
                nextItem = 0;
            }
            done = true;
        }
    }

    /** Same dimension first, nearest first; other dimensions after. */
    public static double sortDistance(ContainerRecord r) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return 0;
        if (r.dimension.equals(client.level.dimension().identifier().toString())) return client.player.distanceToSqr(r.x, r.y, r.z);
        // Another dimension: by its converted position, so a nether stash "under" you ranks high.
        double converted = Dimensions.convertedDistance(r);
        return Double.isNaN(converted) ? 1e15 : converted * converted;
    }

    private void showTooltip(@Nullable String text, int x, int y) {
        if (text == null || !menu.isEmpty()) return;
        pendingTooltip = text;
        tooltipX = x;
        tooltipY = y;
    }

    /** Wrapped explanation tooltip, readable width. */
    static void drawExplanation(GuiGraphics context, net.minecraft.client.gui.Font tr, String text, int x, int y) {
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        for (String para : text.split("\n")) {
            if (para.isEmpty()) lines.add(net.minecraft.util.FormattedCharSequence.EMPTY);
            else lines.addAll(tr.split(Component.literal(para), 220));
        }
        context.setTooltipForNextFrame(lines, x, y);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        pendingTooltip = null;
        continueSearch(); // a search still in progress adds this frame's results before drawing
        super.render(context, mouseX, mouseY, delta);
        if (showingGrid()) renderGrid(context, mouseX, mouseY);
        if (pendingTooltip != null) drawExplanation(context, font, pendingTooltip, tooltipX, tooltipY);
        ScreenTabs.drawUnderline(context, width, false);
        if (fuzzy) drawFuzzyNote(context, font, search);
        // Hint line under the search field: the trash, the selection, or how to select.
        Component hint;
        int hintColor = 0xFFB0B0B0;
        if (trashView) {
            hint = Component.translatable("screen.entitytracker.hint.trash", Settings.trashDays);
            hintColor = 0xFFE8B04A;
        } else if (indexing > 0) {
            hint = Component.translatable("screen.entitytracker.hint.indexing", indexing);
            hintColor = 0xFFE8B04A;
        } else if (selectMode || !selection.isEmpty()) {
            hint = Component.translatable("screen.entitytracker.hint.selected", selection.size());
            hintColor = 0xFF8FC8FF;
        } else if (tallyHint != null) {
            hint = tallyHint;
            hintColor = 0xFFFFE08A;
        } else if (showingGrid()) {
            hint = Component.translatable("screen.entitytracker.grid.hint", grid.size());
        } else {
            hint = Component.translatable("screen.entitytracker.hint.select");
        }
        context.drawCenteredString(font, hint, width / 2, 48, hintColor);
        if (EntityTrackerClient.banks().bank() == null) {
            context.drawCenteredString(font, Component.translatable(EntityTrackerClient.banks().isLoading()
                    ? "screen.entitytracker.loading" : "screen.entitytracker.no_bank"),
                    width / 2, height / 2, 0xFFAAAAAA);
        } else if (showingGrid() ? grid.isEmpty() : list.children().isEmpty() && job == null) {
            context.drawCenteredString(font, Component.translatable("screen.entitytracker.no_results"),
                    width / 2, height / 2, 0xFFAAAAAA);
        }
    }

    /** Darker than the vanilla backdrop, so grey text stays readable over a bright sky. */
    @Override
    public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Either a container (item == null) or one matching item inside a container. */
    private record Row(ContainerRecord record, ItemIndex.IndexedItem item, int itemCount) {}

    private final class ResultList extends ObjectSelectionList<ResultList.Entry> {
        ResultList(Minecraft client, int width, int height, int y) {
            super(client, width, height, y, ROW_HEIGHT);
        }

        io.github.liangforstudy.entitytracker.gui.TrackerScreen.ResultList.Entry entryAt(double x, double y) {
            return getEntryAtPosition(x, y);
        }

        void setRows(List<Row> rows) {
            replaceEntries(rows.stream().map(io.github.liangforstudy.entitytracker.gui.TrackerScreen.ResultList.Entry::new).toList());
            setScrollAmount(0);
        }

        /** Adds rows at the bottom, keeping the scroll and selection (a search still in progress). */
        void addRows(List<Row> rows) {
            for (Row row : rows) addEntry(new io.github.liangforstudy.entitytracker.gui.TrackerScreen.ResultList.Entry(row));
        }

        @Override
        public int getRowWidth() {
            return Math.min(520, width - 30);
        }

        final class Entry extends ObjectSelectionList.Entry<io.github.liangforstudy.entitytracker.gui.TrackerScreen.ResultList.Entry> {
            private final Row row;
            private final ItemStack icon;
            private final ItemStack containerIcon;

            Entry(Row row) {
                this.row = row;
                this.containerIcon = entityIcon(row.record().type);
                this.icon = row.item() != null ? row.item().stack() : containerIcon;
            }

            @Override
            public void renderContent(GuiGraphics context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX();
                int y = getContentY();
                ContainerRecord r = row.record();
                if (selection.contains(r.key())) {
                    context.fill(x - 2, y - 2, x + getRowWidth() - 2, y + ROW_HEIGHT - 4, 0x553A78C8);
                    context.drawString(font, "\u2714", x - 12, y + 17, 0xFF8FC8FF);
                }
                // Icons: the item found, with the container it is in underneath.
                context.renderItem(icon, x + 2, y + 3);
                if (row.item() != null) {
                    context.renderItemDecorations(font, icon, x + 2, y + 3);
                    context.renderItem(containerIcon, x + 2, y + 23);
                }

                int textX = x + 24;
                int maxWidth = getContentWidthSafe() - 42;
                context.drawString(font, "\u22EE", x + getRowWidth() - 10, y + 17,
                        hovered ? 0xFFFFFFFF : 0xFF888888);
                int lineY = y + 3;
                if (row.item() == null) {
                    // Container row: which container / where and status / notes.
                    String count = r.hasSnapshot()
                            ? Component.translatable("screen.entitytracker.stacks", row.itemCount()).getString()
                            : Component.translatable("screen.entitytracker.never_opened").getString();
                    context.drawString(font, trim(title(r) + " · " + count, maxWidth), textX, lineY, 0xFFFFFFFF);
                } else {
                    // Item row: the item / which container (and inside what) / where and status / notes.
                    context.drawString(font, trim(row.item().stack().getHoverName().getString(), maxWidth),
                            textX, lineY, 0xFFFFFFFF);
                    lineY += 11;
                    String path = String.join(" > ", row.item().path());
                    context.drawString(font, trim(title(r) + (path.isEmpty() ? "" : " > " + path), maxWidth),
                            textX, lineY, 0xFFDDDDDD);
                }
                lineY += 11;
                String whereLine = trim(where(r), maxWidth);
                context.drawString(font, whereLine, textX, lineY, statusColor(r.status));
                if (hovered && mouseY >= lineY - 1 && mouseY < lineY + 10 && mouseX >= textX
                        && mouseX < textX + font.width(whereLine)) {
                    showTooltip(statusExplain(r), mouseX, mouseY);
                }
                lineY += 11;
                // Notes: warnings (amber), hints (blue), "last checked" (grey), left to right.
                int nx = textX;
                for (Note note : notes(r)) {
                    String text = (nx == textX ? "" : " · ") + note.text();
                    int room = textX + maxWidth - nx;
                    if (room <= 0) break;
                    String shown = trim(text, room);
                    context.drawString(font, shown, nx, lineY, note.color());
                    int w = font.width(shown);
                    if (hovered && mouseY >= lineY - 1 && mouseY < lineY + 10 && mouseX >= nx && mouseX < nx + w) {
                        showTooltip(note.explain(), mouseX, mouseY);
                    }
                    nx += w;
                }

                if (hovered && mouseX >= x + 2 && mouseX < x + 18 && mouseY >= y + 3 && mouseY < y + 19
                        && row.item() != null) {
                    context.setTooltipForNextFrame(font, icon, mouseX, mouseY);
                }
            }

            private int getContentWidthSafe() {
                return getRowWidth();
            }

            private String trim(String s, int maxWidth) {
                if (font.width(s) <= maxWidth) return s;
                return font.plainSubstrByWidth(s, Math.max(0, maxWidth - font.width("..."))) + "...";
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
                ResultList.this.setSelected(this);
                boolean ctrl = (click.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
                boolean shift = (click.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
                boolean selecting = selectMode || !selection.isEmpty() || ctrl || shift;
                // The "⋮" at the end of the row opens the menu with a plain tap (touch screens);
                // while selecting, a click toggles the row; otherwise it opens the detail view.
                if (click.x() >= getContentX() + getRowWidth() - 16) {
                    openMenu(row.record(), click.x(), click.y());
                } else if (selecting && row.record() instanceof InventoryRecord) {
                    return true; // can't be selected; opening it here would leave the selection behind
                } else if (selecting) {
                    int index = ResultList.this.children().indexOf(this);
                    if (shift && lastToggled >= 0) {
                        int from = Math.min(lastToggled, index), to = Math.max(lastToggled, index);
                        for (int i = from; i <= to; i++) {
                            ContainerRecord r = ResultList.this.children().get(i).row.record();
                            if (!(r instanceof InventoryRecord)) selection.add(r.key());
                        }
                    } else if (!selection.remove(row.record().key())) {
                        selection.add(row.record().key());
                    }
                    lastToggled = index;
                    updateBar();
                } else {
                    String q = search.getValue().trim().toLowerCase(Locale.ROOT);
                    if (row.record() instanceof EntityRecord trader && trader.isTrader()) {
                        minecraft.setScreen(new TradeDetailScreen(trader, TrackerScreen.this, ""));
                    } else {
                        minecraft.setScreen(new DetailScreen(row.record(), TrackerScreen.this, q.startsWith("@") ? q.substring(1) : q));
                    }
                }
                return true;
            }

            @Override
            public Component getNarration() {
                return Component.literal(label(row.record()));
            }
        }
    }

    /** The container as a player would name it: "Chest at 210, 100, 213", "\"Pack\" (Donkey)". */
    public static String title(ContainerRecord r) {
        String label = label(r);
        // In another dimension the place line already shows "Nether (x, y, z)".
        if (r instanceof BlockRecord b && !b.global && r.dimension.equals(Dimensions.current())) {
            return Component.translatable("screen.entitytracker.at", label, b.pos.getX(), b.pos.getY(), b.pos.getZ()).getString();
        }
        return label;
    }

    public static String label(ContainerRecord r) {
        if (r instanceof InventoryRecord) return Component.translatable("screen.entitytracker.on_me", r.name).getString();
        String type = r instanceof BlockRecord
                ? BuiltInRegistries.BLOCK.getValue(Identifier.parse(r.type)).getName().getString()
                : Component.translatable(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(r.type)).getDescriptionId()).getString();
        if (r instanceof BlockRecord block && block.global && block.ownerName != null) return type + " (" + block.ownerName + ")";
        return r.name != null ? "\"" + r.name + "\" (" + type + ")" : type;
    }

    public static String where(ContainerRecord r) {
        Minecraft client = Minecraft.getInstance();
        List<String> parts = new ArrayList<>();
        if (r instanceof InventoryRecord) return Component.translatable("screen.entitytracker.on_me_where").getString();
        if (r instanceof BlockRecord block && block.global) {
            // Ender chest: no place or status. Another account's says which account to switch to.
            MemoryBank bank = EntityTrackerClient.banks().bank();
            if (bank != null && block.owner != null && !block.owner.equals(bank.currentPlayer)) {
                parts.add(Component.translatable("screen.entitytracker.other_account",
                        block.ownerName != null ? block.ownerName : block.owner.toString()).getString());
            }
        } else {
            parts.add(place(r, false));
            String status = Component.translatable("screen.entitytracker.status." + statusId(r)).getString();
            boolean bad = r.status == RecordStatus.MISSING || r.status == RecordStatus.LIKELY_DESTROYED;
            parts.add(bad ? "\u26A0 " + status : status);
        }
        parts.add(Component.translatable("screen.entitytracker.seen_ago", age(r.lastSeen)).getString());
        if (r.isTrashed()) parts.add(Component.translatable("screen.entitytracker.in_trash", age(r.trashedAt)).getString());
        return String.join(" · ", parts);
    }

    /**
     * Where a container is, from where the player stands: "12m (x, y, z)" in this dimension;
     * "Nether (x, y, z) ≈ x, z here - 1,240m" in a dimension that converts (CLAUDE.md 4.1e);
     * "End (x, y, z)" otherwise. {@code withCoords} adds coordinates for entities too.
     */
    public static String place(ContainerRecord r, boolean withCoords) {
        Minecraft client = Minecraft.getInstance();
        String coords = r instanceof BlockRecord b
                ? b.pos.getX() + ", " + b.pos.getY() + ", " + b.pos.getZ()
                : Math.round(Math.floor(r.x)) + ", " + Math.round(Math.floor(r.y)) + ", " + Math.round(Math.floor(r.z));
        boolean showCoords = withCoords;
        if (client.player != null && r.dimension.equals(Dimensions.current())) {
            String d = Math.round(Math.sqrt(client.player.distanceToSqr(r.x, r.y, r.z))) + "m";
            return showCoords ? d + " (" + coords + ")" : d;
        }
        String there = Dimensions.displayName(r.dimension) + " (" + coords + ")";
        double[] here = Dimensions.inCurrent(r);
        if (here == null) return there;
        return Component.translatable("screen.entitytracker.converted", there, Math.round(Math.floor(here[0])),
                Math.round(Math.floor(here[1])), String.format(Locale.ROOT, "%,d", Math.round(Dimensions.convertedDistance(r)))).getString();
    }

    /** {@code explain}: what the note means, shown on hover / "Show warnings"; null if none. */
    public record Note(String text, int color, @Nullable String explain) {}

    /**
     * An explanation from the language file, or null when its entry is empty or absent. Empty
     * entries (e.g. "unloaded") are placeholders a resource pack can fill in.
     */
    static @Nullable String explain(String id, Object... args) {
        String key = "screen.entitytracker.explain." + id;
        String text = Component.translatable(key, args).getString();
        return text.equals(key) || text.isBlank() ? null : text; // empty entry = no tooltip (yet)
    }

    /**
     * The status as shown: a mob (donkey, llama, villager...) that is likely destroyed "died";
     * minecarts and boats stay "likely destroyed". A gone block is certain (its chunk is loaded):
     * "destroyed", or "picked up" for a shulker box.
     */
    public static String statusId(ContainerRecord r) {
        if (r.status == RecordStatus.LIKELY_DESTROYED && r instanceof EntityRecord e && isMob(e)) return "died";
        if (r.status == RecordStatus.LIKELY_DESTROYED && r instanceof BlockRecord) {
            // A broken shulker box keeps its contents and drops as an item: picked up, not destroyed.
            return r.type.endsWith("shulker_box") ? "picked_up" : "destroyed";
        }
        return r.status.name().toLowerCase(Locale.ROOT);
    }

    /** "Mark dead" for a mob, "Mark destroyed" for everything else. */
    public static Component markLabel(ContainerRecord r) {
        return Component.translatable(r instanceof EntityRecord e && isMob(e) ? "screen.entitytracker.mark_dead"
                : "screen.entitytracker.mark_destroyed");
    }

    /** Copies "x y z" (vanilla) or "x z" (Baritone goals) and says so in the action bar. */
    public static void copyCoords(Minecraft client, ContainerRecord r, boolean withY) {
        long x, y, z;
        if (r instanceof BlockRecord b && !b.global) {
            x = b.pos.getX();
            y = b.pos.getY();
            z = b.pos.getZ();
        } else {
            x = Math.round(Math.floor(r.x));
            y = Math.round(Math.floor(r.y));
            z = Math.round(Math.floor(r.z));
        }
        String coords = withY ? x + " " + y + " " + z : x + " " + z;
        client.keyboardHandler.setClipboard(coords);
        Messages.actionBar(Component.translatable("screen.entitytracker.detail.copied", coords,
                Dimensions.displayName(r.dimension)));
    }

    /** Backdrop drawn over the vanilla one by every tracker screen. */
    static final int BACKDROP = 0x90000000;
    /** Where the list starts: below the search field and its hint line. */
    static final int LIST_Y = 60;

    /** Animals and villagers, as opposed to minecarts and boats (which are MISC, like villagers). */
    private static boolean isMob(EntityRecord e) {
        return e.isTrader() || BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(e.type)).getCategory() != net.minecraft.world.entity.MobCategory.MISC;
    }

    /** Explanation of the status line (and the trash, if it is in there), or null. */
    public static @Nullable String statusExplain(ContainerRecord r) {
        if (r instanceof InventoryRecord || (r instanceof BlockRecord b && b.global)) return null;
        String status = explain("status." + statusId(r));
        String trash = r.isTrashed() ? explain("in_trash", Settings.trashDays) : null;
        if (status == null) return trash;
        return trash == null ? status : status + "\n\n" + trash;
    }

    /**
     * Warning flags (amber), hints (blue) and "last checked" (grey) for a container (CLAUDE.md
     * 4.1d), already translated. Shown, never acted on: the player decides what to do.
     */
    public static List<Note> notes(ContainerRecord r) {
        List<Note> out = new ArrayList<>();
        for (String flag : r.flags) out.add(new Note("\u26A0 " + note("warn", flag), 0xFFE8B04A, explain(flag)));
        for (String hint : r.hints) out.add(new Note(note("hint", hint), 0xFF8FB8E8, explain(hint)));
        if (r instanceof EntityRecord e && e.reappeared) {
            out.add(new Note(Component.translatable("screen.entitytracker.note.reappeared").getString(), 0xFF9A9A9A, explain("reappeared")));
        }
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank != null && bank.isStale(r)) {
            int days = bank.playDaysSince(r.lastOpened);
            out.add(new Note(Component.translatable("screen.entitytracker.warn.stale", days).getString(), 0xFF9A9A9A,
                    explain("stale", days)));
        }
        return out;
    }

    /** The same notes as plain text, for chat. */
    public static List<String> warnings(ContainerRecord r) {
        return notes(r).stream().map(Note::text).toList();
    }

    private static String note(String kind, String id) {
        String key = "screen.entitytracker." + kind + "." + id;
        String text = Component.translatable(key).getString();
        return text.equals(key) ? id : text; // unknown note from a newer version: show its id
    }

    static int statusColor(RecordStatus status) {
        return switch (status) {
            case LOADED -> 0xFF8FE08F;
            case UNLOADED -> 0xFFAAAAAA;
            case MISSING -> 0xFFE0C060;
            case LIKELY_DESTROYED -> 0xFFE07070;
        };
    }

    /** The item with the same id (chest, barrel, chest_minecart, oak_chest_boat), else the spawn egg, else a chest. */
    public static ItemStack entityIcon(String type) {
        Identifier id = Identifier.parse(type);
        Item item = BuiltInRegistries.ITEM.getValue(id);
        if (item == Items.AIR) item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath(id.getNamespace(), id.getPath() + "_spawn_egg"));
        if (item == Items.AIR) item = Items.CHEST;
        return new ItemStack(item);
    }

    public static String age(long millis) {
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        if (s < 86400) return s / 3600 + "h";
        return s / 86400 + "d";
    }
}
