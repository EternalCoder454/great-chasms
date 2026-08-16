package com.zachary.greatchasms;

import com.mojang.logging.LogUtils;
import com.zachary.greatchasms.chasm.ChasmCarver;
import com.zachary.greatchasms.chasm.ChasmField;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

@Mod(GreatChasms.MOD_ID)
public final class GreatChasms {

    public static final String MOD_ID = "greatchasms";
    public static final Logger LOGGER = LogUtils.getLogger();

    public GreatChasms(ModContainer container, IEventBus modBus) {
        container.registerConfig(ModConfig.Type.SERVER, ChasmConfig.SPEC);

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ChasmCommands.register(event));

        // Fields are cached per world seed. Without this a client that joins many single player
        // worlds in one session would retain a field for every seed it has ever seen.
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            ChasmField.clearCache();
            ChasmCarver.resetAnnouncement();
        });
    }
}
