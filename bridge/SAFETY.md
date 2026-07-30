# Safety stop

Advanced ReZygisk activation is disabled on the S938B CZG3 test target. Both direct zygote termination and `ksud soft-reboot` were observed to cause or escalate into a full Samsung watchdog reboot, which unloads the temporary KernelSU root. The bridge must fail closed and must not request any user-space or full reboot.
