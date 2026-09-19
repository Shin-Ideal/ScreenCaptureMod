package com.github.shin_ideal.screencapture.client;

/**
 * Holds raw RGBA frame bytes with reusable memory buffer to avoid GC allocation.
 */
public class RawFrame {

    private int width;
    private int height;
    private byte[] data;

    public RawFrame() {
    }

    public synchronized void ensureCapacity(int w, int h) {
        this.width = w;
        this.height = h;
        int requiredBytes = w * h * 4;
        if (data == null || data.length < requiredBytes) {
            data = new byte[requiredBytes];
        }
    }

    public synchronized void put(byte[] src, int offset, int length) {
        if (data != null && src != null) {
            int toCopy = Math.min(length, data.length);
            System.arraycopy(src, offset, data, 0, toCopy);
        }
    }

    public synchronized int getWidth() {
        return width;
    }

    public synchronized int getHeight() {
        return height;
    }

    public synchronized byte[] getData() {
        return data;
    }
}
