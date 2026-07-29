# Root My Galaxy ReZygisk Bridge

KernelSU bridge used by Root My Galaxy. It remains inert unless the app creates a boot-specific arm token at `/data/local/tmp/rmg-rezygisk-arm`.

## Requirements

- KernelSU late-load support
- ReZygisk installed and enabled as the only Zygisk provider
- Root My Galaxy advanced ReZygisk toggle enabled for the current run

## Operation

The app creates the token before invoking `ksud late-load`. Because bootstrap uid 0 can still be confined to the app SELinux domain, the module normalizes the token ownership, mode, and SELinux label from KernelSU's domain before reading it.

Both `late-load.sh` and `service.sh` use the same idempotent launcher. A lock directory and verified PID prevent duplicate workers. The service stage retries automatically if the late-load stage did not launch or could not keep the worker alive.

The bridge then starts the ReZygisk ptrace monitor, performs a supervised zygote restart, verifies the daemon, zygote64 injection, and replacement `system_server`, and disables ReZygisk on rollback.

Diagnostic files are written under `/data/local/tmp/rmg-rezygisk-*`. The app-owned status file records whether the launcher came from `late-load` or `service`.
