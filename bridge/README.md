# Root My Galaxy ReZygisk Bridge

KernelSU late-load bridge used by Root My Galaxy. It remains inert unless the app arms it for the current Android boot through `/data/local/tmp/rmg-rezygisk-arm`.

## Requirements

- KernelSU late-load and `soft-reboot` support
- ReZygisk installed and enabled as the only Zygisk provider
- Root My Galaxy advanced ReZygisk toggle enabled for the current run

## Operation

The app creates a boot-specific arm token before invoking `ksud late-load`. The bridge validates the ReZygisk installation, removes only a demonstrably stale ReZygisk runtime, and requests the official `ksud soft-reboot` lifecycle. KernelSU stops Android, executes module `post-fs-data.sh` scripts, starts Android again, and runs service scripts.

The post-service verifier uses shell-only PID parsing and verifies the replacement zygote and `system_server`, the ReZygisk monitor socket, the live `zygiskd` process, the KernelSU backend, and Android boot completion. `module.prop` status is recorded for diagnostics but is not the sole source of truth.

A verification timeout is non-destructive: the bridge records an inconclusive result and preserves the ReZygisk runtime for inspection. It does not disable ReZygisk, terminate the monitor, or delete `state.json` merely because the verifier could not reach a conclusion.
