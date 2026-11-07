package io.github.liangforstudy.entitytracker.trace;

import io.github.liangforstudy.entitytracker.EntityTrackerClient;
import io.github.liangforstudy.entitytracker.data.BlockRecord;
import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import io.github.liangforstudy.entitytracker.data.EntityRecord;
import io.github.liangforstudy.entitytracker.data.InventoryRecord;
import io.github.liangforstudy.entitytracker.data.MemoryBank;
import io.github.liangforstudy.entitytracker.gui.Dimensions;
import io.github.liangforstudy.entitytracker.gui.TrackerScreen;
import io.github.liangforstudy.entitytracker.search.Tally;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.liangforstudy.entitytracker.mixin.client.GameRendererAccessor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lines from the crosshair to containers the player picked in the tracker (CLAUDE.md 4.7). Drawn
 * with the game's own gizmos (lines, boxes, floating text), through walls, from the camera, so
 * they work in freecam. Client-side only: nothing is sent to the server.
 * <ul>
 *   <li>a block: to its centre (a double chest's middle), with its outline;</li>
 *   <li>a loaded entity: to where it is this frame, following it as it moves;</li>
 *   <li>an entity that isn't loaded: dashed, to where it was last seen (a guess).</li>
 * </ul>
 * Only the current dimension gets lines; the rest are counted on screen. A container stops being
 * traced once it's opened again: you've found it.
 */
public final class Tracers {
    /** Lines drawn at once, nearest first. */
    public static final int MAX_LINES = 16;
    private static final int BLOCK = 0xFFFFFFFF;
    private static final int ANIMAL = 0xFFFFC040;
    private static final int VEHICLE = 0xFF40E0F0;
    private static final int MAYBE_GONE = 0xFFFF7060;
    /** Label scale grows with distance so the text keeps one size on screen. */
    private static final double LABEL_SCALE_PER_BLOCK = 0.06;

    /** Traced containers by key, with when they were picked. */
    private static final Map<String, Long> traced = new LinkedHashMap<>();
    private static boolean hidden;

    private Tracers() {}

    public static void add(Collection<? extends ContainerRecord> records) {
        long now = System.currentTimeMillis();
        for (ContainerRecord r : records) {
            if (!(r instanceof InventoryRecord)) traced.put(r.key(), now);
        }
        hidden = false;
    }

    public static void remove(ContainerRecord r) {
        traced.remove(r.key());
    }

    public static boolean isTraced(ContainerRecord r) {
        return traced.containsKey(r.key());
    }

    public static int count() {
        return traced.size();
    }

    public static void clear() {
        traced.clear();
    }

    public static boolean hidden() {
        return hidden;
    }

    /** Hides the lines without forgetting what's traced (the "Toggle tracers" key). */
    public static void toggle() {
        hidden = !hidden;
    }

    /** Drops containers opened since they were traced, gone from memory, or put in the trash. */
    public static void tick(@Nullable MemoryBank bank) {
        if (bank == null || traced.isEmpty()) return;
        traced.entrySet().removeIf(e -> {
            ContainerRecord r = bank.getByKey(e.getKey());
            return r == null || r.isTrashed() || r.lastOpened > e.getValue();
        });
    }

    private record Target(ContainerRecord record, Vec3 end, @Nullable AABB box, boolean guess, double distance) {}

    /**
     * Adds this frame's lines to the game's gizmos. Called right after the camera is placed for
     * the frame, so a line starts exactly at the crosshair even while turning.
     */
    public static void emit(Minecraft client, float partialTick) {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        if (hidden || traced.isEmpty() || bank == null || client.level == null || client.player == null) return;
        Camera camera = client.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;
        Vec3 eye = camera.position();
        Vec3 start = crosshair(client, camera, eye);

        String dimension = Dimensions.current();
        if (dimension == null) return;
        Map<UUID, Entity> loaded = loadedEntities(client, bank);
        List<Target> targets = new ArrayList<>();
        for (String key : traced.keySet()) {
            ContainerRecord r = bank.getByKey(key);
            if (r == null || !dimension.equals(r.dimension)) continue;
            targets.add(target(r, loaded, partialTick, eye));
        }
        targets.sort(Comparator.comparingDouble(Target::distance));

        //? if >=26.2 {
        /*try (var collection = client.levelExtractor.collectPerFrameMainThreadGizmos()) {
        *///?} else {
        try (var collection = client.levelRenderer.collectPerFrameGizmos()) {
        //?}
            for (int i = 0; i < Math.min(MAX_LINES, targets.size()); i++) draw(targets.get(i), start);
        }
    }

    /**
     * The point half a block in front of the eye that's drawn exactly under the crosshair. Walking
     * bobs the picture (and taking damage shakes it) without moving the camera, so a point straight
     * ahead of the camera would wobble around the crosshair: undo the game's own bob for it.
     */
    private static Vec3 crosshair(Minecraft client, Camera camera, Vec3 eye) {
        GameRendererAccessor renderer = (GameRendererAccessor) client.gameRenderer;
        PoseStack bob = new PoseStack();
        //? if >=26.2 {
        /*var state = renderer.entitytracker$gameRenderState().levelRenderState.cameraRenderState;
        renderer.entitytracker$bobHurt(state, bob);
        if (client.options.bobView().get()) renderer.entitytracker$bobView(state, bob);
        *///?} else {
        float partialTick = camera.getPartialTickTime();
        renderer.entitytracker$bobHurt(bob, partialTick);
        if (client.options.bobView().get()) renderer.entitytracker$bobView(bob, partialTick);
        //?}
        // On screen = projection × bob × view: the view-space point the bob moves to (0, 0, -0.5).
        Vector3f view = new Matrix4f(bob.last().pose()).invert().transformPosition(new Vector3f(0, 0, -0.5f));
        camera.rotation().transform(view);
        return eye.add(view.x(), view.y(), view.z());
    }

    private static Map<UUID, Entity> loadedEntities(Minecraft client, MemoryBank bank) {
        Map<UUID, Entity> out = new HashMap<>();
        boolean anyEntity = false;
        for (String key : traced.keySet()) {
            if (bank.getByKey(key) instanceof EntityRecord) anyEntity = true;
        }
        if (!anyEntity) return out;
        for (Entity e : client.level.entitiesForRendering()) {
            if (!e.isRemoved() && traced.containsKey(e.getUUID().toString())) out.put(e.getUUID(), e);
        }
        return out;
    }

    private static Target target(ContainerRecord r, Map<UUID, Entity> loaded, float partialTick, Vec3 eye) {
        if (r instanceof EntityRecord e && loaded.get(e.uuid) instanceof Entity entity) {
            Vec3 at = entity.getPosition(partialTick);
            AABB box = entity.getBoundingBox().move(at.subtract(entity.position()));
            Vec3 end = at.add(0, entity.getBbHeight() / 2, 0);
            return new Target(r, end, box, false, end.distanceTo(eye));
        }
        if (r instanceof BlockRecord b && b.pos != null) {
            AABB box = new AABB(b.pos);
            for (BlockPos other : b.otherPositions) box = box.minmax(new AABB(other));
            Vec3 end = box.getCenter();
            return new Target(r, end, box, false, end.distanceTo(eye));
        }
        // An entity out of sight: where it was last seen.
        Vec3 end = new Vec3(r.x, r.y + 0.5, r.z);
        return new Target(r, end, null, true, end.distanceTo(eye));
    }

    private static int color(ContainerRecord r) {
        if (Tally.maybeGone(r)) return MAYBE_GONE;
        if (r instanceof BlockRecord) return BLOCK;
        String type = r.type == null ? "" : r.type;
        return type.endsWith("donkey") || type.endsWith("mule") || type.endsWith("llama") || type.endsWith("horse")
                || type.endsWith("villager") || type.endsWith("trader") ? ANIMAL : VEHICLE;
    }

    private static void draw(Target t, Vec3 start) {
        int color = color(t.record());
        if (t.guess()) {
            dashed(start, t.end(), color & 0xB0FFFFFF);
        } else {
            Gizmos.line(start, t.end(), color, 2f).setAlwaysOnTop();
        }
        if (t.box() != null) Gizmos.cuboid(t.box().inflate(0.02), GizmoStyle.stroke(color, 2f)).setAlwaysOnTop();
        // Floating label over the target, about name tag size on screen at any distance.
        String text = TrackerScreen.label(t.record()) + " · " + Math.round(t.distance()) + " m";
        float scale = (float) Math.max(TextGizmo.Style.DEFAULT_SCALE, t.distance() * LABEL_SCALE_PER_BLOCK);
        Vec3 labelAt = t.end().add(0, (t.box() != null ? t.box().getYsize() / 2 : 0) + 0.3 * scale, 0);
        Gizmos.billboardText(text, labelAt, TextGizmo.Style.forColorAndCentered(color).withScale(scale)).setAlwaysOnTop();
    }

    /** A dashed line: about 20 dashes whatever the distance, at least half a block each. */
    private static void dashed(Vec3 from, Vec3 to, int color) {
        double length = from.distanceTo(to);
        if (length < 1e-3) return;
        double dash = Math.max(0.5, length / 40);
        Vec3 dir = to.subtract(from).scale(1 / length);
        for (double d = 0; d < length; d += dash * 2) {
            Vec3 a = from.add(dir.scale(d));
            Vec3 b = from.add(dir.scale(Math.min(length, d + dash)));
            Gizmos.line(a, b, color, 2f).setAlwaysOnTop();
        }
    }

    /**
     * Traced containers in other dimensions can't get a line: say where they are instead, top
     * left ("In the Nether: 2 traced").
     */
    public static void renderHud(GuiGraphics context) {
        MemoryBank bank = EntityTrackerClient.banks().bank();
        Minecraft client = Minecraft.getInstance();
        if (hidden || traced.isEmpty() || bank == null || client.level == null) return;
        String here = Dimensions.current();
        if (here == null) return;
        Map<String, Integer> elsewhere = new LinkedHashMap<>();
        for (String key : traced.keySet()) {
            ContainerRecord r = bank.getByKey(key);
            if (r != null && !here.equals(r.dimension)) elsewhere.merge(r.dimension, 1, Integer::sum);
        }
        int y = 4;
        for (Map.Entry<String, Integer> e : elsewhere.entrySet()) {
            context.drawString(client.font, Component.translatable("screen.entitytracker.tracers.elsewhere",
                    Dimensions.displayName(e.getKey()), e.getValue()), 4, y, 0xFFFFE08A);
            y += 10;
        }
    }
}
