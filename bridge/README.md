# Root My Galaxy ReZygisk Bridge

KernelSU late-load bridge used by Root My Galaxy. It remains inert unless the app arms it for the current Android boot through `/data/local/tmp/rmg-rezygisk-arm`.

The bridge requires the official ReZygisk module to already be installed and enabled. It refuses conflicting Zygisk providers, starts the ReZygisk monitor from KernelSU's late-load context, requests a controlled zygote restart, verifies injection, and disables ReZygisk on rollback.
