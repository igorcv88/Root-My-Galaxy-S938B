# Root My Galaxy — S938B

Root My Galaxy is a firmware-profiled installer for temporary KernelSU root on supported Samsung builds. This fork is maintained and hardware-validated primarily for the Galaxy S25 Ultra `SM-S938B`.

Validated target:

```text
Model:       SM-S938B
Device:      pa3q
Build:       BP4A.251205.006.S938BXXSBCZG3
Fingerprint: samsung/pa3qxxx/pa3q:16/BP4A.251205.006/S938BXXSBCZG3_OXMBCZG3:user/release-keys
Kernel:      6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k
Android:     16 / SDK 36
ABI:         arm64-v8a
Page size:   4096
```

[Download the latest signed APK](https://github.com/igorcv88/Root-My-Galaxy-S938B/releases/latest)

The application and payload feed are intentionally separated:

- application: this repository;
- controlled payload feed: [Root-My-Galaxy-Payloads-S938B](https://github.com/igorcv88/Root-My-Galaxy-Payloads-S938B).

## Scope

The application performs only the root bootstrap flow:

```text
supported device
    ↓
exact firmware/profile validation
    ↓
kernel exploit
    ↓
bootstrap privilege
    ↓
KernelSU late-load
    ↓
KernelSU control channel verification
    ↓
verified temporary root
```

Zygisk providers, LSPosed, Shamiko and other KernelSU modules are outside the scope of Root My Galaxy and are managed separately through KernelSU Manager.

The root is temporary. A full reboot or shutdown returns the device to the stock Samsung kernel state and removes the active KernelSU late-load session.

## Auto Root post-boot

After a successful manual installation, the installer can explicitly enable **Automatic root after reboot**. This does not make the kernel modification persistent: Android still boots the stock Samsung kernel first and Root My Galaxy restores the KernelSU late-load session afterward.

The automatic path is deliberately fail-closed:

- it listens only for `BOOT_COMPLETED`; Direct Boot is not used in this phase;
- it waits for `sys.boot_completed=1` and an additional 45-second stabilization interval;
- it starts at most one automatic run for each `/proc/sys/kernel/random/boot_id`, with no automatic retry after a failed run; low-level retries performed internally by the existing exploit helper remain part of that single run;
- it requires a prior verified manual installation receipt;
- it requires the exact embedded v3 CZG3 identity to match the current device snapshot;
- it uses only the locally cached exploit and KernelSU payload and verifies both byte size and SHA-256 before execution;
- it performs no network request during the automatic run;
- it uses Shizuku only when its binder is already running and permission is already granted; otherwise it falls back to standalone execution without requesting Shizuku at boot;
- it stops after KernelSU late-load/control verification and does not manage Zygisk or other KernelSU modules.

A foreground notification reports the current stage and can disable Auto Root directly. Notification permission is therefore requested when the user opts in.

## Safety model

Automatic profile selection is fail-closed. A target must match the maintained manifest exactly, including device/build/kernel identity and platform properties. Advanced/manual selection is intended only for deliberate interactive testing and must not be treated as equivalent to automatic matching.

Use only on devices you own or are explicitly authorized to test.

## Shizuku execution

When Shizuku is enabled, authorized and running, Root My Galaxy can execute the helper and staging flow through the Shizuku shell context. The transport choice is frozen for the duration of each install run so execution cannot switch between Shizuku and standalone mode midway through the exploit or KernelSU staging steps.

Standalone execution remains available as the fallback path.

## Payload integrity

The APK resolves the current commit of `igorcv88/Root-My-Galaxy-Payloads-S938B`, downloads `targets-v3.json` from that immutable commit and rewrites payload URLs to the same pinned commit before use. Each downloaded artifact must match both the declared byte size and SHA-256 before the temporary file is promoted for execution. The maintained feed is validated separately from upstream so the installed APK does not silently follow mutable third-party payload URLs.

### KernelSU v3.3.0 compatibility

The exact CZG3 profile uses the controlled feed's versioned
`ksud-s25u-kdp-v3.3.0` late-load artifact. The payload forward-port preserves
the existing `/data/local/tmp/.ksud-stage` rename and `ksud late-load` contract,
so the app continues to stage the matched daemon and delegates finalization to
the canonical `ksud` implementation rather than duplicating its installation
logic.

The root helper's `--late-load` operation remains the authoritative control
check: after loading, it opens the KernelSU driver and uses the retained
`KSU_IOCTL_GET_INFO` UAPI, failing the operation if the control request does not
succeed. `NativeProbe.isKernelSuActive()` remains an additional module-presence
check used by manual and Auto Root status handling; it is not a replacement for
the helper's control-channel verification. KernelSU v3.3.0 retains that ioctl
generation and the forward-port preserves the staging contract, so no weaker or
version-specific app-side fallback is necessary. Hardware validation on the
exact SM-S938B/CZG3 target remains required before merge.

The Auto Root path does not perform this network resolution during boot. Its exact target metadata is embedded in the APK at build time, and CI/release gates require that embedded profile to remain equivalent at the JSON object level to the controlled CZG3 target before an APK can be accepted. Cached files are then re-verified locally against the embedded size and SHA-256 values before every automatic run.

## Signed releases

Stable APKs are built, aligned, signed and published by the repository release workflow. Release builds use monotonically increasing version codes so a later build can update an earlier stable installation when the signing certificate is unchanged. The release workflow independently validates the same controlled v3 manifest, exact CZG3 identity, artifact sizes and SHA-256 values before signing.

## Local development build

Requirements:

- Android Studio JBR 21;
- Android SDK 37;
- Android NDK 28 or newer;
- CMake 3.22.1.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Local debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```
