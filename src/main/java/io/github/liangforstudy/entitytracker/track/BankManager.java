package io.github.liangforstudy.entitytracker.track;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.Settings;
import io.github.liangforstudy.entitytracker.data.BankStorage;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Opens the bank for the current server once the player exists, saves it after changes, and
 * closes it on disconnect. Respawns and dimension changes never touch it.
 */
public final class BankManager {
    private final BankStorage storage;
    private @Nullable MemoryBank bank;
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

    public void tick(MinecraftClient client) {
        if (bank == null && client.player != null && client.world != null) open(client);
        if (bank != null && ++ticks % 1200 == 0) bank.markPlayedToday(); // also catches playing past midnight
        if (bank != null && bank.tickDebounce(Settings.saveDebounceTicks)) storage.save(bank);
        if (pendingWarning != null && client.player != null) {
            client.player.sendMessage(Text.literal(pendingWarning).formatted(Formatting.RED), false);
            pendingWarning = null;
        }
    }

    private void open(MinecraftClient client) {
        String address = serverName(client);
        String server = storage.resolveServer(address);
        var result = storage.load(server);
        bank = result.bank();
        UUID me = client.player.getUuid();
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

    private static String serverName(MinecraftClient client) {
        if (client.isIntegratedServerRunning() && client.getServer() != null) {
            return "singleplayer_" + client.getServer().getSaveProperties().getLevelName();
        }
        ServerInfo info = client.getCurrentServerEntry();
        if (info != null) return info.address;
        return "unknown_server";
    }

    public void close() {
        if (bank != null) {
            if (bank.isDirty()) storage.save(bank);
            EntityTrackerClient.LOGGER.info("Closed bank {}", bank.key);
        }
        bank = null;
        pendingWarning = null;
    }

    public void saveNow() {
        if (bank != null) storage.save(bank);
    }
}
