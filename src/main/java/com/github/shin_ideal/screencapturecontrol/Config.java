package com.github.shin_ideal.screencapturecontrol;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class Config {

    public static int overlayWidth = 320;
    public static int overlayMargin = 8;
    public static boolean overlayEnabled = true;
    public static float worldScreenWidth = 2.0F;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

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
                "Default width in blocks for /screencapturecontrol set world projection.")
            .getDouble(worldScreenWidth);

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
