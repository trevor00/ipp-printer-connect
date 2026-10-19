package kr.ac.hit.networkprinterstudio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PrintRequestGateTest {
    @Test fun repeatedClickCannotStartAnotherSubmission() {
        val gate = PrintRequestGate()
        val first = gate.tryStart()!!
        assertNull(gate.tryStart())
        assertTrue(gate.isActive(first))
        assertTrue(gate.finish(first))
        assertNotNull(gate.tryStart())
    }

    @Test fun oldCallbackCannotUnlockANewerRequest() {
        val gate = PrintRequestGate()
        val old = gate.tryStart()!!
        gate.clear()
        val current = gate.tryStart()!!
        assertFalse(gate.finish(old))
        assertFalse(gate.isActive(old))
        assertTrue(gate.isActive(current))
        assertNull(gate.tryStart())
    }

    @Test fun cancelOrFailureReleasesTheReservationOnlyOnce() {
        val gate = PrintRequestGate()
        val id = gate.tryStart()!!
        assertTrue(gate.finish(id))
        assertFalse(gate.finish(id))
        assertFalse(gate.isBusy())
        assertNotNull(gate.tryStart())
    }

    @Test fun concurrentRequestsHaveOnlyOneWinner() {
        val gate = PrintRequestGate()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val results = (1..8).map { pool.submit<Long?> { start.await(); gate.tryStart() } }
            start.countDown()
            assertEquals(1, results.map { it.get(5, TimeUnit.SECONDS) }.count { it != null })
        } finally {
            pool.shutdownNow()
        }
    }
}
