package com.github.shin_ideal.screencapture.client;

import com.github.shin_ideal.screencapture.Config;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import org.lwjgl.opengl.GL11;

@SideOnly(Side.CLIENT)
public class CaptureOverlay {

    private static volatile boolean visible = true;

    public static void setVisible(boolean value) {
        visible = value;
    }

    public static boolean isVisible() {
        return visible;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        if (!Config.overlayEnabled || !CaptureSession.isRunning()) {
            return;
        }

        // Keep ingesting frames while hidden so /show can resume immediately.
        CaptureTexture.tick();
        if (!visible || !CaptureTexture.hasFrame()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);

        int texW = CaptureTexture.getWidth();
        int texH = CaptureTexture.getHeight();
        if (texW <= 0 || texH <= 0) {
            return;
        }

        int drawW = Config.overlayWidth;
        int drawH = Math.max(1, (int) (drawW * ((float) texH / (float) texW)));
        int x = sr.getScaledWidth() - drawW - Config.overlayMargin;
        int y = Config.overlayMargin;

        mc.getTextureManager()
            .bindTexture(CaptureTexture.getLocation());

        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glDisable(GL11.GL_LIGHTING);

        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertexWithUV(x, y + drawH, 0.0D, 0.0D, 1.0D);
        tess.addVertexWithUV(x + drawW, y + drawH, 0.0D, 1.0D, 1.0D);
        tess.addVertexWithUV(x + drawW, y, 0.0D, 1.0D, 0.0D);
        tess.addVertexWithUV(x, y, 0.0D, 0.0D, 0.0D);
        tess.draw();

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }
}
