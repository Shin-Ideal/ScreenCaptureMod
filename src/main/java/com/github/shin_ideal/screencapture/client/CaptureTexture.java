package com.github.shin_ideal.screencapture.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;

/**
 * Uploads the latest browser capture frame into an OpenGL texture on the client render thread.
 */
public final class CaptureTexture {

    private static DynamicTexture texture;
    private static ResourceLocation location;
    private static int width;
    private static int height;
    private static boolean hasFrame;

    private CaptureTexture() {
    }

    public static void tick() {
        BufferedImage frame = CaptureSession.pollLatestFrame();
        if (frame == null) {
            return;
        }
        upload(frame);
    }

    public static boolean hasFrame() {
        return hasFrame && location != null;
    }

    public static ResourceLocation getLocation() {
        return location;
    }

    public static int getWidth() {
        return width;
    }

    public static int getHeight() {
        return height;
    }

    public static void clear() {
        if (location != null) {
            Minecraft.getMinecraft()
                .getTextureManager()
                .deleteTexture(location);
        }
        texture = null;
        location = null;
        width = 0;
        height = 0;
        hasFrame = false;
    }

    private static void upload(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();

        if (texture == null || w != width || h != height) {
            if (location != null) {
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .deleteTexture(location);
            }
            texture = new DynamicTexture(w, h);
            location = Minecraft.getMinecraft()
                .getTextureManager()
                .getDynamicTextureLocation("screencapture_frame", texture);
            width = w;
            height = h;
        }

        int[] pixels = texture.getTextureData();
        image.getRGB(0, 0, w, h, pixels, 0, w);
        texture.updateDynamicTexture();
        hasFrame = true;
    }
}
