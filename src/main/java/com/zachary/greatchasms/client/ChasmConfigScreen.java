package com.zachary.greatchasms.client;

import com.zachary.greatchasms.ChasmConfig;
import com.zachary.greatchasms.chasm.ChasmField;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The optional Cloth Config screen.
 * <p>
 * Everything that touches Cloth lives in this class and nothing else references it, so the JVM only
 * ever loads it when Cloth is installed and the player actually opens the screen. That is what keeps
 * Cloth a genuinely optional dependency rather than a soft-required one.
 * <p>
 * The settings split into two groups that behave very differently, and the screen says so rather
 * than pretending they are alike:
 * <ul>
 *   <li><b>General</b> is read fresh on every call, so changes take effect the moment you save.</li>
 *   <li><b>Shape</b> is snapshotted into {@link ChasmField} when a world's field is first built,
 *       because chasm geometry has to stay fixed for chunks to line up along their shared borders.
 *       Saving invalidates that snapshot so the new values are picked up, but only chunks generated
 *       afterwards use them, which means a visible seam where new terrain meets old.</li>
 * </ul>
 */
public final class ChasmConfigScreen {

    private ChasmConfigScreen() {
    }

    private static final Component SHAPE_WARNING = Component
            .literal("Applies to newly generated chunks only. Terrain already generated keeps its old shape, so changing this mid-world leaves a seam.")
            .withStyle(ChatFormatting.GOLD);

    public static Screen create(Screen parent) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parent)
                .setTitle(Component.literal("Great Chasms"))
                // Values are written straight into the live ModConfigSpec, so a discard has to put
                // the screen's own state back rather than the spec's.
                .setDoesConfirmSave(false);

        ConfigEntryBuilder entry = builder.entryBuilder();

        // ---- General: read per call, so these are live the instant they are saved ----
        ConfigCategory general = builder.getOrCreateCategory(
                Component.literal("General").withStyle(ChatFormatting.WHITE));

        general.addEntry(entry.startBooleanToggle(Component.literal("Enabled"), ChasmConfig.enabled())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Master switch. Takes effect immediately for any chunk generated from now on."))
                .setSaveConsumer(v -> ChasmConfig.enabledEntry().set(v))
                .build());

        general.addEntry(entry.startBooleanToggle(Component.literal("Block structures in chasms"), ChasmConfig.blockStructures())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Stop structures starting in a chunk a chasm runs through, so nothing hangs over the drop."),
                        Component.literal("Live: applies to the next chunk generated.").withStyle(ChatFormatting.GREEN))
                .setSaveConsumer(v -> ChasmConfig.blockStructuresEntry().set(v))
                .build());

        general.addEntry(entry.startIntSlider(Component.literal("Locate search radius"), ChasmConfig.searchRadius(), 512, 60000)
                .setDefaultValue(12000)
                .setTextGetter(v -> Component.literal(v + " blocks"))
                .setTooltip(Component.literal("How far /greatchasms locate looks before giving up."),
                        Component.literal("Live: used by the very next command.").withStyle(ChatFormatting.GREEN))
                .setSaveConsumer(v -> ChasmConfig.searchRadiusEntry().set(v))
                .build());

        // ---- Shape: snapshotted per world, so these only affect new terrain ----
        ConfigCategory shape = builder.getOrCreateCategory(
                Component.literal("Shape").withStyle(ChatFormatting.WHITE));

        shape.addEntry(entry.startTextDescription(Component.literal(
                        "These control chasm geometry. Chasm shape must stay fixed for a world so chunks line up along their borders, so changes here only affect terrain generated afterwards.")
                .withStyle(ChatFormatting.GRAY)).build());

        shape.addEntry(entry.startDoubleField(Component.literal("Spacing"), ChasmConfig.chasmSpacing())
                .setDefaultValue(5000.0D)
                .setMin(256.0D).setMax(60000.0D)
                .setTooltip(Component.literal("Wavelength of the field chasms are cut from, in blocks. The single biggest control over how LONG a chasm is, because a chasm follows a contour and contours are only as long as the field is smooth. Also sets how far apart they are."),
                        SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.spacingEntry().set(v))
                .build());

        shape.addEntry(entry.startDoubleField(Component.literal("Rarity"), ChasmConfig.rarity())
                .setDefaultValue(0.0D)
                .setMin(-1.0D).setMax(0.95D)
                .setTooltip(Component.literal("How much of the world may contain chasms. Higher is rarer. Near 0.0 the eligible area joins into one connected network, which is what lets a chasm run its full length."),
                        SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.rarityEntry().set(v))
                .build());

        shape.addEntry(entry.startDoubleField(Component.literal("Ocean bias"), ChasmConfig.oceanBias())
                .setDefaultValue(0.35D)
                .setMin(0.0D).setMax(1.5D)
                .setTooltip(Component.literal("How much more readily chasms form under deep water. Driven by sea floor depth rather than biome, so it fades smoothly across a coast instead of stepping at the waterline."),
                        SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.oceanBiasEntry().set(v))
                .build());

        shape.addEntry(entry.startIntSlider(Component.literal("Minimum width"), ChasmConfig.minWidth(), 8, 2048)
                .setDefaultValue(200)
                .setTextGetter(v -> Component.literal(v + " blocks"))
                .setTooltip(Component.literal("Narrowest a chasm gets at its rim. A floor, not a target."), SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.minWidthEntry().set(v))
                .build());

        shape.addEntry(entry.startIntSlider(Component.literal("Maximum width"), ChasmConfig.maxWidth(), 8, 2048)
                .setDefaultValue(700)
                .setTextGetter(v -> Component.literal(v + " blocks"))
                .setTooltip(Component.literal("Widest a chasm gets at its rim. At 700 the far side is beyond normal render distance."), SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.maxWidthEntry().set(v))
                .build());

        shape.addEntry(entry.startDoubleField(Component.literal("Wall scale"), ChasmConfig.wallScale())
                .setDefaultValue(0.28D)
                .setMin(0.0D).setMax(0.8D)
                .setTooltip(Component.literal("Coarse wall shape as a fraction of the chasm's own width, which is what makes walls wander into bays and headlands rather than two parallel planes. 0 gives dead straight walls."),
                        SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.wallScaleEntry().set(v))
                .build());

        shape.addEntry(entry.startDoubleField(Component.literal("Wall roughness"), ChasmConfig.wallRoughness())
                .setDefaultValue(22.0D)
                .setMin(0.0D).setMax(96.0D)
                .setTooltip(Component.literal("Fine wall detail in blocks, the rock texture scale. 0 gives glassy smooth sides."), SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.wallRoughnessEntry().set(v))
                .build());

        shape.addEntry(entry.startDoubleField(Component.literal("Floor narrowing"), ChasmConfig.floorNarrowing())
                .setDefaultValue(0.42D)
                .setMin(0.05D).setMax(1.0D)
                .setTooltip(Component.literal("Width at the world floor as a fraction of the rim width. 1.0 gives vertical walls, lower values give a tapering profile. Never reaches 0, so the chasm stays open to the void."),
                        SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.floorNarrowingEntry().set(v))
                .build());

        shape.addEntry(entry.startBooleanToggle(Component.literal("Carve through bedrock"), ChasmConfig.removeBedrock())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Remove the bedrock floor so the chasm opens into the void. Off leaves a very large but ordinary ravine."),
                        SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.removeBedrockEntry().set(v))
                .build());

        shape.addEntry(entry.startBooleanToggle(Component.literal("Drain water above chasms"), ChasmConfig.drainWater())
                .setDefaultValue(true)
                .setTooltip(Component.literal("Empty the sea sitting directly over a chasm so it does not simply fill during generation. Water still flows back in from the sides, which is intended."),
                        SHAPE_WARNING)
                .setSaveConsumer(v -> ChasmConfig.drainWaterEntry().set(v))
                .build());

        builder.setSavingRunnable(() -> {
            ChasmConfig.SPEC.save();
            // Shape values are snapshotted per world seed when a field is first built. Without this
            // the file would hold the new numbers while generation quietly carried on using the old
            // ones until the world was reloaded, which is the sort of silent no-op that is very hard
            // to tell apart from "the setting does nothing".
            ChasmField.clearCache();
        });

        return builder.build();
    }
}
