package com.github.shin_ideal.screencapture.client;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

@SideOnly(Side.CLIENT)
public class CaptureKeyHandler {

    private static volatile boolean openInteractGuiRequested = false;

    public static void requestOpenInteractGui() {
        openInteractGuiRequested = true;
    }

    public static final KeyBinding KEY_INTERACT = new KeyBinding(
        "key.screencapturecontrolmod.interact",
        Keyboard.KEY_O,
        "key.categories.screencapturecontrolmod"
    );

    public static void init() {
        ClientRegistry.registerKeyBinding(KEY_INTERACT);
        FMLCommonHandler.instance().bus().register(new CaptureKeyHandler());
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }

        if (event.phase == TickEvent.Phase.START) {
            // If requested via chat command, open GUI after GuiChat's close cleanup has finished
            if (openInteractGuiRequested) {
                openInteractGuiRequested = false;
                if (mc.thePlayer != null) {
                    mc.displayGuiScreen(new CaptureInteractionGui());
                }
            }

            // Apply VSync disable & 260 FPS cap directly on the OpenGL render thread
            CaptureSession.applyLowLatencyRenderSettingsOnGlThread();

            if (CaptureSession.isRunning()) {
                boolean inInteractGui = (mc.currentScreen instanceof CaptureInteractionGui) && Display.isActive();
                SharedMemoryBridge.setInteractionMode(inInteractGui ? 1 : 0);
                if (mc.currentScreen instanceof CaptureInteractionGui) {
                    ((CaptureInteractionGui) mc.currentScreen).pollInputsNow();
                }
                // Upload newest SharedMemory frame right before 3D world projection & 2D overlay render
                CaptureTexture.tick();
            } else {
                SharedMemoryBridge.setInteractionMode(0);
            }
        } else if (event.phase == TickEvent.Phase.END) {
            if (CaptureSession.isRunning() && mc.currentScreen instanceof CaptureInteractionGui) {
                ((CaptureInteractionGui) mc.currentScreen).pollInputsNow();
            }
        }
    }

    @SubscribeEvent
    public void onKeyInput(InputEvent.KeyInputEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }

        if (KEY_INTERACT.isPressed()) {
            if (CaptureSession.isRunning() && CaptureSession.isConnected()) {
                mc.displayGuiScreen(new CaptureInteractionGui());
            }
        }
    }
}
