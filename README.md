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

The Auto Root path does not perform this network resolution during boot. Its exact target metadata is embedded in the APK at build time, and CI/release gates require that embedded profile to remain equivalent at the JSON object level to the controlled CZG3 target before an APK can be accepted. Cached files are then re-verified locally against the embedded size and SHA-256 values before every automatic run.

## Exploit diagnostics and retry contract

The payload owns its bounded low-level race retry policy. The application launches one payload process for a root run and does not restart that process for a clean race miss. Verified payload and helper artifacts are reused within that run; exact firmware matching, immutable-commit resolution, byte-size checks, SHA-256 checks, and the frozen Shizuku/standalone transport remain unchanged.

Machine decisions use newline-delimited structured events, not the human log. Each event starts with `RMG_DIAG:` followed by one JSON object:

```text
RMG_DIAG:{"schema":1,"run_id":"...","stage":"attempting_race","attempt":2,"elapsed_ms":4210,"failure_class":"clean_race_miss","safety":"safe_retry","retryable":true,"timing":{"window_ns":420}}
RMG_DIAG:{"schema":1,"run_id":"...","stage":"acquiring_privilege","attempt":2,"elapsed_ms":4890,"outcome":"succeeded"}
```

The app supplies the expected ID through `RMG_RUN_ID`. Supported stages are `preparing_exploit`, `resolving_kernel_state`, `attempting_race`, `validating_primitive`, `acquiring_privilege`, `staging_kernelsu`, `late_loading_kernelsu`, and `verifying_kernelsu`. A terminal event is mandatory. Unknown/malformed events, a changed run ID, elapsed time moving backwards, and missing terminal diagnostics fail closed.

Failure classes distinguish a clean race miss, deterministic precondition failure, unsafe or ambiguous kernel state, privilege bootstrap failure, KernelSU staging failure, and KernelSU verification failure. A clean miss may be retried only inside the same bounded payload run. `do_not_retry`, `unknown`, or an unsafe terminal outcome prevents an app-level immediate retry. Auto Root still performs at most one automatic run per boot and never opens Shizuku permission UI.

## Local diagnostic history

The History screen retains at most 50 recent runs. Each record includes the run and boot IDs, wall-clock start time, `CLOCK_BOOTTIME`-based uptime, exact controlled device/build identity, app version, payload size/SHA-256, frozen transport, coarse stage transitions, attempt count, stage timing, failure/safety classification, and terminal outcome. Timing-sensitive updates are checkpointed at most once every two seconds, with terminal transitions forced to disk.

If a persisted running record is found on a later boot ID, it is classified as **previous exploit run ended during an unexpected reboot**. A boot-ID change alone is not evidence of a kernel panic. The app then checks `/sys/fs/pstore` and `/proc/fs/pstore` directly. It may use Shizuku only when the service is already running and permission is already granted; it never prompts or weakens SELinux/permissions for diagnostics. The record explicitly distinguishes a crash record found, no record found, and pstore present but inaccessible.

History shows local success rate, median/P90 acquisition time, median attempt count, and success counts in coarse uptime buckets. These statistics are observational: the app records uptime but does not reject a run based on uptime, and they must not be used to claim that uptime causes a panic without further evidence.

Open a run to copy or export its diagnostic report. When tuning the controlled CZG3 payload, compare reports by payload hash and app/profile version, then inspect attempt counts, stage timings, failure class, unexpected-reboot flag, and pstore status. Remove unrelated log content before sharing outside the development team; the report intentionally excludes unrelated personal data.

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
