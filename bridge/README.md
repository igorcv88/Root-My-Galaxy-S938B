# Root My Galaxy ReZygisk Bridge

KernelSU late-load bridge used by Root My Galaxy. It remains inert unless the app arms it for the current Android boot through `/data/local/tmp/rmg-rezygisk-arm`.

## Requirements

- KernelSU late-load and `soft-reboot` support
- ReZygisk installed and enabled as the only Zygisk provider
- Root My Galaxy advanced ReZygisk toggle enabled for the current run

## Operation

The app creates a boot-specific arm token before invoking `ksud late-load`. The bridge records every existing ReZygisk monitor and daemon, stops only processes whose command line and process name identify them as ReZygisk, and confirms that no stale runtime remains. It does not disable the module or delete `state.json` as a rollback action.

Before requesting the official `ksud soft-reboot` lifecycle, the bridge applies a one-shot instrumentation patch to ReZygisk's existing `post-fs-data.sh`. The patch changes only the monitor launch redirection, captures the monitor and inherited tracer output in `/data/local/tmp/rmg-rezygisk-monitor.log`, and restores the original script immediately after the monitor is launched. KernelSU remains responsible for executing ReZygisk's normal post-fs-data lifecycle and starting the replacement Android runtime.

The verifier records monitor and daemon PIDs before cleanup and after the soft reboot, detects surviving or newly created processes, rejects duplicate stacks, and checks the parent relationship between the single daemon and monitor. It never calls `zygisk-ptrace64 info` during preflight. Health requires a replacement zygote and `system_server`, one monitor, one 64-bit daemon, both ReZygisk sockets, a KernelSU root entry in `state.json`, `zygote.64 = 1`, and `ReZygisk 64-bit: ✅` in `module.prop`.

A live daemon with an uninjected zygote, a persistent duplicate stack, or a completed Android restart without verified injection is reported as `NOT_WORKING`, never `SUCCESS`. Incomplete boot evidence is reported as `INCONCLUSIVE`. Failure handling preserves the installed ReZygisk module, its enabled state, its runtime files, and the captured diagnostic log.
