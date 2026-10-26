package io.github.liangforstudy.entitytracker.gui;

import io.github.liangforstudy.entitytracker.data.ContainerRecord;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Converting positions between dimensions (CLAUDE.md 4.1e), like JourneyMap's nether/overworld
 * waypoints. The scale comes from each dimension type in the registry (nether 8, overworld 1;
 * modded and datapack dimensions bring their own). The End is never converted: its coordinates
 * don't correspond to anything in the other dimensions.
 */
public final class Dimensions {
    private Dimensions() {}

    public static @Nullable String current() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? null : client.level.dimension().identifier().toString();
    }

    /** Coordinate scale of a dimension, or NaN when unknown. */
    static double scale(String dimension) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) return Double.NaN;
        if (dimension.equals(current())) return client.level.dimensionType().coordinateScale();
        return client.level.registryAccess().lookup(Registries.DIMENSION_TYPE)
                .flatMap(r -> r.getOptional(Identifier.parse(dimension)))
                .map(DimensionType::coordinateScale)
                .orElse(dimension.equals("minecraft:the_nether") ? 8.0 : Double.NaN);
    }

    private static boolean isEnd(String dimension) {
        return dimension.equals("minecraft:the_end");
    }

    /**
     * The record's x/z expressed in the player's current dimension, or null when it is already
     * there or can't be converted.
     */
    public static double @Nullable [] inCurrent(ContainerRecord r) {
        String here = current();
        if (here == null || r.dimension.equals(here) || isEnd(here) || isEnd(r.dimension)) return null;
        double from = scale(r.dimension);
        double to = scale(here);
        if (Double.isNaN(from) || Double.isNaN(to) || to == 0) return null;
        double f = from / to;
        return new double[]{r.x * f, r.z * f};
    }

    /** Horizontal distance from the player to the converted position, or NaN. */
    public static double convertedDistance(ContainerRecord r) {
        Minecraft client = Minecraft.getInstance();
        double[] p = inCurrent(r);
        if (p == null || client.player == null) return Double.NaN;
        double dx = p[0] - client.player.getX();
        double dz = p[1] - client.player.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** "Nether", "Overworld", "End", or the dimension's own path for modded ones. */
    public static String displayName(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> "Overworld";
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:the_end" -> "End";
            default -> {
                String path = Identifier.parse(dimension).getPath().replace('_', ' ');
                yield path.isEmpty() ? dimension : path.substring(0, 1).toUpperCase(Locale.ROOT) + path.substring(1);
            }
        };
    }
}
