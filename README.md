# SafeSpace Guard 🛡️

> **The ultimate tamper-proof parental control app protecting children online.**

---

## 🚀 Elevator Pitch
SafeSpace Guard is a powerful parental control app designed to protect children from inappropriate online content and accidental exposure to adult websites. Unlike traditional apps that can be easily uninstalled, disabled in system settings, or bypassed using alternative VPNs, SafeSpace Guard features an unbreakable defense system: a parent PIN locks down app uninstallation, system settings, and recents menus. Operating entirely on-device, it enforces strict SafeSearch across Google, Bing, and YouTube at the network layer while keeping all family data 100% private.

---

## ✨ Key Features
- **Tamper-Proof Protection:** PIN-protected Accessibility Guard blocks unauthorized access to system settings, accessibility services, app info, and uninstallation.
- **Universal SafeSearch & Restricted Mode:** Automatically intercepts DNS requests for Google, Bing, and YouTube, forcing strict SafeSearch and restricted mode at the network layer across all browsers (Chrome, Phoenix, Firefox, etc.).
- **Zero-Cloud Local VPN:** All DNS filtering and content checks run 100% locally on the device using Android's `VpnService`. No browsing data ever leaves your phone.
- **Anti-Bypass Security:** Blocks public DNS resolvers and DoH (DNS-over-HTTPS) to prevent users from bypassing restrictions.
- **Lightweight & Battery Efficient:** Minimalist design with zero background tracking or cloud latency.

---

## 🛠️ Tech Stack
- **Language:** Kotlin
- **Platform:** Android (API 24+)
- **Core APIs:** `AccessibilityService`, `VpnService`, `SharedPreferences`, PBKDF2 Hashing.

---

## 📥 Installation Guide (For Judges & Testers)

Because SafeSpace Guard implements local VPN and Accessibility protections, standard sideloading may trigger security prompts. Installing via **ADB** is the cleanest and fastest way to test the app:

### 1. Build the APK
```bash
./gradlew assembleDebug
```

### 2. Install via ADB
Connect your Android device via USB (with USB Debugging enabled) and run:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Setup & Test
1. Open **SafeSpace Guard** on your device.
2. Choose and set a **Parent PIN**.
3. **To enable Accessibility protection:** Press and **hold the protection toggle button for 5 seconds**, enter your Parent PIN when prompted, and within 30 seconds find **SafeSpace Guard** in Accessibility Services and turn it **ON**.
4. Go back to the app and turn protection **ON** (accept the VPN permission prompt).
5. **To set up Always-on VPN:** Tap **Set up Always-on VPN**, enter your Parent PIN, and within 30 seconds tap the ⚙️ gear next to **SafeSpace Guard** and turn on both **Always-on VPN** and **Block connections without VPN**.

> **Note:** The VPN settings, Accessibility settings, App info and uninstall screens for SafeSpace Guard are always blocked while the Accessibility Guard is on, even when protection is OFF. Entering the Parent PIN pauses the blocks for 30 seconds, then they turn back on automatically.
