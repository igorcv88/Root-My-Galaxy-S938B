# NeoZygisk PostBoot on SM-S938B / S938BXXSBCZG3

## Supported Root-My-Galaxy flow

Root-My-Galaxy must perform only the simple KernelSU exploit and late-load operation. It must not attempt to activate ReZygisk, restart zygote, or initiate an automatic soft reboot.

The validated sequence is:

1. start from a clean full boot;
2. run the simple Root-My-Galaxy exploit;
3. confirm KernelSU is active;
4. install or enable NeoZygisk PostBoot and dependent Zygisk modules in KernelSU Manager;
5. use the **Soft Reboot** action inside KernelSU Manager once;
6. verify NeoZygisk after Android returns.

A full reboot removes the temporary root session and requires the simple exploit again.

## Withdrawn paths

Do not use:

- the old Root-My-Galaxy ReZygisk bridge;
- `ctl.restart zygote` or `setprop ctl.restart zygote`;
- ReZygisk Start, Stop, or Exit controls;
- an app-initiated `ksud soft-reboot` bridge.

On the target firmware, targeted zygote activation reproduced Samsung's `Device Services Uninstalled` state and required a full reboot for recovery.

## Validated result

After the simple exploit and KernelSU Manager Soft Reboot, the target device reported:

```text
monitor: tracing
zygote64: injected
daemon64: running
Root: KernelSU
Modules:
  zygisk-assistant
  zygisk_lsposed
```

The monitor was attached to init with a matching `TracerPid`, `zygiskd64` was running, `/dev/.neozygisk/cp64.sock` existed, and `/dev/.neozygisk/lib64/libzygisk.so` was mapped in the live zygote.

## Future app integration

A future automated verifier may read the NeoZygisk status after the user returns from KernelSU Manager. It must remain read-only and must never initiate a restart or recovery action.
