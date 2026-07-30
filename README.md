# Root My Galaxy — S938B

Root My Galaxy is a firmware-profiled installer for temporary KernelSU root on
supported Samsung builds. This fork is maintained for the Galaxy S25 Ultra
`SM-S938B` running:

```text
Build:  BP4A.251205.006.S938BXXSBCZG3
Kernel: 6.6.98-android15-8-pd6ff1cd-abogkiS938BXXSBCZG3-4k
```

[Download the latest signed APK](https://github.com/igorcv88/Root-My-Galaxy-S938B/releases/latest)

The application source, firmware feed and Zygisk provider are intentionally
separated:

- application: this repository;
- payload feed: [Root-My-Galaxy-Payloads-S938B](https://github.com/igorcv88/Root-My-Galaxy-Payloads-S938B);
- tested post-boot Zygisk provider: [NeoZygisk-PostBoot](https://github.com/igorcv88/NeoZygisk-PostBoot).

## Safety model

The root is temporary. A full reboot or shutdown removes the active KernelSU
session, although installed modules remain in `/data/adb/modules` for the next
successful exploit run.

The app automatically matches the complete kernel release, build display ID,
SDK, ABI and page size. Advanced mode permits manual profile selection, but a
similar model or kernel family is not equivalent to an exact firmware profile.

Use only on devices you own or are explicitly authorized to test.

## Root procedure

1. Install the latest signed APK from Releases.
2. Run the **simple** exploit flow and wait until KernelSU is reported active.
3. Open KernelSU Manager and confirm root access.
4. For KernelSU-only use, stop here.
5. For Zygisk, install exactly one provider and the modules that depend on it.
6. On a first installation in a clean kernel session, use **Soft Reboot** from
   KernelSU Manager once.
7. Wait for Android to return and verify the provider and dependent modules.

Do not use the withdrawn automatic ReZygisk bridge and do not issue a targeted
`ctl.restart zygote` command on the validated Samsung firmware. Hardware testing
showed that path can enter Samsung's **Device Services Uninstalled** failure
state and require a full reboot.

## Provider updates require a full reboot

Do not install a new Zygisk provider build over a live monitor and then press
KernelSU Soft Reboot in the same kernel boot. A hardware test reproduced a
`stopped(zygote crashed)` state when an old monitor/runtime survived while newer
provider files were activated.

After updating Zygisk Next or NeoZygisk PostBoot:

1. install the update but do not Soft Reboot;
2. perform a full device reboot;
3. run the simple Root My Galaxy exploit again;
4. use KernelSU Manager **Soft Reboot once**;
5. verify the provider.

After any `zygote crashed`, deleted-monitor, generation-mismatch, or
`FULL_REBOOT_REQUIRED` report, do not attempt another Soft Reboot in that kernel
session.

## Zygisk choices

Use only one Zygisk provider at a time.

### Zygisk Next

Zygisk Next can be used as the conventional provider. Install its KernelSU
module, configure it normally, install dependent modules such as LSPosed or
Zygisk Assistant, and then perform one KernelSU Manager **Soft Reboot** from a
clean post-exploit session. Provider updates follow the full-reboot lifecycle
above.

Zygisk Next is a separate project. Compatibility and closed-source release
changes are controlled by its maintainers.

### NeoZygisk PostBoot

The maintained [NeoZygisk PostBoot fork](https://github.com/igorcv88/NeoZygisk-PostBoot)
was hardware validated on `S938BXXSBCZG3`. It stages its runtime under
`/dev/.neozygisk` to avoid Samsung DEFEX blocking a root-credential zygote from
opening the persistent library under `/data/adb`.

Validated first-install sequence:

1. complete the simple Root My Galaxy exploit;
2. install or enable NeoZygisk PostBoot;
3. install or enable Zygisk Assistant and/or LSPosed modules;
4. use KernelSU Manager **Soft Reboot** once;
5. use the NeoZygisk module Action button for live verification.

A successful verification reports an injected `zygote64`, running `zygiskd64`,
a single same-generation monitor attached to init, and the live mapping of
`/dev/.neozygisk/lib64/libzygisk.so`.

Do not install NeoZygisk PostBoot beside Zygisk Next, ReZygisk, or another
provider using the same Zygisk lifecycle.

## Payload integrity

The APK resolves the current commit of
`igorcv88/Root-My-Galaxy-Payloads-S938B`, downloads
`support/targets-v2.json` from that immutable commit and rewrites every artifact
URL to the same commit. The release workflow verifies:

- the exact `pa3q-S938BXXSBCZG3` target metadata;
- that every URL belongs to the maintained payload repository;
- that every referenced payload exists and matches its declared byte size;
- that the application contains no upstream mutable payload endpoint.

## Signed APK updates

Stable APKs are signed by GitHub Actions and published directly as assets under
Releases, without an Actions artifact wrapper. `versionCode` increases on every
release run, so later APKs can update earlier stable builds without uninstalling
them, provided the signing certificate is unchanged.

The first migration from a debug-signed or differently signed APK may still
require one uninstall. Android only accepts an in-place update when the installed
and incoming APKs share the same signing certificate.

Required repository secrets:

```text
KEYSTORE_BASE64
KEYSTORE_PASSWORD
KEY_ALIAS
KEY_PASSWORD
```

The same signing key may technically sign multiple package names. Reusing the
BatteryRemapper key is valid, but it couples the security of both applications:
a key compromise affects updates for both packages.

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
