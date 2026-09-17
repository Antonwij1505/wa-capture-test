# WA Capture Test — Uji MediaProjection untuk Rekaman WA Call

App uji mini untuk membuktikan: **apakah suara lawan bicara di panggilan WhatsApp
bisa direkam KERAS & JERNIH lewat MediaProjection** (tanpa root, tanpa Shizuku,
tanpa "helper" yang diblokir ColorOS).

## Kenapa ini penting

Cube ACR merekam via MIC → suara lawan yang keluar dari earpiece harus "terbang
di udara" balik ke mikrofon → hasilnya kecil. ColorOS juga memblokir "helper"
Cube → Cube mentok di MIC selamanya.

App ini **tidak pakai MIC**. Ia memakai AudioPlaybackCapture (API resmi Android
10+) untuk mengambil stream audio digital WhatsApp SEBELUM keluar ke speaker.

## Cara install

### Cara A — lewat adb (kalau HP tersambung)
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Cara B — manual
1. Copy `app/build/outputs/apk/debug/app-debug.apk` ke HP (kabel/kirim file)
2. Di HP: Setelan → Keamanan → izinkan "Install unknown apps" untuk File Manager
3. Ketuk APK → Install

## Cara uji

1. Buka app **WA Capture Test**
2. Tekan **"Mulai Rekam"**
3. Muncul dialog Android *"Mulai merekam atau mentransmisikan?"* → **Setujui**
4. Sekarang **lakukan panggilan suara WhatsApp** (ke siapa saja yang mau menerima)
5. Lihat **level meter** di app:
   - Bar bergerak naik-turun = audio tertangkap ✅
   - "Sinyal: N buffer, M berbunyi (X%)" — kalau X% > 0, ada audio
6. Ngobrol 30 detik (pastikan lawan bicara juga bersuara)
7. Tekan **"Stop"**
8. Tekan **"Putar Hasil"** → dengarkan

## Cara membaca hasil

| Hasil | Arti |
|---|---|
| Suara lawan **KERAS & jernih** | ✅ SOLUSI TERBUKTI — lanjut bangun app lengkap |
| File besar, tapi **senyap** | WA memblokir capture di device ini → cari alternatif |
| File **0–5 KB** | Tidak ada audio tertangkap (WA blokir / salah usage) |
| Suara lawan **tetap kecil** | Kemungkinan masih lewat MIC → laporkan, kita debug |

## Lokasi file hasil
`/sdcard/Android/data/id.orimax.wacapturetest/files/Music/test_wa_*.m4a`

## Spesifikasi
- Format: AAC 48kHz stereo 192 kbps (.m4a)
- Capture: `USAGE_VOICE_COMMUNICATION` + `USAGE_MEDIA` + `USAGE_GAME` + `USAGE_UNKNOWN`
- minSdk 29 (Android 10), targetSdk 34

## Catatan hukum
App ini untuk merekam panggilan **milik sendiri** di device sendiri, untuk
keperluan internal Orimax. Pastikan ada kebijakan/persetujuan perusahaan.
