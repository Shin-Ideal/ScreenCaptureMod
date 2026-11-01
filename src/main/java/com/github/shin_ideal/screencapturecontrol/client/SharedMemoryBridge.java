package com.github.shin_ideal.screencapturecontrol.client;

import com.github.shin_ideal.screencapturecontrol.ScreenCaptureControlMod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Ultra-low latency, zero-copy in-process Direct ByteBuffer bridge between Minecraft and ScreenCaptureNative.dll.
 * Reads raw BGRA8 frames directly from C++ _aligned_malloc memory via JNI NewDirectByteBuffer.
 */
public final class SharedMemoryBridge {

    public static final int MAGIC = 0x53434150; // "SCAP"
    public static final int VERSION = 1;

    public static final int FRAME_SLOT_0_OFFSET = 0x00010000; // 64 KB
    public static final int FRAME_SLOT_SIZE = 0x02000000;     // 32 MB
    public static final int FRAME_SLOT_1_OFFSET = FRAME_SLOT_0_OFFSET + FRAME_SLOT_SIZE; // 0x02010000
    public static final long TOTAL_BUFFER_SIZE = (long) FRAME_SLOT_1_OFFSET + FRAME_SLOT_SIZE;

    private static ByteBuffer nativeBuffer;
    private static ByteBuffer slot0Buffer;
    private static ByteBuffer slot1Buffer;

    private static long lastSequence = -1;
    private static int lastWidth = 0;
    private static int lastHeight = 0;
    private static int lastInteractionMode = 0;
    private static volatile boolean active = false;

    private SharedMemoryBridge() {
    }

    public static synchronized boolean isAvailable() {
        return active && nativeBuffer != null;
    }

    public static boolean isCompanionAlive() {
        ByteBuffer buf = nativeBuffer;
        if (!active || buf == null) {
            return false;
        }
        try {
            int magic = buf.getInt(0);
            int state = buf.getInt(8);
            return magic == MAGIC && state == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized boolean open() {
        if (active && nativeBuffer != null) {
            return true;
        }

        try {
            ByteBuffer rawBuf = NativeCaptureBridge.init();
            if (rawBuf == null) {
                ScreenCaptureControlMod.LOG.error("NativeCaptureBridge.init() returned null buffer");
                return false;
            }

            rawBuf.order(ByteOrder.LITTLE_ENDIAN);
            nativeBuffer = rawBuf;

            nativeBuffer.position(FRAME_SLOT_0_OFFSET);
            nativeBuffer.limit(FRAME_SLOT_0_OFFSET + FRAME_SLOT_SIZE);
            slot0Buffer = nativeBuffer.slice();
            slot0Buffer.order(ByteOrder.LITTLE_ENDIAN);

            nativeBuffer.position(FRAME_SLOT_1_OFFSET);
            nativeBuffer.limit(FRAME_SLOT_1_OFFSET + FRAME_SLOT_SIZE);
            slot1Buffer = nativeBuffer.slice();
            slot1Buffer.order(ByteOrder.LITTLE_ENDIAN);

            nativeBuffer.clear();
            lastSequence = -1;
            lastInteractionMode = 0;
            active = true;
            ScreenCaptureControlMod.LOG.info("In-process C++ JNI DirectByteBuffer initialized (64 MB double-buffered)");
            return true;
        } catch (Exception e) {
            ScreenCaptureControlMod.LOG.error("Failed to open in-process native buffer", e);
            close();
            return false;
        }
    }

    public static synchronized void close() {
        setInteractionMode(0);
        active = false;
        lastSequence = -1;
        slot0Buffer = null;
        slot1Buffer = null;
        nativeBuffer = null;
        NativeCaptureBridge.shutdown();
    }

    public static boolean isActive() {
        return active && nativeBuffer != null;
    }

    /**
     * Checks for a new frame in the C++ double-buffer. If available, returns a Direct ByteBuffer pointing
     * to the active frame slot with zero copying.
     */
    public static ByteBuffer pollFrame(int[] outDim) {
        ByteBuffer buf = nativeBuffer;
        if (!active || buf == null) {
            return null;
        }

        try {
            int magic = buf.getInt(0);
            if (magic != MAGIC) {
                return null;
            }

            int state = buf.getInt(8);
            if (state != 1) {
                return null;
            }

            long seq = buf.getLong(32);
            if (seq <= 0 || seq == lastSequence) {
                return null;
            }

            int w = buf.getInt(12);
            int h = buf.getInt(16);
            int activeSlot = buf.getInt(24);

            if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || (activeSlot != 0 && activeSlot != 1)) {
                return null;
            }

            long payloadSizeLong = (long) w * (long) h * 4L;
            if (payloadSizeLong <= 0L || payloadSizeLong > FRAME_SLOT_SIZE) {
                return null;
            }
            int payloadSize = (int) payloadSizeLong;

            ByteBuffer targetSlot = (activeSlot == 0) ? slot0Buffer : slot1Buffer;
            if (targetSlot == null) {
                return null;
            }

            lastSequence = seq;
            targetSlot.position(0);
            targetSlot.limit(payloadSize);

            outDim[0] = w;
            outDim[1] = h;
            lastWidth = w;
            lastHeight = h;

            CaptureSession.onSharedMemoryFrameReceived();
            return targetSlot;
        } catch (Exception e) {
            return null;
        }
    }

    public static float getCursorX() {
        ByteBuffer buf = nativeBuffer;
        if (!active || buf == null) return 0.5F;
        try {
            float cx = buf.getFloat(48);
            return (cx >= 0.0F && cx <= 1.0F) ? cx : 0.5F;
        } catch (Exception e) {
            return 0.5F;
        }
    }

    public static float getCursorY() {
        ByteBuffer buf = nativeBuffer;
        if (!active || buf == null) return 0.5F;
        try {
            float cy = buf.getFloat(52);
            return (cy >= 0.0F && cy <= 1.0F) ? cy : 0.5F;
        } catch (Exception e) {
            return 0.5F;
        }
    }

    public static int getLastWidth() {
        return lastWidth;
    }

    public static int getLastHeight() {
        return lastHeight;
    }

    public static void setInteractionMode(int mode) {
        ByteBuffer buf = nativeBuffer;
        if (!active || buf == null) return;
        try {
            if (lastInteractionMode != mode) {
                lastInteractionMode = mode;
                buf.putInt(56, mode);
                NativeCaptureBridge.setInteractionMode(mode);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Dispatches an input event synchronously to the C++ InputInjector via JNI.
     */
    public static void sendInput(byte type, int buttonOrKey, float normX, float normY) {
        if (!active) {
            return;
        }
        NativeCaptureBridge.sendInput(type, buttonOrKey, normX, normY);
    }
}
