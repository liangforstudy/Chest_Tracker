package io.github.liangforstudy.entitytracker.gui;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.data.RecordStatus;
import io.github.liangforstudy.entitytracker.search.ItemIndex;
import io.github.liangforstudy.entitytracker.track.Trash;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Search screen over everything remembered. Empty search lists containers; typing lists matching
 * items (nested ones included). Vanilla widgets only, rows tall enough to tap (PojavLauncher).
 */
public final class TrackerScreen extends Screen {
    private static final int ROW_HEIGHT = 26;
    private static final int MAX_ROWS = 500;

    private TextFieldWidget search;
    private ResultList list;
    private boolean showDestroyed = true;
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

        list = new ResultList(client, width, height - 80, 50);
        addDrawableChild(list);

        // Bottom bar: views and filters only. Actions on a container live in its row menu.
        menu.clear();
        int buttonY = height - 26;
        int count = trashView ? 2 : 3;
        int w = Math.min(120, (width - 40) / count);
        int x0 = width / 2 - (w * count + 4 * (count - 1)) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable(trashView ? "screen.entitytracker.view_trash"
                : "screen.entitytracker.view_active"), b -> {
            trashView = !trashView;
            clearAndInit();
        }).dimensions(x0, buttonY, w, 20).build());
        if (!trashView) {
            addDrawableChild(ButtonWidget.builder(destroyedLabel(), b -> {
                showDestroyed = !showDestroyed;
                b.setMessage(destroyedLabel());
                refresh();
            }).dimensions(x0 + w + 4, buttonY, w, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
                .dimensions(x0 + (w + 4) * (count - 1), buttonY, w, 20).build());

        setInitialFocus(search);
        refresh();
    }

    private Text destroyedLabel() {
        return Text.translatable(showDestroyed ? "screen.entitytracker.destroyed_shown"
                : "screen.entitytracker.destroyed_hidden");
    }

    /** Opens the action menu for a container at the given screen position. */
    private void openMenu(ContainerRecord record, double x, double y) {
        closeMenu();
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
            items.add(ButtonWidget.builder(Text.translatable("screen.entitytracker.mark_destroyed"), b -> act(() -> {
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
        List<Row> rows = new ArrayList<>();
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (bank != null) {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            if (query.startsWith("@")) query = query.substring(1); // @head:Notch until full filters (4.6)
            List<ContainerRecord> records = bank.allRecords()
                    .filter(r -> trashView ? r.isTrashed()
                            : !r.isTrashed() && (showDestroyed || r.status != RecordStatus.LIKELY_DESTROYED))
                    .sorted(Comparator.comparingDouble(TrackerScreen::sortDistance))
                    .toList();
            ItemIndex index = EntityTrackerClient.index();
            for (ContainerRecord record : records) {
                if (query.isEmpty()) {
                    rows.add(new Row(record, null, index.items(record).size()));
                } else {
                    for (ItemIndex.IndexedItem item : index.items(record)) {
                        if (item.searchText().contains(query)) rows.add(new Row(record, item, 0));
                    }
                }
                if (rows.size() >= MAX_ROWS) break;
            }
        }
        list.setRows(rows);
    }

    /** Same dimension first, nearest first; other dimensions after. */
    private static double sortDistance(ContainerRecord r) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return 0;
        double d = client.player.squaredDistanceTo(r.x, r.y, r.z);
        return r.dimension.equals(client.world.getRegistryKey().getValue().toString()) ? d : 1e15 + d;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);
        if (EntityTrackerClient.banks().bank() == null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.entitytracker.no_bank"),
                    width / 2, height / 2, 0xFFAAAAAA);
        } else if (list.children().isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.entitytracker.no_results"),
                    width / 2, height / 2, 0xFFAAAAAA);
        }
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
            return Math.min(360, width - 24);
        }

        final class Entry extends AlwaysSelectedEntryListWidget.Entry<Entry> {
            private final Row row;
            private final ItemStack icon;

            Entry(Row row) {
                this.row = row;
                this.icon = row.item() != null ? row.item().stack() : entityIcon(row.record().type);
            }

            @Override
            public void render(DrawContext context, int mouseX, int mouseY, boolean hovered, float delta) {
                int x = getContentX();
                int y = getContentY();
                ContainerRecord r = row.record();
                context.drawItem(icon, x + 2, y + 3);
                if (row.item() != null) context.drawStackOverlay(textRenderer, icon, x + 2, y + 3);

                String line1;
                String line2;
                if (row.item() == null) {
                    line1 = label(r) + "  -  " + (r.hasSnapshot()
                            ? Text.translatable("screen.entitytracker.stacks", row.itemCount()).getString()
                            : Text.translatable("screen.entitytracker.never_opened").getString());
                    line2 = where(r);
                } else {
                    line1 = row.item().stack().getName().getString();
                    String path = String.join(" > ", row.item().path());
                    line2 = label(r) + " #" + (row.item().slot() + 1) + (path.isEmpty() ? "" : " > " + path)
                            + "  -  " + where(r);
                }
                int textX = x + 24;
                int maxWidth = getContentWidthSafe() - 42;
                context.drawTextWithShadow(textRenderer, "\u22EE", x + getRowWidth() - 10, y + 9,
                        hovered ? 0xFFFFFFFF : 0xFF888888);
                context.drawTextWithShadow(textRenderer, trim(line1, maxWidth), textX, y + 3, 0xFFFFFFFF);
                context.drawTextWithShadow(textRenderer, trim(line2, maxWidth), textX, y + 14, statusColor(r.status));

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
                // The "⋮" at the end of the row opens the menu with a plain tap (touch screens).
                if (click.x() >= getContentX() + getRowWidth() - 16) openMenu(row.record(), click.x(), click.y());
                return true;
            }

            @Override
            public Text getNarration() {
                return Text.literal(label(row.record()));
            }
        }
    }

    public static String label(ContainerRecord r) {
        String type = r instanceof BlockRecord
                ? Registries.BLOCK.get(Identifier.of(r.type)).getName().getString()
                : Text.translatable(Registries.ENTITY_TYPE.get(Identifier.of(r.type)).getTranslationKey()).getString();
        if (r instanceof BlockRecord block && block.global && block.ownerName != null) return type + " (" + block.ownerName + ")";
        return r.name != null ? "\"" + r.name + "\" (" + type + ")" : type;
    }

    public static String where(ContainerRecord r) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<String> parts = new ArrayList<>();
        if (r instanceof BlockRecord block && block.global) {
            // Ender chest: no place or status. Another account's says which account to switch to.
            MemoryBank bank = EntityTrackerClient.banks().bank();
            if (bank != null && block.owner != null && !block.owner.equals(bank.currentPlayer)) {
                parts.add(Text.translatable("screen.entitytracker.other_account",
                        block.ownerName != null ? block.ownerName : block.owner.toString()).getString());
            }
        } else {
            String place = client.player != null && client.world != null
                    && r.dimension.equals(client.world.getRegistryKey().getValue().toString())
                    ? Math.round(Math.sqrt(client.player.squaredDistanceTo(r.x, r.y, r.z))) + "m"
                    : r.dimension.replace("minecraft:", "");
            if (r instanceof BlockRecord block) {
                place += " (" + block.pos.getX() + ", " + block.pos.getY() + ", " + block.pos.getZ() + ")";
            }
            parts.add(place);
            parts.add(Text.translatable("screen.entitytracker.status." + r.status.name().toLowerCase(Locale.ROOT)).getString());
        }
        parts.add(Text.translatable("screen.entitytracker.seen_ago", age(r.lastSeen)).getString());
        if (r.isTrashed()) parts.add(Text.translatable("screen.entitytracker.in_trash", age(r.trashedAt)).getString());
        return String.join("  -  ", parts);
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
    static ItemStack entityIcon(String type) {
        Identifier id = Identifier.of(type);
        Item item = Registries.ITEM.get(id);
        if (item == Items.AIR) item = Registries.ITEM.get(Identifier.of(id.getNamespace(), id.getPath() + "_spawn_egg"));
        if (item == Items.AIR) item = Items.CHEST;
        return new ItemStack(item);
    }

    static String age(long millis) {
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        if (s < 86400) return s / 3600 + "h";
        return s / 86400 + "d";
    }
}
