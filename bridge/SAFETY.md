# Safety constraints

The bridge uses only KernelSU's existing `ksud soft-reboot` path after the exploit and late-load stages have completed. It does not request a full reboot, rerun the exploit, invoke `trace <pid> --restart` directly, execute ReZygisk's full `post-fs-data.sh` manually, or enable dependent Zygisk modules.

Before the soft reboot, it stops only processes positively identified as ReZygisk monitor or daemon processes. The official ReZygisk post-fs-data lifecycle then creates a single clean runtime. Verification failure is non-destructive: the bridge does not disable ReZygisk, remove `state.json`, or request another restart.

The boot-scoped guard is backed up before modification and remains active until verification finishes, preventing a repeated post-fs-data pass from deleting the first ReZygisk runtime. It is tied to the current kernel boot ID, and the verifier and uninstaller restore the original script. The bridge captures logs but does not invoke injector commands itself.
