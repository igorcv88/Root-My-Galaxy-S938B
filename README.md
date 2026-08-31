<p align="center">
  <img src=".github/assets/root-my-galaxy-banner.svg" alt="Root My Galaxy" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/igorcv88/Root-My-Galaxy-S938B?label=release" /></a>
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/igorcv88/Root-My-Galaxy-S938B/total" /></a>
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/igorcv88/Root-My-Galaxy-S938B?label=stars&amp;labelColor=555&amp;color=2f81f7" /></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-16-3DDC84?logo=android&amp;logoColor=white" />
  <img alt="KernelSU" src="https://img.shields.io/badge/KernelSU-3.3.0-2f81f7" />
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/actions/workflows/release.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/igorcv88/Root-My-Galaxy-S938B/release.yml?branch=main&amp;label=build" /></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/igorcv88/Root-My-Galaxy-S938B" /></a>
</p>

<p align="center">
  <strong>Temporary KernelSU root for supported Samsung Galaxy firmware without unlocking the bootloader or flashing a modified boot image.</strong>
</p>

<p align="center">
  <a href="https://github.com/igorcv88/Root-My-Galaxy-Payloads-S938B">Payloads</a>
  ·
  <a href="https://github.com/BuSung-dev/Root-My-Galaxy">Upstream</a>
  ·
  <a href="https://github.com/igorcv88/Root-My-Galaxy-S938B/releases/latest">Latest release</a>
</p>

Root My Galaxy checks the device, downloads the matching payload, runs the kernel exploit and late-loads KernelSU. Root lasts for the current kernel boot; **Auto Root** can restore it automatically after a full reboot.

> [!WARNING]
> This software uses a kernel exploit. Root acquisition is timing-sensitive, may take from seconds to several minutes, and a failed attempt can cause a kernel panic/reboot. Use it only on a device you own or are explicitly authorized to test.

## Why this fork?

Compared with [upstream Root My Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy), this fork currently adds:

- **Auto Root** after a full reboot, using the last payload set that successfully rooted the device.
- **KernelSU 3.3.0**, the current stable KernelSU release; upstream payloads are still based on 3.2.5.
- **Strict CZG3 validation** with exact device/firmware/kernel matching and SHA-256 verification.
- **Exploit diagnostics**, currently being developed to measure each acquisition stage, classify failures and help reduce root latency and run-to-run variability.

Shizuku support, Advanced mode, installation history and much of the base UI originate from upstream and are therefore not fork-exclusive features.

## Compatibility

The app itself is not inherently limited to one Galaxy model, and upstream maintains a broader device catalog. The automatic profile currently maintained in this fork is:

| | Current profile |
| --- | --- |
| Device | Samsung Galaxy S25 Ultra `SM-S938B` (`pa3q`) |
| Firmware | `S938BXXSBCZG3` |
| Android | Android 16 / API 36 |
| Kernel | `6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k` |
| ABI | `arm64-v8a` |
| Page size | 4K |

Firmware updates can change the kernel and invalidate the exploit profile. Automatic installation stops when the maintained profile no longer matches.

## Installation

1. Download the latest signed APK from [GitHub Releases](https://github.com/igorcv88/Root-My-Galaxy-S938B/releases/latest).
2. Install and open **Root My Galaxy**.
3. Optional: enable **Use Shizuku** in Settings if Shizuku is already running.
4. Tap **Install KernelSU** and keep the installer open while the exploit runs.
5. When **KernelSU active** appears, open or install KernelSU Manager.
6. Optional: enable **Automatic root after reboot**.

The app does not unlock the bootloader, flash `boot` or replace the Samsung kernel.

## Auto Root

After a successful manual root, Root My Galaxy keeps that verified payload set locally. On the next **full reboot**, Auto Root reuses those same files and does not need internet access.

Publishing a newer payload therefore does not invalidate an already working Auto Root setup. A newer payload only replaces the Auto Root set after it has also completed a successful manual root on the device.

Auto Root runs at most once per full kernel boot. Soft/userspace reboots keep the existing root state and do not trigger another exploit run.

## Exploit diagnostics

The app includes structured diagnostics for the root acquisition path. During a run it can track the current exploit stage, attempt number, elapsed time, failure class and whether the observed state is considered safe to retry.

These diagnostics are stored with the run history and exported logs. They are currently being expanded to identify where time is being lost, distinguish clean race misses from unsafe failures, and guide work to make root acquisition faster and less variable. This optimization work is ongoing; the exploit is still timing-dependent.

## Shizuku

Shizuku is optional. When enabled, Root My Galaxy uses its shell context for payload execution and staging. Shizuku must already be running and the app must already have permission.

If Shizuku is unavailable, start it and grant permission or disable **Use Shizuku** to use the standalone path.

## Advanced mode

Advanced mode allows manual payload selection instead of automatic matching. Use it only when deliberately testing a known-compatible profile; an incompatible payload can crash the device.

## History and logs

The **History** tab records each installation attempt, result, selected payload, transport and captured logs. Diagnostic stages and timing data are included when available.

Open a run for details or use **Export log** when troubleshooting or comparing exploit behavior between runs.

## App updates

Root My Galaxy can check this repository for newer releases and open the Android installer directly from the app. You can also use **Settings → Check for updates**.

## KernelSU

This fork currently integrates **KernelSU 3.3.0** through late-load. KernelSU Manager handles root permissions and KernelSU modules after root becomes active.

Root My Galaxy does not install Magisk or APatch and does not manage Zygisk, LSPosed or other KernelSU modules itself.

## Common problems

**Support check failed:** the current device, firmware or kernel does not match an automatic profile.

**Root is taking a long time:** acquisition is timing-sensitive. The diagnostic/history view can show which stage is consuming time or repeatedly missing the race.

**The phone rebooted during the exploit:** a failed exploit attempt can trigger a kernel panic. After Android boots normally, inspect the previous run log before trying again.

**Auto Root failed:** it attempts restoration only once for that full boot. Open the app, inspect the run and perform a manual installation if needed.

## License and credits

This project is distributed under the license in [LICENSE](LICENSE). It is derived from [BuSung-dev/Root-My-Galaxy](https://github.com/BuSung-dev/Root-My-Galaxy) and uses [KernelSU](https://github.com/tiann/KernelSU) for kernel-based root management.
