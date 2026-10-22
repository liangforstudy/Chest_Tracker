package io.github.liangforstudy.entitytracker.gui;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.TradeData;
import io.github.liangforstudy.entitytracker.track.Trash;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Every offer of one villager or wandering trader, as last seen (CLAUDE.md 4.11). */
public final class TradeDetailScreen extends Screen {
    private static final int ROW_HEIGHT = 22;

    private final EntityRecord record;
    private final Screen parent;
    private final String query;
    private final List<String> header = new ArrayList<>();
    private final List<Integer> headerColors = new ArrayList<>();
    private @Nullable ItemStack hoveredStack;

    public TradeDetailScreen(EntityRecord record, Screen parent, String query) {
        super(Text.literal(TradeView.label(record)));
        this.record = record;
        this.parent = parent;
        this.query = query;
    }

    @Override
    protected void init() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        TradeData.Snapshot s = record.trades == null ? null : record.trades.snapshotFor(bank == null ? null : bank.currentPlayer);
        header.clear();
        headerColors.clear();
        line(TrackerScreen.place(record, true) + " · " + statusText(), TrackerScreen.statusColor(record.status));
        if (s != null) {
            String when = Text.translatable("screen.entitytracker.trades.seen", TrackerScreen.age(s.time)).getString();
            String other = TradeView.otherAccount(record.trades, s);
            line(other != null ? when + " · " + other : when, 0xFFAAAAAA);
            int gone = TradeView.gone(s);
            if (gone > 0) line(Text.translatable("screen.entitytracker.trades.gone", gone).getString(), 0xFFE8B04A);
        }
        for (TrackerScreen.Note note : TrackerScreen.notes(record)) line(note.text(), note.color());
        String explain = TrackerScreen.explain("trades.changes");
        if (explain != null) line(explain, 0xFFB0B0B0);

        int listY = 22 + header.size() * 11 + 6;
        OfferList list = new OfferList(client, width, height - listY - 32, listY);
        for (TradeView.ShownOffer o : TradeView.offers(record, s)) list.add(o);
        addDrawableChild(list);

        int w = Math.min(100, (width - 40) / 5);
        int x = width / 2 - (w * 5 + 16) / 2;
        int y = height - 26;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.entitytracker.detail.back"), b -> close())
                .dimensions(x, y, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.entitytracker.detail.copy_xyz"),
                b -> TrackerScreen.copyCoords(client, record, true)).dimensions(x + w + 4, y, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.entitytracker.detail.copy_xz"),
                b -> TrackerScreen.copyCoords(client, record, false))
                .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("screen.entitytracker.detail.copy_xz_tip")))
                .dimensions(x + (w + 4) * 2, y, w, 20).build());
        addDrawableChild(ButtonWidget.builder(record.isTrashed()
                ? Text.translatable("screen.entitytracker.restore") : TrackerScreen.markLabel(record), b -> toggleTrash())
                .dimensions(x + (w + 4) * 3, y, w, 20).build());
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> client.setScreen(null))
                .dimensions(x + (w + 4) * 4, y, w, 20).build());
    }

    /** Header text, wrapped to the screen width. */
    private void line(String text, int color) {
        for (var part : textRenderer.wrapLines(Text.literal(text), Math.min(420, width - 40))) {
            StringBuilder sb = new StringBuilder();
            part.accept((index, style, codePoint) -> {
                sb.appendCodePoint(codePoint);
                return true;
            });
            header.add(sb.toString());
            headerColors.add(color);
        }
    }

    private String statusText() {
        return Text.translatable("screen.entitytracker.status." + TrackerScreen.statusId(record)).getString();
    }

    private void toggleTrash() {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank == null) return;
        if (record.isTrashed()) Trash.restore(bank, record);
        else Trash.trash(bank, record);
        clearAndInit();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    /** Darker than the vanilla backdrop, so grey text stays readable over a bright sky. */
    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);
        context.fill(0, 0, width, height, TrackerScreen.BACKDROP);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        hoveredStack = null;
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);
        for (int i = 0; i < header.size(); i++) {
            context.drawCenteredTextWithShadow(textRenderer, header.get(i), width / 2, 22 + i * 11, headerColors.get(i));
        }
        if (hoveredStack != null) context.drawItemTooltip(textRenderer, hoveredStack, mouseX, mouseY);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private final class OfferList extends ElementListWidget<OfferList.Entry> {
        OfferList(MinecraftClient client, int width, int height, int y) {
            super(client, width, height, y, ROW_HEIGHT);
        }

        void add(TradeView.ShownOffer offer) {
            addEntry(new Entry(offer));
        }

        @Override
        public int getRowWidth() {
            return Math.min(480, width - 30);
        }

        final class Entry extends ElementListWidget.Entry<Entry> {
            private final TradeView.ShownOffer offer;
            private final boolean match;

            Entry(TradeView.ShownOffer offer) {
                this.offer = offer;
                this.match = !query.isEmpty() && (offer.searchText().contains(query)
                        || io.github.liangforstudy.entitytracker.search.Fuzzy.matches(offer.searchText(), query));
            }

            @Override
            public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX(), y = getContentY();
                if (match) context.fill(x - 2, y - 1, x + getRowWidth() - 2, y + ROW_HEIGHT - 3, 0x553A78C8);
                boolean out = offer.offer().outOfStock();
                item(context, offer.buy1(), x + 2, y + 1, mouseX, mouseY);
                if (!offer.buy2().isEmpty()) item(context, offer.buy2(), x + 22, y + 1, mouseX, mouseY);
                TradeView.tradeArrow(context, x + 42, y + 1, out);
                item(context, offer.sell(), x + 56, y + 1, mouseX, mouseY);

                int textX = x + 80;
                int room = getRowWidth() - 84;
                List<String> parts = new ArrayList<>();
                parts.add(TradeView.describe(offer.sell()));
                String stock = out ? Text.translatable("screen.entitytracker.trades.out_of_stock").getString()
                        : Text.translatable("screen.entitytracker.trades.uses", offer.offer().maxUses() - offer.offer().uses(),
                        offer.offer().maxUses()).getString();
                String main = String.join(" · ", parts);
                int w = draw(context, main, textX, y + 1, room, 0xFFFFFFFF);
                int nx = textX;
                nx += draw(context, stock, nx, y + 11, room, out ? 0xFFE07070 : 0xFFAAAAAA);
                int base = offer.offer().baseCount();
                if (base > 0 && offer.buy1().getCount() < base) {
                    nx += draw(context, " · " + Text.translatable("screen.entitytracker.trades.discount", base).getString(),
                            nx, y + 11, textX + room - nx, 0xFF8FE08F);
                } else if (base > 0 && offer.buy1().getCount() > base) {
                    nx += draw(context, " · " + Text.translatable("screen.entitytracker.trades.demand", base).getString(),
                            nx, y + 11, textX + room - nx, 0xFFE8B04A);
                }
                if (offer.change() != null) draw(context, " · " + offer.change(), nx, y + 11, textX + room - nx, offer.changeColor());
            }

            private void item(DrawContext context, ItemStack stack, int x, int y, int mouseX, int mouseY) {
                context.drawItem(stack, x, y);
                context.drawStackOverlay(textRenderer, stack, x, y);
                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16 && !stack.isEmpty()) hoveredStack = stack;
            }

            private int draw(DrawContext context, String s, int x, int y, int maxWidth, int color) {
                if (maxWidth <= 0) return 0;
                if (textRenderer.getWidth(s) > maxWidth) {
                    s = textRenderer.trimToWidth(s, Math.max(0, maxWidth - textRenderer.getWidth("..."))) + "...";
                }
                context.drawTextWithShadow(textRenderer, s, x, y, color);
                return textRenderer.getWidth(s);
            }

            @Override
            public List<? extends Element> children() {
                return List.of();
            }

            @Override
            public List<? extends Selectable> selectableChildren() {
                return List.of();
            }
        }
    }
}
