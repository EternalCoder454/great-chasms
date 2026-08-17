package com.zachary.greatchasms.chasm;

import com.zachary.greatchasms.ChasmConfig;
import com.zachary.greatchasms.GreatChasms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.ticks.ScheduledTick;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies the chasm to a chunk.
 * <p>
 * This runs at the tail of {@code applyCarvers}, which is a deliberate choice. By that point the
 * noise pass has filled in terrain and the surface pass has already laid down bedrock, so a single
 * carve here can remove both and leave the shaft genuinely open to the void. It is also still ahead
 * of the feature stage, so nothing has decorated or placed structure pieces into blocks that are
 * about to be deleted.
 */
public final class ChasmCarver {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Height above bedrock left intact when bedrock removal is disabled. */
    private static final int BEDROCK_LAYER = 5;

    /** Height between samples of each wall term. Both must be powers of two for the {@code &} test.
     *  Set from the terms' own vertical wavelengths rather than a single shared guess: the coarse
     *  term repeats about every 470 blocks and the fine one about every 57, so sampling both every
     *  four blocks evaluated the coarse one roughly eight times more often than it changes. */
    private static final int COARSE_WALL_STEP = 16;
    private static final int FINE_WALL_STEP = 4;

    /** How far below sea level to look for water that should start flowing into the chasm. */
    private static final int SPILL_SCAN_DEPTH = 40;

    /** Height above sea level treated as the chasm rim for the vertical profile. Must not depend on
     *  terrain, or the carve stops being idempotent across repeated passes. */
    private static final int PROFILE_RIM_ABOVE_SEA = 72;

    /** So the log says once, clearly, that chasms are actually being cut. The first version of this
     *  mod silently disabled itself and looked identical to "no chasms nearby". */
    private static final AtomicBoolean ANNOUNCED = new AtomicBoolean(false);

    private ChasmCarver() {
    }

    public static void resetAnnouncement() {
        ANNOUNCED.set(false);
    }

    /** Resolved form of the configured dimension list.
     *  <p>
     *  {@link #appliesTo} used to build {@code dimension.identifier().toString()} and compare it
     *  against the raw config strings, so it allocated a String every call, twice per chunk. The
     *  config list is rebuilt into keys on first use and whenever the underlying list instance
     *  changes, which turns the check into a set lookup on an object the caller already holds. */
    private record DimensionGate(List<? extends String> source, Set<ResourceKey<Level>> keys) {}
    private static volatile DimensionGate dimensionGate;

    public static boolean appliesTo(ResourceKey<Level> dimension) {
        if (!ChasmConfig.enabled()) {
            return false;
        }
        List<? extends String> allowed = ChasmConfig.dimensions();
        DimensionGate gate = dimensionGate;
        // Identity, not equals. The config holds one list instance and hands the same one back every
        // call, so this is a pointer compare in the steady state rather than a list walk.
        if (gate == null || gate.source != allowed) {
            Set<ResourceKey<Level>> keys = new HashSet<>();
            for (int i = 0; i < allowed.size(); i++) {
                Identifier id = Identifier.tryParse(allowed.get(i));
                if (id != null) {
                    keys.add(ResourceKey.create(Registries.DIMENSION, id));
                }
            }
            gate = new DimensionGate(allowed, keys);
            dimensionGate = gate;
        }
        return gate.keys.contains(dimension);
    }

    /**
     * True when a chasm passes close enough to this chunk that a structure starting here would end
     * up hanging over the drop or buried in a wall.
     *
     * @param floorHeight sea floor height at the chunk middle, for the ocean bias
     */
    public static boolean intersectsChunk(long worldSeed, ChunkPos pos, int floorHeight, int seaLevel) {
        ChasmField field = ChasmField.forSeed(worldSeed);
        ChasmField.Column col = new ChasmField.Column();
        field.sample(pos.getMiddleBlockX(), pos.getMiddleBlockZ(),
                ChasmField.oceanFactor(floorHeight, seaLevel), col);
        if (col.halfWidth <= 0.5) {
            return false;
        }
        // maxWallOffset, not wallAmplitude. The carve widens by the coarse term too, which scales
        // with the chasm's own width and dwarfs the fine amplitude: on a 700 block chasm that is
        // roughly 98 blocks versus 22. Testing against the fine term alone made the structure
        // exclusion band far narrower than the hole it is meant to cover, so structures could still
        // start in a chunk the chasm actually reaches.
        // Half a chunk diagonal of slack on top, so a chasm clipping the corner still counts.
        return col.distance - 12.0 < col.halfWidth + field.maxWallOffset(col.halfWidth);
    }

    /**
     * Cheap whole chunk rejection, which is the case for the overwhelming majority of chunks.
     * <p>
     * The distance estimate is very nearly 1-Lipschitz in world space, so no column inside this
     * chunk can be closer to a centreline than the middle is, minus one chunk diagonal. Uses the
     * widest a chasm could ever be, so it is independent of the ocean bias and therefore answerable
     * without touching the generator.
     * <p>
     * Five noise evaluations. Separated from {@link #carve} so callers can run it <em>before</em>
     * paying for {@link #cornerOceanFactors}.
     */
    public static boolean mightIntersect(ChasmField field, ChunkPos chunkPos) {
        double widest = field.maxPossibleHalfWidth();
        return field.distanceToCentreline(chunkPos.getMinBlockX() + 8, chunkPos.getMinBlockZ() + 8) - 24.0
                <= widest + field.maxWallOffset(widest);
    }

    /**
     * Carves one chunk, sampling the ocean bias only if the chunk survives both cheap rejections.
     * <p>
     * The corner factors are four {@code getBaseHeight} calls, and each of those evaluates a whole
     * density column through the generator's noise router. They used to be computed at the call
     * site and handed in as an argument, which meant Java evaluated them before {@code carve} had
     * any chance to reject the chunk. A chunk is visited ten times, once for its own carve and once
     * from each of the nine decoration passes that touch it, so every chunk in the world was paying
     * for forty density columns whose results were discarded a few instructions later. They are now
     * taken after both rejections, so only chunks a chasm actually reaches ever sample them.
     */
    public static void carve(ChunkAccess chunk, long worldSeed, int seaLevel,
                             ChunkGenerator generator, RandomState randomState) {
        ChasmField field = ChasmField.forSeed(worldSeed);

        ChunkPos chunkPos = chunk.getPos();
        int baseX = chunkPos.getMinBlockX();
        int baseZ = chunkPos.getMinBlockZ();
        if (!mightIntersect(field, chunkPos)) {
            return;
        }

        boolean drainWater = ChasmConfig.drainWater();
        int minY = chunk.getMinY();
        int minSectionY = minY >> 4;
        int bottom = ChasmConfig.removeBedrock() ? minY : minY + BEDROCK_LAYER;
        int worldTop = chunk.getMinY() + chunk.getHeight() - 1;

        // Fixed rim height for the vertical profile. Anything above it is carved at full rim width,
        // which is what you want anyway: a chasm cutting a mountain should not pinch shut just
        // because that column happens to start higher. Terrain independent, so it is identical on
        // every pass over the same chunk.
        int profileRim = seaLevel + PROFILE_RIM_ABOVE_SEA;

        LevelChunkSection[] sections = chunk.getSections();

        // Repeat-pass fast path. A chunk is carved up to nine times: once for itself and once from
        // each neighbour's decoration. After the first pass the chasm volume is already air, so the
        // remaining eight would otherwise re-sample the field for all 256 columns just to discover
        // there is nothing left to remove. If every section in the carve range is empty, there is
        // provably nothing to do, and this costs one flag read per section instead.
        // Upper bound is worldTop, NOT profileRim. Above the rim the profile clamps to t = 1 and
        // carves at full width, so material up there is still in scope. Bounding this at profileRim
        // would let a tall feature standing over a chasm skip the carve entirely.
        if (!anySolidInRange(sections, minSectionY, bottom, worldTop)) {
            return;
        }

        // Only now, past both rejections, is the ocean bias worth four density columns.
        double[] cornerOceanFactor = cornerOceanFactors(generator, chunk, randomState, seaLevel);

        ChasmField.Column col = new ChasmField.Column();
        boolean carvedAnything = false;
        // Which columns the chasm actually reaches. The water spill pass below uses this so it only
        // touches the rim rather than every water block in the chunk.
        boolean[] nearChasm = new boolean[256];

        for (int lx = 0; lx < 16; lx++) {
            int worldX = baseX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int worldZ = baseZ + lz;

                // Ocean depth comes from the generator's prediction, bilinearly interpolated from the
                // chunk corners, NOT from OCEAN_FLOOR_WG.
                //
                // The heightmap is re-primed by this very method, so after one pass the sea floor
                // reads far lower, the depth saturates, oceanFactor goes to 1 and oceanScale widens
                // the chasm. Every subsequent pass would then carve wider than the last: the same
                // runaway that produced vertical blades, arriving through the ocean bias instead of
                // the vertical profile. The generator's base height is a pure function of the noise
                // and carving cannot touch it.
                //
                // Corners are shared with the neighbouring chunks, so the interpolation is continuous
                // across chunk borders and costs four samples per chunk rather than 256.
                double oceanFactor = bilinear(cornerOceanFactor, lx, lz);
                field.sample(worldX, worldZ, oceanFactor, col);

                if (col.halfWidth <= 0.5) {
                    continue;
                }
                if (col.distance >= col.halfWidth + field.maxWallOffset(col.halfWidth)) {
                    // Outside even the most outward excursion the wall noise could make.
                    continue;
                }
                nearChasm[(lx << 4) | lz] = true;

                // Scan from the top of the world, NOT from a heightmap.
                //
                // ProtoChunk.setBlockState updates MOTION_BLOCKING, MOTION_BLOCKING_NO_LEAVES,
                // OCEAN_FLOOR and WORLD_SURFACE. It does NOT update the _WG variants, which only
                // primeHeightmaps writes. So the sequence was: carve empties the column and re-primes
                // WORLD_SURFACE_WG to the emptied state, a feature then places blocks well above
                // that, and the cleanup pass reads the stale low value and starts scanning beneath
                // what it was supposed to remove. Icebergs made this obvious because they stand
                // twenty-odd blocks proud of the sea, so their tops survived as spikes, but any tall
                // feature over a chasm had the same hole.
                //
                // Empty sections are skipped a whole section at a time below, so starting at the
                // world top costs one iteration per empty section rather than sixteen.
                int top = worldTop;

                // The profile is measured against a FIXED reference, never against this column's
                // current surface height.
                //
                // carve() finishes by re-priming the heightmaps, and a chunk is carved up to nine
                // times: once for itself and once from each neighbour's decoration pass. Deriving
                // the span from getHeight() meant every pass recomputed the profile against a
                // surface the previous pass had just lowered, so each pass carved to a slightly
                // different width and columns that survived one pass were cut by the next. The
                // leftovers were thin vertical blades standing in the chasm and streaks radiating
                // across it. Anchoring to sea level makes the carve idempotent: running it nine
                // times now produces exactly the result of running it once.
                double span = Math.max(1.0, profileRim - bottom);
                double terracePhase = field.terracePhase(worldX, worldZ);

                // The two wall terms have very different vertical wavelengths, roughly 470 blocks
                // against 57, so sampling them at the same rate meant the coarse one was evaluated
                // about eight times more often than it changes. This is the hottest loop in the mod
                // by a wide margin: it runs per block of height for every column inside a chasm,
                // which is far more work than the per-column field sampling.
                double wallCoarse = field.wallOffsetCoarse(worldX, top, worldZ, col.halfWidth);
                double wallFine = field.wallOffsetFine(worldX, top, worldZ);
                // Track the height each term was last sampled at rather than testing y against a
                // modulus. The loop now jumps whole empty sections, so a modulus test could be
                // stepped straight over and leave an offset sampled hundreds of blocks away in use.
                int lastCoarseY = top;
                int lastFineY = top;

                for (int y = top; y >= bottom; y--) {
                    int sectionIndex = (y >> 4) - minSectionY;
                    if (sectionIndex < 0 || sectionIndex >= sections.length) {
                        continue;
                    }
                    LevelChunkSection section = sections[sectionIndex];
                    if (section.hasOnlyAir()) {
                        // Nothing in this whole section, so drop straight past it. The loop's own
                        // decrement then lands on the block below. This is what makes starting at
                        // the world top affordable.
                        y = (sectionIndex + minSectionY) << 4;
                        continue;
                    }
                    int localY = y & 15;
                    if (section.getBlockState(lx, localY, lz).isAir()) {
                        continue;
                    }
                    // With drainWater off, water is left where it is rather than carved out. The
                    // heightmap used to express this; it cannot be trusted for the reason above.
                    if (!drainWater && !section.getFluidState(lx, localY, lz).isEmpty()) {
                        continue;
                    }

                    if (lastCoarseY - y >= COARSE_WALL_STEP) {
                        wallCoarse = field.wallOffsetCoarse(worldX, y, worldZ, col.halfWidth);
                        lastCoarseY = y;
                    }
                    if (lastFineY - y >= FINE_WALL_STEP) {
                        wallFine = field.wallOffsetFine(worldX, y, worldZ);
                        lastFineY = y;
                    }

                    double allowed = field.profileHalfWidth(col.halfWidth, (y - bottom) / span, col.narrowing, terracePhase)
                            + wallCoarse + wallFine;
                    if (col.distance >= allowed) {
                        continue;
                    }
                    // Written straight into the section rather than through ChunkAccess so the
                    // per-block heightmap bookkeeping does not run 100k times for a chunk that sits
                    // fully inside a chasm. The heightmaps are rebuilt once at the end instead.
                    section.setBlockState(lx, localY, lz, AIR, false);
                    carvedAnything = true;
                }
            }
        }

        if (carvedAnything) {
            spillWaterIntoChasm(chunk, sections, minSectionY, seaLevel, nearChasm);
            if (ANNOUNCED.compareAndSet(false, true)) {
                GreatChasms.LOGGER.info("Carved the first chasm of this session at chunk {}, {}"
                        + " (config loaded: {})", chunkPos.x(), chunkPos.z(), ChasmConfig.isLoaded());
            }
            // Only the worldgen heightmaps exist at this stage; the rest are primed later by the
            // chunk status pipeline itself.
            Heightmap.primeHeightmaps(chunk, EnumSet.of(
                    Heightmap.Types.WORLD_SURFACE_WG,
                    Heightmap.Types.OCEAN_FLOOR_WG));
        }
    }

    /**
     * Ocean factor at a chunk's four corners, from the generator's predicted sea floor.
     * <p>
     * Four samples rather than 256 because {@code getBaseHeight} evaluates a whole density column
     * and is far too expensive per block. Corners are shared with the neighbouring chunks, so
     * interpolating between them stays continuous across chunk borders instead of stepping.
     */
    public static double[] cornerOceanFactors(ChunkGenerator generator, ChunkAccess chunk,
                                              RandomState randomState, int seaLevel) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        double[] out = new double[4];
        out[0] = cornerFactor(generator, chunk, randomState, seaLevel, baseX, baseZ);
        out[1] = cornerFactor(generator, chunk, randomState, seaLevel, baseX + 16, baseZ);
        out[2] = cornerFactor(generator, chunk, randomState, seaLevel, baseX, baseZ + 16);
        out[3] = cornerFactor(generator, chunk, randomState, seaLevel, baseX + 16, baseZ + 16);
        return out;
    }

    private static double cornerFactor(ChunkGenerator generator, ChunkAccess chunk,
                                       RandomState randomState, int seaLevel, int x, int z) {
        int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, chunk, randomState);
        return ChasmField.oceanFactor(floor, seaLevel);
    }

    /** Bilinear interpolation of the four corner values across the chunk. */
    private static double bilinear(double[] corners, int lx, int lz) {
        double fx = lx / 16.0;
        double fz = lz / 16.0;
        double lower = corners[0] + (corners[1] - corners[0]) * fx;
        double upper = corners[2] + (corners[3] - corners[2]) * fx;
        return lower + (upper - lower) * fz;
    }

    /** True if any section overlapping [minY, maxY] still holds something other than air. Reads the
     *  section's own emptiness flag rather than scanning blocks, so it is a handful of comparisons. */
    private static boolean anySolidInRange(LevelChunkSection[] sections, int minSectionY, int minY, int maxY) {
        int first = (minY >> 4) - minSectionY;
        int last = (maxY >> 4) - minSectionY;
        if (first < 0) first = 0;
        if (last >= sections.length) last = sections.length - 1;
        for (int i = first; i <= last; i++) {
            if (!sections[i].hasOnlyAir()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes an ocean above a chasm actually pour over the rim instead of standing there as a wall of
     * water with a suspiciously clean vertical edge.
     * <p>
     * Nothing here places water. The ocean is already an infinite source and Minecraft's fluid system
     * will happily cascade it down the walls forever, but only once something tells those blocks they
     * need to re-evaluate. During generation no block updates fire, so the water is left exactly as
     * the noise pass wrote it and simply hangs there.
     * <p>
     * {@code markPosForPostprocessing} is vanilla's own answer to this, used for the same problem at
     * chunk borders. Every fluid block left standing against open air gets marked, and the moment the
     * chunk goes live the fluid ticks run and the sea starts falling in by itself. Doing it this way
     * rather than painting waterfalls in means the cascades land wherever the terrain actually lets
     * them, and they keep flowing instead of evaporating on the first tick for want of a source.
     */
    private static void spillWaterIntoChasm(ChunkAccess chunk, LevelChunkSection[] sections,
                                            int minSectionY, int seaLevel, boolean[] nearChasm) {
        // Only the band near the surface matters. Water far below is already enclosed, and scanning
        // the whole column would multiply the cost of every chasm chunk for no visible gain.
        int top = Math.min(seaLevel, chunk.getMinY() + chunk.getHeight() - 1);
        int bottom = Math.max(chunk.getMinY(), seaLevel - SPILL_SCAN_DEPTH);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        for (int y = top; y >= bottom; y--) {
            int sectionIndex = (y >> 4) - minSectionY;
            if (sectionIndex < 0 || sectionIndex >= sections.length) {
                continue;
            }
            LevelChunkSection section = sections[sectionIndex];
            if (!section.hasFluid()) {
                continue;
            }
            int localY = y & 15;
            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    // Only the rim. Without this every water block in the chunk whose column happened
                    // to sit on a chunk edge was marked, since a chunk edge always reads as exposed.
                    // In an ocean that is roughly a quarter of the whole water body: thousands of
                    // scheduled fluid ticks and a BlockPos allocation each, per chunk, to make water
                    // hundreds of blocks from the chasm re-evaluate and settle straight back down.
                    if (!nearRim(nearChasm, lx, lz)) {
                        continue;
                    }
                    FluidState fluid = section.getFluidState(lx, localY, lz);
                    if (fluid.isEmpty()) {
                        continue;
                    }
                    if (!touchesOpenAir(section, lx, localY, lz)) {
                        continue;
                    }
                    pos.set(baseX + lx, y, baseZ + lz);
                    chunk.markPosForPostprocessing(pos);
                    // Marking alone only asks the block to re-evaluate its shape on load, which is
                    // not reliably enough to start a fluid moving. An explicit tick in the past is,
                    // so it fires the instant the chunk goes live and the sea begins to fall in.
                    chunk.getFluidTicks().schedule(
                            new ScheduledTick<>(fluid.getType(), pos.immutable(), 0L, 0L));
                }
            }
        }
    }

    /** True if this column or one of its four neighbours is inside the chasm, which is where water
     *  can actually fall in. Columns outside the chunk count as candidates, since the void they
     *  border may live in the neighbouring chunk. */
    private static boolean nearRim(boolean[] nearChasm, int lx, int lz) {
        if (nearChasm[(lx << 4) | lz]) return true;
        if (lx == 0 || lx == 15 || lz == 0 || lz == 15) return true;
        return nearChasm[((lx - 1) << 4) | lz]
                || nearChasm[((lx + 1) << 4) | lz]
                || nearChasm[(lx << 4) | (lz - 1)]
                || nearChasm[(lx << 4) | (lz + 1)];
    }

    /** True if this fluid block has open air beside or beneath it within this section. Blocks on a
     *  section boundary are treated as exposed, because the neighbour lives in another section or
     *  chunk and is not cheaply reachable here; a needless mark costs one settled fluid tick. */
    private static boolean touchesOpenAir(LevelChunkSection section, int x, int y, int z) {
        if (x == 0 || x == 15 || z == 0 || z == 15 || y == 0) {
            return true;
        }
        return section.getBlockState(x - 1, y, z).isAir()
                || section.getBlockState(x + 1, y, z).isAir()
                || section.getBlockState(x, y, z - 1).isAir()
                || section.getBlockState(x, y, z + 1).isAir()
                || section.getBlockState(x, y - 1, z).isAir();
    }
}
