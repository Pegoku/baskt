package nl.baskt.ui.shop

import org.junit.Assert.assertEquals
import org.junit.Test

class TripStartTest {
    private val now = 100_000_000L
    private val min = 60_000L

    @Test fun nothingRecentStartsNow() = assertEquals(now, tripStartedAt(listOf(now - 300 * min), now))

    @Test fun nothingAtAllStartsNow() = assertEquals(now, tripStartedAt(emptyList(), now))

    @Test fun keepsTheRunUpToNow() = assertEquals(now - 50 * min, tripStartedAt(listOf(now - 5 * min, now - 50 * min, now - 20 * min), now))

    @Test fun stopsAtTheGap() = assertEquals(now - 30 * min, tripStartedAt(listOf(now - 30 * min, now - 400 * min, now - 410 * min), now))
}
