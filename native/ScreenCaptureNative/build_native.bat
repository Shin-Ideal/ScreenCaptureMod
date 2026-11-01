@echo off
setlocal
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
if errorlevel 1 exit /b 1

if defined JAVA_HOME (
  set "JDK_INC=%JAVA_HOME%\include"
) else (
  set "JDK_INC=C:\jdk\jdk8u462-b08\include"
)

if not exist "%~dp0..\..\src\main\resources\natives" mkdir "%~dp0..\..\src\main\resources\natives"

cl.exe /nologo /O2 /Oi /Ot /GS /arch:AVX2 /std:c++20 /EHsc /MT /D_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR /utf-8 ^
  /I"%JDK_INC%" ^
  /I"%JDK_INC%\win32" ^
  "%~dp0ScreenCaptureNative.cpp" ^
  /LD /Fe:"%~dp0..\..\src\main\resources\natives\ScreenCaptureNative.dll" ^
  /Fo:"%~dp0ScreenCaptureNative.obj" ^
  /link /OPT:REF /OPT:ICF /DYNAMICBASE /NXCOMPAT /HIGHENTROPYVA /IMPLIB:"%~dp0ScreenCaptureNative.lib" ^
  d3d11.lib dxgi.lib user32.lib kernel32.lib ole32.lib oleaut32.lib runtimeobject.lib windowsapp.lib winmm.lib
if errorlevel 1 exit /b %ERRORLEVEL%

if exist "%~dp0..\..\src\main\resources\natives\ScreenCaptureNative.lib" del /q "%~dp0..\..\src\main\resources\natives\ScreenCaptureNative.lib"
if exist "%~dp0..\..\src\main\resources\natives\ScreenCaptureNative.exp" del /q "%~dp0..\..\src\main\resources\natives\ScreenCaptureNative.exp"
exit /b 0
