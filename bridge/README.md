# Root My Galaxy ReZygisk Bridge

KernelSU late-load bridge used by Root My Galaxy. It remains inert unless the app arms it for the current Android boot through `/data/local/tmp/rmg-rezygisk-arm`.

## Requirements

- KernelSU late-load and `soft-reboot` support
- ReZygisk installed and enabled as the only Zygisk provider
- Root My Galaxy advanced ReZygisk toggle enabled for the current run

## Operation

The app creates a boot-specific arm token before invoking `ksud late-load`. The bridge records every existing ReZygisk monitor and daemon, stops only processes whose command line and process name identify them as ReZygisk, and confirms that no stale runtime remains. It does not disable the module or delete `state.json` as a rollback action.

Before requesting the official `ksud soft-reboot` lifecycle, the bridge applies a boot-scoped instrumentation patch to ReZygisk's existing `post-fs-data.sh`. The patch keeps the official script body but adds a lock and existing-monitor guard before ReZygisk removes `/data/adb/rezygisk`. A repeated post-fs-data invocation during the same kernel boot therefore exits without deleting the first runtime or launching another monitor. The original script is restored only after verification reaches a terminal state. A boot-ID marker prevents an interrupted run from suppressing ReZygisk on a later full boot.

The verifier separates `zygisk-ptrace ... monitor` processes from transient `trace <pid>` injector processes, records the PID tracing `init`, rejects real duplicate monitors, and checks the parent relationship between the single daemon and monitor. Health additionally requires that the single monitor is the current `TracerPid` of PID 1. It never calls `zygisk-ptrace64 info` during preflight. Success still requires a replacement zygote and `system_server`, both ReZygisk sockets, a KernelSU root entry in `state.json`, `zygote.64 = 1`, and `ReZygisk 64-bit: ✅` in `module.prop`.

A filtered logcat capture records `zygisk-core`, `zygisk-injector`, `zygiskd`, linker, and fatal libc messages throughout the soft reboot. The bridge also copies KernelSU's 30-second boot log when available. A live daemon with an uninjected zygote, an active monitor that no longer traces `init`, a persistent duplicate stack, or a completed Android restart without verified injection is reported as `NOT_WORKING`, never `SUCCESS`. Failure handling preserves the installed module and runtime for inspection.
