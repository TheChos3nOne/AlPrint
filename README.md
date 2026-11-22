# AlPrint

**Universal offline-first desktop & mobile printing and scanning studio for Windows and Android — zero sign-in, zero cloud telemetry, zero OEM lock-in.**  
Built by **Almoukhtar Amiouni**:
- **Windows Desktop (`src/` & `src-tauri/`):** **Tauri v2**, **Rust**, **React**, **TypeScript**, and **Tailwind CSS**
- **Android Mobile (`mobile/`):** **Native Kotlin**, **Jetpack Compose (Material 3)**, **Google ML Kit Document Scanner & Offline OCR**, **Driverless eSCL Wi-Fi Scanning**, and **Android PrintManager (Mopria / IPP)**

---

## Features

### Android Mobile App (`mobile/`)
- **Phone Camera Document Scanner:** Integrated **Google ML Kit Document Scanner** (`GmsDocumentScanning`) with live document edge tracking, automatic perspective/trapezoid deskew, shadow cleanup, and a quick-snap camera fallback.
- **Driverless Wi-Fi Flatbed Scanning (eSCL / AirScan):** Scans directly from your network printer's glass flatbed (`http://<ip>/eSCL/ScanJobs` — pre-configured for the HP Smart Tank 580 at `192.168.0.58` plus live mDNS/NSD `_uscan._tcp.` & `_ipp._tcp.` LAN discovery).
- **Wi-Fi Document & Photo Printing:** Native Android `PrintManager` (Mopria / IPP) spooling with live `1 / 2 / 4` N-up sheet preview, custom page ranges (`1-3, 5`), photo presets (`4×6"`, `5×7"`, `8×10"`, `A4`, `Letter`), and the **2×2 visual Two-Sided Duplex grid** including **Guided Manual 2-Pass Duplex** with automatic blank back-page padding on odd sheet counts.
- **Android Share Sheet Receiver:** Share any PDF or photo directly from WhatsApp, Gmail, Chrome, or Files to **AlPrint** to print or scan immediately.
- **Non-Destructive Editor & Offline OCR:** Auto-fitting rotated document canvas, optional post-scan **8-handle adjustable edge-detection crop box**, filters (`Auto-Enhance`, `Grayscale`, `B&W Otsu`), pen/highlighter/text markup, on-device offline ML Kit Text Recognition, and Searchable PDF export to `Downloads/AlPrint`.

### Windows Desktop App (`src/` & `src-tauri/`)
- **Print Documents & Photos:** Native Windows Print Spooler integration across USB, Wi-Fi, and virtual printers (`HP`, `Canon`, `Epson`, `Brother`, `Microsoft Print to PDF`, etc.).
- **Live Offline PDF & Image Previews:** Powered by bundled `pdfjs-dist` with `1 / 2 / 4` pages-per-sheet (N-up) sheet previews, grayscale preview, custom page ranges (`1-3, 5`), fit/fill scaling, and `4×6`, `5×7`, `8×10`, `A4`, `Letter` photo borderless framing.
- **Guided Manual 2-Pass Duplex:** Built-in 2-pass double-sided workflow for single-sided printers (such as the HP Smart Tank 580), automatically appending a blank white back page on odd sheet counts so you can reload the entire stack as-is.
- **WIA 2.0 (USB / Local) + Driverless Wi-Fi eSCL / AirScan:** Flatbed and ADF support with dynamic DPI (`75`–`1200` DPI), color/grayscale/B&W modes, brightness/contrast controls, and custom network scanner probing (`http://<ip>/eSCL/ScannerCapabilities`).
- **Smart Scan Presets:**
  - **Document** — Standard single-page scan.
  - **Photo** — Defaults to `300 DPI` Color.
  - **ID Card** — Guided 2-step Front + Back scan stitched onto one clean page.
  - **Multi-Item** — Scans several receipts/photos placed on the glass at once and splits them into separate cropped pages.
  - **Book Spread** — Splits two facing open pages down the center gutter.
- **Interactive Page Editor:** Auto-fits rotated pages (`0° / 90° / 180° / 270°`) to the viewport, offers an optional post-scan **8-handle adjustable edge-detection crop box**, tone sliders, enhancement filters (`Auto-Enhance`, `Grayscale`, `B&W Crisp`), and pen/highlighter/text markup.
- **Offline OCR, Shortcuts & Diagnostics:** Uses built-in `Windows.Media.Ocr` to extract editable text, auto-suggest filenames, and embed an invisible searchable text layer into exported PDFs via `pdf-lib`, plus live Print Spooler & WIA service diagnostics and UDP SNMP ink tank telemetry.

---

## Download & Install

Grab the latest Windows and Android binaries from the **[Releases](../../releases)** page:
- **`AlPrint_0.1.0_x64-setup.exe`** — Standard Windows installer (NSIS)
- **`AlPrint_0.1.0_x64_en-US.msi`** — Windows MSI installer
- **`AlPrint-0.1.0-portable-x64.exe`** — Single-file portable Windows executable (no installation required)
- **`AlPrint-Android-0.1.0.apk`** — Android mobile app (Android 8.0+ / API 26+)

---

## Build from Source

### Windows Desktop (`Tauri v2`)
```powershell
# Install frontend dependencies
npm install

# Run in development mode
npm run tauri dev

# Run checks & unit tests
npm run typecheck
cd src-tauri; cargo test; cd ..

# Build portable executable & Windows installers
npm run tauri build
```

### Android Mobile (`mobile/`)
```powershell
cd mobile
.\gradlew.bat testDebugUnitTest assembleDebug
# Output APK: mobile/app/build/outputs/apk/debug/app-debug.apk
```

---

## License

Licensed under the [GNU General Public License v3.0 (GPLv3)](LICENSE) — © 2026 Almoukhtar Amiouni.  
Any modified or derivative versions distributed must also remain open-source under the GNU GPLv3.
