package com.github.shin_ideal.screencapture;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class Config {

    public static int serverPort = 8765;
    public static int captureFps = 30;
    public static int maxFrameWidth = 1280;
    public static int overlayWidth = 320;
    public static int overlayMargin = 8;
    public static boolean overlayEnabled = true;
    public static float worldScreenWidth = 2.0F;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        serverPort = configuration.getInt(
            "serverPort",
            Configuration.CATEGORY_GENERAL,
            serverPort,
            1024,
            65535,
            "Local HTTP port for the self-hosted capture page (bound to 127.0.0.1).");
        captureFps = configuration.getInt(
            "captureFps",
            Configuration.CATEGORY_GENERAL,
            captureFps,
            1,
            60,
            "Target frame rate sent from the browser to Minecraft.");
        maxFrameWidth = configuration.getInt(
            "maxFrameWidth",
            Configuration.CATEGORY_GENERAL,
            maxFrameWidth,
            160,
            3840,
            "Maximum width of frames encoded in the browser before upload.");
        overlayWidth = configuration.getInt(
            "overlayWidth",
            Configuration.CATEGORY_GENERAL,
            overlayWidth,
            64,
            1920,
            "Width of the in-game picture-in-picture overlay in GUI pixels.");
        overlayMargin = configuration.getInt(
            "overlayMargin",
            Configuration.CATEGORY_GENERAL,
            overlayMargin,
            0,
            200,
            "Margin from the top-right corner for the overlay.");
        overlayEnabled = configuration.getBoolean(
            "overlayEnabled",
            Configuration.CATEGORY_GENERAL,
            overlayEnabled,
            "Whether the capture overlay is drawn when a stream is active.");
        worldScreenWidth = (float) configuration.get(
                Configuration.CATEGORY_GENERAL,
                "worldScreenWidth",
                worldScreenWidth,
                "Default width in blocks for /screencapture set world projection.")
            .getDouble(worldScreenWidth);

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
