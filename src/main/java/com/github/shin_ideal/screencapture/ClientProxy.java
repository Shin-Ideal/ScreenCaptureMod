package com.github.shin_ideal.screencapture;

import com.github.shin_ideal.screencapture.client.CaptureKeyHandler;
import com.github.shin_ideal.screencapture.client.CaptureOverlay;
import com.github.shin_ideal.screencapture.client.CaptureSession;
import com.github.shin_ideal.screencapture.client.CaptureWorldProjection;
import com.github.shin_ideal.screencapture.command.ScreenCaptureControlCommand;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        CaptureSession.init();
        MinecraftForge.EVENT_BUS.register(new CaptureOverlay());
        MinecraftForge.EVENT_BUS.register(new CaptureWorldProjection());
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        CaptureKeyHandler.init();
        ClientCommandHandler.instance.registerCommand(new ScreenCaptureControlCommand());
    }
}
