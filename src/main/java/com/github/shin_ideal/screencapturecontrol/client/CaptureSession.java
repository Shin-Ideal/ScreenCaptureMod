package com.github.shin_ideal.screencapturecontrol.client;

import com.github.shin_ideal.screencapturecontrol.ScreenCaptureControlMod;
import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

public final class CaptureSession {

    private static final AtomicLong frameCount = new AtomicLong();
    private static volatile boolean running;
    private static volatile boolean glLowLatencyApplied;
    private static Boolean savedVsync = null;
    private static Integer savedLimitFramerate = null;

    private CaptureSession() {
    }

    public static void init() {
        NativeCaptureBridge.ensureLoaded();
    }

    /**
     * Must be called on the Minecraft OpenGL Client Thread so Display.setVSyncEnabled(false)
     * actually invokes wglSwapIntervalEXT(0) on the current OpenGL context.
     */
    public static void applyLowLatencyRenderSettingsOnGlThread() {
        if (!running) {
            if (glLowLatencyApplied) {
                glLowLatencyApplied = false;
                try {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null && mc.gameSettings != null) {
                        if (savedVsync != null) {
                            mc.gameSettings.enableVsync = savedVsync;
                            org.lwjgl.opengl.Display.setVSyncEnabled(savedVsync);
                            savedVsync = null;
                        }
                        if (savedLimitFramerate != null) {
                            mc.gameSettings.limitFramerate = savedLimitFramerate;
                            savedLimitFramerate = null;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return;
        }

        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.gameSettings != null) {
                mc.gameSettings.pauseOnLostFocus = false;
                if (savedVsync == null) {
                    savedVsync = mc.gameSettings.enableVsync;
                }
                if (savedLimitFramerate == null) {
                    savedLimitFramerate = mc.gameSettings.limitFramerate;
                }
                if (mc.gameSettings.enableVsync || !glLowLatencyApplied) {
                    mc.gameSettings.enableVsync = false;
                    org.lwjgl.opengl.Display.setVSyncEnabled(false);
                    glLowLatencyApplied = true;
                }
                if (mc.gameSettings.limitFramerate < 260) {
                    mc.gameSettings.limitFramerate = 260;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void start() throws Exception {
        if (!SharedMemoryBridge.open()) {
            throw new IllegalStateException("C++ JNI ライブラリ (ScreenCaptureNative.dll) の初期化に失敗しました。");
        }
        running = true;
        glLowLatencyApplied = false;
        refreshCachedWindows();
    }

    public static synchronized void stop() {
        running = false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.gameSettings != null) {
                if (savedVsync != null) {
                    mc.gameSettings.enableVsync = savedVsync;
                    org.lwjgl.opengl.Display.setVSyncEnabled(savedVsync);
                    savedVsync = null;
                }
                if (savedLimitFramerate != null) {
                    mc.gameSettings.limitFramerate = savedLimitFramerate;
                    savedLimitFramerate = null;
                }
            }
        } catch (Throwable ignored) {
        }
        SharedMemoryBridge.close();
        CaptureTexture.clear();
        ScreenCaptureControlMod.LOG.info("Screen capture session stopped");
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isConnected() {
        return running && SharedMemoryBridge.isActive();
    }

    public static void sendRawInput(byte type, int buttonOrKey, float normX, float normY) {
        if (SharedMemoryBridge.isActive()) {
            SharedMemoryBridge.sendInput(type, buttonOrKey, normX, normY);
        }
    }

    public static String getCaptureUrl() {
        return "JNI://ScreenCaptureNative.dll (" + NativeCaptureBridge.getTargetSummary() + ")";
    }

    public static long getFrameCount() {
        return frameCount.get();
    }

    public static void onSharedMemoryFrameReceived() {
        frameCount.incrementAndGet();
    }

    public static class WindowEntry {
        public final int index;
        public final long hwnd;
        public final String title;
        public final String process;
        public final int width;
        public final int height;

        public WindowEntry(int index, long hwnd, String title, String process, int width, int height) {
            this.index = index;
            this.hwnd = hwnd;
            this.title = title;
            this.process = process;
            this.width = width;
            this.height = height;
        }
    }

    private static final List<WindowEntry> cachedWindows = new CopyOnWriteArrayList<>();

    public static List<WindowEntry> getCachedWindows() {
        if (cachedWindows.isEmpty() && NativeCaptureBridge.isLoaded()) {
            refreshCachedWindows();
        }
        return Collections.unmodifiableList(cachedWindows);
    }

    public static WindowEntry getCachedWindowByIndex(int index) {
        if (cachedWindows.isEmpty()) {
            refreshCachedWindows();
        }
        for (WindowEntry entry : cachedWindows) {
            if (entry.index == index) {
                return entry;
            }
        }
        return null;
    }

    public static void refreshCachedWindows() {
        String[] rawLines = NativeCaptureBridge.listWindows();
        cachedWindows.clear();
        if (rawLines == null) {
            return;
        }

        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            if (line == null || line.isEmpty()) continue;
            // Format: hwnd|processName|width|height|title
            String[] parts = line.split("\\|", 5);
            if (parts.length < 5) continue;
            try {
                long hwnd = Long.parseUnsignedLong(parts[0]);
                String proc = parts[1];
                int w = Integer.parseInt(parts[2]);
                int h = Integer.parseInt(parts[3]);
                String title = parts[4];
                cachedWindows.add(new WindowEntry(i, hwnd, title, proc, w, h));
            } catch (Exception ignored) {
            }
        }
    }

    public static void requestWindowList() {
        refreshCachedWindows();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }

        mc.thePlayer.addChatMessage(new ChatComponentText(
            EnumChatFormatting.GOLD + "=== キャプチャ可能な画面・ウィンドウ一覧 (クリックで切替) ==="));

        for (WindowEntry entry : cachedWindows) {
            ChatComponentText chatEntry;
            if (entry.hwnd == 0 || "Desktop".equalsIgnoreCase(entry.process)) {
                chatEntry = new ChatComponentText(
                    EnumChatFormatting.GREEN + " [" + entry.index + "] " +
                    EnumChatFormatting.BOLD + "デスクトップ全体 (全画面)" +
                    EnumChatFormatting.RESET + EnumChatFormatting.DARK_GRAY + " (" + entry.width + "x" + entry.height + ")");
                chatEntry.getChatStyle().setChatClickEvent(new ClickEvent(
                    ClickEvent.Action.RUN_COMMAND,
                    "/screencapturecontrol window desktop"));
                chatEntry.getChatStyle().setChatHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    new ChatComponentText(EnumChatFormatting.YELLOW + "クリックしてデスクトップ全体を投影")));
            } else {
                chatEntry = new ChatComponentText(
                    EnumChatFormatting.AQUA + " [" + entry.index + "] " +
                    EnumChatFormatting.WHITE + entry.title +
                    EnumChatFormatting.DARK_GRAY + " (" + entry.process + ", " + entry.width + "x" + entry.height + ")");
                chatEntry.getChatStyle().setChatClickEvent(new ClickEvent(
                    ClickEvent.Action.RUN_COMMAND,
                    "/screencapturecontrol window " + entry.index));
                chatEntry.getChatStyle().setChatHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    new ChatComponentText(EnumChatFormatting.YELLOW + "クリックして [" + entry.index + "] " + entry.title + " を投影")));
            }
            mc.thePlayer.addChatMessage(chatEntry);
        }

        ChatComponentText testEntry = new ChatComponentText(
            EnumChatFormatting.YELLOW + " [T] テストパターンを表示 (カラーバー)");
        testEntry.getChatStyle().setChatClickEvent(new ClickEvent(
            ClickEvent.Action.RUN_COMMAND, "/screencapturecontrol window test"));
        testEntry.getChatStyle().setChatHoverEvent(new HoverEvent(
            HoverEvent.Action.SHOW_TEXT,
            new ChatComponentText(EnumChatFormatting.YELLOW + "クリックしてテストパターンを投影")));
        mc.thePlayer.addChatMessage(testEntry);

        mc.thePlayer.addChatMessage(new ChatComponentText(
            EnumChatFormatting.GRAY + "※ チャット欄を開いて対象をクリックするか、/screencapturecontrol window <番号/名前/desktop> で指定できます"));
    }

    public static boolean selectWindow(long hwnd) {
        return NativeCaptureBridge.startWindowCapture(hwnd);
    }

    public static WindowEntry selectWindowByFilter(String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return null;
        }
        refreshCachedWindows();
        String lower = filter.trim().toLowerCase(Locale.ROOT);
        for (WindowEntry entry : cachedWindows) {
            if (entry.hwnd == 0) continue;
            if ((entry.title != null && entry.title.toLowerCase(Locale.ROOT).contains(lower)) ||
                (entry.process != null && entry.process.toLowerCase(Locale.ROOT).contains(lower))) {
                if (NativeCaptureBridge.startWindowCapture(entry.hwnd)) {
                    return entry;
                }
            }
        }
        return null;
    }

    public static boolean selectDesktop() {
        return NativeCaptureBridge.startMonitorCapture();
    }

    public static void selectTestPattern() {
        NativeCaptureBridge.startTestPattern();
    }

    public static void setScaleMode(String mode) {
        int scaleInt = "half".equalsIgnoreCase(mode) ? 0 : 1;
        NativeCaptureBridge.setScaleMode(scaleInt);
    }

    public static void setCursorCapture(boolean enabled) {
        NativeCaptureBridge.setCursorCapture(enabled);
    }
}
