package com.github.shin_ideal.screencapture.client;

import com.github.shin_ideal.screencapture.Config;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import org.lwjgl.opengl.GL11;

/**
 * Client-only world-space projection of the capture texture. No server / block entity required.
 */
@SideOnly(Side.CLIENT)
public final class CaptureWorldProjection {

    private static volatile WorldScreenPlacement placement;

    public CaptureWorldProjection() {
    }

    public static WorldScreenPlacement getPlacement() {
        return placement;
    }

    public static boolean hasPlacement() {
        return placement != null;
    }

    public static void remove() {
        placement = null;
    }

    public static void setAt(double centerX, double centerY, double centerZ, WorldScreenFacing facing,
                             float widthBlocks) {
        float width = Math.max(0.5F, Math.min(64.0F, widthBlocks));
        WorldScreenFacing face = facing == null ? WorldScreenFacing.NORTH : facing;
        placement = new WorldScreenPlacement(centerX, centerY, centerZ, width, face);
    }

    public static boolean updateFacing(WorldScreenFacing facing) {
        WorldScreenPlacement current = placement;
        if (current == null || facing == null) {
            return false;
        }
        placement = new WorldScreenPlacement(
            current.centerX,
            current.centerY,
            current.centerZ,
            current.widthBlocks,
            facing);
        return true;
    }

    public static boolean updateSize(float widthBlocks) {
        WorldScreenPlacement current = placement;
        if (current == null) {
            return false;
        }
        float width = Math.max(0.5F, Math.min(64.0F, widthBlocks));
        placement = new WorldScreenPlacement(
            current.centerX,
            current.centerY,
            current.centerZ,
            width,
            current.facing);
        return true;
    }

    /**
     * Current size, or config default when nothing is placed yet.
     */
    public static float currentOrDefaultWidth() {
        WorldScreenPlacement current = placement;
        return current != null ? current.widthBlocks : Config.worldScreenWidth;
    }

    /**
     * Current facing, or null when nothing is placed yet.
     */
    public static WorldScreenFacing currentFacingOrNull() {
        WorldScreenPlacement current = placement;
        return current != null ? current.facing : null;
    }

    public static boolean setFromLook(float widthBlocks) {
        return setFromLook(widthBlocks, null);
    }

    /**
     * Places a screen on the block face under the crosshair, or floating in front of the player.
     *
     * @param facingOverride optional facing; null keeps auto-detect from look / hit face
     * @return true if placement succeeded
     */
    public static boolean setFromLook(float widthBlocks, WorldScreenFacing facingOverride) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityClientPlayerMP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            return false;
        }

        float width = Math.max(0.5F, Math.min(64.0F, widthBlocks));
        MovingObjectPosition mop = mc.objectMouseOver;

        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            WorldScreenFacing hitFace = WorldScreenFacing.fromBlockSide(mop.sideHit);
            if (hitFace != null) {
                double cx = mop.blockX + 0.5D + hitFace.normalX * 0.51D;
                double cy = mop.blockY + 0.5D;
                double cz = mop.blockZ + 0.5D + hitFace.normalZ * 0.51D;
                WorldScreenFacing facing = facingOverride != null ? facingOverride : hitFace;
                placement = new WorldScreenPlacement(cx, cy, cz, width, facing);
                return true;
            }

            // Floor / ceiling: vertical screen at the hit point.
            WorldScreenFacing facing = facingOverride != null ? facingOverride
                : WorldScreenFacing.facingPlayer(player.rotationYaw);
            double cx = mop.hitVec.xCoord;
            double cz = mop.hitVec.zCoord;
            double cy = mop.sideHit == 1 ? mop.hitVec.yCoord + width * 0.35D
                : mop.hitVec.yCoord - width * 0.35D;
            placement = new WorldScreenPlacement(cx, cy, cz, width, facing);
            return true;
        }

        // Air / entity: place a few blocks ahead of the player.
        Vec3 look = player.getLook(1.0F);
        double dist = 3.0D;
        double cx = player.posX + look.xCoord * dist;
        double cy = player.posY + player.getEyeHeight() + look.yCoord * dist;
        double cz = player.posZ + look.zCoord * dist;
        WorldScreenFacing facing = facingOverride != null ? facingOverride
            : WorldScreenFacing.facingPlayer(player.rotationYaw);
        placement = new WorldScreenPlacement(cx, cy, cz, width, facing);
        return true;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        WorldScreenPlacement current = placement;
        if (current == null || !CaptureSession.isRunning()) {
            return;
        }

        // Ensure frames update even if HUD overlay is hidden / disabled.
        CaptureTexture.tick();
        if (!CaptureTexture.hasFrame()) {
            return;
        }

        int texW = CaptureTexture.getWidth();
        int texH = CaptureTexture.getHeight();
        if (texW <= 0 || texH <= 0) {
            return;
        }

        float drawW = current.widthBlocks;
        float drawH = drawW * ((float) texH / (float) texW);
        float halfW = drawW * 0.5F;
        float halfH = drawH * 0.5F;

        Minecraft mc = Minecraft.getMinecraft();
        EntityLivingBase viewer = mc.renderViewEntity;
        if (viewer == null) {
            return;
        }

        double px = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * event.partialTicks;
        double py = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * event.partialTicks;
        double pz = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * event.partialTicks;

        WorldScreenFacing facing = current.facing;
        double x0 = current.centerX - facing.rightX * halfW;
        double y0 = current.centerY - halfH;
        double z0 = current.centerZ - facing.rightZ * halfW;
        double x1 = current.centerX + facing.rightX * halfW;
        double y1 = current.centerY + halfH;
        double z1 = current.centerZ + facing.rightZ * halfW;

        mc.getTextureManager()
            .bindTexture(CaptureTexture.getLocation());

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        GL11.glTranslated(-px, -py, -pz);

        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        // Front
        tess.addVertexWithUV(x0, y0, z0, 0.0D, 1.0D);
        tess.addVertexWithUV(x1, y0, z1, 1.0D, 1.0D);
        tess.addVertexWithUV(x1, y1, z1, 1.0D, 0.0D);
        tess.addVertexWithUV(x0, y1, z0, 0.0D, 0.0D);
        tess.draw();

        GL11.glPopMatrix();
        GL11.glPopAttrib();
    }
}
