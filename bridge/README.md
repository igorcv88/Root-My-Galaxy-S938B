# Root My Galaxy ReZygisk Bridge

KernelSU late-load bridge used by Root My Galaxy. It remains inert unless the app arms it for the current Android boot through `/data/local/tmp/rmg-rezygisk-arm`.

## Requirements

- KernelSU late-load and `soft-reboot` support
- ReZygisk installed and enabled as the only Zygisk provider
- Root My Galaxy advanced ReZygisk toggle enabled for the current run

## Operation

The app creates a boot-specific arm token before invoking `ksud late-load`. The bridge validates the ReZygisk installation and requests the official `ksud soft-reboot` lifecycle. KernelSU stops Android, executes module `post-fs-data.sh` scripts, starts Android again, and runs service scripts. ReZygisk therefore starts its monitor before the replacement zygote, while this module's `service.sh` verifies the new zygote, `system_server`, daemon, KernelSU backend, and 64-bit injection.

On verification failure, the bridge stops the monitor and disables ReZygisk without attempting an additional user-space restart. Diagnostic files are written under `/data/local/tmp/rmg-rezygisk-*`.
