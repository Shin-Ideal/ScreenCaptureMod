package com.github.shin_ideal.screencapturecontrol.client;

import com.github.shin_ideal.screencapturecontrol.ScreenCaptureControlMod;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * In-process JNI bridge to ScreenCaptureNative.dll (C++20 Windows Graphics Capture + Direct3D 11 + AVX2 + Win32 Input).
 * Verifies SHA-256 integrity of extracted native library to prevent DLL hijacking.
 */
public final class NativeCaptureBridge {

    private static volatile boolean loaded = false;
    private static volatile boolean loadFailed = false;
    private static boolean shutdownHookRegistered = false;

    private NativeCaptureBridge() {
    }

    public static synchronized boolean ensureLoaded() {
        if (loaded) {
            return true;
        }
        if (loadFailed) {
            return false;
        }

        try {
            File dllFile = extractAndVerifyNativeDll();
            if (dllFile == null || !dllFile.exists()) {
                ScreenCaptureControlMod.LOG.error("ScreenCaptureNative.dll could not be extracted or verified.");
                loadFailed = true;
                return false;
            }

            System.load(dllFile.getAbsolutePath());
            loaded = true;

            if (!shutdownHookRegistered) {
                Runtime.getRuntime().addShutdownHook(new Thread(NativeCaptureBridge::shutdown, "ScreenCaptureNative-Shutdown"));
                shutdownHookRegistered = true;
            }

            ScreenCaptureControlMod.LOG.info("Loaded verified C++ JNI library: " + dllFile.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            ScreenCaptureControlMod.LOG.error("Failed to load ScreenCaptureNative.dll", t);
            loadFailed = true;
            return false;
        }
    }

    private static File extractAndVerifyNativeDll() {
        try {
            byte[] dllBytes = readBundledDllBytes();
            if (dllBytes == null || dllBytes.length == 0) {
                ScreenCaptureControlMod.LOG.error("Failed to locate or read bundled natives/ScreenCaptureNative.dll from JAR resources.");
                return null;
            }

            byte[] expectedHash = sha256(dllBytes);
            String hashPrefix = toHexPrefix(expectedHash, 8);

            File baseDir = null;
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.mcDataDir != null) {
                    baseDir = mc.mcDataDir;
                }
            } catch (Throwable ignored) {
            }
            if (baseDir == null) {
                baseDir = new File(".");
            }

            File outDir = new File(baseDir, "screencapturecontrol");
            if (!outDir.exists() && !outDir.mkdirs()) {
                ScreenCaptureControlMod.LOG.error("Failed to create native output directory: " + outDir.getAbsolutePath());
                return null;
            }

            File target = new File(outDir, "ScreenCaptureNative.dll");
            if (target.exists() && target.isFile() && target.length() == dllBytes.length) {
                byte[] existingHash = sha256File(target);
                if (existingHash != null && Arrays.equals(expectedHash, existingHash)) {
                    return target;
                }
            }

            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(dllBytes);
                out.getFD().sync();
            } catch (Exception writeLockedEx) {
                // If locked by another running Minecraft instance, create a unique file in the same directory
                File tempDll = Files.createTempFile(outDir.toPath(), "ScreenCaptureNative_" + hashPrefix + "_", ".dll").toFile();
                tempDll.deleteOnExit();
                try (FileOutputStream out2 = new FileOutputStream(tempDll)) {
                    out2.write(dllBytes);
                    out2.getFD().sync();
                }
                target = tempDll;
            }

            byte[] writtenHash = sha256File(target);
            if (writtenHash == null || !Arrays.equals(expectedHash, writtenHash)) {
                ScreenCaptureControlMod.LOG.error("SHA-256 verification failed for extracted ScreenCaptureNative.dll");
                return null;
            }

            return target;
        } catch (Throwable t) {
            ScreenCaptureControlMod.LOG.error("Error extracting ScreenCaptureNative.dll", t);
            return null;
        }
    }

    private static byte[] readBundledDllBytes() throws Exception {
        if (Boolean.getBoolean("screencapture.dev")) {
            File devDll = new File("src/main/resources/natives/ScreenCaptureNative.dll");
            if (!devDll.exists()) {
                devDll = new File("../src/main/resources/natives/ScreenCaptureNative.dll");
            }
            if (devDll.exists() && devDll.isFile()) {
                return Files.readAllBytes(devDll.toPath());
            }
        }

        InputStream in = NativeCaptureBridge.class.getResourceAsStream("/natives/ScreenCaptureNative.dll");
        if (in == null && NativeCaptureBridge.class.getClassLoader() != null) {
            in = NativeCaptureBridge.class.getClassLoader().getResourceAsStream("natives/ScreenCaptureNative.dll");
        }
        if (in == null && Thread.currentThread().getContextClassLoader() != null) {
            in = Thread.currentThread().getContextClassLoader().getResourceAsStream("natives/ScreenCaptureNative.dll");
        }
        if (in == null) {
            in = NativeCaptureBridge.class.getResourceAsStream("natives/ScreenCaptureNative.dll");
        }

        if (in == null) {
            return null;
        }

        try (InputStream stream = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(131072);
            byte[] buf = new byte[16384];
            int read;
            while ((read = stream.read(buf)) != -1) {
                bos.write(buf, 0, read);
            }
            return bos.toByteArray();
        }
    }

    private static byte[] sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }

    private static byte[] sha256File(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[16384];
            int read;
            while ((read = in.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
            return md.digest();
        } catch (Exception e) {
            return null;
        }
    }

    private static String toHexPrefix(byte[] hash, int byteCount) {
        StringBuilder sb = new StringBuilder(byteCount * 2);
        int limit = Math.min(hash.length, byteCount);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02x", hash[i] & 0xFF));
        }
        return sb.toString();
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static ByteBuffer init() {
        if (!ensureLoaded()) {
            return null;
        }
        return nativeInit();
    }

    public static void shutdown() {
        if (loaded) {
            try {
                nativeShutdown();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String[] listWindows() {
        if (!ensureLoaded()) {
            return new String[0];
        }
        try {
            String[] res = nativeListWindows();
            return res != null ? res : new String[0];
        } catch (Throwable t) {
            ScreenCaptureControlMod.LOG.warn("nativeListWindows failed", t);
            return new String[0];
        }
    }

    public static boolean startWindowCapture(long hwnd) {
        if (!ensureLoaded()) {
            return false;
        }
        return nativeStartWindowCapture(hwnd);
    }

    public static boolean startMonitorCapture() {
        if (!ensureLoaded()) {
            return false;
        }
        return nativeStartMonitorCapture();
    }

    public static void startTestPattern() {
        if (ensureLoaded()) {
            nativeStartTestPattern();
        }
    }

    public static void setScaleMode(int mode) {
        if (ensureLoaded()) {
            nativeSetScaleMode(mode);
        }
    }

    public static void setCursorCapture(boolean enabled) {
        if (ensureLoaded()) {
            nativeSetCursorCapture(enabled);
        }
    }

    public static void setInteractionMode(int mode) {
        if (loaded) {
            nativeSetInteractionMode(mode);
        }
    }

    public static void sendInput(int type, int buttonOrKey, float normX, float normY) {
        if (loaded) {
            nativeSendInput(type, buttonOrKey, normX, normY);
        }
    }

    public static double getCaptureFps() {
        if (!loaded) {
            return 0.0;
        }
        return nativeGetCaptureFps();
    }

    public static String getTargetSummary() {
        if (!loaded) {
            return "None";
        }
        return nativeGetTargetSummary();
    }

    // JNI bindings registered in JNI_OnLoad
    private static native ByteBuffer nativeInit();
    private static native void nativeShutdown();
    private static native String[] nativeListWindows();
    private static native boolean nativeStartWindowCapture(long hwnd);
    private static native boolean nativeStartMonitorCapture();
    private static native void nativeStartTestPattern();
    private static native void nativeSetScaleMode(int mode);
    private static native void nativeSetCursorCapture(boolean enabled);
    private static native void nativeSetInteractionMode(int mode);
    private static native void nativeSendInput(int type, int buttonOrKey, float normX, float normY);
    private static native double nativeGetCaptureFps();
    private static native String nativeGetTargetSummary();
}
