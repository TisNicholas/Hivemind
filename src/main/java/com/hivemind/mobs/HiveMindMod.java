package com.hivemind.mobs;

import org.apache.logging.log4j.Logger;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod(modid = HiveMindMod.MODID, name = "HiveMind", version = "1.0.0",
        acceptedMinecraftVersions = "[1.12.2]", acceptableRemoteVersions = "*")
public class HiveMindMod {
    public static final String MODID = "hivemind";
    public static Logger LOG;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        LOG = e.getModLog();
        MinecraftForge.EVENT_BUS.register(HiveManager.INSTANCE);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent e) {
        HiveManager.INSTANCE.onServerStart();
        e.registerServerCommand(new CommandHiveMind());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent e) {
        HiveManager.INSTANCE.onServerStop();
    }

    @Mod.EventBusSubscriber(modid = MODID)
    public static class ConfigHandler {
        @SubscribeEvent
        public static void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent e) {
            if (MODID.equals(e.getModID())) {
                ConfigManager.sync(MODID, Config.Type.INSTANCE);
            }
        }
    }
}
