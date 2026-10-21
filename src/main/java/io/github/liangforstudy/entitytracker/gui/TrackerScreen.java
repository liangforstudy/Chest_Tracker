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
import io.github.liangforstudy.entitytracker.track.Trash;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Search screen over everything remembered. Empty search lists containers; typing lists matching
 * items (nested ones included). Vanilla widgets only, rows tall enough to tap (PojavLauncher).
 */
public final class TrackerScreen extends Screen {
    private static final int ROW_HEIGHT = 46; // four lines: what / which container / where+status / notes
    private static final int MAX_ROWS = 500;

    private TextFieldWidget search;
    private @Nullable String pendingTooltip;
    /** Keys of selected containers (Ctrl/Shift-click, or taps in Select mode). */
    private final Set<String> selection = new LinkedHashSet<>();
    private boolean selectMode;
    private int lastToggled = -1;
    // Normal bar / bulk bar buttons; shown and hidden in place so the list keeps its scroll.
    private ButtonWidget viewButton, destroyedButton, selectButton, doneButton;
    private ButtonWidget bulkPrimary, bulkDelete, selectAllButton, cancelButton;
    private int tooltipX, tooltipY;
    private ResultList list;
    private boolean showDestroyed = true;
    /** The results shown are close matches, not exact ones. */
    private boolean fuzzy;
    /** false = remembered containers, true = the trash. */
    private boolean trashView;
    /** The open row menu (right-click or the row's "⋮"), empty when closed. */
    private final List<ButtonWidget> menu = new ArrayList<>();
    private int menuX, menuY, menuW, menuH;

    public TrackerScreen() {
        super(Text.translatable("screen.entitytracker.title"));
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(300, width - 32);
        String previous = search != null ? search.getText() : "";
        search = new TextFieldWidget(textRenderer, (width - fieldWidth) / 2, 24, fieldWidth, 20,
                Text.translatable("screen.entitytracker.search"));
        search.setPlaceholder(Text.translatable("screen.entitytracker.search"));
        search.setText(previous);
        search.setChangedListener(q -> refresh());
        addDrawableChild(search);

        list = new ResultList(client, width, height - 30 - LIST_Y, LIST_Y);
        addDrawableChild(list);
        for (ButtonWidget tab : ScreenTabs.create(client, width, false)) addDrawableChild(tab);

        // Bottom bar: views and filters, or bulk actions while something is selected.
        menu.clear();
        viewButton = addDrawableChild(ButtonWidget.builder(trashLabel(), b -> {
            trashView = !trashView;
            clearSelection();
            clearAndInit();
        }).build());
        destroyedButton = addDrawableChild(ButtonWidget.builder(destroyedLabel(), b -> {
            showDestroyed = !showDestroyed;
            b.setMessage(destroyedLabel());
            refresh();
        }).build());
        selectButton = addDrawableChild(ButtonWidget.builder(Text.translatable("screen.entitytracker.select"), b -> {
            selectMode = true;
            updateBar();
        }).build());
        doneButton = addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close()).build());
        bulkPrimary = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> bulkPrimary()).build());
        bulkDelete = addDrawableChild(ButtonWidget.builder(Text.empty(), b -> bulkDelete()).build());
        selectAllButton = addDrawableChild(ButtonWidget.builder(Text.translatable("screen.entitytracker.select_all"), b -> {
            for (ResultList.Entry e : list.children()) {
                if (!(e.row.record() instanceof InventoryRecord)) selection.add(e.row.record().key());
            }
            selectMode = true;
            updateBar();
        }).build());
        cancelButton = addDrawableChild(ButtonWidget.builder(ScreenTexts.CANCEL, b -> {
            clearSelection();
            updateBar();
        }).build());
        updateBar();

        setInitialFocus(search);
        refresh();
    }

    /** "Trash (3)" in the list, "<- Back to list" in the trash: a place to go, not a mode. */
    private Text trashLabel() {
        if (trashView) return Text.translatable("screen.entitytracker.trash_back");
        MemoryBank bank = EntityTrackerClient.banks().bank();
        long n = bank == null ? 0 : bank.containers().filter(ContainerRecord::isTrashed).count();
        return Text.translatable("screen.entitytracker.trash_open", n);
    }

    private Text destroyedLabel() {
        return Text.translatable(showDestroyed ? "screen.entitytracker.destroyed_shown"
                : "screen.entitytracker.destroyed_hidden");
    }

    /** Lays out whichever bottom bar applies: views and filters, or bulk actions. */
    private void updateBar() {
        boolean bulk = selectMode || !selection.isEmpty();
        List<ButtonWidget> shown = new ArrayList<>();
        for (ButtonWidget b : List.of(viewButton, destroyedButton, selectButton, doneButton, bulkPrimary, bulkDelete,
                selectAllButton, cancelButton)) {
            b.visible = false;
        }
        if (bulk) {
            int n = selection.size();
            bulkPrimary.setMessage(Text.translatable(trashView ? "screen.entitytracker.bulk.restore"
                    : "screen.entitytracker.bulk.mark", n));
            bulkPrimary.active = n > 0;
            shown.add(bulkPrimary);
            if (trashView) {
                bulkDelete.setMessage(Text.translatable("screen.entitytracker.bulk.delete", n));
                bulkDelete.active = n > 0;
                shown.add(bulkDelete);
            }
            shown.add(selectAllButton);
            shown.add(cancelButton);
        } else {
            shown.add(viewButton);
            if (!trashView) shown.add(destroyedButton);
            shown.add(selectButton);
            shown.add(doneButton);
        }
        int w = Math.min(120, (width - 40) / shown.size());
        int x = width / 2 - (w * shown.size() + 4 * (shown.size() - 1)) / 2;
        for (ButtonWidget b : shown) {
            b.visible = true;
            b.setDimensionsAndPosition(w, 20, x, height - 26);
            x += w + 4;
        }
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
            if (client.player != null) {
                client.player.sendMessage(Text.translatable("screen.entitytracker.area.done", records.size()), false);
            }
        }
        clearSelection();
        refresh();
        updateBar();
    }

    private void bulkDelete() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        List<ContainerRecord> records = selectedRecords();
        if (bank == null || records.isEmpty()) return;
        client.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                for (ContainerRecord r : records) Trash.deletePermanently(bank, r);
                clearSelection();
            }
            client.setScreen(this);
        }, Text.translatable("screen.entitytracker.bulk.delete_confirm", records.size()),
                Text.translatable("screen.entitytracker.bulk.delete_body")));
    }

    /** Opens the action menu for a container at the given screen position. */
    private void openMenu(ContainerRecord record, double x, double y) {
        closeMenu();
        if (record instanceof InventoryRecord) return; // nothing to mark destroyed
        List<ButtonWidget.Builder> items = new ArrayList<>();
        if (trashView) {
            items.add(ButtonWidget.builder(Text.translatable("screen.entitytracker.restore"), b -> act(() -> {
                MemoryBank bank = EntityTrackerClient.banks().bank();
                if (bank != null) Trash.restore(bank, record);
            })));
            items.add(ButtonWidget.builder(Text.translatable("screen.entitytracker.delete"), b -> {
                closeMenu();
                confirmDelete(record);
            }));
        } else {
            boolean hasExplanations = statusExplain(record) != null || notes(record).stream().anyMatch(n -> n.explain() != null);
            if (hasExplanations) {
                items.add(ButtonWidget.builder(Text.translatable("screen.entitytracker.show_warnings"), b -> {
                    closeMenu();
                    client.setScreen(new DetailScreen(record, this, ""));
                }));
            }
            items.add(ButtonWidget.builder(markLabel(record), b -> act(() -> {
                MemoryBank bank = EntityTrackerClient.banks().bank();
                if (bank != null) Trash.trash(bank, record);
            })));
        }
        menuW = 120;
        menuH = items.size() * 20;
        menuX = (int) Math.min(x, width - menuW - 4);
        menuY = (int) Math.min(y, height - menuH - 4);
        for (int i = 0; i < items.size(); i++) {
            menu.add(addDrawableChild(items.get(i).dimensions(menuX, menuY + i * 20, menuW, 20).build()));
        }
    }

    private void act(Runnable action) {
        closeMenu();
        action.run();
        refresh();
    }

    private void closeMenu() {
        for (ButtonWidget b : menu) remove(b);
        menu.clear();
    }

    private boolean insideMenu(double x, double y) {
        return x >= menuX && x < menuX + menuW && y >= menuY && y < menuY + menuH;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
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
            if (!search.getText().isEmpty()) {
                search.setText("");
                return true;
            }
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (!menu.isEmpty()) {
            if (insideMenu(click.x(), click.y())) return super.mouseClicked(click, doubled);
            closeMenu(); // a click anywhere else just closes the menu
            return true;
        }
        if (click.button() == 1) {
            ResultList.Entry entry = list.entryAt(click.x(), click.y());
            if (entry != null) {
                list.setSelected(entry);
                openMenu(entry.row.record(), click.x(), click.y());
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    private void confirmDelete(ContainerRecord record) {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank == null || record == null) return;
        client.setScreen(new ConfirmScreen(yes -> {
            if (yes) Trash.deletePermanently(bank, record);
            client.setScreen(this);
        }, Text.translatable("screen.entitytracker.delete_confirm"),
                Text.translatable("screen.entitytracker.delete_confirm_body", label(record))));
    }

    private void refresh() {
        if (viewButton != null) viewButton.setMessage(trashLabel());
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        if (query.startsWith("@")) query = query.substring(1); // @head:Notch until full filters (4.6)
        fuzzy = false;
        List<Row> rows = rows(query, false);
        if (rows.isEmpty() && !query.isEmpty()) {
            // Nothing exact: try close matches ("diamnd" -> diamond) and say so.
            rows = rows(query, true);
            fuzzy = !rows.isEmpty();
        }
        list.setRows(rows);
    }

    /** "close matches" next to the search field, when the results are fuzzy ones. */
    static void drawFuzzyNote(DrawContext context, net.minecraft.client.font.TextRenderer tr, TextFieldWidget field) {
        String note = Text.translatable("screen.entitytracker.close_matches").getString();
        context.drawTextWithShadow(tr, note, field.getX() + field.getWidth() - tr.getWidth(note) - 6,
                field.getY() + 6, 0xFFE8B04A);
    }

    private List<Row> rows(String query, boolean fuzzy) {
        List<Row> rows = new ArrayList<>();
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank != null) {
            List<ContainerRecord> records = new ArrayList<>();
            // Your own inventory first, so "it was on me all along" is the first thing you see.
            InventoryRecord onMe = trashView ? null : InventoryRecord.current();
            if (onMe != null) records.add(onMe);
            records.addAll(bank.containers()
                    .filter(r -> trashView ? r.isTrashed()
                            : !r.isTrashed() && (showDestroyed || r.status != RecordStatus.LIKELY_DESTROYED))
                    .sorted(Comparator.comparingDouble(TrackerScreen::sortDistance))
                    .toList());
            ItemIndex index = EntityTrackerClient.index();
            for (ContainerRecord record : records) {
                if (query.isEmpty()) {
                    rows.add(new Row(record, null, index.items(record).size()));
                } else {
                    for (ItemIndex.IndexedItem item : index.items(record)) {
                        if (fuzzy ? Fuzzy.matches(item.searchText(), query) : item.searchText().contains(query)) {
                            rows.add(new Row(record, item, 0));
                        }
                    }
                }
                if (rows.size() >= MAX_ROWS) break;
            }
        }
        return rows;
    }

    /** Same dimension first, nearest first; other dimensions after. */
    static double sortDistance(ContainerRecord r) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return 0;
        if (r.dimension.equals(client.world.getRegistryKey().getValue().toString())) return client.player.squaredDistanceTo(r.x, r.y, r.z);
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
    static void drawExplanation(DrawContext context, net.minecraft.client.font.TextRenderer tr, String text, int x, int y) {
        List<net.minecraft.text.OrderedText> lines = new ArrayList<>();
        for (String para : text.split("\n")) {
            if (para.isEmpty()) lines.add(net.minecraft.text.OrderedText.EMPTY);
            else lines.addAll(tr.wrapLines(Text.literal(para), 220));
        }
        context.drawTooltip(lines, x, y);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        pendingTooltip = null;
        super.render(context, mouseX, mouseY, delta);
        if (pendingTooltip != null) drawExplanation(context, textRenderer, pendingTooltip, tooltipX, tooltipY);
        ScreenTabs.drawUnderline(context, width, false);
        if (fuzzy) drawFuzzyNote(context, textRenderer, search);
        // Hint line under the search field: the trash, the selection, or how to select.
        Text hint;
        int hintColor = 0xFFB0B0B0;
        if (trashView) {
            hint = Text.translatable("screen.entitytracker.hint.trash", Settings.trashDays);
            hintColor = 0xFFE8B04A;
        } else if (selectMode || !selection.isEmpty()) {
            hint = Text.translatable("screen.entitytracker.hint.selected", selection.size());
            hintColor = 0xFF8FC8FF;
        } else {
            hint = Text.translatable("screen.entitytracker.hint.select");
        }
        context.drawCenteredTextWithShadow(textRenderer, hint, width / 2, 48, hintColor);
        if (EntityTrackerClient.banks().bank() == null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.entitytracker.no_bank"),
                    width / 2, height / 2, 0xFFAAAAAA);
        } else if (list.children().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.entitytracker.no_results"),
                    width / 2, height / 2, 0xFFAAAAAA);
        }
    }

    /** Darker than the vanilla backdrop, so grey text stays readable over a bright sky. */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /** Either a container (item == null) or one matching item inside a container. */
    private record Row(ContainerRecord record, ItemIndex.IndexedItem item, int itemCount) {}

    private final class ResultList extends AlwaysSelectedEntryListWidget<ResultList.Entry> {
        ResultList(MinecraftClient client, int width, int height, int y) {
            super(client, width, height, y, ROW_HEIGHT);
        }

        Entry entryAt(double x, double y) {
            return getEntryAtPosition(x, y);
        }

        void setRows(List<Row> rows) {
            replaceEntries(rows.stream().map(Entry::new).toList());
            setScrollY(0);
        }

        @Override
        public int getRowWidth() {
            return Math.min(520, width - 30);
        }

        final class Entry extends AlwaysSelectedEntryListWidget.Entry<Entry> {
            private final Row row;
            private final ItemStack icon;
            private final ItemStack containerIcon;

            Entry(Row row) {
                this.row = row;
                this.containerIcon = entityIcon(row.record().type);
                this.icon = row.item() != null ? row.item().stack() : containerIcon;
            }

            @Override
            public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX();
                int y = getContentY();
                ContainerRecord r = row.record();
                if (selection.contains(r.key())) {
                    context.fill(x - 2, y - 2, x + getRowWidth() - 2, y + ROW_HEIGHT - 4, 0x553A78C8);
                    context.drawTextWithShadow(textRenderer, "\u2714", x - 12, y + 17, 0xFF8FC8FF);
                }
                // Icons: the item found, with the container it is in underneath.
                context.drawItem(icon, x + 2, y + 3);
                if (row.item() != null) {
                    context.drawStackOverlay(textRenderer, icon, x + 2, y + 3);
                    context.drawItem(containerIcon, x + 2, y + 23);
                }

                int textX = x + 24;
                int maxWidth = getContentWidthSafe() - 42;
                context.drawTextWithShadow(textRenderer, "\u22EE", x + getRowWidth() - 10, y + 17,
                        hovered ? 0xFFFFFFFF : 0xFF888888);
                int lineY = y + 3;
                if (row.item() == null) {
                    // Container row: which container / where and status / notes.
                    String count = r.hasSnapshot()
                            ? Text.translatable("screen.entitytracker.stacks", row.itemCount()).getString()
                            : Text.translatable("screen.entitytracker.never_opened").getString();
                    context.drawTextWithShadow(textRenderer, trim(title(r) + " · " + count, maxWidth), textX, lineY, 0xFFFFFFFF);
                } else {
                    // Item row: the item / which container (and inside what) / where and status / notes.
                    context.drawTextWithShadow(textRenderer, trim(row.item().stack().getName().getString(), maxWidth),
                            textX, lineY, 0xFFFFFFFF);
                    lineY += 11;
                    String path = String.join(" > ", row.item().path());
                    context.drawTextWithShadow(textRenderer, trim(title(r) + (path.isEmpty() ? "" : " > " + path), maxWidth),
                            textX, lineY, 0xFFDDDDDD);
                }
                lineY += 11;
                String whereLine = trim(where(r), maxWidth);
                context.drawTextWithShadow(textRenderer, whereLine, textX, lineY, statusColor(r.status));
                if (hovered && mouseY >= lineY - 1 && mouseY < lineY + 10 && mouseX >= textX
                        && mouseX < textX + textRenderer.getWidth(whereLine)) {
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
                    context.drawTextWithShadow(textRenderer, shown, nx, lineY, note.color());
                    int w = textRenderer.getWidth(shown);
                    if (hovered && mouseY >= lineY - 1 && mouseY < lineY + 10 && mouseX >= nx && mouseX < nx + w) {
                        showTooltip(note.explain(), mouseX, mouseY);
                    }
                    nx += w;
                }

                if (hovered && mouseX >= x + 2 && mouseX < x + 18 && mouseY >= y + 3 && mouseY < y + 19
                        && row.item() != null) {
                    context.drawItemTooltip(textRenderer, icon, mouseX, mouseY);
                }
            }

            private int getContentWidthSafe() {
                return getRowWidth();
            }

            private String trim(String s, int maxWidth) {
                if (textRenderer.getWidth(s) <= maxWidth) return s;
                return textRenderer.trimToWidth(s, Math.max(0, maxWidth - textRenderer.getWidth("..."))) + "...";
            }

            @Override
            public boolean mouseClicked(Click click, boolean doubled) {
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
                    String q = search.getText().trim().toLowerCase(Locale.ROOT);
                    client.setScreen(new DetailScreen(row.record(), TrackerScreen.this, q.startsWith("@") ? q.substring(1) : q));
                }
                return true;
            }

            @Override
            public Text getNarration() {
                return Text.literal(label(row.record()));
            }
        }
    }

    /** The container as a player would name it: "Chest at 210, 100, 213", "\"Pack\" (Donkey)". */
    public static String title(ContainerRecord r) {
        String label = label(r);
        // In another dimension the place line already shows "Nether (x, y, z)".
        if (r instanceof BlockRecord b && !b.global && r.dimension.equals(Dimensions.current())) {
            return Text.translatable("screen.entitytracker.at", label, b.pos.getX(), b.pos.getY(), b.pos.getZ()).getString();
        }
        return label;
    }

    public static String label(ContainerRecord r) {
        if (r instanceof InventoryRecord) return Text.translatable("screen.entitytracker.on_me").getString();
        String type = r instanceof BlockRecord
                ? Registries.BLOCK.get(Identifier.of(r.type)).getName().getString()
                : Text.translatable(Registries.ENTITY_TYPE.get(Identifier.of(r.type)).getTranslationKey()).getString();
        if (r instanceof BlockRecord block && block.global && block.ownerName != null) return type + " (" + block.ownerName + ")";
        return r.name != null ? "\"" + r.name + "\" (" + type + ")" : type;
    }

    public static String where(ContainerRecord r) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<String> parts = new ArrayList<>();
        if (r instanceof InventoryRecord) return Text.translatable("screen.entitytracker.on_me_where").getString();
        if (r instanceof BlockRecord block && block.global) {
            // Ender chest: no place or status. Another account's says which account to switch to.
            MemoryBank bank = EntityTrackerClient.banks().bank();
            if (bank != null && block.owner != null && !block.owner.equals(bank.currentPlayer)) {
                parts.add(Text.translatable("screen.entitytracker.other_account",
                        block.ownerName != null ? block.ownerName : block.owner.toString()).getString());
            }
        } else {
            parts.add(place(r, false));
            String status = Text.translatable("screen.entitytracker.status." + statusId(r)).getString();
            boolean bad = r.status == RecordStatus.MISSING || r.status == RecordStatus.LIKELY_DESTROYED;
            parts.add(bad ? "\u26A0 " + status : status);
        }
        parts.add(Text.translatable("screen.entitytracker.seen_ago", age(r.lastSeen)).getString());
        if (r.isTrashed()) parts.add(Text.translatable("screen.entitytracker.in_trash", age(r.trashedAt)).getString());
        return String.join(" · ", parts);
    }

    /**
     * Where a container is, from where the player stands: "12m (x, y, z)" in this dimension;
     * "Nether (x, y, z) ≈ x, z here - 1,240m" in a dimension that converts (CLAUDE.md 4.1e);
     * "End (x, y, z)" otherwise. {@code withCoords} adds coordinates for entities too.
     */
    public static String place(ContainerRecord r, boolean withCoords) {
        MinecraftClient client = MinecraftClient.getInstance();
        String coords = r instanceof BlockRecord b
                ? b.pos.getX() + ", " + b.pos.getY() + ", " + b.pos.getZ()
                : Math.round(Math.floor(r.x)) + ", " + Math.round(Math.floor(r.y)) + ", " + Math.round(Math.floor(r.z));
        boolean showCoords = withCoords;
        if (client.player != null && r.dimension.equals(Dimensions.current())) {
            String d = Math.round(Math.sqrt(client.player.squaredDistanceTo(r.x, r.y, r.z))) + "m";
            return showCoords ? d + " (" + coords + ")" : d;
        }
        String there = Dimensions.displayName(r.dimension) + " (" + coords + ")";
        double[] here = Dimensions.inCurrent(r);
        if (here == null) return there;
        return Text.translatable("screen.entitytracker.converted", there, Math.round(Math.floor(here[0])),
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
        String text = Text.translatable(key, args).getString();
        return text.equals(key) || text.isBlank() ? null : text; // empty entry = no tooltip (yet)
    }

    /**
     * The status as shown: a mob (donkey, llama, villager...) that is likely destroyed "died";
     * minecarts, boats and blocks stay "likely destroyed".
     */
    public static String statusId(ContainerRecord r) {
        if (r.status == RecordStatus.LIKELY_DESTROYED && r instanceof EntityRecord e && isMob(e)) return "died";
        // A broken shulker box keeps its contents and drops as an item: picked up, not destroyed.
        if (r.status == RecordStatus.LIKELY_DESTROYED && r instanceof BlockRecord && r.type.endsWith("shulker_box")) return "picked_up";
        return r.status.name().toLowerCase(Locale.ROOT);
    }

    /** "Mark dead" for a mob, "Mark destroyed" for everything else. */
    public static Text markLabel(ContainerRecord r) {
        return Text.translatable(r instanceof EntityRecord e && isMob(e) ? "screen.entitytracker.mark_dead"
                : "screen.entitytracker.mark_destroyed");
    }

    /** Copies "x y z" (vanilla) or "x z" (Baritone goals) and says so in the action bar. */
    public static void copyCoords(MinecraftClient client, ContainerRecord r, boolean withY) {
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
        client.keyboard.setClipboard(coords);
        if (client.player != null) {
            client.player.sendMessage(Text.translatable("screen.entitytracker.detail.copied", coords,
                    Dimensions.displayName(r.dimension)), true);
        }
    }

    /** Backdrop drawn over the vanilla one by every tracker screen. */
    static final int BACKDROP = 0x90000000;
    /** Where the list starts: below the search field and its hint line. */
    static final int LIST_Y = 60;

    /** Animals and villagers, as opposed to minecarts and boats (which are MISC, like villagers). */
    private static boolean isMob(EntityRecord e) {
        return e.isTrader() || Registries.ENTITY_TYPE.get(Identifier.of(e.type)).getSpawnGroup() != net.minecraft.entity.SpawnGroup.MISC;
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
            out.add(new Note(Text.translatable("screen.entitytracker.note.reappeared").getString(), 0xFF9A9A9A, explain("reappeared")));
        }
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank != null && bank.isStale(r)) {
            int days = bank.playDaysSince(r.lastOpened);
            out.add(new Note(Text.translatable("screen.entitytracker.warn.stale", days).getString(), 0xFF9A9A9A,
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
        String text = Text.translatable(key).getString();
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
        Identifier id = Identifier.of(type);
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) item = Registries.ITEM.get(Identifier.of(id.getNamespace(), id.getPath() + "_spawn_egg"));
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
