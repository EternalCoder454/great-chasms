package com.zachary.greatchasms;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Common scoped, so it is attached at mod load and editable from the main menu as well as in game.
 * <p>
 * This was originally SERVER, on the reasoning that worldgen settings ought to travel with the save.
 * They did not: the file lands in {@code config/} globally and nothing is written under
 * {@code saves/<world>/serverconfig}. So the scoping delivered none of its intended benefit while
 * still carrying the restriction that a SERVER spec is only writable while a world is loaded, which
 * broke the config screen from the title screen.
 * <p>
 * Chasm shape still has to stay fixed for the life of a world, or chunks generated before and after
 * an edit will not line up along their shared border. That is now enforced where it actually belongs:
 * {@link com.zachary.greatchasms.chasm.ChasmField} snapshots the shape values per seed, and the
 * config screen states plainly which settings only affect newly generated terrain.
 */
public final class ChasmConfig {

    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue ENABLED;
    private static final ModConfigSpec.DoubleValue SPACING;
    private static final ModConfigSpec.DoubleValue RARITY;
    private static final ModConfigSpec.IntValue MIN_WIDTH;
    private static final ModConfigSpec.IntValue MAX_WIDTH;
    private static final ModConfigSpec.DoubleValue WALL_ROUGHNESS;
    private static final ModConfigSpec.DoubleValue WALL_SCALE;
    private static final ModConfigSpec.DoubleValue FLOOR_NARROWING;
    private static final ModConfigSpec.DoubleValue TERRACE_STRENGTH;
    private static final ModConfigSpec.IntValue TERRACE_COUNT;
    private static final ModConfigSpec.BooleanValue REMOVE_BEDROCK;
    private static final ModConfigSpec.BooleanValue DRAIN_WATER;
    private static final ModConfigSpec.BooleanValue BLOCK_STRUCTURES;
    private static final ModConfigSpec.DoubleValue OCEAN_BIAS;
    private static final ModConfigSpec.IntValue SEARCH_RADIUS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("Great Chasms: enormous ravines that cut from the surface straight through to the void.").push("general");

        ENABLED = b
                .comment("Master switch. Turning this off leaves terrain completely untouched.")
                .define("enabled", true);

        DIMENSIONS = b
                .comment("Dimensions that get chasms. The default is overworld only, because a chasm",
                        "is defined as opening into the void below the world floor, which is not a",
                        "meaningful thing to do in the Nether or the End.")
                .defineList("dimensions", List.of("minecraft:overworld"), () -> "", o -> o instanceof String);

        b.pop().comment("Shape and placement.").push("shape");

        SPACING = b
                .comment("Wavelength in blocks of the field the chasms are cut from. This is the single",
                        "most important number for how LONG a chasm is, because a chasm follows a",
                        "contour of that field and contours are only as long as the field is smooth.",
                        "At 1400 contours curl back on themselves every few hundred blocks and you get",
                        "gashes. At 5000 they sweep for many thousands of blocks. It also sets how far",
                        "apart chasms are, so raising it makes them longer, grander and rarer together.")
                .defineInRange("spacing", 5000.0D, 256.0D, 60000.0D);

        RARITY = b
                .comment("How much of the world is allowed to contain chasms at all, as a threshold on",
                        "a second large scale field. Higher is rarer. At 0.0 roughly half the world is",
                        "eligible and chasms form a near continuous network; at 0.6 they are isolated",
                        "runs separated by hundreds of thousands of blocks. Lower this if you want to",
                        "trip over chasms constantly. Around 0.0 the eligible area is near the point",
                        "where it joins up into one connected network, which is what lets a single",
                        "chasm run for its full length instead of being cut short by the gate closing.")
                .defineInRange("rarity", 0.0D, -1.0D, 0.95D);

        OCEAN_BIAS = b
                .comment("How much easier it is for a chasm to exist under deep water than on land.",
                        "This is subtracted from the rarity threshold in proportion to how far the sea",
                        "floor sits below sea level, so shallow coast is barely affected and deep ocean",
                        "gets the full amount. 0 removes the bias and treats ocean like anywhere else.",
                        "Because it is driven by terrain depth rather than by biome, it fades smoothly",
                        "and never puts a hard step in a chasm wall at a biome border.")
                .defineInRange("oceanBias", 0.35D, 0.0D, 1.5D);

        MIN_WIDTH = b
                .comment("Narrowest a chasm gets at its rim, in blocks. Even the narrowest should still",
                        "be far too wide to jump, so this is a floor, not a target.")
                .defineInRange("minWidth", 200, 8, 2048);

        MAX_WIDTH = b
                .comment("Widest a chasm gets at its rim, in blocks. Wide enough that crossing means",
                        "flying or building a bridge is the whole point, so keep this large. At 700 the",
                        "far rim is beyond normal render distance and reads as an ocean of open air.")
                .defineInRange("maxWidth", 700, 8, 2048);

        WALL_ROUGHNESS = b
                .comment("Fine wall detail in blocks, the rock texture scale. 0 gives glassy smooth",
                        "sides; larger values give ledges, overhangs and broken edges.")
                .defineInRange("wallRoughness", 22.0D, 0.0D, 96.0D);

        WALL_SCALE = b
                .comment("Coarse wall shape, as a fraction of the chasm's own half width. This is what",
                        "makes the walls wander in and out over hundreds of blocks, forming bays and",
                        "headlands rather than two parallel planes. A fixed block amplitude cannot do",
                        "this job, because what reads as rugged on a 60 block chasm is invisible on a",
                        "600 block one. 0 gives dead straight walls.")
                .defineInRange("wallScale", 0.28D, 0.0D, 0.8D);

        FLOOR_NARROWING = b
                .comment("Width at the world floor as a fraction of the width at the rim. 1.0 gives",
                        "vertical walls, lower values give the classic tapering chasm profile. This is",
                        "never allowed to reach 0, so the chasm always stays open to the void.",
                        "Raised to 0.62 for steeper sides: at 0.42 the walls read as a smooth funnel.")
                .defineInRange("floorNarrowing", 0.62D, 0.05D, 1.0D);

        TERRACE_STRENGTH = b
                .comment("How strongly the walls are cut into benches, 0 to 1.",
                        "A plain taper is a cone, and a cone reads as a smooth funnel however rough the",
                        "wall noise is, because the width changes by the same amount every block of",
                        "height. Real cliffs hold their width for a stretch and then step. This quantises",
                        "the profile into shelves with vertical risers between them, which is what puts",
                        "actual edges on the walls. 0 restores the old smooth cone.")
                .defineInRange("terraceStrength", 0.55D, 0.0D, 1.0D);

        TERRACE_COUNT = b
                .comment("How many benches from the floor to the rim. Fewer means taller cliffs with",
                        "bigger drops; more means finer stepping.")
                .defineInRange("terraceCount", 7, 1, 40);

        b.pop().comment("What the carve removes and prevents.").push("behaviour");

        REMOVE_BEDROCK = b
                .comment("Carve through the bedrock floor as well, leaving the chasm open to the void.",
                        "This is the whole point of the mod, so turning it off leaves you with a very",
                        "large but ordinary ravine that bottoms out on bedrock.")
                .define("removeBedrock", true);

        DRAIN_WATER = b
                .comment("Clear the water column above a chasm so an ocean above it does not simply",
                        "fill it during generation. Water still flows in from the sides afterwards,",
                        "which is intended.")
                .define("drainWater", true);

        BLOCK_STRUCTURES = b
                .comment("Prevent structures from starting in any chunk that a chasm passes through.",
                        "Without this you get oil wells, camps and the like hanging in mid air over the",
                        "drop, or buried in a wall.")
                .define("blockStructures", true);

        SEARCH_RADIUS = b
                .comment("How far out in blocks '/greatchasms locate' will look before giving up.")
                .defineInRange("searchRadius", 12000, 512, 200000);

        b.pop();
        SPEC = b.build();
    }

    private ChasmConfig() {
    }

    // Write access for the optional config screen. Kept as explicit accessors rather than making the
    // fields public so the entries stay read-only to everything else, and so the screen is the only
    // thing that can move them.
    public static ModConfigSpec.BooleanValue enabledEntry() { return ENABLED; }
    public static ModConfigSpec.DoubleValue spacingEntry() { return SPACING; }
    public static ModConfigSpec.DoubleValue rarityEntry() { return RARITY; }
    public static ModConfigSpec.DoubleValue oceanBiasEntry() { return OCEAN_BIAS; }
    public static ModConfigSpec.IntValue minWidthEntry() { return MIN_WIDTH; }
    public static ModConfigSpec.IntValue maxWidthEntry() { return MAX_WIDTH; }
    public static ModConfigSpec.DoubleValue wallRoughnessEntry() { return WALL_ROUGHNESS; }
    public static ModConfigSpec.DoubleValue wallScaleEntry() { return WALL_SCALE; }
    public static ModConfigSpec.DoubleValue floorNarrowingEntry() { return FLOOR_NARROWING; }
    public static ModConfigSpec.BooleanValue removeBedrockEntry() { return REMOVE_BEDROCK; }
    public static ModConfigSpec.BooleanValue drainWaterEntry() { return DRAIN_WATER; }
    public static ModConfigSpec.BooleanValue blockStructuresEntry() { return BLOCK_STRUCTURES; }
    public static ModConfigSpec.IntValue searchRadiusEntry() { return SEARCH_RADIUS; }

    // Reading a ModConfigSpec value before its file is attached throws, and throwing out of a chunk
    // worker is unacceptable. The first version of this gated on a ModConfigEvent listener and
    // defaulted to "disabled" when that listener had not fired, which silently disabled the entire
    // mod when the server config file was never written. Ask the spec directly instead, and fall
    // back to the built in defaults rather than to doing nothing: a mod that quietly does nothing is
    // far harder to diagnose than one that quietly uses its defaults.
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    private static final boolean DEF_ENABLED = true;
    private static final double DEF_SPACING = 5000.0D;
    private static final double DEF_RARITY = 0.0D;
    private static final int DEF_MIN_WIDTH = 200;
    private static final int DEF_MAX_WIDTH = 700;
    private static final double DEF_WALL_ROUGHNESS = 22.0D;
    private static final double DEF_WALL_SCALE = 0.28D;
    private static final double DEF_FLOOR_NARROWING = 0.62D;
    private static final double DEF_TERRACE_STRENGTH = 0.55D;
    private static final int DEF_TERRACE_COUNT = 7;
    private static final double DEF_OCEAN_BIAS = 0.35D;
    private static final int DEF_SEARCH_RADIUS = 12000;
    private static final List<String> DEF_DIMENSIONS = List.of("minecraft:overworld");

    public static boolean enabled() {
        return isLoaded() ? ENABLED.get() : DEF_ENABLED;
    }

    public static double chasmSpacing() {
        return isLoaded() ? SPACING.get() : DEF_SPACING;
    }

    public static double rarity() {
        return isLoaded() ? RARITY.get() : DEF_RARITY;
    }

    public static int minWidth() {
        return Math.min(rawMinWidth(), rawMaxWidth());
    }

    public static int maxWidth() {
        return Math.max(rawMinWidth(), rawMaxWidth());
    }

    private static int rawMinWidth() {
        return isLoaded() ? MIN_WIDTH.get() : DEF_MIN_WIDTH;
    }

    private static int rawMaxWidth() {
        return isLoaded() ? MAX_WIDTH.get() : DEF_MAX_WIDTH;
    }

    public static double wallRoughness() {
        return isLoaded() ? WALL_ROUGHNESS.get() : DEF_WALL_ROUGHNESS;
    }

    public static double wallScale() {
        return isLoaded() ? WALL_SCALE.get() : DEF_WALL_SCALE;
    }

    public static double floorNarrowing() {
        return isLoaded() ? FLOOR_NARROWING.get() : DEF_FLOOR_NARROWING;
    }

    public static double terraceStrength() {
        return isLoaded() ? TERRACE_STRENGTH.get() : DEF_TERRACE_STRENGTH;
    }

    public static int terraceCount() {
        return isLoaded() ? TERRACE_COUNT.get() : DEF_TERRACE_COUNT;
    }

    public static ModConfigSpec.DoubleValue terraceStrengthEntry() { return TERRACE_STRENGTH; }
    public static ModConfigSpec.IntValue terraceCountEntry() { return TERRACE_COUNT; }

    public static double oceanBias() {
        return isLoaded() ? OCEAN_BIAS.get() : DEF_OCEAN_BIAS;
    }

    public static int searchRadius() {
        return isLoaded() ? SEARCH_RADIUS.get() : DEF_SEARCH_RADIUS;
    }

    public static boolean removeBedrock() {
        return isLoaded() ? REMOVE_BEDROCK.get() : true;
    }

    public static boolean drainWater() {
        return isLoaded() ? DRAIN_WATER.get() : true;
    }

    public static boolean blockStructures() {
        return isLoaded() ? BLOCK_STRUCTURES.get() : true;
    }

    public static List<? extends String> dimensions() {
        return isLoaded() ? DIMENSIONS.get() : DEF_DIMENSIONS;
    }
}
