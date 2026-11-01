package com.github.shin_ideal.screencapturecontrol;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = ScreenCaptureControlMod.MODID,
    version = Tags.VERSION,
    name = "ScreenCaptureControlMod",
    acceptedMinecraftVersions = "[1.7.10]")
public class ScreenCaptureControlMod {

    public static final String MODID = "screencapturecontrolmod";
    public static final Logger LOG = LogManager.getLogger("ScreenCaptureControlMod");

    @SidedProxy(
        clientSide = "com.github.shin_ideal.screencapturecontrol.ClientProxy",
        serverSide = "com.github.shin_ideal.screencapturecontrol.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        System.out.println("Hello ScreenCaptureControlMod!");
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
