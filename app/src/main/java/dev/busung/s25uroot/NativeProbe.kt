package dev.busung.s25uroot

object NativeProbe {
    init {
        System.loadLibrary("s25u_native")
    }

    external fun run(): String

    external fun isKernelSuActive(): Boolean

    /** Starts the native sampler in the dedicated :observer app process. */
    external fun observerStart(logPath: String?): Boolean

    /** Attaches the sampler to the launcher/helper PID after the exploit is spawned. */
    external fun observerAttachPid(pid: Long): Boolean

    /** Feeds a sparse marker from a remote/Shizuku output stream into the observer. */
    external fun observerMarker(line: String): Boolean

    /** Stops sampling and flushes the preallocated trace buffer after the exploit exits. */
    external fun observerStop(outputPath: String): Boolean
}
