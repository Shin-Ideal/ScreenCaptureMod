package com.github.shin_ideal.screencapture.client;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import com.github.shin_ideal.screencapture.Config;
import com.github.shin_ideal.screencapture.ScreenCaptureMod;

public final class CaptureSession {

    private static CaptureHttpServer server;
    private static final AtomicReference<BufferedImage> latestFrame = new AtomicReference<>();
    private static final AtomicLong frameCount = new AtomicLong();
    private static volatile boolean running;

    private CaptureSession() {}

    public static void init() {
        // Eager class load on client.
    }

    public static synchronized void start() throws IOException {
        if (running) {
            openBrowser();
            return;
        }

        server = new CaptureHttpServer(Config.serverPort);
        server.start();
        running = true;
        ScreenCaptureMod.LOG.info("Local capture server listening on " + getCaptureUrl());
        openBrowser();
    }

    public static synchronized void stop() {
        running = false;
        if (server != null) {
            server.stop();
            server = null;
        }
        latestFrame.set(null);
        CaptureTexture.clear();
        ScreenCaptureMod.LOG.info("Local capture server stopped");
    }

    public static boolean isRunning() {
        return running;
    }

    public static String getCaptureUrl() {
        return "http://127.0.0.1:" + Config.serverPort + "/";
    }

    public static long getFrameCount() {
        return frameCount.get();
    }

    public static void acceptJpegFrame(byte[] jpegBytes) {
        if (!running || jpegBytes == null || jpegBytes.length == 0) {
            return;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(jpegBytes));
            if (image != null) {
                latestFrame.set(image);
                frameCount.incrementAndGet();
            }
        } catch (IOException e) {
            ScreenCaptureMod.LOG.warn("Failed to decode capture frame", e);
        }
    }

    public static BufferedImage pollLatestFrame() {
        return latestFrame.getAndSet(null);
    }

    private static void openBrowser() {
        String url = getCaptureUrl();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop()
                .isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop()
                    .browse(new URI(url));
                return;
            }
        } catch (Exception e) {
            ScreenCaptureMod.LOG.warn("Desktop.browse failed, trying OS fallback", e);
        }

        try {
            String os = System.getProperty("os.name", "")
                .toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime()
                    .exec(new String[] { "rundll32", "url.dll,FileProtocolHandler", url });
            } else if (os.contains("mac")) {
                Runtime.getRuntime()
                    .exec(new String[] { "open", url });
            } else {
                Runtime.getRuntime()
                    .exec(new String[] { "xdg-open", url });
            }
        } catch (Exception e) {
            ScreenCaptureMod.LOG.error("Could not open browser for " + url, e);
            throw new IllegalStateException("Could not open browser. Open manually: " + url, e);
        }
    }
}
