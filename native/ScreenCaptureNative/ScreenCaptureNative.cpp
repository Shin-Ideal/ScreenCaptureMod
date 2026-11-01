// ScreenCaptureNative.cpp
// Ultra-low latency Windows Graphics Capture (WGC) + Direct3D 11 + AVX2 + Win32 Input Injection JNI DLL

#ifndef _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR
#define _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX

#include <windows.h>
#include <mmsystem.h>
#include <psapi.h>
#include <d3d11.h>
#include <dxgi1_2.h>
#include <inspectable.h>
#include <roapi.h>
#include <winstring.h>
#include <immintrin.h>

// C++/WinRT headers for Windows.Graphics.Capture
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.System.h>
#include <winrt/Windows.Graphics.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <windows.graphics.capture.interop.h>
#include <windows.graphics.directx.direct3d11.interop.h>

#include <jni.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>

#pragma comment(lib, "d3d11.lib")
#pragma comment(lib, "dxgi.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "kernel32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "oleaut32.lib")
#pragma comment(lib, "runtimeobject.lib")
#pragma comment(lib, "windowsapp.lib")
#pragma comment(lib, "winmm.lib")

namespace winrt_cap = winrt::Windows::Graphics::Capture;
namespace winrt_dx  = winrt::Windows::Graphics::DirectX;
namespace winrt_d3d = winrt::Windows::Graphics::DirectX::Direct3D11;

// IDirect3DDxgiInterfaceAccess: {A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1}
struct __declspec(uuid("A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1")) __declspec(novtable)
IDirect3DDxgiInterfaceAccess : ::IUnknown {
    virtual HRESULT __stdcall GetInterface(REFIID iid, void** p) = 0;
};

extern "C" HRESULT __stdcall CreateDirect3D11DeviceFromDXGIDevice(
    IDXGIDevice* dxgiDevice,
    IInspectable** graphicsDevice);

// ============================================================================
// Shared Memory Buffer Layout (In-Process Direct ByteBuffer)
// ============================================================================
static constexpr uint32_t SHM_MAGIC             = 0x53434150; // "SCAP"
static constexpr int32_t  SHM_VERSION           = 1;
static constexpr size_t   HEADER_SIZE           = 64;
static constexpr size_t   FRAME_SLOT_0_OFFSET   = 0x00010000; // 64 KB
static constexpr size_t   FRAME_SLOT_SIZE       = 0x02000000; // 32 MB
static constexpr size_t   FRAME_SLOT_1_OFFSET   = FRAME_SLOT_0_OFFSET + FRAME_SLOT_SIZE;
static constexpr size_t   TOTAL_BUFFER_SIZE     = FRAME_SLOT_1_OFFSET + FRAME_SLOT_SIZE;
static constexpr int      MAX_FRAME_DIMENSION   = 16384;

#pragma pack(push, 1)
struct SharedHeader {
    uint32_t          Magic;           // 0
    int32_t           Version;         // 4
    volatile LONG     State;           // 8  (0: idle, 1: capturing, 2: stopped)
    volatile LONG     Width;           // 12
    volatile LONG     Height;          // 16
    volatile LONG     Format;          // 20 (3: BGRA8)
    volatile LONG     ActiveSlot;      // 24 (0 or 1)
    volatile LONG     ScaleMode;       // 28 (0: Half, 1: Full)
    volatile LONGLONG FrameSequence;   // 32
    volatile LONGLONG TimestampUs;     // 40 (Unix ms)
    volatile float    CursorX;         // 48
    volatile float    CursorY;         // 52
    volatile LONG     InteractionMode; // 56 (1 when Minecraft 'O' mode is active)
    int32_t           Reserved;        // 60
};
#pragma pack(pop)

static uint8_t*      g_sharedBuffer = nullptr;
static SharedHeader* g_header       = nullptr;
static uint8_t*      g_slot0Ptr     = nullptr;
static uint8_t*      g_slot1Ptr     = nullptr;

static int64_t NowUnixMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

// ============================================================================
// Window Validation & Low-Latency Process Booster
// ============================================================================
static bool IsSafeTargetWindow(HWND hWnd) {
    if (!hWnd || !IsWindow(hWnd)) return false;
    DWORD pid = 0;
    GetWindowThreadProcessId(hWnd, &pid);
    if (pid == 0 || pid == GetCurrentProcessId()) {
        return false;
    }
    return true;
}

#ifndef PROCESS_POWER_THROTTLING_CURRENT_VERSION
#define PROCESS_POWER_THROTTLING_CURRENT_VERSION 1
#endif
#ifndef PROCESS_POWER_THROTTLING_EXECUTION_SPEED
#define PROCESS_POWER_THROTTLING_EXECUTION_SPEED 0x1
#endif
#ifndef PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION
#define PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION 0x4
#endif

struct LOCAL_PROCESS_POWER_THROTTLING_STATE {
    ULONG Version;
    ULONG ControlMask;
    ULONG StateMask;
};

static void DisableProcessPowerThrottlingOnHandle(HANDLE hProc) {
    if (!hProc) return;
    LOCAL_PROCESS_POWER_THROTTLING_STATE state{};
    state.Version = PROCESS_POWER_THROTTLING_CURRENT_VERSION;
    state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED | PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION;
    state.StateMask = 0; // 0 = High QoS, disable all throttling
    if (!SetProcessInformation(hProc, (PROCESS_INFORMATION_CLASS)4 /*ProcessPowerThrottling*/, &state, sizeof(state))) {
        state.ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED;
        SetProcessInformation(hProc, (PROCESS_INFORMATION_CLASS)4, &state, sizeof(state));
    }
}

static void BoostWindowProcessForLowLatency(HWND hWnd) {
    if (!IsSafeTargetWindow(hWnd)) return;
    DWORD pid = 0;
    DWORD tid = GetWindowThreadProcessId(hWnd, &pid);
    if (pid != 0 && pid != GetCurrentProcessId()) {
        HANDLE hProc = OpenProcess(PROCESS_SET_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
        if (hProc) {
            DWORD currentPrio = GetPriorityClass(hProc);
            if (currentPrio == NORMAL_PRIORITY_CLASS || currentPrio == BELOW_NORMAL_PRIORITY_CLASS) {
                SetPriorityClass(hProc, ABOVE_NORMAL_PRIORITY_CLASS);
            }
            DisableProcessPowerThrottlingOnHandle(hProc);
            CloseHandle(hProc);
        }
    }
    if (tid != 0) {
        HANDLE hThread = OpenThread(THREAD_SET_INFORMATION, FALSE, tid);
        if (hThread) {
            SetThreadPriority(hThread, THREAD_PRIORITY_ABOVE_NORMAL);
            CloseHandle(hThread);
        }
    }
}

// ============================================================================
// AVX2 Non-Temporal Frame Copy to Shared Memory
// ============================================================================
static void WriteFrameToSharedMemory(const void* pSrcData, uint32_t rowPitch, int width, int height, int scaleMode) {
    if (!g_header || !pSrcData || width <= 0 || height <= 0 || width > MAX_FRAME_DIMENSION || height > MAX_FRAME_DIMENSION) {
        return;
    }
    size_t minRowBytes = (size_t)width * 4ULL;
    if ((size_t)rowPitch < minRowBytes) {
        return;
    }

    LONG currentSlot = InterlockedCompareExchange(&g_header->ActiveSlot, 0, 0);
    LONG nextSlot = 1 - (currentSlot & 1);
    uint8_t* pDstBase = (nextSlot == 0) ? g_slot0Ptr : g_slot1Ptr;

    int outW = width;
    int outH = height;

    if (scaleMode == 0 && width >= 400 && height >= 400) {
        outW = width / 2;
        outH = height / 2;
        size_t requiredBytes = (size_t)outW * (size_t)outH * 4ULL;
        if (requiredBytes > FRAME_SLOT_SIZE) return;

        uint32_t* pDst = reinterpret_cast<uint32_t*>(pDstBase);
        const uint8_t* pSrcBase = reinterpret_cast<const uint8_t*>(pSrcData);

        const __m256i permMask = _mm256_setr_epi32(0, 2, 4, 6, 1, 3, 5, 7);
        int vecEnd = outW & ~7;

        for (int y = 0; y < outH; y++) {
            const uint32_t* pSrcRow = reinterpret_cast<const uint32_t*>(pSrcBase + (size_t)(y * 2) * rowPitch);
            uint32_t* pDstRow = pDst + (size_t)y * outW;
            int x = 0;

            if ((((uintptr_t)pSrcRow) & 31) == 0) {
                for (; x < vecEnd; x += 8) {
                    const __m256i* srcPtr = reinterpret_cast<const __m256i*>(pSrcRow + (x * 2));
                    __m256i v0 = _mm256_stream_load_si256(srcPtr);
                    __m256i v1 = _mm256_stream_load_si256(srcPtr + 1);
                    __m256i p0 = _mm256_permutevar8x32_epi32(v0, permMask);
                    __m256i p1 = _mm256_permutevar8x32_epi32(v1, permMask);
                    __m256i packed = _mm256_permute2x128_si256(p0, p1, 0x20);
                    _mm256_storeu_si256(reinterpret_cast<__m256i*>(pDstRow + x), packed);
                }
            } else {
                for (; x < vecEnd; x += 8) {
                    const __m256i* srcPtr = reinterpret_cast<const __m256i*>(pSrcRow + (x * 2));
                    __m256i v0 = _mm256_loadu_si256(srcPtr);
                    __m256i v1 = _mm256_loadu_si256(srcPtr + 1);
                    __m256i p0 = _mm256_permutevar8x32_epi32(v0, permMask);
                    __m256i p1 = _mm256_permutevar8x32_epi32(v1, permMask);
                    __m256i packed = _mm256_permute2x128_si256(p0, p1, 0x20);
                    _mm256_storeu_si256(reinterpret_cast<__m256i*>(pDstRow + x), packed);
                }
            }

            for (; x < outW; x++) {
                pDstRow[x] = pSrcRow[x * 2];
            }
        }
    } else {
        size_t lineBytes = (size_t)outW * 4ULL;
        size_t totalBytes = lineBytes * (size_t)outH;
        if (totalBytes > FRAME_SLOT_SIZE) return;

        const uint8_t* pSrcBase = reinterpret_cast<const uint8_t*>(pSrcData);
        size_t vecEnd = lineBytes & ~63ULL;

        for (int y = 0; y < outH; y++) {
            const uint8_t* pSrcLine = pSrcBase + ((size_t)y * rowPitch);
            uint8_t* pDstLine = pDstBase + ((size_t)y * lineBytes);
            size_t offset = 0;

            if ((((uintptr_t)pSrcLine) & 31) == 0) {
                for (; offset < vecEnd; offset += 64) {
                    __m256i v0 = _mm256_stream_load_si256(reinterpret_cast<const __m256i*>(pSrcLine + offset));
                    __m256i v1 = _mm256_stream_load_si256(reinterpret_cast<const __m256i*>(pSrcLine + offset + 32));
                    _mm256_storeu_si256(reinterpret_cast<__m256i*>(pDstLine + offset), v0);
                    _mm256_storeu_si256(reinterpret_cast<__m256i*>(pDstLine + offset + 32), v1);
                }
            } else {
                for (; offset < vecEnd; offset += 64) {
                    __m256i v0 = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(pSrcLine + offset));
                    __m256i v1 = _mm256_loadu_si256(reinterpret_cast<const __m256i*>(pSrcLine + offset + 32));
                    _mm256_storeu_si256(reinterpret_cast<__m256i*>(pDstLine + offset), v0);
                    _mm256_storeu_si256(reinterpret_cast<__m256i*>(pDstLine + offset + 32), v1);
                }
            }

            size_t rem = lineBytes - offset;
            if (rem > 0) {
                std::memcpy(pDstLine + offset, pSrcLine + offset, rem);
            }
        }
    }

    InterlockedExchange(&g_header->Width, (LONG)outW);
    InterlockedExchange(&g_header->Height, (LONG)outH);
    InterlockedExchange(&g_header->Format, 3); // BGRA8
    InterlockedExchange(&g_header->ScaleMode, (LONG)scaleMode);
    InterlockedExchange64(&g_header->TimestampUs, NowUnixMs());
    InterlockedExchange(&g_header->State, 1);
    _mm_sfence();
    std::atomic_thread_fence(std::memory_order_release);
    InterlockedExchange(&g_header->ActiveSlot, nextSlot);
    InterlockedIncrement64(&g_header->FrameSequence);
}

// ============================================================================
// Ultra-Low Latency Input Injector & 1000Hz Physical Keyboard Poller
// ============================================================================
static std::mutex           g_inputMutex;
static HWND                 g_targetHWnd = nullptr;
static HWND                 g_lastChildHWnd = nullptr;
static std::set<int>        g_pressedKeys;
static std::set<int>        g_pressedMouseButtons;
static BYTE                 g_keyStateBuffer[256]{};
static bool                 g_wasInteractionActive = false;
static float                g_lastNormX = 0.5f;
static float                g_lastNormY = 0.5f;
static std::atomic<int64_t> g_lastInjectedMoveTick{0};
static std::vector<int>     g_watchedVks;
static std::atomic<bool>    g_testPatternActive{false};

static void InitWatchedVks() {
    if (!g_watchedVks.empty()) return;
    int baseKeys[] = {
        0x25, 0x26, 0x27, 0x28, // Left, Up, Right, Down
        0xA0, 0xA1,             // VK_LSHIFT, VK_RSHIFT
        0xA2, 0xA3,             // VK_LCONTROL, VK_RCONTROL
        0xA4, 0xA5,             // VK_LMENU, VK_RMENU
        0x20, 0x0D, 0x08, 0x09, // Space, Enter, Backspace, Tab
        0x1B,                   // VK_ESCAPE
        0x21, 0x22, 0x23, 0x24, 0x2D, 0x2E // PgUp, PgDn, End, Home, Ins, Del
    };
    for (int k : baseKeys) g_watchedVks.push_back(k);
    for (int vk = 0x41; vk <= 0x5A; vk++) g_watchedVks.push_back(vk); // A-Z
    for (int vk = 0x30; vk <= 0x39; vk++) g_watchedVks.push_back(vk); // 0-9
    for (int vk = 0x70; vk <= 0x7B; vk++) g_watchedVks.push_back(vk); // F1-F12
}

static int NormalizeModifierVk(int vk) {
    switch (vk) {
        case 0xA0: case 0xA1: return 0x10; // VK_SHIFT
        case 0xA2: case 0xA3: return 0x11; // VK_CONTROL
        case 0xA4: case 0xA5: return 0x12; // VK_MENU
        default: return vk;
    }
}

static bool IsModifierPairStillHeld(int normVk) {
    switch (normVk) {
        case 0x10: return ((GetAsyncKeyState(0xA0) | GetAsyncKeyState(0xA1)) & 0x8000) != 0;
        case 0x11: return ((GetAsyncKeyState(0xA2) | GetAsyncKeyState(0xA3)) & 0x8000) != 0;
        case 0x12: return ((GetAsyncKeyState(0xA4) | GetAsyncKeyState(0xA5)) & 0x8000) != 0;
        default: return false;
    }
}

static bool IsExtendedKey(int vk) {
    return (vk >= 0x21 && vk <= 0x2E) || vk == 0xA3 || vk == 0xA5;
}

static LPARAM MakeMouseLParam(int x, int y) {
    return (LPARAM)(((uint32_t)(uint16_t)y << 16) | ((uint32_t)(uint16_t)x));
}

static HWND GetActiveKeyboardTargetLocked() {
    if (g_testPatternActive.load(std::memory_order_relaxed)) return nullptr;
    if (g_lastChildHWnd && IsSafeTargetWindow(g_lastChildHWnd)) return g_lastChildHWnd;
    if (g_targetHWnd && IsSafeTargetWindow(g_targetHWnd)) return g_targetHWnd;
    return nullptr;
}

static void SendFakeActivate(HWND hWnd) {
    if (!IsSafeTargetWindow(hWnd)) return;
    PostMessageW(hWnd, WM_ACTIVATEAPP, (WPARAM)1, 0);
    PostMessageW(hWnd, WM_ACTIVATE, (WPARAM)WA_ACTIVE, 0);
    PostMessageW(hWnd, WM_SETFOCUS, 0, 0);
}

static void SyncTargetThreadKeyboardStateLocked(HWND targetHwnd) {
    if (!IsSafeTargetWindow(targetHwnd) || IsHungAppWindow(targetHwnd)) return;
    DWORD targetTid = GetWindowThreadProcessId(targetHwnd, nullptr);
    DWORD myTid = GetCurrentThreadId();
    if (targetTid == 0 || targetTid == myTid) return;

    if (AttachThreadInput(myTid, targetTid, TRUE)) {
        GetKeyboardState(g_keyStateBuffer);
        for (int wvk : g_watchedVks) {
            int nvk = NormalizeModifierVk(wvk);
            bool held = g_pressedKeys.count(nvk) > 0;
            BYTE stateByte = held ? 0x81 : 0x00;
            g_keyStateBuffer[wvk & 0xFF] = stateByte;
            g_keyStateBuffer[nvk & 0xFF] = stateByte;
        }
        SetKeyboardState(g_keyStateBuffer);
        AttachThreadInput(myTid, targetTid, FALSE);
    }
}

static void DispatchKeyStateChangeLocked(HWND targetHwnd, int normVk, int rawVk, bool isDown) {
    if (!IsSafeTargetWindow(targetHwnd)) return;
    SyncTargetThreadKeyboardStateLocked(targetHwnd);

    UINT scanVk = (UINT)(rawVk != 0 ? rawVk : normVk);
    UINT scanCode = MapVirtualKeyW(scanVk, 0);
    UINT extBit = IsExtendedKey(rawVk != 0 ? rawVk : normVk) ? (1u << 24) : 0u;

    if (isDown) {
        LPARAM lParam = (LPARAM)(1u | (scanCode << 16) | extBit);
        PostMessageW(targetHwnd, WM_KEYDOWN, (WPARAM)normVk, lParam);
    } else {
        LPARAM lParam = (LPARAM)(1u | (scanCode << 16) | extBit | (1u << 30) | (1u << 31));
        PostMessageW(targetHwnd, WM_KEYUP, (WPARAM)normVk, lParam);
    }
}

struct ResolvedTarget {
    HWND hwnd;
    int clientX;
    int clientY;
    POINT screenPt;
};

static ResolvedTarget ResolveTargetAndCoordsLocked(float normX, float normY) {
    if (g_testPatternActive.load(std::memory_order_relaxed)) {
        return { nullptr, 0, 0, {0, 0} };
    }

    if (!g_targetHWnd) {
        int screenW = std::max(1, GetSystemMetrics(SM_CXSCREEN));
        int screenH = std::max(1, GetSystemMetrics(SM_CYSCREEN));
        POINT screenPt{ (LONG)(normX * screenW), (LONG)(normY * screenH) };

        HWND hitHwnd = WindowFromPoint(screenPt);
        if (!IsSafeTargetWindow(hitHwnd)) {
            return { nullptr, 0, 0, screenPt };
        }

        HWND fgHwnd = GetForegroundWindow();
        if (fgHwnd) {
            DWORD fgPid = 0, hitPid = 0;
            GetWindowThreadProcessId(fgHwnd, &fgPid);
            GetWindowThreadProcessId(hitHwnd, &hitPid);
            if (fgPid == hitPid) {
                return { nullptr, 0, 0, screenPt };
            }
        }

        POINT clientPt = screenPt;
        ScreenToClient(hitHwnd, &clientPt);
        return { hitHwnd, clientPt.x, clientPt.y, screenPt };
    }

    if (!IsSafeTargetWindow(g_targetHWnd)) {
        return { nullptr, 0, 0, {0, 0} };
    }

    RECT winRect{};
    GetWindowRect(g_targetHWnd, &winRect);
    int winW = std::max(1L, winRect.right - winRect.left);
    int winH = std::max(1L, winRect.bottom - winRect.top);

    POINT screenPoint{
        winRect.left + (LONG)(normX * winW),
        winRect.top  + (LONG)(normY * winH)
    };

    HWND current = g_targetHWnd;
    for (int depth = 0; depth < 8; depth++) {
        POINT ptClient = screenPoint;
        ScreenToClient(current, &ptClient);
        HWND child = ChildWindowFromPointEx(
            current,
            ptClient,
            CWP_SKIPINVISIBLE | CWP_SKIPDISABLED | CWP_SKIPTRANSPARENT);
        if (!child || child == current) break;
        current = child;
    }

    if (!IsSafeTargetWindow(current)) {
        return { nullptr, 0, 0, screenPoint };
    }

    POINT finalClient = screenPoint;
    ScreenToClient(current, &finalClient);
    return { current, finalClient.x, finalClient.y, screenPoint };
}

static void ResetAllInputsLocked() {
    HWND targetHwnd = GetActiveKeyboardTargetLocked();
    if (!targetHwnd) {
        g_pressedKeys.clear();
        g_pressedMouseButtons.clear();
        return;
    }

    auto resolved = ResolveTargetAndCoordsLocked(g_lastNormX, g_lastNormY);
    LPARAM lParam = MakeMouseLParam(resolved.clientX, resolved.clientY);

    for (int button : g_pressedMouseButtons) {
        UINT msg = (button == 1) ? WM_RBUTTONUP : (button == 2 ? WM_MBUTTONUP : WM_LBUTTONUP);
        PostMessageW(targetHwnd, msg, 0, lParam);
    }
    g_pressedMouseButtons.clear();

    for (int vk : g_pressedKeys) {
        UINT scanCode = MapVirtualKeyW((UINT)vk, 0);
        UINT extBit = IsExtendedKey(vk) ? (1u << 24) : 0u;
        LPARAM keyLParam = (LPARAM)(1u | (scanCode << 16) | extBit | (1u << 30) | (1u << 31));
        PostMessageW(targetHwnd, WM_KEYUP, (WPARAM)vk, keyLParam);
    }
    g_pressedKeys.clear();
    SyncTargetThreadKeyboardStateLocked(targetHwnd);
}

static void SetInputTargetWindow(HWND hWnd) {
    std::lock_guard<std::mutex> lock(g_inputMutex);
    ResetAllInputsLocked();
    if (hWnd && !IsSafeTargetWindow(hWnd)) {
        g_targetHWnd = nullptr;
        g_lastChildHWnd = nullptr;
        return;
    }
    g_targetHWnd = hWnd;
    g_lastChildHWnd = nullptr;
    if (hWnd) {
        BoostWindowProcessForLowLatency(hWnd);
        SendFakeActivate(hWnd);
    }
}

static void ProcessRawInputEvent(int type, int buttonOrKey, float x, float y) {
    std::lock_guard<std::mutex> lock(g_inputMutex);
    float clampedX = std::clamp(x, 0.0f, 1.0f);
    float clampedY = std::clamp(y, 0.0f, 1.0f);

    switch (type) {
        case 1: { // mouse_move
            g_lastNormX = clampedX;
            g_lastNormY = clampedY;
            g_lastInjectedMoveTick.store((int64_t)GetTickCount64(), std::memory_order_relaxed);
            if (g_header) {
                g_header->CursorX = g_lastNormX;
                g_header->CursorY = g_lastNormY;
            }
            auto res = ResolveTargetAndCoordsLocked(g_lastNormX, g_lastNormY);
            if (!res.hwnd) break;
            WPARAM wParam = 0;
            if (g_pressedMouseButtons.count(0)) wParam |= MK_LBUTTON;
            if (g_pressedMouseButtons.count(1)) wParam |= MK_RBUTTON;
            if (g_pressedMouseButtons.count(2)) wParam |= MK_MBUTTON;
            PostMessageW(res.hwnd, WM_MOUSEMOVE, wParam, MakeMouseLParam(res.clientX, res.clientY));
            break;
        }
        case 2: { // mouse_down
            g_lastNormX = clampedX;
            g_lastNormY = clampedY;
            g_lastInjectedMoveTick.store((int64_t)GetTickCount64(), std::memory_order_relaxed);
            if (g_header) {
                g_header->CursorX = g_lastNormX;
                g_header->CursorY = g_lastNormY;
            }
            g_pressedMouseButtons.insert(buttonOrKey);
            auto res = ResolveTargetAndCoordsLocked(g_lastNormX, g_lastNormY);
            if (!res.hwnd) break;
            g_lastChildHWnd = res.hwnd;
            PostMessageW(res.hwnd, WM_ACTIVATE, (WPARAM)WA_ACTIVE, 0);
            UINT msg = (buttonOrKey == 1) ? WM_RBUTTONDOWN : (buttonOrKey == 2 ? WM_MBUTTONDOWN : WM_LBUTTONDOWN);
            WPARAM wParam = (buttonOrKey == 1) ? MK_RBUTTON : (buttonOrKey == 2 ? MK_MBUTTON : MK_LBUTTON);
            PostMessageW(res.hwnd, msg, wParam, MakeMouseLParam(res.clientX, res.clientY));
            break;
        }
        case 3: { // mouse_up
            g_lastNormX = clampedX;
            g_lastNormY = clampedY;
            g_lastInjectedMoveTick.store((int64_t)GetTickCount64(), std::memory_order_relaxed);
            if (g_header) {
                g_header->CursorX = g_lastNormX;
                g_header->CursorY = g_lastNormY;
            }
            g_pressedMouseButtons.erase(buttonOrKey);
            auto res = ResolveTargetAndCoordsLocked(g_lastNormX, g_lastNormY);
            if (!res.hwnd) break;
            UINT msg = (buttonOrKey == 1) ? WM_RBUTTONUP : (buttonOrKey == 2 ? WM_MBUTTONUP : WM_LBUTTONUP);
            PostMessageW(res.hwnd, msg, 0, MakeMouseLParam(res.clientX, res.clientY));
            break;
        }
        case 4: { // mouse_wheel
            auto res = ResolveTargetAndCoordsLocked(g_lastNormX, g_lastNormY);
            if (!res.hwnd) break;
            WPARAM wParam = (WPARAM)((int16_t)buttonOrKey << 16);
            PostMessageW(res.hwnd, WM_MOUSEWHEEL, wParam, MakeMouseLParam(res.screenPt.x, res.screenPt.y));
            break;
        }
        case 5: { // key_down
            if (buttonOrKey <= 0) break;
            int normVk = NormalizeModifierVk(buttonOrKey);
            if (!g_pressedKeys.insert(normVk).second) break;
            HWND targetHwnd = GetActiveKeyboardTargetLocked();
            if (targetHwnd) {
                DispatchKeyStateChangeLocked(targetHwnd, normVk, buttonOrKey, true);
            }
            break;
        }
        case 6: { // key_up
            if (buttonOrKey <= 0) break;
            int normVk = NormalizeModifierVk(buttonOrKey);
            if (g_pressedKeys.erase(normVk) == 0) break;
            HWND targetHwnd = GetActiveKeyboardTargetLocked();
            if (targetHwnd) {
                DispatchKeyStateChangeLocked(targetHwnd, normVk, buttonOrKey, false);
            }
            break;
        }
        case 7: { // reset
            ResetAllInputsLocked();
            break;
        }
    }
}

static void PollPhysicalKeyboard1000Hz(bool interactionModeActive) {
    std::lock_guard<std::mutex> lock(g_inputMutex);
    if (!interactionModeActive || g_testPatternActive.load(std::memory_order_relaxed)) {
        if (g_wasInteractionActive) {
            g_wasInteractionActive = false;
            ResetAllInputsLocked();
        }
        return;
    }

    HWND targetHwnd = GetActiveKeyboardTargetLocked();
    if (!targetHwnd) return;

    if (!g_wasInteractionActive) {
        g_wasInteractionActive = true;
        BoostWindowProcessForLowLatency(g_targetHWnd ? g_targetHWnd : targetHwnd);
        SendFakeActivate(targetHwnd);
        if (g_targetHWnd && g_targetHWnd != targetHwnd) {
            SendFakeActivate(g_targetHWnd);
        }
    }

    HWND fgHwnd = GetForegroundWindow();
    if (fgHwnd && (fgHwnd == g_targetHWnd || fgHwnd == targetHwnd)) {
        return;
    }

    bool isShiftPhysicallyDown = ((GetAsyncKeyState(0xA0) | GetAsyncKeyState(0xA1)) & 0x8000) != 0;

    for (int vk : g_watchedVks) {
        // Do not inject ESC when Shift is held (Shift+ESC is reserved to exit Minecraft interaction mode)
        if (vk == 0x1B && isShiftPhysicallyDown) {
            continue;
        }

        bool isDown = (GetAsyncKeyState(vk) & 0x8000) != 0;
        int normVk = NormalizeModifierVk(vk);

        if (isDown) {
            if (g_pressedKeys.insert(normVk).second) {
                DispatchKeyStateChangeLocked(targetHwnd, normVk, vk, true);
            }
        } else {
            if (IsModifierPairStillHeld(normVk)) continue;
            if (g_pressedKeys.erase(normVk) > 0) {
                DispatchKeyStateChangeLocked(targetHwnd, normVk, vk, false);
            }
        }
    }
}

static void GetCursorNormalized(float& normX, float& normY) {
    int64_t nowTick = (int64_t)GetTickCount64();
    if (nowTick - g_lastInjectedMoveTick.load(std::memory_order_relaxed) < 1500) {
        normX = g_lastNormX;
        normY = g_lastNormY;
        return;
    }

    POINT pt{};
    if (GetCursorPos(&pt)) {
        HWND hWnd = g_targetHWnd;
        if (hWnd && IsWindow(hWnd)) {
            RECT rect{};
            if (GetWindowRect(hWnd, &rect)) {
                int w = rect.right - rect.left;
                int h = rect.bottom - rect.top;
                if (w > 0 && h > 0 && pt.x >= rect.left && pt.x <= rect.right && pt.y >= rect.top && pt.y <= rect.bottom) {
                    normX = std::clamp((float)(pt.x - rect.left) / (float)w, 0.0f, 1.0f);
                    normY = std::clamp((float)(pt.y - rect.top)  / (float)h, 0.0f, 1.0f);
                    return;
                }
            }
        } else {
            int screenW = std::max(1, GetSystemMetrics(SM_CXSCREEN));
            int screenH = std::max(1, GetSystemMetrics(SM_CYSCREEN));
            normX = std::clamp((float)pt.x / (float)screenW, 0.0f, 1.0f);
            normY = std::clamp((float)pt.y / (float)screenH, 0.0f, 1.0f);
            return;
        }
    }

    normX = g_lastNormX;
    normY = g_lastNormY;
}

// ============================================================================
// Direct3D 11 + Windows Graphics Capture Engine
// ============================================================================
static std::mutex                            g_captureMutex;
static ID3D11Device*                         g_d3dDevice      = nullptr;
static ID3D11DeviceContext*                  g_d3dContext     = nullptr;
static winrt_d3d::IDirect3DDevice            g_winrtDevice    = nullptr;
static winrt_cap::GraphicsCaptureItem        g_captureItem    = nullptr;
static winrt_cap::Direct3D11CaptureFramePool g_framePool      = nullptr;
static winrt_cap::GraphicsCaptureSession     g_captureSession = nullptr;
static winrt::event_token                    g_frameArrivedToken{};

static ID3D11Texture2D*                      g_stagingTextures[2]{ nullptr, nullptr };
static int                                   g_stagingWriteIndex = 0;
static int                                   g_stagingWidth   = 0;
static int                                   g_stagingHeight  = 0;

static std::atomic<bool>                     g_isCapturing{false};
static std::atomic<int>                      g_scaleMode{1}; // 1: Full (1:1 default), 0: Half
static std::atomic<bool>                     g_captureCursor{false};
static std::atomic<double>                   g_currentFps{0.0};
static std::atomic<int64_t>                  g_framesLastSecond{0};
static std::atomic<int64_t>                  g_lastFpsTimeMs{0};
static std::wstring                          g_targetSummary = L"Primary Monitor";

static void ReleaseStagingTexturesLocked() {
    for (int i = 0; i < 2; i++) {
        if (g_stagingTextures[i]) {
            g_stagingTextures[i]->Release();
            g_stagingTextures[i] = nullptr;
        }
    }
    g_stagingWidth = 0;
    g_stagingHeight = 0;
}

static bool CreateStagingTexturesLocked(int width, int height) {
    ReleaseStagingTexturesLocked();
    if (!g_d3dDevice || width <= 0 || height <= 0 || width > MAX_FRAME_DIMENSION || height > MAX_FRAME_DIMENSION) {
        return false;
    }

    D3D11_TEXTURE2D_DESC desc{};
    desc.Width = (UINT)width;
    desc.Height = (UINT)height;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;
    desc.SampleDesc.Count = 1;
    desc.SampleDesc.Quality = 0;
    desc.Usage = D3D11_USAGE_STAGING;
    desc.BindFlags = 0;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    desc.MiscFlags = 0;

    for (int i = 0; i < 2; i++) {
        HRESULT hr = g_d3dDevice->CreateTexture2D(&desc, nullptr, &g_stagingTextures[i]);
        if (FAILED(hr) || !g_stagingTextures[i]) {
            ReleaseStagingTexturesLocked();
            return false;
        }
    }
    g_stagingWidth = width;
    g_stagingHeight = height;
    g_stagingWriteIndex = 0;
    return true;
}

static bool EnsureD3DInitializedLocked() {
    if (g_d3dDevice && g_d3dContext && g_winrtDevice) return true;

    D3D_FEATURE_LEVEL featureLevels[] = {
        D3D_FEATURE_LEVEL_11_1,
        D3D_FEATURE_LEVEL_11_0,
        D3D_FEATURE_LEVEL_10_1,
        D3D_FEATURE_LEVEL_10_0
    };
    D3D_FEATURE_LEVEL selectedLevel{};

    HRESULT hr = D3D11CreateDevice(
        nullptr,
        D3D_DRIVER_TYPE_HARDWARE,
        nullptr,
        D3D11_CREATE_DEVICE_BGRA_SUPPORT,
        featureLevels,
        ARRAYSIZE(featureLevels),
        D3D11_SDK_VERSION,
        &g_d3dDevice,
        &selectedLevel,
        &g_d3dContext);

    if (FAILED(hr) || !g_d3dDevice || !g_d3dContext) {
        return false;
    }

    IDXGIDevice* dxgiDevice = nullptr;
    if (SUCCEEDED(g_d3dDevice->QueryInterface(__uuidof(IDXGIDevice), reinterpret_cast<void**>(&dxgiDevice))) && dxgiDevice) {
        dxgiDevice->SetGPUThreadPriority(7);

        IDXGIDevice1* dxgiDevice1 = nullptr;
        if (SUCCEEDED(dxgiDevice->QueryInterface(__uuidof(IDXGIDevice1), reinterpret_cast<void**>(&dxgiDevice1))) && dxgiDevice1) {
            dxgiDevice1->SetMaximumFrameLatency(1);
            dxgiDevice1->Release();
        }

        IInspectable* inspectableDevice = nullptr;
        if (SUCCEEDED(CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice, &inspectableDevice)) && inspectableDevice) {
            winrt::copy_from_abi(g_winrtDevice, inspectableDevice);
            inspectableDevice->Release();
        }
        dxgiDevice->Release();
    }

    return g_winrtDevice != nullptr;
}

static void StopCaptureLocked() {
    g_isCapturing.store(false, std::memory_order_relaxed);
    g_testPatternActive.store(false, std::memory_order_relaxed);

    if (g_captureSession) {
        try { g_captureSession.Close(); } catch (...) {}
        g_captureSession = nullptr;
    }
    if (g_framePool) {
        try {
            g_framePool.FrameArrived(g_frameArrivedToken);
            g_framePool.Close();
        } catch (...) {}
        g_framePool = nullptr;
    }
    g_captureItem = nullptr;
    ReleaseStagingTexturesLocked();
    g_currentFps.store(0.0, std::memory_order_relaxed);
    g_framesLastSecond.store(0, std::memory_order_relaxed);
    g_lastFpsTimeMs.store(0, std::memory_order_relaxed);
}

static void ReleaseD3DResourcesLocked() {
    StopCaptureLocked();
    g_winrtDevice = nullptr;
    if (g_d3dContext) {
        g_d3dContext->Release();
        g_d3dContext = nullptr;
    }
    if (g_d3dDevice) {
        g_d3dDevice->Release();
        g_d3dDevice = nullptr;
    }
}

static void OnFrameArrived(winrt_cap::Direct3D11CaptureFramePool const& sender, winrt::Windows::Foundation::IInspectable const&) {
    if (!g_isCapturing.load(std::memory_order_relaxed)) return;

    std::lock_guard<std::mutex> lock(g_captureMutex);
    if (!g_isCapturing.load(std::memory_order_relaxed)) return;

    winrt_cap::Direct3D11CaptureFrame frame{ nullptr };
    try {
        while (true) {
            auto next = sender.TryGetNextFrame();
            if (!next) break;
            if (frame) frame.Close();
            frame = next;
        }
    } catch (...) {
        if (frame) try { frame.Close(); } catch (...) {}
        return;
    }

    if (!frame) return;

    int frameW = 0;
    int frameH = 0;
    ID3D11Texture2D* currentStaging = nullptr;

    try {
        auto contentSize = frame.ContentSize();
        frameW = contentSize.Width;
        frameH = contentSize.Height;
        if (frameW <= 0 || frameH <= 0 || frameW > MAX_FRAME_DIMENSION || frameH > MAX_FRAME_DIMENSION) {
            frame.Close();
            return;
        }

        if (!g_stagingTextures[0] || g_stagingWidth != frameW || g_stagingHeight != frameH) {
            if (!CreateStagingTexturesLocked(frameW, frameH)) {
                frame.Close();
                return;
            }
            sender.Recreate(
                g_winrtDevice,
                winrt_dx::DirectXPixelFormat::B8G8R8A8UIntNormalized,
                2,
                contentSize);
        }

        auto surface = frame.Surface();
        auto access = surface.as<IDirect3DDxgiInterfaceAccess>();
        ID3D11Texture2D* srcTex = nullptr;
        HRESULT hr = access->GetInterface(__uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&srcTex));
        if (FAILED(hr) || !srcTex) {
            frame.Close();
            return;
        }

        currentStaging = g_stagingTextures[g_stagingWriteIndex];
        g_d3dContext->CopyResource(currentStaging, srcTex);
        g_d3dContext->Flush();
        srcTex->Release();
    } catch (...) {
        try { frame.Close(); } catch (...) {}
        return;
    }

    // Release WGC surface back to DWM immediately before CPU map/copy
    try { frame.Close(); } catch (...) {}

    D3D11_MAPPED_SUBRESOURCE mapped{};
    HRESULT hr = g_d3dContext->Map(currentStaging, 0, D3D11_MAP_READ, 0, &mapped);
    if (SUCCEEDED(hr)) {
        WriteFrameToSharedMemory(mapped.pData, mapped.RowPitch, frameW, frameH, g_scaleMode.load(std::memory_order_relaxed));
        g_d3dContext->Unmap(currentStaging, 0);

        int64_t frames = g_framesLastSecond.fetch_add(1, std::memory_order_relaxed) + 1;
        int64_t nowMs = NowUnixMs();
        int64_t lastMs = g_lastFpsTimeMs.load(std::memory_order_relaxed);
        if (lastMs == 0) {
            g_lastFpsTimeMs.store(nowMs, std::memory_order_relaxed);
        } else {
            int64_t elapsedMs = nowMs - lastMs;
            if (elapsedMs >= 1000) {
                g_currentFps.store((double)frames * 1000.0 / (double)elapsedMs, std::memory_order_relaxed);
                g_framesLastSecond.store(0, std::memory_order_relaxed);
                g_lastFpsTimeMs.store(nowMs, std::memory_order_relaxed);
            }
        }
    }

    g_stagingWriteIndex = 1 - g_stagingWriteIndex;
}

static bool StartCaptureWithItemLocked(winrt_cap::GraphicsCaptureItem const& item) {
    if (!item) return false;
    auto size = item.Size();
    if (size.Width <= 0 || size.Height <= 0 || size.Width > MAX_FRAME_DIMENSION || size.Height > MAX_FRAME_DIMENSION) {
        return false;
    }

    try {
        g_captureItem = item;
        g_framePool = winrt_cap::Direct3D11CaptureFramePool::CreateFreeThreaded(
            g_winrtDevice,
            winrt_dx::DirectXPixelFormat::B8G8R8A8UIntNormalized,
            2,
            size);

        g_frameArrivedToken = g_framePool.FrameArrived(&OnFrameArrived);
        g_captureSession = g_framePool.CreateCaptureSession(g_captureItem);
        try {
            g_captureSession.IsCursorCaptureEnabled(g_captureCursor.load(std::memory_order_relaxed));
        } catch (...) {}
        g_captureSession.StartCapture();
        g_isCapturing.store(true, std::memory_order_relaxed);
        return true;
    } catch (...) {
        StopCaptureLocked();
        return false;
    }
}

static bool StartWindowCapture(HWND hWnd) {
    if (!IsSafeTargetWindow(hWnd)) return false;
    RoInitialize(RO_INIT_MULTITHREADED);

    std::lock_guard<std::mutex> lock(g_captureMutex);
    StopCaptureLocked();
    if (!EnsureD3DInitializedLocked()) return false;

    BoostWindowProcessForLowLatency(hWnd);

    try {
        auto interop = winrt::get_activation_factory<winrt_cap::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        winrt_cap::GraphicsCaptureItem item{ nullptr };
        HRESULT hr = interop->CreateForWindow(
            hWnd,
            winrt::guid_of<winrt_cap::GraphicsCaptureItem>(),
            winrt::put_abi(item));
        if (FAILED(hr) || !item) return false;

        if (StartCaptureWithItemLocked(item)) {
            SetInputTargetWindow(hWnd);
            wchar_t titleBuf[256]{};
            int len = GetWindowTextW(hWnd, titleBuf, 256);
            g_targetSummary = (len > 0) ? std::wstring(titleBuf, len) : L"Window";
            return true;
        }
    } catch (...) {}

    return false;
}

static bool StartMonitorCapture(HMONITOR hMon = nullptr) {
    RoInitialize(RO_INIT_MULTITHREADED);
    std::lock_guard<std::mutex> lock(g_captureMutex);
    StopCaptureLocked();
    if (!EnsureD3DInitializedLocked()) return false;

    if (!hMon) {
        hMon = MonitorFromWindow(nullptr, MONITOR_DEFAULTTOPRIMARY);
    }

    try {
        auto interop = winrt::get_activation_factory<winrt_cap::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();
        winrt_cap::GraphicsCaptureItem item{ nullptr };
        HRESULT hr = interop->CreateForMonitor(
            hMon,
            winrt::guid_of<winrt_cap::GraphicsCaptureItem>(),
            winrt::put_abi(item));
        if (FAILED(hr) || !item) return false;

        if (StartCaptureWithItemLocked(item)) {
            SetInputTargetWindow(nullptr);
            g_targetSummary = L"Desktop (Primary Monitor)";
            return true;
        }
    } catch (...) {}

    return false;
}

static void StartTestPatternMode() {
    std::lock_guard<std::mutex> lock(g_captureMutex);
    StopCaptureLocked();
    SetInputTargetWindow(nullptr);
    g_testPatternActive.store(true, std::memory_order_relaxed);
    g_targetSummary = L"Test Pattern (60fps Color Bars)";
}

static void GenerateTestPatternFrame(int tick) {
    std::lock_guard<std::mutex> lock(g_captureMutex);
    if (!g_header || !g_testPatternActive.load(std::memory_order_relaxed)) return;

    const int width = 854;
    const int height = 480;
    LONG currentSlot = InterlockedCompareExchange(&g_header->ActiveSlot, 0, 0);
    LONG nextSlot = 1 - (currentSlot & 1);
    uint32_t* dst = reinterpret_cast<uint32_t*>((nextSlot == 0) ? g_slot0Ptr : g_slot1Ptr);

    static const uint32_t bars[8] = {
        0xFFEBEBEB, 0xFFEBEB10, 0xFF10EBEB, 0xFF10EB10,
        0xFFEB10EB, 0xFFEB1010, 0xFF1010EB, 0xFF181818
    };

    int barWidth = width / 8;
    int sweepX = (tick * 8) % width;

    for (int y = 0; y < height; y++) {
        uint32_t* row = dst + y * width;
        for (int x = 0; x < width; x++) {
            int idx = std::min(7, x / barWidth);
            uint32_t color = bars[idx];
            if (std::abs(x - sweepX) < 6) {
                color = 0xFFFFFFFF;
            }
            row[x] = color;
        }
    }

    InterlockedExchange(&g_header->Width, width);
    InterlockedExchange(&g_header->Height, height);
    InterlockedExchange(&g_header->Format, 3);
    InterlockedExchange64(&g_header->TimestampUs, NowUnixMs());
    InterlockedExchange(&g_header->State, 1);
    _mm_sfence();
    std::atomic_thread_fence(std::memory_order_release);
    InterlockedExchange(&g_header->ActiveSlot, nextSlot);
    InterlockedIncrement64(&g_header->FrameSequence);
}

// ============================================================================
// Window Enumeration
// ============================================================================
struct WindowEntryInfo {
    HWND hwnd;
    std::wstring processName;
    std::wstring title;
    int width;
    int height;
};

static std::wstring GetProcessBaseName(DWORD pid) {
    std::wstring name = L"Unknown";
    HANDLE hProc = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (hProc) {
        wchar_t pathBuf[MAX_PATH]{};
        DWORD size = MAX_PATH;
        if (QueryFullProcessImageNameW(hProc, 0, pathBuf, &size)) {
            std::wstring full(pathBuf, size);
            size_t slash = full.find_last_of(L"\\/");
            name = (slash != std::wstring::npos) ? full.substr(slash + 1) : full;
            size_t dot = name.find_last_of(L'.');
            if (dot != std::wstring::npos) {
                name = name.substr(0, dot);
            }
        }
        CloseHandle(hProc);
    }
    return name;
}

static std::vector<WindowEntryInfo> EnumerateCapturableWindows() {
    std::vector<WindowEntryInfo> list;

    EnumWindows([](HWND hWnd, LPARAM lParam) -> BOOL {
        auto* outList = reinterpret_cast<std::vector<WindowEntryInfo>*>(lParam);
        if (!IsWindowVisible(hWnd) || IsIconic(hWnd)) return TRUE;

        wchar_t titleBuf[256]{};
        int len = GetWindowTextW(hWnd, titleBuf, 256);
        if (len <= 0) return TRUE;

        std::wstring title(titleBuf, len);
        if (title == L"Program Manager" || title == L"Settings" || title == L"Microsoft Text Input Application") {
            return TRUE;
        }

        RECT rect{};
        if (!GetWindowRect(hWnd, &rect)) return TRUE;
        int w = rect.right - rect.left;
        int h = rect.bottom - rect.top;
        if (w <= 100 || h <= 100) return TRUE;

        DWORD pid = 0;
        GetWindowThreadProcessId(hWnd, &pid);
        if (pid == 0 || pid == GetCurrentProcessId()) return TRUE;

        std::wstring procName = GetProcessBaseName(pid);
        std::replace(title.begin(), title.end(), L'|', L'/');
        std::replace(procName.begin(), procName.end(), L'|', L'/');

        outList->push_back({ hWnd, procName, title, w, h });
        return TRUE;
    }, reinterpret_cast<LPARAM>(&list));

    std::sort(list.begin(), list.end(), [](const WindowEntryInfo& a, const WindowEntryInfo& b) {
        if (a.processName != b.processName) return a.processName < b.processName;
        return a.title < b.title;
    });

    int screenW = std::max(1, GetSystemMetrics(SM_CXSCREEN));
    int screenH = std::max(1, GetSystemMetrics(SM_CYSCREEN));
    list.insert(list.begin(), { nullptr, L"Desktop", L"デスクトップ全体 (全画面)", screenW, screenH });
    return list;
}

// ============================================================================
// Background 1000Hz Input & Heartbeat Thread
// ============================================================================
static std::atomic<bool> g_workerRunning{false};
static std::atomic<bool> g_timerPeriodActive{false};
static std::thread       g_workerThread;

static void InputAndHeartbeatLoop() {
    RoInitialize(RO_INIT_MULTITHREADED);
    SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_HIGHEST);
    int64_t lastHeartbeatTick = 0;
    int testPatternTick = 0;

    while (g_workerRunning.load(std::memory_order_relaxed)) {
        bool interactActive = g_header && (InterlockedCompareExchange(&g_header->InteractionMode, 0, 0) == 1);
        PollPhysicalKeyboard1000Hz(interactActive);

        int64_t nowTick = (int64_t)GetTickCount64();
        if (nowTick - lastHeartbeatTick >= 16) {
            lastHeartbeatTick = nowTick;
            if (g_header) {
                float cx = 0.5f, cy = 0.5f;
                GetCursorNormalized(cx, cy);
                g_header->CursorX = cx;
                g_header->CursorY = cy;
                InterlockedExchange64(&g_header->TimestampUs, NowUnixMs());
            }
            if (g_testPatternActive.load(std::memory_order_relaxed)) {
                GenerateTestPatternFrame(testPatternTick++);
            }
        }

        Sleep(1);
    }
}

// ============================================================================
// JNI Implementation (com.github.shin_ideal.screencapturecontrol.client.NativeCaptureBridge)
// ============================================================================
static jobject JNICALL jni_nativeInit(JNIEnv* env, jclass) {
    static std::mutex initMutex;
    std::lock_guard<std::mutex> lock(initMutex);

    if (!g_timerPeriodActive.exchange(true)) {
        timeBeginPeriod(1);
    }
    DisableProcessPowerThrottlingOnHandle(GetCurrentProcess());
    InitWatchedVks();

    // Initialize WinRT without throwing if Client thread is already STA (RPC_E_CHANGED_MODE)
    RoInitialize(RO_INIT_MULTITHREADED);

    if (!g_sharedBuffer) {
        g_sharedBuffer = reinterpret_cast<uint8_t*>(_aligned_malloc(TOTAL_BUFFER_SIZE, 64));
        if (!g_sharedBuffer) return nullptr;
        std::memset(g_sharedBuffer, 0, HEADER_SIZE);

        g_header   = reinterpret_cast<SharedHeader*>(g_sharedBuffer);
        g_slot0Ptr = g_sharedBuffer + FRAME_SLOT_0_OFFSET;
        g_slot1Ptr = g_sharedBuffer + FRAME_SLOT_1_OFFSET;

        g_header->Version = SHM_VERSION;
        InterlockedExchange(&g_header->Format, 3); // BGRA8
        InterlockedExchange(&g_header->ActiveSlot, 0);
        InterlockedExchange64(&g_header->FrameSequence, 0);
        InterlockedExchange(&g_header->ScaleMode, 1); // Full 1:1
        g_header->CursorX = 0.5f;
        g_header->CursorY = 0.5f;
        InterlockedExchange64(&g_header->TimestampUs, NowUnixMs());
        InterlockedExchange(&g_header->State, 1);
        std::atomic_thread_fence(std::memory_order_release);
        g_header->Magic = SHM_MAGIC;
    } else {
        InterlockedExchange64(&g_header->TimestampUs, NowUnixMs());
        InterlockedExchange(&g_header->State, 1);
    }

    if (!g_workerRunning.load()) {
        g_workerRunning.store(true);
        g_workerThread = std::thread(InputAndHeartbeatLoop);
    }

    if (!g_isCapturing.load() && !g_testPatternActive.load()) {
        if (!StartMonitorCapture(nullptr)) {
            StartTestPatternMode();
        }
    }

    return env->NewDirectByteBuffer(g_sharedBuffer, (jlong)TOTAL_BUFFER_SIZE);
}

static void JNICALL jni_nativeShutdown(JNIEnv*, jclass) {
    if (g_header) {
        InterlockedExchange(&g_header->InteractionMode, 0);
    }
    PollPhysicalKeyboard1000Hz(false);

    if (g_workerRunning.exchange(false)) {
        if (g_workerThread.joinable()) {
            g_workerThread.join();
        }
    }

    {
        std::lock_guard<std::mutex> lock(g_captureMutex);
        ReleaseD3DResourcesLocked();
    }

    if (g_header) {
        InterlockedExchange(&g_header->State, 2);
    }

    if (g_timerPeriodActive.exchange(false)) {
        timeEndPeriod(1);
    }
}

static jobjectArray JNICALL jni_nativeListWindows(JNIEnv* env, jclass) {
    auto windows = EnumerateCapturableWindows();
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;

    jobjectArray result = env->NewObjectArray((jsize)windows.size(), stringClass, nullptr);
    if (!result) return nullptr;

    for (size_t i = 0; i < windows.size(); i++) {
        const auto& w = windows[i];
        std::wstring line = std::to_wstring((uintptr_t)w.hwnd)
            + L"|" + w.processName
            + L"|" + std::to_wstring(w.width)
            + L"|" + std::to_wstring(w.height)
            + L"|" + w.title;
        jstring jstr = env->NewString(reinterpret_cast<const jchar*>(line.data()), (jsize)line.size());
        if (jstr) {
            env->SetObjectArrayElement(result, (jsize)i, jstr);
            env->DeleteLocalRef(jstr);
        }
    }
    return result;
}

static jboolean JNICALL jni_nativeStartWindowCapture(JNIEnv*, jclass, jlong hwndLong) {
    if (hwndLong == 0) {
        return StartMonitorCapture(nullptr) ? JNI_TRUE : JNI_FALSE;
    }
    return StartWindowCapture(reinterpret_cast<HWND>((uintptr_t)hwndLong)) ? JNI_TRUE : JNI_FALSE;
}

static jboolean JNICALL jni_nativeStartMonitorCapture(JNIEnv*, jclass) {
    return StartMonitorCapture(nullptr) ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL jni_nativeStartTestPattern(JNIEnv*, jclass) {
    StartTestPatternMode();
}

static void JNICALL jni_nativeSetScaleMode(JNIEnv*, jclass, jint mode) {
    g_scaleMode.store(mode == 0 ? 0 : 1, std::memory_order_relaxed);
}

static void JNICALL jni_nativeSetCursorCapture(JNIEnv*, jclass, jboolean enabled) {
    bool flag = (enabled == JNI_TRUE);
    g_captureCursor.store(flag, std::memory_order_relaxed);
    std::lock_guard<std::mutex> lock(g_captureMutex);
    if (g_captureSession) {
        try { g_captureSession.IsCursorCaptureEnabled(flag); } catch (...) {}
    }
}

static void JNICALL jni_nativeSetInteractionMode(JNIEnv*, jclass, jint mode) {
    if (g_header) {
        InterlockedExchange(&g_header->InteractionMode, (LONG)mode);
    }
    if (mode == 0) {
        PollPhysicalKeyboard1000Hz(false);
    }
}

static void JNICALL jni_nativeSendInput(JNIEnv*, jclass, jint type, jint buttonOrKey, jfloat normX, jfloat normY) {
    ProcessRawInputEvent((int)type, (int)buttonOrKey, (float)normX, (float)normY);
}

static jdouble JNICALL jni_nativeGetCaptureFps(JNIEnv*, jclass) {
    return (jdouble)g_currentFps.load(std::memory_order_relaxed);
}

static jstring JNICALL jni_nativeGetTargetSummary(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_captureMutex);
    return env->NewString(reinterpret_cast<const jchar*>(g_targetSummary.data()), (jsize)g_targetSummary.size());
}

static JNINativeMethod g_methods[] = {
    { const_cast<char*>("nativeInit"),                const_cast<char*>("()Ljava/nio/ByteBuffer;"),   reinterpret_cast<void*>(&jni_nativeInit) },
    { const_cast<char*>("nativeShutdown"),            const_cast<char*>("()V"),                       reinterpret_cast<void*>(&jni_nativeShutdown) },
    { const_cast<char*>("nativeListWindows"),         const_cast<char*>("()[Ljava/lang/String;"),     reinterpret_cast<void*>(&jni_nativeListWindows) },
    { const_cast<char*>("nativeStartWindowCapture"),  const_cast<char*>("(J)Z"),                      reinterpret_cast<void*>(&jni_nativeStartWindowCapture) },
    { const_cast<char*>("nativeStartMonitorCapture"), const_cast<char*>("()Z"),                       reinterpret_cast<void*>(&jni_nativeStartMonitorCapture) },
    { const_cast<char*>("nativeStartTestPattern"),    const_cast<char*>("()V"),                       reinterpret_cast<void*>(&jni_nativeStartTestPattern) },
    { const_cast<char*>("nativeSetScaleMode"),        const_cast<char*>("(I)V"),                      reinterpret_cast<void*>(&jni_nativeSetScaleMode) },
    { const_cast<char*>("nativeSetCursorCapture"),    const_cast<char*>("(Z)V"),                      reinterpret_cast<void*>(&jni_nativeSetCursorCapture) },
    { const_cast<char*>("nativeSetInteractionMode"),  const_cast<char*>("(I)V"),                      reinterpret_cast<void*>(&jni_nativeSetInteractionMode) },
    { const_cast<char*>("nativeSendInput"),           const_cast<char*>("(IIFF)V"),                   reinterpret_cast<void*>(&jni_nativeSendInput) },
    { const_cast<char*>("nativeGetCaptureFps"),       const_cast<char*>("()D"),                       reinterpret_cast<void*>(&jni_nativeGetCaptureFps) },
    { const_cast<char*>("nativeGetTargetSummary"),    const_cast<char*>("()Ljava/lang/String;"),      reinterpret_cast<void*>(&jni_nativeGetTargetSummary) }
};

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        return JNI_ERR;
    }

    jclass cls = env->FindClass("com/github/shin_ideal/screencapturecontrol/client/NativeCaptureBridge");
    if (!cls) {
        return JNI_ERR;
    }

    if (env->RegisterNatives(cls, g_methods, sizeof(g_methods) / sizeof(g_methods[0])) < 0) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
