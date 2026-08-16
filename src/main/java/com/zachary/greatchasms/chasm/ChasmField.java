package com.zachary.greatchasms.chasm;

import com.zachary.greatchasms.ChasmConfig;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides where the chasms are.
 * <p>
 * Vanilla ravines are produced by a {@code WorldCarver}, which walks a tunnel outward from an origin
 * chunk. That approach cannot produce what this mod needs: a carver may only reach
 * {@code WorldCarver.getRange()} chunks from its origin, and raising that range makes every chunk
 * iterate over {@code (2r+1)^2} candidate origins, which becomes unusable long before the reach is
 * measured in thousands of blocks.
 * <p>
 * So instead of walking a path, a chasm is defined implicitly as a level set of a global noise
 * field. Each column asks a pure function "how far am I from the nearest chasm centreline, and how
 * wide is the chasm there", and the answer is consistent everywhere without any chunk ever needing
 * to know about its neighbours. That is also what makes this safe under C2ME: there is no shared
 * mutable state and no ordering dependency between chunks.
 * <p>
 * The distance estimate itself is the useful trick. For a smooth field {@code n}, the distance from
 * a point to the nearest place where {@code n == 0} is approximately {@code |n| / |grad n|}. That
 * converts an arbitrary noise value into a figure measured in blocks, which is what lets the config
 * express widths as real block counts rather than as an opaque threshold.
 */
public final class ChasmField {

    /** One field per world seed. Built once, then shared by every generating thread. */
    private static final ConcurrentHashMap<Long, ChasmField> CACHE = new ConcurrentHashMap<>();

    public static ChasmField forSeed(long seed) {
        return CACHE.computeIfAbsent(seed, ChasmField::new);
    }

    /** Called when a world unloads so a long running client does not retain fields for dead seeds. */
    public static void clearCache() {
        CACHE.clear();
    }

    /** Step in blocks used for the numeric gradient. Large enough to stay clear of floating point
     *  noise, small enough that the field is effectively linear across it. */
    private static final double GRADIENT_STEP = 8.0;

    // Two, not three. The third octave put small scale wiggle into the centreline, which spawned
    // extra saddles and pinch points and broke long chasms into short kinked ones.
    private static final int PATH_OCTAVES = 2;
    private static final int REGION_OCTAVES = 2;

    /** Fraction of the region gate's fade that is spent getting up to full width. Below this the
     *  chasm is tapering shut near its end; above it, full configured width. Small on purpose: the
     *  taper should be the ends of a chasm, not most of it. */
    private static final double END_TAPER = 0.35;
    // Three, not two. A single chasm should not be one uniform trench: it should swell into basins
    // and pinch into gorges along its length, which needs detail at more than one scale.
    private static final int WIDTH_OCTAVES = 3;

    /** How far the wall profile is allowed to swing either side of the configured floor narrowing.
     *  At 0.75 a chasm ranges from a near vertical shaft to a wide open V over its length. */
    private static final double PROFILE_VARIATION = 0.75;

    /** Fraction of the ocean bias spent on whether a chasm exists at all. The remainder is spent on
     *  how wide it is. Deliberately small: a large share here is what made chasms end at coastlines. */
    private static final double OCEAN_EXISTENCE_SHARE = 0.25;

    private final ChasmNoise pathNoise;
    private final ChasmNoise widthNoise;
    private final ChasmNoise regionNoise;
    private final ChasmNoise wallNoise;
    private final ChasmNoise wallCoarseNoise;
    private final ChasmNoise profileNoise;
    private final ChasmNoise terraceNoise;

    // Snapshotted at construction. Generation must stay reproducible for a given world even if
    // someone edits the config mid-session, otherwise chunks generated before and after the edit
    // would not line up along their shared border.
    private final double pathFrequency;
    private final double regionFrequency;
    private final double widthFrequency;
    private final double minHalfWidth;
    private final double maxHalfWidth;
    private final double regionThreshold;
    private final double regionFade;
    private final double wallAmplitude;
    private final double wallFineFrequency;
    private final double wallCoarseFrequency;
    private final double wallRelative;
    private final double floorNarrowing;
    private final double profileFrequency;
    private final double terraceStrength;
    private final int terraceCount;
    private final double terraceFrequency;
    private final double oceanBias;
    private final double gradientFloor;

    private ChasmField(long worldSeed) {
        this.pathNoise = new ChasmNoise(worldSeed, 0x9A17);
        this.widthNoise = new ChasmNoise(worldSeed, 0x5C3B);
        this.regionNoise = new ChasmNoise(worldSeed, 0x21EE);
        this.wallNoise = new ChasmNoise(worldSeed, 0x7D04);
        this.wallCoarseNoise = new ChasmNoise(worldSeed, 0x3F19);
        this.profileNoise = new ChasmNoise(worldSeed, 0x6B72);
        this.terraceNoise = new ChasmNoise(worldSeed, 0x4E8D);

        this.pathFrequency = 1.0 / Math.max(64.0, ChasmConfig.chasmSpacing());
        // The region gate decides where chasms may exist at all, so its wavelength is what sets how
        // LONG an individual chasm run is: a run lasts for as long as the centreline stays inside an
        // open region. At 4x spacing these were blobs a few thousand blocks across, and intersecting
        // a winding line with a blob that size gave short lens shaped pits instead of chasms. At 16x
        // the open regions are tens of thousands of blocks across and a run crosses one for
        // thousands of blocks, which is what the design actually asks for.
        this.regionFrequency = 1.0 / Math.max(32768.0, ChasmConfig.chasmSpacing() * 16.0);
        // Width varies over a scale comparable to the chasm itself. At the old 1/900 a single chasm
        // swung between its narrowest and widest several times over its length, which read as lumpy
        // rather than as one enormous feature.
        this.widthFrequency = 1.0 / Math.max(2048.0, ChasmConfig.chasmSpacing());
        // Two scales of wall shape. The fine one is rock texture, the coarse one is the difference
        // between a canyon and a slot cut by a laser: bays, buttresses and headlands measured in
        // tens of blocks. A fixed amplitude alone looked smooth once chasms grew to hundreds of
        // blocks wide, because 10 blocks of wobble on a 600 block gap is nothing, so the coarse term
        // is a fraction of the chasm's own width and scales with it.
        this.wallFineFrequency = 1.0 / 40.0;
        this.wallCoarseFrequency = 1.0 / 260.0;
        this.wallRelative = ChasmConfig.wallScale();
        this.minHalfWidth = ChasmConfig.minWidth() * 0.5;
        this.maxHalfWidth = ChasmConfig.maxWidth() * 0.5;
        this.regionThreshold = ChasmConfig.rarity();
        this.regionFade = 0.22;
        this.wallAmplitude = ChasmConfig.wallRoughness();
        this.floorNarrowing = ChasmConfig.floorNarrowing();
        // Section shape changes over a shorter distance than width does, so a single chasm passes
        // through several different characters rather than being one shape end to end.
        this.profileFrequency = 1.0 / 2200.0;
        this.terraceStrength = ChasmConfig.terraceStrength();
        this.terraceCount = ChasmConfig.terraceCount();
        // Benches stay coherent over roughly this distance before stepping out of phase.
        this.terraceFrequency = 1.0 / 300.0;
        this.oceanBias = ChasmConfig.oceanBias();
        // The field varies by roughly 1 over a wavelength, so a representative gradient magnitude is
        // on the order of the path frequency. See the clamp in distanceToCentreline for why this
        // matters.
        // Raised from 0.30. A typical gradient for this field is around 3.8x the path frequency, so
        // even at 0.85 the clamp still only engages on genuinely flat spots. The lower value was
        // leaving thin vertical sheets of stone standing down the middle of a chasm wherever two
        // branches of the contour pinched together and the hump between them mapped to a distance
        // just outside the half width. Clamping harder pulls those humps inside it.
        this.gradientFloor = this.pathFrequency * 0.85;
    }

    /** Depth below sea level, in blocks, at which a column counts as fully oceanic. */
    private static final double FULL_OCEAN_DEPTH = 24.0;

    /**
     * Turns a sea floor height into a 0..1 measure of how oceanic a column is. Driven by terrain
     * depth rather than by biome on purpose: terrain height is continuous, so the bias fades in
     * smoothly across a coastline instead of stepping at a biome border and leaving a visible ledge
     * down the side of a chasm.
     */
    public static double oceanFactor(int floorHeight, int seaLevel) {
        return smoothstep(0.0, FULL_OCEAN_DEPTH, seaLevel - floorHeight);
    }

    /** Reusable per column result. Callers allocate one per chunk and pass it back in, so a chunk
     *  full of chasm does not produce 256 short lived objects per carve. */
    public static final class Column {
        /** Approximate distance in blocks from this column to the chasm centreline. */
        public double distance;
        /** Half width in blocks of the chasm at this column, at the top of its profile. Zero when
         *  no chasm exists here at all. */
        public double halfWidth;
        /** Diagnostics, surfaced by /greatchasms info. Kept because guessing at why a chasm came out
         *  the wrong size cost several rounds of rebuilding. */
        public double regionFactor;
        public double taper;
        /** Width at the world floor as a fraction of the rim width, for THIS column. Varies along a
         *  chasm so some stretches are sheer shafts and others open into wide sloping valleys. */
        public double narrowing;
    }

    public double maxPossibleHalfWidth() {
        return this.maxHalfWidth;
    }

    public double wallAmplitude() {
        return this.wallAmplitude;
    }

    /**
     * Distance in blocks from a column to the nearest chasm centreline, ignoring whether a chasm
     * actually exists there. Five noise evaluations and nothing else, which is what makes it usable
     * as the coarse pass of a wide area search.
     */
    public double distanceToCentreline(int worldX, int worldZ) {
        double x = worldX * this.pathFrequency;
        double z = worldZ * this.pathFrequency;
        double step = GRADIENT_STEP * this.pathFrequency;

        double n = this.pathNoise.fbm(x, z, PATH_OCTAVES);

        // Central differences, in field units per block.
        double dx = (this.pathNoise.fbm(x + step, z, PATH_OCTAVES)
                - this.pathNoise.fbm(x - step, z, PATH_OCTAVES)) / (2.0 * GRADIENT_STEP);
        double dz = (this.pathNoise.fbm(x, z + step, PATH_OCTAVES)
                - this.pathNoise.fbm(x, z - step, PATH_OCTAVES)) / (2.0 * GRADIENT_STEP);

        double gradient = Math.sqrt(dx * dx + dz * dz);

        // |n| / |grad n| is only an estimate of the distance to the nearest zero of n, and it fails
        // in exactly one place: where two zero crossings pinch together. On the small hump between
        // them BOTH |n| and the gradient are near zero, and small divided by small is large, so the
        // estimate reports that spot as enormously far from any centreline and it never gets carved.
        // The visible result was a rounded uncarved mass sitting in the middle of a chasm.
        //
        // Clamping the divisor from below fixes it without breaking the honest cases. Far from any
        // centreline |n| is large, so even with a floored divisor the result stays large and the
        // point is still correctly rejected. The clamp only bites where |n| is already small, which
        // is precisely the pinch.
        if (gradient < this.gradientFloor) {
            gradient = this.gradientFloor;
        }
        return Math.abs(n) / gradient;
    }

    /**
     * Evaluates the field for one world column. Pure, so it may be called from any thread.
     *
     * @param oceanFactor 0 for dry land, 1 for deep ocean, from {@link #oceanFactor(int, int)}
     */
    public void sample(int worldX, int worldZ, double oceanFactor, Column out) {
        out.distance = distanceToCentreline(worldX, worldZ);

        // A single continuous level set would wrap the whole world in one unbroken chasm network.
        // The region field gates that down to occasional runs, and fading rather than cutting means
        // a chasm tapers shut at its ends instead of terminating in a sheer wall.
        double region = this.regionNoise.fbm(worldX * this.regionFrequency, worldZ * this.regionFrequency, REGION_OCTAVES);
        // The ocean preference used to be applied entirely to the existence threshold, which is why
        // chasms stopped dead at the shoreline: a run whose region value sat between the ocean
        // threshold and the land threshold simply ceased to qualify the moment the sea floor rose
        // above water, and the chasm ended in a wall of dirt.
        //
        // Only a small share of the bias moves the threshold now. The rest is applied to width
        // below. A chasm therefore carries on across a coast under its own momentum, narrowing as it
        // goes inland, and ends where the region field closes rather than where the water does.
        double threshold = this.regionThreshold - this.oceanBias * oceanFactor * OCEAN_EXISTENCE_SHARE;
        double regionFactor = smoothstep(threshold, threshold + this.regionFade, region);
        out.regionFactor = regionFactor;
        if (regionFactor <= 0.0) {
            out.halfWidth = 0.0;
            out.taper = 0.0;
            return;
        }

        // Width used to be multiplied by regionFactor directly, which was the bug behind every
        // chasm coming out as a slot. The region field is roughly normal with a standard deviation
        // near 0.29, so regionFactor only reaches 1 in the small upper tail; across most of a run it
        // sits around 0.2 to 0.4 and was scaling a 200 block chasm down to a 50 block crack.
        //
        // The gate has two jobs and they need separating. Whether a chasm exists is regionFactor > 0.
        // How wide it is should be full size across the interior, with the taper reserved for the
        // last stretch before each end so a chasm still closes gracefully rather than stopping dead.
        double taper = smoothstep(0.0, END_TAPER, regionFactor);
        out.taper = taper;

        double w = this.widthNoise.fbm(worldX * this.widthFrequency, worldZ * this.widthFrequency, WIDTH_OCTAVES);
        double width01 = (w + 1.0) * 0.5;

        // The rest of the ocean preference, expressed as width rather than as existence. Deep water
        // gets the full chasm, land gets a narrower one. Because oceanFactor ramps across the shelf
        // rather than switching at the waterline, the narrowing reads as the chasm tightening as it
        // comes ashore instead of as a step in the wall.
        double landNarrowing = Math.min(0.70, this.oceanBias * 1.4);
        double oceanScale = 1.0 - landNarrowing * (1.0 - oceanFactor);

        out.halfWidth = (this.minHalfWidth + (this.maxHalfWidth - this.minHalfWidth) * width01)
                * taper * oceanScale;

        // A constant floor narrowing made every stretch of every chasm the same shape in section.
        // Varying it along the run is what produces the difference between a sheer walled shaft and
        // a wide sloping gorge, which is most of what makes a canyon look eroded rather than cut.
        double p = this.profileNoise.fbm(worldX * this.profileFrequency, worldZ * this.profileFrequency, 2);
        double narrowing = this.floorNarrowing * (1.0 + p * PROFILE_VARIATION);
        out.narrowing = narrowing < 0.10 ? 0.10 : (narrowing > 1.0 ? 1.0 : narrowing);
    }

    /**
     * Half width permitted at a given height, before wall roughness.
     *
     * @param depth01 0 at the very bottom of the world, 1 at the chasm rim
     */
    /**
     * @param terracePhase per column offset from {@link #terracePhase}, which is what stops every
     *                     bench forming at the same height and turns a set of concentric rings into
     *                     broken cliff lines
     */
    public double profileHalfWidth(double halfWidth, double depth01, double narrowing, double terracePhase) {
        // Straight sided all the way down would read as a box canyon. Narrowing toward the floor
        // gives the classic chasm silhouette while still leaving the bottom wide open to the void.
        double t = depth01 < 0.0 ? 0.0 : (depth01 > 1.0 ? 1.0 : depth01);

        // A linear taper is a cone, and a cone reads as a smooth funnel no matter how rough the
        // walls are, because the width changes by the same amount every block of height. Real cliffs
        // do not: they hold width for a stretch, then step. Quantising the profile into benches
        // produces vertical risers between flat shelves, which is what gives the walls their edges.
        if (this.terraceStrength > 0.0 && this.terraceCount > 0) {
            double stepped = Math.floor(t * this.terraceCount + terracePhase) / this.terraceCount;
            if (stepped < 0.0) stepped = 0.0;
            if (stepped > 1.0) stepped = 1.0;
            t = t + (stepped - t) * this.terraceStrength;
        }

        return halfWidth * (narrowing + (1.0 - narrowing) * t);
    }

    /** Vertical offset of this column's terrace pattern, in bench fractions. Low frequency so a run
     *  of cliff stays coherent for tens of blocks before stepping out of phase with its neighbour. */
    public double terracePhase(int worldX, int worldZ) {
        if (this.terraceStrength <= 0.0) {
            return 0.0;
        }
        return (this.terraceNoise.fbm(worldX * this.terraceFrequency, worldZ * this.terraceFrequency, 2) + 1.0) * 0.5;
    }

    /** Wall irregularity in blocks, added to the permitted half width. */
    public double wallOffset(int worldX, int y, int worldZ, double halfWidth) {
        // Vertical frequency is deliberately lower than horizontal. Equal frequencies give a wall
        // that is evenly bumpy in every direction, which reads as noise; stretching it vertically
        // produces the long ledges and shelves that make a rock face look bedded.
        double coarse = this.wallCoarseNoise.fbm(
                worldX * this.wallCoarseFrequency,
                y * this.wallCoarseFrequency * 0.55,
                worldZ * this.wallCoarseFrequency, 2) * halfWidth * this.wallRelative;

        double fine = this.wallAmplitude <= 0.0 ? 0.0 : this.wallNoise.fbm(
                worldX * this.wallFineFrequency,
                y * this.wallFineFrequency * 0.7,
                worldZ * this.wallFineFrequency, 2) * this.wallAmplitude;

        return coarse + fine;
    }

    /** Largest outward excursion {@link #wallOffset} can produce, used for rejection tests. */
    public double maxWallOffset(double halfWidth) {
        return halfWidth * this.wallRelative + this.wallAmplitude;
    }

    private static double smoothstep(double edge0, double edge1, double v) {
        if (edge1 <= edge0) {
            return v >= edge1 ? 1.0 : 0.0;
        }
        double t = (v - edge0) / (edge1 - edge0);
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }
}
