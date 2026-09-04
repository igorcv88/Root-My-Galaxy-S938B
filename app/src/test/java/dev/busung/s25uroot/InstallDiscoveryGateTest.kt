package dev.busung.s25uroot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallDiscoveryGateTest {
    @Test
    fun newerDiscoveryInvalidatesOlderPublisher() {
        val gate = InstallDiscoveryGate()
        val first = gate.begin()
        val second = gate.begin()
        var firstPublished = false
        var secondPublished = false

        assertFalse(gate.publishIfCurrent(first) { firstPublished = true })
        assertTrue(gate.publishIfCurrent(second) { secondPublished = true })
        assertFalse(firstPublished)
        assertTrue(secondPublished)
    }

    @Test
    fun installInvalidationPreventsLateDiscoveryPublication() {
        val gate = InstallDiscoveryGate()
        val discovery = gate.begin()
        var published = false

        gate.invalidate()

        assertFalse(gate.publishIfCurrent(discovery) { published = true })
        assertFalse(published)
    }
}
