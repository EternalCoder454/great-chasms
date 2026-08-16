package com.zachary.greatchasms;

import com.mojang.logging.LogUtils;
import com.zachary.greatchasms.chasm.ChasmCarver;
import com.zachary.greatchasms.chasm.ChasmField;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(GreatChasms.MOD_ID)
public final class GreatChasms {

    public static final String MOD_ID = "greatchasms";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GreatChasms(ModContainer container, IEventBus modBus) {
        // COMMON, not SERVER.
        //
        // SERVER was chosen on the reasoning that worldgen settings should travel with the save. In
        // this setup they do not: the file lands in config/ globally, and nothing appears under
        // saves/<world>/serverconfig at all. So the scoping bought none of the benefit while costing
        // the whole of it, because a SERVER spec only has a Config object attached while a world is
        // loaded, which made the mod list Config button throw from the title screen.
        //
        // COMMON attaches at mod load, so the screen works from the main menu and from in game, and
        // the file stays exactly where it already was.
        container.registerConfig(ModConfig.Type.COMMON, ChasmConfig.SPEC);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ChasmCommands.register(event));

        registerConfigScreen(container);

        // Editing the file directly, or another mod reloading the config, must invalidate the same
        // snapshot the config screen invalidates. Otherwise the file and the running generation
        // disagree and the setting looks like it does nothing.
        modBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == ChasmConfig.SPEC) {
                ChasmField.clearCache();
            }
        });

        // Fields are cached per world seed. Without this a client that joins many single player
        // worlds in one session would retain a field for every seed it has ever seen.
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            ChasmField.clearCache();
            ChasmCarver.resetAnnouncement();
        });
    }

    /**
     * Registers the Cloth Config screen, if Cloth is installed and we are on a client.
     * <p>
     * The lambda is the only thing that ever names {@code ChasmConfigScreen}, and that class is the
     * only thing that names Cloth. Because the JVM resolves a class at first use, none of Cloth is
     * loaded unless someone actually opens the screen, which is what makes the dependency optional
     * rather than merely declared optional. The whole thing is wrapped as well, so a Cloth version
     * whose API has moved costs you the button rather than the game.
     */
    private static void registerConfigScreen(ModContainer container) {
        if (FMLEnvironment.getDist() != Dist.CLIENT) {
            return;
        }
        if (!ModList.get().isLoaded("cloth_config")) {
            LOGGER.info("Cloth Config not installed, skipping the config screen. Edit the config file instead.");
            return;
        }
        try {
            container.registerExtensionPoint(IConfigScreenFactory.class,
                    (c, parent) -> com.zachary.greatchasms.client.ChasmConfigScreen.create(parent));
            LOGGER.info("Registered Cloth Config screen");
        } catch (Throwable t) {
            LOGGER.warn("Could not register the Cloth Config screen, continuing without it", t);
        }
    }
}
