package io.github.liangforstudy.entitytracker.gui;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.TradeData;
import io.github.liangforstudy.entitytracker.search.Fuzzy;
import io.github.liangforstudy.entitytracker.track.Trash;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The shops the player has seen (CLAUDE.md 4.11), kept apart from the storage search so offers
 * never mix into inventory results. Empty search lists villagers; typing lists matching offers.
 */
public final class TradesScreen extends Screen {
    private static final int ROW_HEIGHT = 46;
    private static final int MAX_ROWS = 500;
    /** Width of the "price -> goods" icons at the end of an offer row. */
    private static final int STRIP_WIDTH = 72;
    /** Space between preview columns, so two offers never read as one chain. */
    private static final int STRIP_GAP = 10;

    private TextFieldWidget search;
    private ResultList list;
    /** false = remembered villagers, true = the trash. */
    private boolean trashView;
    /** The results shown are close matches, not exact ones. */
    private boolean fuzzy;
    /** Wandering traders come and go; this hides them all at once. Kept while the game runs. */
    private static boolean showWandering = true;
    /** The open row menu (right-click or the row's "⋮"), empty when closed. */
    private final List<ButtonWidget> menu = new ArrayList<>();
    private int menuX, menuY, menuW, menuH;

    private final String initialQuery;

    public TradesScreen() {
        this("");
    }

    /** Opens already searching for {@code query} (/offers mending). */
    public TradesScreen(String query) {
        super(Text.translatable("screen.entitytracker.trades.title"));
        this.initialQuery = query;
    }

    @Override
    protected void init() {
        int fieldWidth = Math.min(300, width - 32);
        String previous = search != null ? search.getText() : initialQuery;
        search = new TextFieldWidget(textRenderer, (width - fieldWidth) / 2, 24, fieldWidth, 20,
                Text.translatable("screen.entitytracker.trades.search"));
        search.setPlaceholder(Text.translatable("screen.entitytracker.trades.search"));
        search.setText(previous);
        search.setChangedListener(q -> refresh());
        addDrawableChild(search);

        list = new ResultList(client, width, height - 30 - TrackerScreen.LIST_Y, TrackerScreen.LIST_Y);
        addDrawableChild(list);
        for (ButtonWidget tab : ScreenTabs.create(client, width, true)) addDrawableChild(tab);

        List<ButtonWidget.Builder> bar = List.of(
                ButtonWidget.builder(trashLabel(), b -> {
                    trashView = !trashView;
                    clearAndInit();
                }),
                ButtonWidget.builder(wanderingLabel(), b -> {
                    showWandering = !showWandering;
                    b.setMessage(wanderingLabel());
                    refresh();
                }),
                ButtonWidget.builder(ScreenTexts.DONE, b -> close()));
        menu.clear();
        int w = Math.min(120, (width - 40) / bar.size());
        int x = width / 2 - (w * bar.size() + 4 * (bar.size() - 1)) / 2;
        for (ButtonWidget.Builder b : bar) {
            addDrawableChild(b.dimensions(x, height - 26, w, 20).build());
            x += w + 4;
        }
        setInitialFocus(search);
        refresh();
    }

    private void refresh() {
        String query = TradeView.query(search.getText());
        fuzzy = false;
        List<Row> rows = rows(query, false);
        if (rows.isEmpty() && !query.isEmpty()) {
            // Nothing exact: try close matches ("wheet" -> wheat) and say so.
            rows = rows(query, true);
            fuzzy = !rows.isEmpty();
        }
        list.setRows(rows);
    }

    private static boolean matches(String text, String query, boolean fuzzy) {
        return fuzzy ? Fuzzy.matches(text, query) : text.contains(query);
    }

    private List<Row> rows(String query, boolean fuzzy) {
        List<Row> rows = new ArrayList<>();
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank != null) {
            List<EntityRecord> traders = bank.traders()
                    .filter(r -> trashView == r.isTrashed())
                    .filter(r -> showWandering || !isWandering(r))
                    .sorted(Comparator.comparingDouble(TrackerScreen::sortDistance))
                    .toList();
            for (EntityRecord r : traders) {
                TradeData.Snapshot s = r.trades.snapshotFor(bank.currentPlayer);
                List<TradeView.ShownOffer> offers = TradeView.offers(r, s);
                if (query.isEmpty()) {
                    rows.add(new Row(r, s, offers, null));
                } else {
                    boolean any = false;
                    for (TradeView.ShownOffer o : offers) {
                        if (matches(o.searchText(), query, fuzzy)) {
                            rows.add(new Row(r, s, offers, o));
                            any = true;
                        }
                    }
                    // "librarian", "Bob": the villager itself
                    if (!any && matches(TradeView.label(r).toLowerCase(Locale.ROOT), query, fuzzy)) rows.add(new Row(r, s, offers, null));
                }
                if (rows.size() >= MAX_ROWS) break;
            }
        }
        return rows;
    }

    /** "Trash (3)" in the list, "<- Back to list" in the trash. */
    private Text trashLabel() {
        if (trashView) return Text.translatable("screen.entitytracker.trash_back");
        MemoryBank bank = EntityTrackerClient.banks().bank();
        long n = bank == null ? 0 : bank.traders().filter(EntityRecord::isTrashed).count();
        return Text.translatable("screen.entitytracker.trash_open", n);
    }

    private static Text wanderingLabel() {
        return Text.translatable(showWandering ? "screen.entitytracker.trades.wandering_shown"
                : "screen.entitytracker.trades.wandering_hidden");
    }

    private static boolean isWandering(EntityRecord r) {
        return "minecraft:wandering_trader".equals(r.type);
    }

    /** Row menu: mark destroyed (to the trash, e.g. a wandering trader that left) or restore. */
    private void openMenu(EntityRecord record, double x, double y) {
        closeMenu();
        ButtonWidget.Builder item = ButtonWidget.builder(record.isTrashed()
                ? Text.translatable("screen.entitytracker.restore") : TrackerScreen.markLabel(record), b -> {
            closeMenu();
            MemoryBank bank = EntityTrackerClient.banks().bank();
            if (bank == null) return;
            if (record.isTrashed()) Trash.restore(bank, record);
            else Trash.trash(bank, record);
            clearAndInit(); // also updates the trash count
        });
        menuW = 120;
        menuH = 20;
        menuX = (int) Math.min(x, width - menuW - 4);
        menuY = (int) Math.min(y, height - menuH - 4);
        menu.add(addDrawableChild(item.dimensions(menuX, menuY, menuW, menuH).build()));
    }

    private void closeMenu() {
        for (ButtonWidget b : menu) remove(b);
        menu.clear();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (!menu.isEmpty()) {
            boolean inside = click.x() >= menuX && click.x() < menuX + menuW && click.y() >= menuY && click.y() < menuY + menuH;
            if (inside) return super.mouseClicked(click, doubled);
            closeMenu(); // a click anywhere else just closes the menu
            return true;
        }
        if (click.button() == 1) {
            ResultList.Entry entry = list.entryAt(click.x(), click.y());
            if (entry != null) {
                openMenu(entry.row.record(), click.x(), click.y());
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    /** For commands outside this package. */
    public static String label(EntityRecord r) {
        return TradeView.label(r);
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE && !menu.isEmpty()) {
            closeMenu();
            return true;
        }
        if (input.key() == GLFW.GLFW_KEY_ESCAPE && !search.getText().isEmpty()) {
            search.setText("");
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        ScreenTabs.drawUnderline(context, width, true);
        if (fuzzy) TrackerScreen.drawFuzzyNote(context, textRenderer, search);
        context.drawCenteredTextWithShadow(textRenderer, trashView
                ? Text.translatable("screen.entitytracker.hint.trash", io.github.liangforstudy.entitytracker.Settings.trashDays)
                : Text.translatable("screen.entitytracker.hint.trades"), width / 2, 48, trashView ? 0xFFE8B04A : 0xFFB0B0B0);
        if (EntityTrackerClient.banks().bank() == null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.entitytracker.no_bank"),
                    width / 2, height / 2, 0xFFAAAAAA);
        } else if (list.children().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable(search.getText().isBlank() && !trashView
                    ? "screen.entitytracker.trades.none_yet" : "screen.entitytracker.no_results"), width / 2, height / 2, 0xFFAAAAAA);
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

    /** A villager (offer == null) or one of its offers that matched the search. */
    private record Row(EntityRecord record, @Nullable TradeData.Snapshot snapshot, List<TradeView.ShownOffer> offers,
                       @Nullable TradeView.ShownOffer offer) {}

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
            private final ItemStack traderIcon;

            Entry(Row row) {
                this.row = row;
                this.traderIcon = TrackerScreen.entityIcon(row.record().type);
            }

            @Override
            public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX(), y = getContentY();
                int textX = x + 24, maxWidth = getRowWidth() - 30;
                EntityRecord r = row.record();
                TradeView.ShownOffer o = row.offer();
                ItemStack hoveredStack = null;
                if (o != null) {
                    // The trade as in the villager's own screen: price (and second price) -> goods.
                    int sx = x + getRowWidth() - 16 - STRIP_WIDTH;
                    hoveredStack = strip(context, o, sx, y + 13, mouseX, mouseY, hoveredStack);
                    maxWidth = sx - 8 - textX;
                }
                context.drawTextWithShadow(textRenderer, "⋮", x + getRowWidth() - 10, y + 17,
                        hovered ? 0xFFFFFFFF : 0xFF888888);
                int lineY = y + 3;
                List<TrackerScreen.Note> notes = new ArrayList<>();
                if (o != null) {
                    // Offer row: what it sells and for how much / who sells it / where / notes.
                    context.drawItem(o.sell(), x + 2, y + 3);
                    context.drawStackOverlay(textRenderer, o.sell(), x + 2, y + 3);
                    context.drawItem(traderIcon, x + 2, y + 23);
                    draw(context, TradeView.sold(o) + "  ←  " + TradeView.price(o), textX, lineY, maxWidth, 0xFFFFFFFF);
                    lineY += 11;
                    draw(context, TradeView.label(r), textX, lineY, maxWidth, 0xFFDDDDDD);
                    if (o.offer().outOfStock()) {
                        notes.add(new TrackerScreen.Note(Text.translatable("screen.entitytracker.trades.out_of_stock").getString(),
                                0xFFE07070, null));
                    }
                    if (o.change() != null) notes.add(new TrackerScreen.Note(o.change(), o.changeColor(), null));
                } else {
                    // Villager row: who / what it sells / where / notes, with a preview of its offers
                    // (price -> goods, one column of two) at the end of the row.
                    context.drawItem(traderIcon, x + 2, y + 3);
                    int cols = row.offers().isEmpty() ? 0 : 1;
                    int slots = cols * 2;
                    boolean more = row.offers().size() > slots;
                    int stripsX = x + getRowWidth() - 16 - cols * (STRIP_WIDTH + STRIP_GAP) + STRIP_GAP;
                    for (int i = 0; i < Math.min(row.offers().size(), slots); i++) {
                        int sx = stripsX + (i / 2) * (STRIP_WIDTH + STRIP_GAP);
                        int sy = y + 3 + (i % 2) * 20;
                        if (more && i == slots - 1) {
                            context.drawTextWithShadow(textRenderer, Text.translatable("screen.entitytracker.trades.more",
                                    row.offers().size() - slots + 1), sx + 4, sy + 4, 0xFFAAAAAA);
                            break;
                        }
                        hoveredStack = strip(context, row.offers().get(i), sx, sy, mouseX, mouseY, hoveredStack);
                    }
                    if (cols > 0) maxWidth = stripsX - 8 - textX;
                    String count = row.snapshot() == null ? Text.translatable("screen.entitytracker.never_opened").getString()
                            : Text.translatable(row.offers().size() == 1 ? "screen.entitytracker.trades.offer"
                                    : "screen.entitytracker.trades.offers", row.offers().size()).getString();
                    draw(context, TradeView.label(r) + " · " + count, textX, lineY, maxWidth, 0xFFFFFFFF);
                    lineY += 11;
                    List<String> sold = row.offers().stream().map(TradeView::sold).toList();
                    draw(context, String.join(", ", sold), textX, lineY, maxWidth, 0xFFDDDDDD);
                    int gone = TradeView.gone(row.snapshot());
                    if (gone > 0) notes.add(new TrackerScreen.Note(Text.translatable("screen.entitytracker.trades.gone", gone).getString(),
                            0xFFE8B04A, null));
                }
                lineY += 11;
                draw(context, TrackerScreen.where(r), textX, lineY, maxWidth, TrackerScreen.statusColor(r.status));
                lineY += 11;
                if (row.snapshot() != null) {
                    String other = TradeView.otherAccount(r.trades, row.snapshot());
                    if (other != null) notes.add(new TrackerScreen.Note(other, 0xFF8FB8E8, null));
                }
                notes.addAll(TrackerScreen.notes(r));
                int nx = textX;
                for (TrackerScreen.Note note : notes) {
                    int room = textX + maxWidth - nx;
                    if (room <= 0) break;
                    nx += draw(context, (nx == textX ? "" : " · ") + note.text(), nx, lineY, room, note.color());
                }
                if (hovered && o != null && mouseX >= x + 2 && mouseX < x + 18 && mouseY >= y + 3 && mouseY < y + 19) {
                    hoveredStack = o.sell();
                }
                if (hovered && hoveredStack != null) context.drawItemTooltip(textRenderer, hoveredStack, mouseX, mouseY);
            }

            /** price (+ second price) -> goods, as in the villager's own screen; returns the hovered stack. */
            private ItemStack strip(DrawContext context, TradeView.ShownOffer o, int sx, int sy, int mouseX, int mouseY,
                                    ItemStack hoveredSoFar) {
                // Each offer on its own faint box, like the trade buttons in the villager's screen.
                context.fill(sx - 3, sy - 1, sx + STRIP_WIDTH - 1, sy + 17, 0x30FFFFFF);
                ItemStack h = stripItem(context, o.buy1(), sx, sy, mouseX, mouseY, hoveredSoFar);
                if (!o.buy2().isEmpty()) h = stripItem(context, o.buy2(), sx + 20, sy, mouseX, mouseY, h);
                context.drawTextWithShadow(textRenderer, "→", sx + 42, sy + 4, 0xFFFFFFFF);
                h = stripItem(context, o.sell(), sx + 54, sy, mouseX, mouseY, h);
                if (o.offer().outOfStock()) TradeView.outOfStockMark(context, sx + 54, sy);
                return h;
            }

            /** One icon of the trade strip, with its count; returns the stack if the mouse is on it. */
            private ItemStack stripItem(DrawContext context, ItemStack stack, int x, int y, int mouseX, int mouseY,
                                        ItemStack hoveredSoFar) {
                context.drawItem(stack, x, y);
                context.drawStackOverlay(textRenderer, stack, x, y);
                return mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16 ? stack : hoveredSoFar;
            }

            /** Draws text cut to fit; returns its width. */
            private int draw(DrawContext context, String s, int x, int y, int maxWidth, int color) {
                if (textRenderer.getWidth(s) > maxWidth) {
                    s = textRenderer.trimToWidth(s, Math.max(0, maxWidth - textRenderer.getWidth("..."))) + "...";
                }
                context.drawTextWithShadow(textRenderer, s, x, y, color);
                return textRenderer.getWidth(s);
            }

            @Override
            public boolean mouseClicked(Click click, boolean doubled) {
                ResultList.this.setSelected(this);
                // The "⋮" at the end of the row opens the menu with a plain tap (touch screens).
                if (click.x() >= getContentX() + getRowWidth() - 16) {
                    openMenu(row.record(), click.x(), click.y());
                    return true;
                }
                client.setScreen(new TradeDetailScreen(row.record(), TradesScreen.this, TradeView.query(search.getText())));
                return true;
            }

            @Override
            public Text getNarration() {
                return Text.literal(TradeView.label(row.record()));
            }
        }
    }
}
