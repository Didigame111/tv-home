package br.arthur.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshnessTest {
    private val day = Freshness.DAY_MS
    private val t0 = 1_800_000_000_000L

    @Test
    fun `shown counts once per day and the penalty stops at a week`() {
        val seen = HashMap<String, Seen>()
        repeat(3) { Freshness.shown(seen, "a", t0, today = 100) }
        assertEquals(1, seen.getValue("a").shownDays)
        for (d in 101L..120L) Freshness.shown(seen, "a", t0 + (d - 100) * day, today = d)
        assertEquals(21, seen.getValue("a").shownDays)
        assertEquals(Freshness.MAX_DAYS * Freshness.PLACES_PER_DAY, Freshness.penalty(seen["a"]), 0.0)
        assertEquals(0.0, Freshness.penalty(null), 0.0)
    }

    @Test
    fun `a title still in the lists is never forgotten, however long ago it first came`() {
        val seen = HashMap<String, Seen>()
        for (d in 0..40) {
            val now = t0 + d * day
            Freshness.listed(seen, "staple", now)
            Freshness.shown(seen, "staple", now, today = d.toLong())
            Freshness.forget(seen, now)
        }
        assertEquals(Freshness.MAX_DAYS * Freshness.PLACES_PER_DAY, Freshness.penalty(seen["staple"]), 0.0)
    }

    @Test
    fun `a title gone from the lists for three weeks is forgotten`() {
        val seen = HashMap<String, Seen>()
        Freshness.listed(seen, "gone", t0)
        Freshness.forget(seen, t0 + 20 * day)
        assertTrue("gone" in seen)
        Freshness.forget(seen, t0 + 22 * day)
        assertFalse("gone" in seen)
    }

    @Test
    fun `popularity still beats a week-old title after the lift`() {
        // A newcomer at place 20 against week-old titles at places 1 and 10.
        val old = Seen(lastListed = t0, shownDays = 9, lastShownDay = 9)
        val score = mapOf(
            "old#1" to 1 + Freshness.penalty(old),
            "old#10" to 10 + Freshness.penalty(old),
            "new#20" to 20 + Freshness.penalty(null),
        )
        assertEquals(listOf("old#1", "new#20", "old#10"), score.entries.sortedBy { it.value }.map { it.key })
    }

    @Test
    fun `saves and reads back, and reads the old first-seen format`() {
        val seen = mutableMapOf("a" to Seen(t0, 3, 42))
        assertEquals(seen, Freshness.read(Freshness.write(seen), t0))

        val legacy = Freshness.read("""{"old":${t0 - 3 * day},"older":${t0 - 30 * day}}""", t0)
        assertEquals(Seen(t0, 3, -1), legacy["old"])
        assertEquals(Seen(t0, Freshness.MAX_DAYS, -1), legacy["older"])
    }
}
