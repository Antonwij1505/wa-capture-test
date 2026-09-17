@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
title WA Capture Test - Diagnostik v4

REM ============================================================
REM  Skrip diagnostik 1-klik untuk WA Capture Test (v4)
REM  Dijalankan di PC Windows user (HP tersambung via USB).
REM
REM  Yang dilakukan:
REM   1. Cek HP terdeteksi adb?
REM   2. Cek VERSI terpasang (versionName/versionCode + waktu install)
REM   3. Cek IZIN (mikrofon, notifikasi)
REM   4. Install ulang v4 (opsional, tanya dulu)
REM   5. Bersihkan logcat
REM   6. [USER] jalankan uji manual di HP
REM   7. Ambil logcat + cek folder output
REM   8. Simpan semua ke folder hasil
REM ============================================================

set PKG=id.orimax.wacapturetest
set APK=%~dp0wa-capture-test-v4.apk
set OUT=%~dp0diagnostik-%DATE:~-4%%DATE:~3,2%%DATE:~0,2%-%TIME:~0,2%%TIME:~3,2%
set OUT=%OUT: =0%

REM --- Cari adb ---
set ADB=
where adb >nul 2>&1 && set ADB=adb
if "!ADB!"=="" if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set ADB="%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if "!ADB!"=="" if exist "%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe" set ADB="%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if "!ADB!"=="" if exist "C:\platform-tools\adb.exe" set ADB="C:\platform-tools\adb.exe"

echo ============================================================
echo   WA CAPTURE TEST - DIAGNOSTIK v4
echo ============================================================
echo.

if "!ADB!"=="" (
  echo [X] adb.exe TIDAK DITEMUKAN.
  echo     Letakkan adb.exe di folder yang sama dengan skrip ini,
  echo     atau tambahkan platform-tools ke PATH.
  echo.
  pause
  exit /b 1
)

echo [1/8] Cek koneksi HP...
!ADB! devices -l > "%TEMP%\wact_devices.txt" 2>&1
type "%TEMP%\wact_devices.txt"
findstr /C:"device " "%TEMP%\wact_devices.txt" >nul
if errorlevel 1 (
  echo.
  echo [X] TIDAK ADA HP terdeteksi.
  echo     - Pastikan kabel USB tersambung
  echo     - Aktifkan "USB debugging" di Opsi Pengembang
  echo     - Kalau muncul dialog di HP: tekan "Izinkan / Selalu izinkan"
  echo     - Coba: !ADB! kill-server ^&^& !ADB! devices
  echo.
  pause
  exit /b 1
)
echo [OK] HP terdeteksi.
echo.

mkdir "%OUT%" 2>nul

echo [2/8] Cek VERSI terpasang...
echo --- dumpsys package (versi ^& waktu update) --- > "%OUT%\01_versi.txt"
!ADB! shell dumpsys package %PKG% | findstr /C:"versionName" /C:"versionCode" /C:"lastUpdateTime" /C:"firstInstallTime" >> "%OUT%\01_versi.txt" 2>&1
!ADB! shell dumpsys package %PKG% | findstr /C:"versionName" /C:"versionCode" /C:"lastUpdateTime" /C:"firstInstallTime"
echo.
echo   ^(Cek: kalau versi BUKAN 4.0/4, berarti v4 belum terpasang.^)
echo.

echo [3/8] Cek IZIN...
echo --- izin granted --- > "%OUT%\02_izin.txt"
!ADB! shell dumpsys package %PKG% | findstr /C:"android.permission.RECORD_AUDIO" /C:"android.permission.POST_NOTIFICATIONS" /C:"android.permission.FOREGROUND_SERVICE" >> "%OUT%\02_izin.txt" 2>&1
!ADB! shell dumpsys package %PKG% | findstr /C:"android.permission.RECORD_AUDIO" /C:"android.permission.POST_NOTIFICATIONS" /C:"android.permission.FOREGROUND_SERVICE"
echo.

echo [4/8] Install ulang v4?
if not exist "%APK%" (
  echo   [!] File "%APK%" tidak ada di folder skrip ini.
  goto skip_install
)
set /p ANS=  Install ulang sekarang? (y/n): 
if /i "!ANS!"=="y" (
  echo   Menginstall...
  !ADB! install -r "%APK%" > "%OUT%\03_install.txt" 2>&1
  type "%OUT%\03_install.txt"
  echo.
  echo   Memberi izin mikrofon + notifikasi otomatis...
  !ADB! shell pm grant %PKG% android.permission.RECORD_AUDIO > "%OUT%\03_install.txt" 2>&1
  !ADB! shell pm grant %PKG% android.permission.POST_NOTIFICATIONS >> "%OUT%\03_install.txt" 2>&1
  echo   [OK] Izin diberikan ^(kalau gagal, berikan manual di HP^).
) else (
  echo   Dilewati.
)
:skip_install
echo.

echo [5/8] Bersihkan logcat...
!ADB! logcat -c
echo [OK] Logcat dibersihkan.
echo.

echo ============================================================
echo   SEKARANG UJI DI HP (jangan tutup jendela ini)
echo ============================================================
echo.
echo   1. Buka app "WA Capture Test" di HP
echo   2. Tekan "Mulai Rekam"
echo   3. Kalau muncul dialog izin mikrofon -^> tekan IZINKAN
echo   4. Kalau muncul dialog rekam layar -^> tekan "MULAI SEKARANG"
echo      (JANGAN tap di luar dialog)
echo   5. Pastikan notifikasi "WA Capture Test" muncul ^& BERTAHAN
echo   6. Minimize app, TELPON WA ke nomor lain, ngobrol ^>=30 detik
echo   7. Buka lagi app, tekan "Stop"
echo   8. Tekan "Putar Hasil" - dengarkan: suara lawan KERAS atau kecil?
echo      CATAT: apakah suara lawan terdengar keras/jernih?
echo.
echo   Setelah selesai, tekan tombol apa saja di sini untuk mengumpulkan hasil.
echo.
pause >nul

echo.
echo [6/8] Mengambil logcat (tag WaCaptureTest)...
!ADB! logcat -d -s WaCaptureTest:V > "%OUT%\04_logcat_wacapture.txt" 2>&1
for %%A in ("%OUT%\04_logcat_wacapture.txt") do echo   Ukuran: %%~zA byte
type "%OUT%\04_logcat_wacapture.txt"
echo.

echo [7/8] Mengambil logcat lengkap (untuk error MediaProjection/AudioRecord)...
!ADB! logcat -d > "%OUT%\05_logcat_full.txt" 2>&1
!ADB! logcat -d ^| findstr /I /C:"orimax" /C:"MediaProjection" /C:"AudioRecord" /C:"AudioPlayback" /C:"RemoteService" /C:"ANR" /C:"FATAL" > "%OUT%\06_logcat_filtered.txt" 2>&1
echo   --- Baris penting: ---
type "%OUT%\06_logcat_filtered.txt"
echo.

echo [8/8] Cek folder output rekaman...
!ADB! shell "ls -la /sdcard/Android/data/%PKG%/files/Music/ 2>&1" > "%OUT%\07_files.txt" 2>&1
type "%OUT%\07_files.txt"
echo.
!ADB! shell "find /sdcard/Android/data/%PKG%/files -type f 2>/dev/null" >> "%OUT%\07_files.txt" 2>&1

echo.
echo ============================================================
echo   SELESAI - hasil tersimpan di:
echo   %OUT%
echo ============================================================
echo.
echo   Kirim SEMUA file di folder itu ke asisten.
echo.
explorer "%OUT%" 2>nul
pause
