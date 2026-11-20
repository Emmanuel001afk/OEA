@echo off
setlocal
rem Starts the OEA Android device and brings OEA to the front. This is what the
rem desktop shortcut points at after OEA-Laptop-Setup.cmd has run once.
set "SDK=%ANDROID_HOME%"
if not defined SDK if defined ANDROID_SDK_ROOT set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMULATOR=%SDK%\emulator\emulator.exe"
set "AVD=oea-laptop"
if exist "%LOCALAPPDATA%\OEA\avd-name.txt" set /p AVD=<"%LOCALAPPDATA%\OEA\avd-name.txt"

if not exist "%EMULATOR%" (
  echo OEA is not set up on this PC yet.
  echo Run OEA-Laptop-Setup.cmd once, then use this shortcut.
  pause
  exit /b 1
)

"%ADB%" devices | findstr /R /C:"emulator-[0-9].*device" >nul
if errorlevel 1 (
  echo Starting the OEA Android device...
  start "" "%EMULATOR%" -avd "%AVD%" -gpu auto
)
"%ADB%" wait-for-device
"%ADB%" shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >nul
exit /b 0
