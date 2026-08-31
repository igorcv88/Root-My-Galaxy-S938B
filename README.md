<p align="center">
  <img src=".github/assets/root-my-galaxy-banner.svg" alt="Root My Galaxy" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/releases/latest">
    <img alt="Latest release" src="https://img.shields.io/github/v/release/igorcv88/Root-My-Galaxy-S938B?style=for-the-badge" />
  </a>
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/releases">
    <img alt="Downloads" src="https://img.shields.io/github/downloads/igorcv88/Root-My-Galaxy-S938B/total?style=for-the-badge" />
  </a>
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/stargazers">
    <img alt="Stars" src="https://img.shields.io/github/stars/igorcv88/Root-My-Galaxy-S938B?style=for-the-badge" />
  </a>
  <img alt="Galaxy S25 Ultra" src="https://img.shields.io/badge/Galaxy%20S25%20Ultra-SM--S938B-78966F?style=for-the-badge" />
  <img alt="Android 16" src="https://img.shields.io/badge/Android-16-3DDC84?style=for-the-badge&logo=android&logoColor=white" />
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/actions/workflows/release.yml">
    <img alt="Release build" src="https://img.shields.io/github/actions/workflow/status/igorcv88/Root-My-Galaxy-S938B/release.yml?style=for-the-badge&label=release" />
  </a>
  <a href="LICENSE">
    <img alt="License" src="https://img.shields.io/github/license/igorcv88/Root-My-Galaxy-S938B?style=for-the-badge" />
  </a>
</p>

<p align="center">
  <strong>Temporary KernelSU root for supported Samsung Galaxy firmware without unlocking the bootloader or flashing a modified boot image.</strong>
</p>

Root My Galaxy checks the phone and firmware, downloads the matching verified payload, runs the kernel exploit, and late-loads KernelSU. Root remains active for the current kernel boot. A full reboot returns the phone to the stock kernel state; the optional **Auto Root** feature can restore KernelSU after Android starts again.

> [!WARNING]
> This software uses a kernel exploit. The exploit is timing-sensitive, may take several minutes, can make the phone temporarily unresponsive or warm, and can cause a kernel panic/reboot on a failed attempt. Use it only on a device you own or are explicitly authorized to test.

## Supported device

The maintained automatic profile currently supports this exact configuration:

| | Supported configuration |
| --- | --- |
| Device | Samsung Galaxy S25 Ultra `SM-S938B` (`pa3q`) |
| Firmware | `S938BXXSBCZG3` |
| Android | Android 16 / API 36 |
| Kernel | `6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k` |
| ABI | `arm64-v8a` |
| Page size | 4K |

Firmware updates can change the kernel and invalidate the exploit profile. If the exact supported identity no longer matches, normal installation and Auto Root stop instead of silently using the old profile.

## Installation

1. Download the latest signed APK from [GitHub Releases](https://github.com/igorcv88/Root-My-Galaxy-S938B/releases/latest).
2. Install and open **Root My Galaxy**.
3. Check the device and firmware information shown on the Home screen.
4. Optional: open **Settings → Use Shizuku** if Shizuku is already installed and running.
5. Tap the installation card and confirm **Install KernelSU**.
6. Keep the installer screen open while it runs. The exploit stage can finish quickly or take several minutes.
7. When **KernelSU active** appears, install or open KernelSU Manager from the app.
8. Optional: enable **Automatic root after reboot** on the successful-install screen.

Official APKs are distributed through this repository. The release also includes a `.sha256` file for checksum verification.

## What happens during installation

The app shows the complete process in four stages:

1. **Support check** — reads the device, firmware and kernel information and selects the matching support profile.
2. **Download** — downloads the exploit and KernelSU payload for that profile and verifies their expected size and SHA-256.
3. **Kernel exploit** — runs the exploit until temporary bootstrap root is acquired or the attempt fails/times out.
4. **Load KernelSU** — stages the KernelSU payload, late-loads the kernel module and verifies the KernelSU control channel before reporting success.

The app does not unlock the bootloader, flash `boot`, replace the Samsung kernel, or make the kernel modification persistent across a full reboot.

## Auto Root

**Automatic root after reboot** becomes available after a successful manual KernelSU installation.

When enabled, a full reboot follows this flow:

```text
Android boots normally
        ↓
Root My Galaxy waits for boot completion
        ↓
short stabilization period
        ↓
firmware + cached payload verification
        ↓
exploit run
        ↓
KernelSU late-load
        ↓
root restored
```

Auto Root uses the payloads cached by the verified manual installation and does not download files during the boot-time run. It attempts restoration at most once for each full kernel boot. If that automatic attempt fails, it does not keep retrying in the background; open the app and run the normal installation again.

A foreground notification shows the current Auto Root stage and includes an action to disable Auto Root. Notification permission is required when the feature is enabled.

If Shizuku is already running and Root My Galaxy already has permission when Auto Root starts, it may be used. Auto Root does not stop at boot to request or start Shizuku.

## Shizuku

Shizuku is optional. Root My Galaxy can run without it.

When **Use Shizuku** is enabled, the app uses the Shizuku shell context for payload execution and staging. This can improve reliability on the supported device. Shizuku must already be running, and Root My Galaxy must have permission to use it.

If Shizuku is unavailable, either start it from the Shizuku app and grant permission or disable **Use Shizuku** to use the standalone path.

## Advanced mode

**Advanced mode** changes installation from automatic profile selection to a manual payload picker. The picker shows device and kernel compatibility and warns before continuing with a mismatch.

Use manual selection only when you deliberately want to test a specific support profile. A payload intended for another device, kernel or firmware can crash the device.

## History and logs

The **History** tab keeps each installation run with its result, time, selected payload, whether Shizuku was used, and the captured log.

Open a run to inspect the full log. Use **Export log** to save it as a text file when troubleshooting or reporting a reproducible problem. Old entries can be selected and deleted from the History screen.

## App updates

Root My Galaxy checks this repository for newer releases. When an update is available, it can download the new signed APK and open the Android installer directly from the app. You can also check manually from **Settings → Check for updates**.

## Common problems

**Support check failed:** the phone, firmware or kernel does not match a maintained automatic profile. Do not force another profile unless you know it is compatible.

**The exploit is taking a long time:** the acquisition step is timing-sensitive. Keep the installer open. The app monitors progress and stops attempts that stall or exceed its overall time limit.

**The phone rebooted during the exploit:** a kernel panic can occur on an unsuccessful run. After Android boots normally, open Root My Galaxy and try again. The stock kernel is used after a full reboot.

**Auto Root failed after reboot:** Auto Root performs one automatic run for that boot. Open the app, check the run result/log, and perform a manual installation if needed.

**Shizuku is not running:** start Shizuku and grant Root My Galaxy permission, or turn off the Shizuku option.

## KernelSU and modules

Root My Galaxy installs **KernelSU** through late-load. KernelSU Manager is used to grant root access to apps and manage KernelSU modules after root is active.

Root My Galaxy does not install Magisk or APatch and does not manage Zygisk, LSPosed or other KernelSU modules itself.

## Payload repository

The firmware profiles and executable payloads used by the app are maintained separately in [Root-My-Galaxy-Payloads-S938B](https://github.com/igorcv88/Root-My-Galaxy-Payloads-S938B). Normal users do not need to download files from that repository manually.

## License and credits

This project is distributed under the license in [LICENSE](LICENSE). It builds on the Root My Galaxy exploit work and uses [KernelSU](https://github.com/tiann/KernelSU) for kernel-based root management.
