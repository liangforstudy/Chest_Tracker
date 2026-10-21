package io.github.liangforstudy.entitytracker;

import io.github.liangforstudy.entitytracker.command.DebugCommand;
import io.github.liangforstudy.entitytracker.data.BankStorage;
import io.github.liangforstudy.entitytracker.gui.OpenTriggers;
import io.github.liangforstudy.entitytracker.search.HeadAliases;
import io.github.liangforstudy.entitytracker.search.ItemIndex;
import io.github.liangforstudy.entitytracker.track.BankManager;
import io.github.liangforstudy.entitytracker.track.InteractionTracker;
import io.github.liangforstudy.entitytracker.track.PositionTracker;
import io.github.liangforstudy.entitytracker.track.ScreenBinder;
import io.github.liangforstudy.entitytracker.track.TradeBinder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntityTrackerClient implements ClientModInitializer {
    public static final String MOD_ID = "entitytracker";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static InteractionTracker interactions;
    private static BankManager banks;
    private static ScreenBinder screens;
    private static TradeBinder trades;
    private static final ItemIndex index = new ItemIndex();
    private static HeadAliases headAliases;

    /** For mixins, which can't be handed the instance. */
    public static InteractionTracker interactions() {
        return interactions;
    }

    public static ScreenBinder screens() {
        return screens;
    }

    public static TradeBinder trades() {
        return trades;
    }

    public static BankManager banks() {
        return banks;
    }

    public static ItemIndex index() {
        return index;
    }

    public static HeadAliases headAliases() {
        return headAliases;
    }

    @Override
    public void onInitializeClient() {
        banks = new BankManager(new BankStorage(FabricLoader.getInstance().getGameDir()));
        headAliases = new HeadAliases(FabricLoader.getInstance().getGameDir());
        interactions = new InteractionTracker();
        PositionTracker positions = new PositionTracker(banks, interactions);
        screens = new ScreenBinder(interactions, banks, positions);
        trades = new TradeBinder(interactions, banks, positions);

        positions.register();
        screens.register();
        trades.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            interactions.tick();
            banks.tick(client);
            positions.tick(client);
        });
        // DISCONNECT fires on the network thread; do the bank work on the game thread.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            interactions.clear();
            banks.close();
            index.clear();
        }));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> banks.close());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                DebugCommand.register(dispatcher, banks));

        OpenTriggers.register();

        LOGGER.info("Entity Container Tracker loaded");
    }
}
