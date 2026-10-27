package io.github.liangforstudy.entitytracker.track;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.Settings;
import io.github.liangforstudy.entitytracker.data.BankStorage;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.gui.Messages;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/**
 * Opens the bank for the current server once the player exists, saves it after changes, and
 * closes it on disconnect. Respawns and dimension changes never touch it.
 */
public final class BankManager {
    private final BankStorage storage;
    private @Nullable MemoryBank bank;
    /**
     * Loads and writes run here, one at a time and in order, so reading or writing a big bank
     * never freezes the game, and a reconnect can't read a file that is still being written.
     */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entitytracker-io");
        t.setDaemon(true);
        return t;
    });
    private @Nullable Future<BankStorage.LoadResult> loading;
    private @Nullable String loadingAddress;
    private @Nullable Future<?> lastWrite;
    private @Nullable String pendingWarning;
    private long ticks;

    public BankManager(BankStorage storage) {
        this.storage = storage;
    }

    public @Nullable MemoryBank bank() {
        return bank;
    }

    public BankStorage storage() {
        return storage;
    }

    public void tick(Minecraft client) {
        if (bank == null && loading == null && client.player != null && client.level != null) startLoading(client);
        if (loading != null && loading.isDone() && client.player != null) open(client);
        if (bank != null && ++ticks % 1200 == 0) bank.markPlayedToday(); // also catches playing past midnight
        if (bank != null && bank.tickDebounce(Settings.saveDebounceTicks)) saveInBackground();
        if (pendingWarning != null && client.player != null) {
            Messages.chat(Component.literal(pendingWarning).withStyle(ChatFormatting.RED));
            pendingWarning = null;
        }
    }

    /** True while the bank is being read from disk (the screens show "Loading memory..."). */
    public boolean isLoading() {
        return loading != null;
    }

    private void startLoading(Minecraft client) {
        loadingAddress = serverName(client);
        String server = storage.resolveServer(loadingAddress);
        loading = io.submit(() -> storage.load(server));
    }

    private void open(Minecraft client) {
        String address = loadingAddress;
        BankStorage.LoadResult result;
        try {
            result = loading.get();
        } catch (Exception e) {
            EntityTrackerClient.LOGGER.error("Loading the bank failed", e);
            loading = null;
            return;
        }
        loading = null;
        bank = result.bank();
        UUID me = client.player.getUUID();
        String myName = client.player.getName().getString();
        bank.currentPlayer = me;
        bank.markPlayedToday();
        if (!myName.equals(bank.accounts.put(me, myName))) bank.markDirty();
        // Ender chests carried over from old per-account banks only know the account's UUID.
        for (BlockRecord r : bank.allBlocks()) {
            if (r.global && r.owner != null && bank.accounts.containsKey(r.owner)
                    && !bank.accounts.get(r.owner).equals(r.ownerName)) {
                r.ownerName = bank.accounts.get(r.owner);
                bank.markDirty();
            }
        }
        pendingWarning = result.warning();
        Trash.purgeExpired(bank);
        EntityTrackerClient.LOGGER.info("Using bank {} for address {} ({}) at {} with {} entities",
                bank.key, address, result.reason(), storage.bankFile(bank.key), bank.size());
    }

    private static String serverName(Minecraft client) {
        if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
            return "singleplayer_" + client.getSingleplayerServer().getWorldData().getLevelName();
        }
        ServerData info = client.getCurrentServer();
        if (info != null) return info.ip;
        return "unknown_server";
    }

    /** Snapshot now (game thread), write in the background. */
    private void saveInBackground() {
        MemoryBank b = bank;
        var root = storage.snapshot(b);
        b.clearDirty();
        int size = b.size();
        lastWrite = io.submit(() -> {
            // A failed write marks the bank dirty again so the next save retries.
            if (!storage.write(b.key, root, size)) Minecraft.getInstance().execute(b::markDirty);
        });
    }

    /** On disconnect: save in the background (the next load waits for it in the same queue). */
    public void close() {
        if (bank != null) {
            if (bank.isDirty()) saveInBackground();
            EntityTrackerClient.LOGGER.info("Closed bank {}", bank.key);
        }
        if (loading != null) loading.cancel(false);
        loading = null;
        bank = null;
        pendingWarning = null;
    }

    /** On game exit: save and wait until everything is on disk. */
    public void closeAndWait() {
        close();
        Future<?> last = io.submit(() -> {});
        try {
            last.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            EntityTrackerClient.LOGGER.error("Waiting for the bank to be written failed", e);
        }
    }

    public void saveNow() {
        if (bank != null) saveInBackground();
    }
}
