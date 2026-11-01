package com.github.shin_ideal.screencapture.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Ultra-low latency Direct OpenGL texture uploader with double-buffered GL textures.
 * Eliminates GPU pipeline stalls when updating textures that are still in flight from the previous frame.
 */
public final class CaptureTexture {

    private static final ResourceLocation LOCATION = new ResourceLocation("screencapture", "dynamic_screen");
    private static DirectTexture directTexture;
    private static final int[] glTextureIds = new int[2];
    private static final int[] slotWidths = new int[2];
    private static final int[] slotHeights = new int[2];
    private static int activeSlot = 0;
    private static int glTextureId = 0;
    private static int width = 0;
    private static int height = 0;
    private static boolean hasFrame = false;
    private static final int[] dimBuffer = new int[2];

    private static class DirectTexture extends AbstractTexture {
        @Override
        public void loadTexture(IResourceManager resourceManager) {
        }

        void setId(int id) {
            this.glTextureId = id;
        }
    }

    private CaptureTexture() {
    }

    public static void tick() {
        // 1. Zero-Copy Shared Memory Frame Check (Fastest, sub-millisecond, zero GC)
        if (SharedMemoryBridge.isActive() || SharedMemoryBridge.isAvailable()) {
            ByteBuffer shmBuf = SharedMemoryBridge.pollFrame(dimBuffer);
            if (shmBuf != null) {
                uploadBuffer(shmBuf, dimBuffer[0], dimBuffer[1], GL12.GL_BGRA);
                return;
            }
            if (SharedMemoryBridge.isCompanionAlive()) {
                // Keep current texture when no new SHM frame arrived in this sub-millisecond interval.
                // Never fall through to delayed WebSocket frames while SharedMemory is active.
                return;
            }
        }

        // 2. WebSocket / RAW fallback (only when SharedMemory is not active)
        RawFrame rawFrame = CaptureSession.pollLatestRawFrame();
        if (rawFrame != null) {
            uploadDirect(rawFrame);
            return;
        }
        BufferedImage frame = CaptureSession.pollLatestFrame();
        if (frame != null) {
            uploadLegacy(frame);
        }
    }

    public static boolean hasFrame() {
        return hasFrame && glTextureId != 0;
    }

    public static ResourceLocation getLocation() {
        return LOCATION;
    }

    public static int getWidth() {
        return width;
    }

    public static int getHeight() {
        return height;
    }

    public static int getGlTextureId() {
        return glTextureId;
    }

    public static void bind() {
        if (glTextureId != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        } else {
            Minecraft.getMinecraft().getTextureManager().bindTexture(LOCATION);
        }
    }

    public static void clear() {
        for (int i = 0; i < 2; i++) {
            if (glTextureIds[i] != 0) {
                TextureUtil.deleteTexture(glTextureIds[i]);
                glTextureIds[i] = 0;
            }
            slotWidths[i] = 0;
            slotHeights[i] = 0;
        }
        glTextureId = 0;
        width = 0;
        height = 0;
        hasFrame = false;
    }

    private static void ensureRegistered() {
        if (directTexture == null) {
            directTexture = new DirectTexture();
            Minecraft.getMinecraft().getTextureManager().loadTexture(LOCATION, directTexture);
        }
    }

    private static void uploadDirect(RawFrame rawFrame) {
        int w = rawFrame.getWidth();
        int h = rawFrame.getHeight();
        int fmt = rawFrame.getFormat();
        ByteBuffer buf = rawFrame.getDirectBuffer();
        int glPixelFormat = (fmt == 3) ? GL12.GL_BGRA : GL11.GL_RGBA;
        uploadBuffer(buf, w, h, glPixelFormat);
    }

    public static void uploadBuffer(ByteBuffer buf, int w, int h, int glPixelFormat) {
        if (buf == null || w <= 0 || h <= 0) {
            return;
        }

        ensureRegistered();

        int nextSlot = 1 - activeSlot;
        int texId = glTextureIds[nextSlot];
        if (texId == 0) {
            texId = GL11.glGenTextures();
            glTextureIds[nextSlot] = texId;
        }

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

        int dataType = (glPixelFormat == GL12.GL_BGRA)
            ? GL12.GL_UNSIGNED_INT_8_8_8_8_REV
            : GL11.GL_UNSIGNED_BYTE;

        if (w != slotWidths[nextSlot] || h != slotHeights[nextSlot]) {
            slotWidths[nextSlot] = w;
            slotHeights[nextSlot] = h;
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            buf.position(0);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, glPixelFormat, dataType, buf);
        } else {
            buf.position(0);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, w, h, glPixelFormat, dataType, buf);
        }

        activeSlot = nextSlot;
        glTextureId = texId;
        directTexture.setId(texId);
        width = w;
        height = h;
        hasFrame = true;
    }

    private static void uploadLegacy(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w <= 0 || h <= 0) return;

        ensureRegistered();

        int[] pixels = new int[w * h];
        image.getRGB(0, 0, w, h, pixels, 0, w);

        int nextSlot = 1 - activeSlot;
        int texId = glTextureIds[nextSlot];
        if (texId == 0 || w != slotWidths[nextSlot] || h != slotHeights[nextSlot]) {
            if (texId != 0) {
                TextureUtil.deleteTexture(texId);
            }
            texId = TextureUtil.glGenTextures();
            glTextureIds[nextSlot] = texId;
            slotWidths[nextSlot] = w;
            slotHeights[nextSlot] = h;
            TextureUtil.allocateTexture(texId, w, h);
        }

        TextureUtil.uploadTexture(texId, pixels, w, h);
        activeSlot = nextSlot;
        glTextureId = texId;
        directTexture.setId(texId);
        width = w;
        height = h;
        hasFrame = true;
    }
}
