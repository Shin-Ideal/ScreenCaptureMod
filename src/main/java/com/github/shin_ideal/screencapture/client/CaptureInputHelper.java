package com.github.shin_ideal.screencapture.client;

import org.lwjgl.input.Keyboard;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps LWJGL 2 keyboard events to Windows Virtual-Key (VK_*) codes
 * and serializes input events into compact JSON strings.
 */
public final class CaptureInputHelper {

    private static final Map<Integer, Integer> LWJGL_TO_VK = new HashMap<>();

    static {
        // Function keys
        for (int i = 0; i < 12; i++) {
            LWJGL_TO_VK.put(Keyboard.KEY_F1 + i, 0x70 + i); // VK_F1 to VK_F12
        }

        // Numbers 0-9
        LWJGL_TO_VK.put(Keyboard.KEY_0, 0x30);
        LWJGL_TO_VK.put(Keyboard.KEY_1, 0x31);
        LWJGL_TO_VK.put(Keyboard.KEY_2, 0x32);
        LWJGL_TO_VK.put(Keyboard.KEY_3, 0x33);
        LWJGL_TO_VK.put(Keyboard.KEY_4, 0x34);
        LWJGL_TO_VK.put(Keyboard.KEY_5, 0x35);
        LWJGL_TO_VK.put(Keyboard.KEY_6, 0x36);
        LWJGL_TO_VK.put(Keyboard.KEY_7, 0x37);
        LWJGL_TO_VK.put(Keyboard.KEY_8, 0x38);
        LWJGL_TO_VK.put(Keyboard.KEY_9, 0x39);

        // Letters A-Z
        LWJGL_TO_VK.put(Keyboard.KEY_A, 0x41);
        LWJGL_TO_VK.put(Keyboard.KEY_B, 0x42);
        LWJGL_TO_VK.put(Keyboard.KEY_C, 0x43);
        LWJGL_TO_VK.put(Keyboard.KEY_D, 0x44);
        LWJGL_TO_VK.put(Keyboard.KEY_E, 0x45);
        LWJGL_TO_VK.put(Keyboard.KEY_F, 0x46);
        LWJGL_TO_VK.put(Keyboard.KEY_G, 0x47);
        LWJGL_TO_VK.put(Keyboard.KEY_H, 0x48);
        LWJGL_TO_VK.put(Keyboard.KEY_I, 0x49);
        LWJGL_TO_VK.put(Keyboard.KEY_J, 0x4A);
        LWJGL_TO_VK.put(Keyboard.KEY_K, 0x4B);
        LWJGL_TO_VK.put(Keyboard.KEY_L, 0x4C);
        LWJGL_TO_VK.put(Keyboard.KEY_M, 0x4D);
        LWJGL_TO_VK.put(Keyboard.KEY_N, 0x4E);
        LWJGL_TO_VK.put(Keyboard.KEY_O, 0x4F);
        LWJGL_TO_VK.put(Keyboard.KEY_P, 0x50);
        LWJGL_TO_VK.put(Keyboard.KEY_Q, 0x51);
        LWJGL_TO_VK.put(Keyboard.KEY_R, 0x52);
        LWJGL_TO_VK.put(Keyboard.KEY_S, 0x53);
        LWJGL_TO_VK.put(Keyboard.KEY_T, 0x54);
        LWJGL_TO_VK.put(Keyboard.KEY_U, 0x55);
        LWJGL_TO_VK.put(Keyboard.KEY_V, 0x56);
        LWJGL_TO_VK.put(Keyboard.KEY_W, 0x57);
        LWJGL_TO_VK.put(Keyboard.KEY_X, 0x58);
        LWJGL_TO_VK.put(Keyboard.KEY_Y, 0x59);
        LWJGL_TO_VK.put(Keyboard.KEY_Z, 0x5A);

        // Control / special keys
        LWJGL_TO_VK.put(Keyboard.KEY_ESCAPE, 0x1B);
        LWJGL_TO_VK.put(Keyboard.KEY_RETURN, 0x0D);
        LWJGL_TO_VK.put(Keyboard.KEY_NUMPADENTER, 0x0D);
        LWJGL_TO_VK.put(Keyboard.KEY_SPACE, 0x20);
        LWJGL_TO_VK.put(Keyboard.KEY_BACK, 0x08);
        LWJGL_TO_VK.put(Keyboard.KEY_TAB, 0x09);
        LWJGL_TO_VK.put(Keyboard.KEY_LSHIFT, 0x10);
        LWJGL_TO_VK.put(Keyboard.KEY_RSHIFT, 0x10);
        LWJGL_TO_VK.put(Keyboard.KEY_LCONTROL, 0x11);
        LWJGL_TO_VK.put(Keyboard.KEY_RCONTROL, 0x11);
        LWJGL_TO_VK.put(Keyboard.KEY_LMENU, 0x12); // Alt
        LWJGL_TO_VK.put(Keyboard.KEY_RMENU, 0x12);
        LWJGL_TO_VK.put(Keyboard.KEY_UP, 0x26);
        LWJGL_TO_VK.put(Keyboard.KEY_DOWN, 0x28);
        LWJGL_TO_VK.put(Keyboard.KEY_LEFT, 0x25);
        LWJGL_TO_VK.put(Keyboard.KEY_RIGHT, 0x27);
        LWJGL_TO_VK.put(Keyboard.KEY_INSERT, 0x2D);
        LWJGL_TO_VK.put(Keyboard.KEY_DELETE, 0x2E);
        LWJGL_TO_VK.put(Keyboard.KEY_HOME, 0x24);
        LWJGL_TO_VK.put(Keyboard.KEY_END, 0x23);
        LWJGL_TO_VK.put(Keyboard.KEY_PRIOR, 0x21); // Page Up
        LWJGL_TO_VK.put(Keyboard.KEY_NEXT, 0x22);  // Page Down

        // Punctuation & symbols
        LWJGL_TO_VK.put(Keyboard.KEY_MINUS, 0xBD);      // - _
        LWJGL_TO_VK.put(Keyboard.KEY_EQUALS, 0xBB);     // = +
        LWJGL_TO_VK.put(Keyboard.KEY_LBRACKET, 0xDB);   // [ {
        LWJGL_TO_VK.put(Keyboard.KEY_RBRACKET, 0xDD);   // ] }
        LWJGL_TO_VK.put(Keyboard.KEY_SEMICOLON, 0xBA);  // ; :
        LWJGL_TO_VK.put(Keyboard.KEY_APOSTROPHE, 0xDE); // ' "
        LWJGL_TO_VK.put(Keyboard.KEY_GRAVE, 0xC0);      // ` ~
        LWJGL_TO_VK.put(Keyboard.KEY_BACKSLASH, 0xDC);  // \ |
        LWJGL_TO_VK.put(Keyboard.KEY_COMMA, 0xBC);      // , <
        LWJGL_TO_VK.put(Keyboard.KEY_PERIOD, 0xBE);     // . >
        LWJGL_TO_VK.put(Keyboard.KEY_SLASH, 0xBF);      // / ?

        // Japanese keyboard keys
        LWJGL_TO_VK.put(Keyboard.KEY_YEN, 0xDC);        // ¥
        LWJGL_TO_VK.put(Keyboard.KEY_CONVERT, 0x1C);    // 変換
        LWJGL_TO_VK.put(Keyboard.KEY_NOCONVERT, 0x1D);  // 無変換
        LWJGL_TO_VK.put(Keyboard.KEY_KANA, 0x15);       // かな
    }

    private CaptureInputHelper() {
    }

    public static int toVirtualKey(int lwjglKey) {
        Integer vk = LWJGL_TO_VK.get(lwjglKey);
        return vk != null ? vk : 0;
    }

    public static String mouseMove(float normX, float normY) {
        return String.format(java.util.Locale.US, "{\"type\":\"mouse_move\",\"x\":%.4f,\"y\":%.4f}", normX, normY);
    }

    public static String mouseDown(int button, float normX, float normY) {
        return String.format(java.util.Locale.US, "{\"type\":\"mouse_down\",\"button\":%d,\"x\":%.4f,\"y\":%.4f}", button, normX, normY);
    }

    public static String mouseUp(int button, float normX, float normY) {
        return String.format(java.util.Locale.US, "{\"type\":\"mouse_up\",\"button\":%d,\"x\":%.4f,\"y\":%.4f}", button, normX, normY);
    }

    public static String mouseDown(int button) {
        return String.format(java.util.Locale.US, "{\"type\":\"mouse_down\",\"button\":%d}", button);
    }

    public static String mouseUp(int button) {
        return String.format(java.util.Locale.US, "{\"type\":\"mouse_up\",\"button\":%d}", button);
    }

    public static String mouseWheel(int delta) {
        return String.format(java.util.Locale.US, "{\"type\":\"mouse_wheel\",\"delta\":%d}", delta);
    }

    public static String keyDown(int vk) {
        return String.format(java.util.Locale.US, "{\"type\":\"key_down\",\"vk\":%d}", vk);
    }

    public static String keyUp(int vk) {
        return String.format(java.util.Locale.US, "{\"type\":\"key_up\",\"vk\":%d}", vk);
    }

    public static String character(char c) {
        String escaped;
        if (c == '"') escaped = "\\\"";
        else if (c == '\\') escaped = "\\\\";
        else if (c == '\r') escaped = "\\r";
        else if (c == '\n') escaped = "\\n";
        else if (c == '\t') escaped = "\\t";
        else escaped = String.valueOf(c);

        return String.format(java.util.Locale.US, "{\"type\":\"char\",\"char\":\"%s\"}", escaped);
    }

    public static String reset() {
        return "{\"type\":\"reset\"}";
    }
}
