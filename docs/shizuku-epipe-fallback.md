# Shizuku staging EPIPE recovery

On SM-S938B/CZG3 hardware the exploit can complete successfully and leave bootstrap root active while the Shizuku remote process stdin pipe used by `cat > /data/local/tmp/...` is broken. This surfaced as `EPIPE (Broken pipe)` while staging `ksud` after `done=1 root=1`.

`ShizukuController.writeFile()` now keeps the normal streaming path first. If that path fails but Shizuku's Binder is still alive and permission remains granted, it truncates the destination and retries without remote stdin: bounded base64 chunks are carried as short shell-command arguments and decoded/appended remotely. The final byte count is checked before success is returned. A failed fallback removes any partial destination and remains fail-closed.

This recovery is transport-only. It does not rerun the exploit, add an Auto Root retry loop, restart zygote, perform a soft reboot, or change KernelSU late-load semantics.
