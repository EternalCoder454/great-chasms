package com.zachary.greatchasms;

import com.mojang.brigadier.context.CommandContext;
import com.zachary.greatchasms.chasm.ChasmCarver;
import com.zachary.greatchasms.chasm.ChasmField;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.concurrent.CompletableFuture;

/**
 * {@code /greatchasms locate} and {@code /greatchasms info}.
 * <p>
 * A chasm cannot be registered with vanilla {@code /locate}, which only knows about structures,
 * biomes and points of interest. It does not need to be: because the chasm field is a pure function
 * of the world seed, the nearest chasm can be found analytically without generating, loading or even
 * touching a single chunk. That makes this search far cheaper than a structure locate, and it works
 * just as well in terrain nobody has ever visited.
 */
public final class ChasmCommands {

    /** Coarse grid spacing for the search. Kept at or below the narrowest possible chasm half width
     *  so a chasm cannot slip between two samples. */
    private static final int MIN_SEARCH_STEP = 16;

    /** How far above the rim the teleport puts you. Chasms are hundreds of blocks wide, so landing
     *  at rim level shows you a wall; this shows you the chasm. */
    private static final int VIEWING_HEIGHT = 90;

    /** Compass bearing from the player to the target, using Minecraft's axes: +X is east, +Z south. */
    private static String bearing(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return "away";
        }
        // Rotated so 0 degrees is south (+Z), matching how the F3 facing readout reads.
        double angle = Math.toDegrees(Math.atan2(-dx, dz));
        String[] points = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        int index = (int) Math.round(((angle % 360) + 360) % 360 / 45.0) % 8;
        return points[index];
    }

    private ChasmCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("greatchasms")
                        .then(Commands.literal("locate").executes(ChasmCommands::locate))
                        .then(Commands.literal("info").executes(ChasmCommands::info)));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        Vec3 at = source.getPosition();
        int x = Mth.floor(at.x);
        int z = Mth.floor(at.z);

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        int seaLevel = generator.getSeaLevel();
        int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
        double ocean = ChasmField.oceanFactor(floor, seaLevel);

        ChasmField field = ChasmField.forSeed(level.getSeed());
        ChasmField.Column col = new ChasmField.Column();
        field.sample(x, z, ocean, col);

        boolean applies = ChasmCarver.appliesTo(level.dimension());

        source.sendSuccess(() -> Component.literal("Great Chasms at " + x + ", " + z)
                .withStyle(ChatFormatting.GOLD), false);
        line(source, "config file loaded", String.valueOf(ChasmConfig.isLoaded()));
        line(source, "active in this dimension", String.valueOf(applies));
        line(source, "sea floor / sea level", floor + " / " + seaLevel);
        line(source, "ocean factor", String.format("%.2f", ocean));
        line(source, "distance to centreline", col.distance == Double.MAX_VALUE
                ? "none" : String.format("%.1f blocks", col.distance));
        line(source, "region gate / width taper", String.format("%.3f / %.3f", col.regionFactor, col.taper));
        line(source, "chasm width here", col.halfWidth <= 0.5
                ? "no chasm (region gate closed)" : String.format("%.0f blocks across", col.halfWidth * 2.0));
        line(source, "inside a chasm", String.valueOf(col.halfWidth > 0.5 && col.distance < col.halfWidth));
        line(source, "spacing / rarity / oceanBias", String.format("%.0f / %.2f / %.2f",
                ChasmConfig.chasmSpacing(), ChasmConfig.rarity(), ChasmConfig.oceanBias()));
        return 1;
    }

    private static void line(CommandSourceStack source, String label, String value) {
        source.sendSuccess(() -> Component.literal("  " + label + ": ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)), false);
    }

    private static int locate(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();

        if (!ChasmCarver.appliesTo(level.dimension())) {
            source.sendFailure(Component.literal("Great Chasms does not generate in this dimension."));
            return 0;
        }

        Vec3 at = source.getPosition();
        int originX = Mth.floor(at.x);
        int originZ = Mth.floor(at.z);
        long seed = level.getSeed();

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        int seaLevel = generator.getSeaLevel();
        int radius = ChasmConfig.searchRadius();

        source.sendSuccess(() -> Component.literal("Searching up to " + radius + " blocks for a chasm...")
                .withStyle(ChatFormatting.GRAY), false);

        // Run off the server thread. The coarse pass is pure arithmetic, and getBaseHeight is
        // already called off-thread by structure placement, so neither needs the main thread. A
        // wide search would otherwise be a visible tick hitch.
        CompletableFuture.supplyAsync(() -> search(seed, originX, originZ, radius, generator, level, randomState, seaLevel))
                .whenComplete((result, error) -> level.getServer().execute(() -> {
                    if (error != null) {
                        GreatChasms.LOGGER.error("Chasm search failed", error);
                        source.sendFailure(Component.literal("Chasm search failed, see the log."));
                        return;
                    }
                    if (result == null) {
                        source.sendFailure(Component.literal(
                                "No chasm within " + radius + " blocks. Lower 'rarity' in the config,"
                                        + " or raise 'searchRadius'."));
                        return;
                    }
                    int dx = result.x - originX;
                    int dz = result.z - originZ;
                    int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);

                    // Land the player well above the rim rather than at their current Y, which would
                    // often be inside the wall. High enough to actually see the thing they asked for.
                    int ground = generator.getBaseHeight(result.x, result.z,
                            Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
                    int tpY = Math.max(ground, seaLevel) + VIEWING_HEIGHT;
                    String tpCommand = "/tp @s " + result.x + " " + tpY + " " + result.z;

                    Component teleport = Component.literal("[Teleport]")
                            .withStyle(s -> s.withColor(ChatFormatting.AQUA)
                                    .withBold(true)
                                    .withClickEvent(new ClickEvent.RunCommand(tpCommand))
                                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                            "Teleport to " + result.x + ", " + tpY + ", " + result.z
                                                    + "\n" + VIEWING_HEIGHT + " blocks above the rim, looking down into it"))));

                    Component coords = Component.literal(result.x + ", " + tpY + ", " + result.z)
                            .withStyle(s -> s.withColor(ChatFormatting.GOLD)
                                    .withClickEvent(new ClickEvent.CopyToClipboard(result.x + " " + tpY + " " + result.z))
                                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy coordinates"))));

                    source.sendSuccess(() -> Component.literal("Chasm ")
                            .withStyle(ChatFormatting.GREEN)
                            .append(coords)
                            .append(Component.literal("  " + dist + " blocks " + bearing(dx, dz)
                                    + ", about " + Math.round(result.halfWidth * 2) + " wide there  ")
                                    .withStyle(ChatFormatting.GRAY))
                            .append(teleport), false);
                }));
        return 1;
    }

    private record Hit(int x, int z, double halfWidth) {
    }

    private static Hit search(long seed, int originX, int originZ, int radius,
                              ChunkGenerator generator, ServerLevel level,
                              RandomState randomState, int seaLevel) {
        ChasmField field = ChasmField.forSeed(seed);
        ChasmField.Column col = new ChasmField.Column();

        int step = Math.max(MIN_SEARCH_STEP, ChasmConfig.minWidth() / 2);
        double coarseLimit = field.maxPossibleHalfWidth() + field.wallAmplitude();

        // Expanding square rings, so the first hit found is the nearest one to within one ring.
        for (int r = 0; r <= radius; r += step) {
            Hit hit = scanRing(field, col, originX, originZ, r, step, coarseLimit,
                    generator, level, randomState, seaLevel);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Hit scanRing(ChasmField field, ChasmField.Column col, int originX, int originZ,
                                int r, int step, double coarseLimit, ChunkGenerator generator,
                                ServerLevel level, RandomState randomState, int seaLevel) {
        if (r == 0) {
            return test(field, col, originX, originZ, coarseLimit, generator, level, randomState, seaLevel);
        }
        for (int offset = -r; offset <= r; offset += step) {
            Hit hit = test(field, col, originX + offset, originZ - r, coarseLimit, generator, level, randomState, seaLevel);
            if (hit != null) return hit;
            hit = test(field, col, originX + offset, originZ + r, coarseLimit, generator, level, randomState, seaLevel);
            if (hit != null) return hit;
            hit = test(field, col, originX - r, originZ + offset, coarseLimit, generator, level, randomState, seaLevel);
            if (hit != null) return hit;
            hit = test(field, col, originX + r, originZ + offset, coarseLimit, generator, level, randomState, seaLevel);
            if (hit != null) return hit;
        }
        return null;
    }

    private static Hit test(ChasmField field, ChasmField.Column col, int x, int z, double coarseLimit,
                            ChunkGenerator generator, ServerLevel level, RandomState randomState, int seaLevel) {
        // Coarse pass first: five noise evaluations and no Minecraft API at all. Only the tiny
        // fraction of points near a centreline pay for a terrain height sample.
        if (field.distanceToCentreline(x, z) > coarseLimit) {
            return null;
        }
        int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
        field.sample(x, z, ChasmField.oceanFactor(floor, seaLevel), col);
        if (col.halfWidth > 0.5 && col.distance < col.halfWidth) {
            return new Hit(x, z, col.halfWidth);
        }
        return null;
    }
}
