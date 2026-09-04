package dev.busung.s25uroot

/**
 * Serializes discovery-state publication against install startup.
 *
 * Cancelling a coroutine is cooperative, so a discovery job blocked in native or network
 * I/O may return after install() has already started. A generation token alone still leaves
 * a tiny check-then-publish race; publishIfCurrent() holds the same lock used by invalidate()
 * while publishing, so an invalidated discovery can never overwrite an active install UI.
 */
internal class InstallDiscoveryGate {
    private val lock = Any()
    private var generation = 0L

    fun begin(): Long = synchronized(lock) {
        generation += 1
        generation
    }

    fun invalidate() {
        synchronized(lock) {
            generation += 1
        }
    }

    fun publishIfCurrent(token: Long, publish: () -> Unit): Boolean = synchronized(lock) {
        if (generation != token) return@synchronized false
        publish()
        true
    }
}
