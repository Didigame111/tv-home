package br.arthur.tvhome

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the home screen remembers about a JustWatch title: when it was last in the lists, and on
 * how many days it has been on screen here (in the spotlight or the trending grids).
 */
data class Seen(val lastListed: Long, val shownDays: Int, val lastShownDay: Long)

/**
 * Keeps the spotlight and grids moving without burying what's popular. A title gives up a few
 * places for each day it has been on screen, up to a week's worth, so a newcomer gets a lift for
 * its first week and after that popularity decides again.
 */
object Freshness {
    const val PLACES_PER_DAY = 2.0
    const val MAX_DAYS = 7 // the most a title can lose is 14 places
    const val DAY_MS = 24 * 60 * 60 * 1000L
    const val FORGET_AFTER_MS = 21 * DAY_MS

    fun penalty(seen: Seen?) = (seen?.shownDays ?: 0).coerceAtMost(MAX_DAYS) * PLACES_PER_DAY

    /** The title is in JustWatch's lists right now. */
    fun listed(seen: MutableMap<String, Seen>, key: String, now: Long) {
        seen[key] = seen[key]?.copy(lastListed = now) ?: Seen(lastListed = now, shownDays = 0, lastShownDay = -1)
    }

    /** The title is on screen today: counts once per day. */
    fun shown(seen: MutableMap<String, Seen>, key: String, now: Long, today: Long) {
        val current = seen[key] ?: Seen(lastListed = now, shownDays = 0, lastShownDay = -1)
        if (current.lastShownDay == today) return
        seen[key] = current.copy(shownDays = current.shownDays + 1, lastShownDay = today)
    }

    /** Titles out of the lists for three weeks are forgotten, so a comeback counts as new. */
    fun forget(seen: MutableMap<String, Seen>, now: Long) {
        seen.values.removeAll { it.lastListed < now - FORGET_AFTER_MS }
    }

    fun read(text: String, now: Long): MutableMap<String, Seen> {
        val json = JSONObject(text)
        val out = HashMap<String, Seen>()
        for (key in json.keys()) {
            val value = json.get(key)
            out[key] = if (value is JSONArray) {
                Seen(value.getLong(0), value.getInt(1), value.getLong(2))
            } else {
                // Saved by an older version: just the time the title was first seen. Treat it
                // as listed now, and as shown on each day since.
                val days = ((now - json.getLong(key)) / DAY_MS).toInt().coerceIn(0, MAX_DAYS)
                Seen(lastListed = now, shownDays = days, lastShownDay = -1)
            }
        }
        return out
    }

    fun write(seen: Map<String, Seen>): String {
        val json = JSONObject()
        seen.forEach { (key, s) -> json.put(key, JSONArray().put(s.lastListed).put(s.shownDays).put(s.lastShownDay)) }
        return json.toString()
    }
}
