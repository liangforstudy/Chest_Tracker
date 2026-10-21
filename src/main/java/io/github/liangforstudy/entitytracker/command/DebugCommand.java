package io.github.liangforstudy.entitytracker.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.search.HeadInfo;
import io.github.liangforstudy.entitytracker.search.ItemIndex;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.InventoryRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.gui.AreaConfirmScreen;
import io.github.liangforstudy.entitytracker.gui.OpenTriggers;
import io.github.liangforstudy.entitytracker.gui.TrackerScreen;
import io.github.liangforstudy.entitytracker.gui.TradesScreen;
import io.github.liangforstudy.entitytracker.track.BankManager;
import io.github.liangforstudy.entitytracker.track.Trash;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Client-only {@code /etracker} command for testing until there is a real UI. Sends nothing to
 * the server.
 */
public final class DebugCommand {
    private DebugCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, BankManager banks) {
        dispatcher.register(literal("etracker")
                .executes(ctx -> {
                    OpenTriggers.openLater();
                    return 1;
                })
                .then(literal("trades").executes(ctx -> {
                    OpenTriggers.openLater(new io.github.liangforstudy.entitytracker.gui.TradesScreen());
                    return 1;
                }))
                .then(literal("bank").executes(ctx -> {
                    MemoryBank bank = banks.bank();
                    if (bank == null) return fail(ctx.getSource(), "No bank open");
                    ctx.getSource().sendFeedback(Text.literal("Bank " + bank.key + ": " + bank.size()
                            + " containers, file " + banks.storage().bankFile(bank.key)));
                    return 1;
                }))
                .then(literal("area")
                        .then(literal("here").then(argument("radius", IntegerArgumentType.integer(1, 512)).executes(ctx -> {
                            var player = MinecraftClient.getInstance().player;
                            var world = MinecraftClient.getInstance().world;
                            if (player == null || world == null) return fail(ctx.getSource(), "Not in a world");
                            int r = IntegerArgumentType.getInteger(ctx, "radius");
                            BlockPos p = player.getBlockPos();
                            return openArea(ctx.getSource(), banks, Trash.Area.of(
                                    new BlockPos(p.getX() - r, world.getBottomY(), p.getZ() - r),
                                    new BlockPos(p.getX() + r, world.getTopYInclusive(), p.getZ() + r)));
                        })))
                        .then(argument("x1", IntegerArgumentType.integer()).then(argument("y1", IntegerArgumentType.integer())
                                .then(argument("z1", IntegerArgumentType.integer()).then(argument("x2", IntegerArgumentType.integer())
                                        .then(argument("y2", IntegerArgumentType.integer()).then(argument("z2", IntegerArgumentType.integer())
                                                .executes(ctx -> openArea(ctx.getSource(), banks, Trash.Area.of(
                                                        new BlockPos(IntegerArgumentType.getInteger(ctx, "x1"),
                                                                IntegerArgumentType.getInteger(ctx, "y1"),
                                                                IntegerArgumentType.getInteger(ctx, "z1")),
                                                        new BlockPos(IntegerArgumentType.getInteger(ctx, "x2"),
                                                                IntegerArgumentType.getInteger(ctx, "y2"),
                                                                IntegerArgumentType.getInteger(ctx, "z2"))))))))))))
                .then(literal("heads").executes(ctx -> {
                    MemoryBank bank = banks.bank();
                    if (bank == null) return fail(ctx.getSource(), "No bank open");
                    java.util.Map<String, java.util.Set<String>> byTexture = new java.util.LinkedHashMap<>();
                    for (ContainerRecord r : bank.allRecords().toList()) {
                        for (ItemIndex.IndexedItem item : EntityTrackerClient.index().items(r)) {
                            HeadInfo head = HeadInfo.of(item.stack());
                            if (head == null) continue;
                            String key = head.textureHash() != null ? head.textureHash() : "(no texture)";
                            byTexture.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>())
                                    .add(item.stack().getName().getString()
                                            + (head.profileName() != null ? " [" + head.profileName() + "]" : ""));
                        }
                    }
                    ctx.getSource().sendFeedback(Text.literal(byTexture.size() + " head textures:"));
                    byTexture.forEach((hash, names) -> {
                        String alias = EntityTrackerClient.headAliases().get(hash);
                        ctx.getSource().sendFeedback(Text.literal("- " + (hash.length() > 12 ? hash.substring(0, 12) : hash)
                                + (alias != null ? " \"" + alias + "\"" : "") + ": " + String.join(", ", names)));
                    });
                    return byTexture.size();
                }))
                .then(literal("alias").then(argument("hash", StringArgumentType.word())
                        .then(argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            String prefix = StringArgumentType.getString(ctx, "hash");
                            String name = StringArgumentType.getString(ctx, "name");
                            MemoryBank bank = banks.bank();
                            if (bank == null) return fail(ctx.getSource(), "No bank open");
                            // Accept the short hash /etracker heads prints.
                            String full = null;
                            for (ContainerRecord r : bank.allRecords().toList()) {
                                for (ItemIndex.IndexedItem item : EntityTrackerClient.index().items(r)) {
                                    HeadInfo head = HeadInfo.of(item.stack());
                                    if (head != null && head.textureHash() != null && head.textureHash().startsWith(prefix)) full = head.textureHash();
                                }
                            }
                            if (full == null) return fail(ctx.getSource(), "No remembered head texture starts with " + prefix);
                            EntityTrackerClient.headAliases().set(full, name);
                            EntityTrackerClient.index().clear();
                            ctx.getSource().sendFeedback(Text.literal("Head texture " + full.substring(0, Math.min(12, full.length())) + " is now \"" + name + "\""));
                            return 1;
                        }))))
                .then(literal("undo").executes(ctx -> {
                    MemoryBank bank = banks.bank();
                    if (bank == null) return fail(ctx.getSource(), "No bank open");
                    int n = Trash.undoArea(bank);
                    ctx.getSource().sendFeedback(Text.literal("Undo: restored " + n + " containers from the trash"));
                    return n;
                }))
                .then(literal("trash").then(literal("restoreall").executes(ctx -> {
                    MemoryBank bank = banks.bank();
                    if (bank == null) return fail(ctx.getSource(), "No bank open");
                    List<ContainerRecord> trashed = bank.allRecords().filter(ContainerRecord::isTrashed).toList();
                    for (ContainerRecord r : trashed) Trash.restore(bank, r);
                    ctx.getSource().sendFeedback(Text.literal("Restored " + trashed.size() + " containers from the trash"));
                    return trashed.size();
                })).executes(ctx -> {
                    MemoryBank bank = banks.bank();
                    if (bank == null) return fail(ctx.getSource(), "No bank open");
                    List<ContainerRecord> trashed = bank.allRecords().filter(ContainerRecord::isTrashed).toList();
                    ctx.getSource().sendFeedback(Text.literal(trashed.size() + " in trash:"));
                    for (ContainerRecord r : trashed) ctx.getSource().sendFeedback(Text.literal(describe(r)));
                    return trashed.size();
                }))
                .then(literal("list").executes(ctx -> {
                    MemoryBank bank = banks.bank();
                    if (bank == null) return fail(ctx.getSource(), "No bank open");
                    var player = MinecraftClient.getInstance().player;
                    List<ContainerRecord> records = bank.containers()
                            .sorted(Comparator.comparingDouble(r -> player == null ? 0
                                    : player.squaredDistanceTo(r.x, r.y, r.z)))
                            .toList();
                    ctx.getSource().sendFeedback(Text.literal(records.size() + " remembered containers:"));
                    for (ContainerRecord r : records) ctx.getSource().sendFeedback(Text.literal(describe(r)));
                    return records.size();
                }))
                .then(searchNode(banks, "search"))
                // Testing only (dev client): open a trader's detail view (test tools can't click list rows).
                .then(literal("debug").requires(src -> FabricLoader.getInstance().isDevelopmentEnvironment())
                        .then(literal("trade").then(argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            MemoryBank bank = banks.bank();
                            if (bank == null) return fail(ctx.getSource(), "No bank open");
                            String name = StringArgumentType.getString(ctx, "name").toLowerCase(java.util.Locale.ROOT);
                            var trader = bank.traders().filter(r -> io.github.liangforstudy.entitytracker.gui.TradesScreen.label(r)
                                    .toLowerCase(java.util.Locale.ROOT).contains(name)).findFirst().orElse(null);
                            if (trader == null) return fail(ctx.getSource(), "No trader matches");
                            OpenTriggers.openLater(new io.github.liangforstudy.entitytracker.gui.TradeDetailScreen(trader,
                                    new io.github.liangforstudy.entitytracker.gui.TradesScreen(), ""));
                            return 1;
                        }))))
                // Testing only (dev client): pretend containers within 8 blocks were last opened N days ago.
                .then(literal("debug").requires(src -> FabricLoader.getInstance().isDevelopmentEnvironment()).then(literal("age").then(argument("days", IntegerArgumentType.integer(0, 365)).executes(ctx -> {
                    MemoryBank bank = banks.bank();
                    var player = MinecraftClient.getInstance().player;
                    if (bank == null || player == null) return fail(ctx.getSource(), "No bank open");
                    int days = IntegerArgumentType.getInteger(ctx, "days");
                    long shift = days * 86_400_000L;
                    // Pretend the player was on this server on each of those days.
                    for (int i = 1; i <= days; i++) bank.playDays.add(java.time.LocalDate.now().minusDays(i).toString());
                    int n = 0;
                    for (ContainerRecord r : bank.allRecords().toList()) {
                        if (r.hasSnapshot() && player.squaredDistanceTo(r.x, r.y, r.z) <= 64) {
                            r.lastOpened = System.currentTimeMillis() - shift;
                            n++;
                        }
                    }
                    bank.markDirty();
                    ctx.getSource().sendFeedback(Text.literal("Aged " + n + " containers"));
                    return n;
                }))))
                .then(literal("save").executes(ctx -> {
                    banks.saveNow();
                    ctx.getSource().sendFeedback(Text.literal("Saved"));
                    return 1;
                })));

        // Short alias. Client commands are handled before anything is sent, so a server /search
        // can't receive it by accident; another client mod's /search would clash, though.
        dispatcher.register(searchNode(banks, "search"));
        // /offers [text]: the Trades screen, optionally already searching.
        dispatcher.register(literal("offers")
                .executes(ctx -> {
                    OpenTriggers.openLater(new TradesScreen());
                    return 1;
                })
                .then(argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                    OpenTriggers.openLater(new TradesScreen(StringArgumentType.getString(ctx, "text")));
                    return 1;
                })));
    }

    private static String describe(ContainerRecord r) {
        var player = MinecraftClient.getInstance().player;
        String dist = player == null ? "?" : String.valueOf(Math.round(Math.sqrt(player.squaredDistanceTo(r.x, r.y, r.z))));
        String label = r.name != null ? "\"" + r.name + "\" " + shortType(r.type) : shortType(r.type);
        String contents = r.hasSnapshot()
                ? r.items.stream().map(DebugCommand::stackSummary).collect(Collectors.joining(", "))
                : "never opened";
        if (r instanceof InventoryRecord) return "- On me: " + contents;
        String place = r instanceof EntityRecord e ? "home chunk " + e.homeChunkX + "," + e.homeChunkZ
                : r instanceof BlockRecord b && b.global ? "ender chest"
                : "at " + ((BlockRecord) r).pos.toShortString()
                        + (((BlockRecord) r).otherPositions.isEmpty() ? "" : " (double)");
        boolean reappeared = r instanceof EntityRecord e && e.reappeared;
        return String.format("- %s [%s] %sm %s, %s, seen %s ago, opened %s | %s%s",
                label, r.status, dist, r.dimension.replace("minecraft:", ""), place,
                age(r.lastSeen), r.hasSnapshot() ? age(r.lastOpened) + " ago" : "never",
                contents.isEmpty() ? "empty" : contents, (reappeared ? " (reappeared)" : "")
                        + (r.isTrashed() ? " (in trash " + age(r.trashedAt) + ")" : "")
                        + warningSuffix(r));
    }

    /**
     * Also registered as plain /search. Results are nearest first. {@code radius} > 0 limits it to
     * containers within that many blocks in this dimension (/search nearby); an empty text then
     * lists those containers instead of items.
     */
    private static int search(FabricClientCommandSource source, BankManager banks, String text, int radius) {
        MemoryBank bank = banks.bank();
        if (bank == null) return fail(source, "No bank open");
        String query = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (query.startsWith("@")) query = query.substring(1);
        var player = MinecraftClient.getInstance().player;
        var world = MinecraftClient.getInstance().world;
        String dimension = world == null ? "" : world.getRegistryKey().getValue().toString();
        int hits = 0;
        List<ContainerRecord> records = java.util.stream.Stream.concat(
                        java.util.stream.Stream.ofNullable(InventoryRecord.current()), // "On me" first
                        bank.containers().filter(r -> !r.isTrashed()))
                .filter(r -> radius <= 0 || (player != null && r.dimension.equals(dimension)
                        && !(r instanceof BlockRecord b && b.global)
                        && player.squaredDistanceTo(r.x, r.y, r.z) <= (double) radius * radius))
                .sorted(Comparator.comparingDouble(r -> player == null ? 0 : player.squaredDistanceTo(r.x, r.y, r.z)))
                .toList();
        if (query.isEmpty()) {
            source.sendFeedback(Text.literal(records.size() + " containers within " + radius + " blocks:"));
            for (ContainerRecord r : records) source.sendFeedback(Text.literal(describe(r)));
            return records.size();
        }
        for (ContainerRecord r : records) {
            for (ItemIndex.IndexedItem item : EntityTrackerClient.index().items(r)) {
                if (!item.searchText().contains(query)) continue;
                hits++;
                String path = String.join(" > ", item.path());
                source.sendFeedback(Text.literal("- " + item.stack().getCount() + "x "
                        + item.stack().getName().getString() + " in " + TrackerScreen.title(r)
                        + (path.isEmpty() ? "" : " > " + path) + "  -  " + TrackerScreen.where(r) + warningSuffix(r)));
            }
        }
        source.sendFeedback(Text.literal(hits + " matches for \"" + query + "\""
                + (radius > 0 ? " within " + radius + " blocks" : "")));
        return hits;
    }

    /** search <text> | search nearby <radius> [text] */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> searchNode(BankManager banks, String name) {
        return literal(name)
                .then(literal("nearby").then(argument("radius", IntegerArgumentType.integer(1, 4096))
                        .executes(ctx -> search(ctx.getSource(), banks, "", IntegerArgumentType.getInteger(ctx, "radius")))
                        .then(argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> search(ctx.getSource(), banks, StringArgumentType.getString(ctx, "text"),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> search(ctx.getSource(), banks, StringArgumentType.getString(ctx, "text"), 0)));
    }

    private static int openArea(FabricClientCommandSource source, BankManager banks, Trash.Area area) {
        MemoryBank bank = banks.bank();
        var world = MinecraftClient.getInstance().world;
        if (bank == null || world == null) return fail(source, "No bank open");
        List<ContainerRecord> found = Trash.inArea(bank, world.getRegistryKey().getValue().toString(), area);
        if (found.isEmpty()) return fail(source, "No remembered containers in that area");
        OpenTriggers.openLater(new AreaConfirmScreen(found, area));
        return found.size();
    }

    private static String warningSuffix(ContainerRecord r) {
        List<String> w = TrackerScreen.warnings(r);
        return w.isEmpty() ? "" : "  [" + String.join("; ", w) + "]";
    }

    private static String stackSummary(ContainerRecord.SlotItem item) {
        if (!item.stack().isJsonObject()) return "?";
        JsonObject o = item.stack().getAsJsonObject();
        String id = o.has("id") ? o.get("id").getAsString().replace("minecraft:", "") : "?";
        int count = o.has("count") ? o.get("count").getAsInt() : 1;
        return id + " x" + count;
    }

    private static String shortType(String type) {
        return type.replace("minecraft:", "");
    }

    private static String age(long millis) {
        long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return s / 60 + "m";
        if (s < 86400) return s / 3600 + "h";
        return s / 86400 + "d";
    }

    private static int fail(FabricClientCommandSource source, String message) {
        source.sendError(Text.literal(message));
        return 0;
    }
}
