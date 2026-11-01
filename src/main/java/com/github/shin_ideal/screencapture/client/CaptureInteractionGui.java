package com.github.shin_ideal.screencapture.client;

import com.github.shin_ideal.screencapture.Config;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

public class CaptureInteractionGui extends GuiScreen {

    private float lastNormX = 0.5F;
    private float lastNormY = 0.5F;
    private float prevSentX = -1.0F;
    private float prevSentY = -1.0F;

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        super.initGui();
        // Disable repeat events so holding STG movement/shot keys never spams synthetic keydowns
        Keyboard.enableRepeatEvents(false);
        SharedMemoryBridge.setInteractionMode(1);
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        SharedMemoryBridge.setInteractionMode(0);
        CaptureSession.sendRawInput((byte) 7, 0, 0, 0); // Reset inputs
    }

    public void pollInputsNow() {
        SharedMemoryBridge.setInteractionMode(org.lwjgl.opengl.Display.isActive() ? 1 : 0);
        this.handleInput();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Drain keyboard & mouse events on every render frame (260Hz) instead of waiting for Minecraft's 20 TPS (50ms) tick!
        pollInputsNow();

        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        updateNormalizedCoords(sr);

        // Draw guidance bar at bottom with auto-fitting so text never clips on any GUI scale
        String status = "§a[操作モード] §f入力転送中 | §e[Shift+ESC] で終了";
        int screenW = sr.getScaledWidth();
        int screenH = sr.getScaledHeight();
        int strW = fontRendererObj.getStringWidth(status);

        float scale = Math.min(1.0F, (float) (screenW - 16) / Math.max(1.0F, (float) strW));
        int barH = 18;
        int barY = screenH - barH;

        drawRect(0, barY, screenW, screenH, 0xCC000000);

        org.lwjgl.opengl.GL11.glPushMatrix();
        if (scale < 1.0F) {
            org.lwjgl.opengl.GL11.glTranslatef(screenW / 2.0F, barY + (barH - 9.0F * scale) / 2.0F, 0.0F);
            org.lwjgl.opengl.GL11.glScalef(scale, scale, 1.0F);
            fontRendererObj.drawStringWithShadow(status, -strW / 2, 0, 0xFFFFFF);
        } else {
            int textX = (screenW - strW) / 2;
            int textY = barY + (barH - 9) / 2;
            fontRendererObj.drawStringWithShadow(status, textX, textY, 0xFFFFFF);
        }
        org.lwjgl.opengl.GL11.glPopMatrix();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void updateNormalizedCoords(ScaledResolution sr) {
        int texW = CaptureTexture.getWidth();
        int texH = CaptureTexture.getHeight();

        // Native physical mouse coordinates (unscaled)
        int physMouseX = Mouse.getX();
        int physMouseY = mc.displayHeight - Mouse.getY();
        int scale = sr.getScaleFactor();

        if (Config.overlayEnabled && CaptureOverlay.isVisible() && texW > 0 && texH > 0) {
            int drawW = Config.overlayWidth;
            int drawH = Math.max(1, (int) (drawW * ((float) texH / (float) texW)));
            int ox = sr.getScaledWidth() - drawW - Config.overlayMargin;
            int oy = Config.overlayMargin;

            int oxPhys = ox * scale;
            int oyPhys = oy * scale;
            int drawWPhys = drawW * scale;
            int drawHPhys = drawH * scale;

            // If physical mouse is inside physical overlay, map to overlay bounds
            if (physMouseX >= oxPhys && physMouseX <= oxPhys + drawWPhys &&
                physMouseY >= oyPhys && physMouseY <= oyPhys + drawHPhys) {
                lastNormX = Math.max(0.0F, Math.min(1.0F, (float) (physMouseX - oxPhys) / (float) drawWPhys));
                lastNormY = Math.max(0.0F, Math.min(1.0F, (float) (physMouseY - oyPhys) / (float) drawHPhys));
            } else {
                lastNormX = Math.max(0.0F, Math.min(1.0F, (float) physMouseX / (float) mc.displayWidth));
                lastNormY = Math.max(0.0F, Math.min(1.0F, (float) physMouseY / (float) mc.displayHeight));
            }
        } else {
            lastNormX = Math.max(0.0F, Math.min(1.0F, (float) physMouseX / (float) mc.displayWidth));
            lastNormY = Math.max(0.0F, Math.min(1.0F, (float) physMouseY / (float) mc.displayHeight));
        }

        // Send high-precision mouse move directly to shared memory
        if (Math.abs(lastNormX - prevSentX) > 0.0001F || Math.abs(lastNormY - prevSentY) > 0.0001F) {
            prevSentX = lastNormX;
            prevSentY = lastNormY;
            CaptureSession.sendRawInput((byte) 1, 0, lastNormX, lastNormY);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        CaptureSession.sendRawInput((byte) 2, button, lastNormX, lastNormY);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        super.mouseMovedOrUp(mouseX, mouseY, which);
        if (which >= 0) {
            CaptureSession.sendRawInput((byte) 3, which, lastNormX, lastNormY);
        }
    }

    @Override
    public void handleMouseInput() {
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        updateNormalizedCoords(sr);

        super.handleMouseInput();

        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            CaptureSession.sendRawInput((byte) 4, dWheel, 0, 0);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // Prevent default GuiScreen behavior which closes GUI on single ESC (keyCode == 1)
    }

    @Override
    public void handleKeyboardInput() {
        super.handleKeyboardInput();

        int key = Keyboard.getEventKey();
        boolean state = Keyboard.getEventKeyState();

        if (state) {
            boolean isShiftDown = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            if (key == Keyboard.KEY_ESCAPE && isShiftDown) {
                mc.displayGuiScreen(null);
                return;
            }

            int vk = CaptureInputHelper.toVirtualKey(key);
            if (vk != 0) {
                CaptureSession.sendRawInput((byte) 5, vk, 0, 0);
            }
        } else {
            int vk = CaptureInputHelper.toVirtualKey(key);
            if (vk != 0) {
                CaptureSession.sendRawInput((byte) 6, vk, 0, 0);
            }
        }
    }
}
