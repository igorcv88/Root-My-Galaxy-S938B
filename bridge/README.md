# Root My Galaxy ReZygisk Bridge

KernelSU late-load bridge used by Root My Galaxy. It remains inert unless the app arms it for the current Android boot through `/data/local/tmp/rmg-rezygisk-arm`.

## Requirements

- KernelSU late-load support
- ReZygisk installed and enabled as the only Zygisk provider
- Root My Galaxy advanced ReZygisk toggle enabled for the current run

## Operation

The app creates a boot-specific arm token before invoking `ksud late-load`. KernelSU promotes pending modules, loads module SELinux rules, and runs this module's `late-load.sh`. The bridge then starts the ReZygisk ptrace monitor, requests a controlled zygote restart, verifies the daemon and zygote64 injection, and disables ReZygisk on rollback.

Diagnostic files are written under `/data/local/tmp/rmg-rezygisk-*`.
