package com.cadence.music.playback

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import com.cadence.music.data.prefs.Prefs

/**
 * Owns the platform Equalizer/BassBoost attached to ExoPlayer's audio session.
 * Lives in the app process (service + UI share it). Falls back to a standard
 * 5-band layout when no session exists yet (settings UI before first playback).
 */
object EqManager {

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private lateinit var prefs: Prefs

    fun init(context: Context) {
        if (!::prefs.isInitialized) prefs = Prefs(context.applicationContext)
    }

    val bandCount: Int get() = eq?.numberOfBands?.toInt() ?: 5

    fun centerFreqLabel(index: Int): String {
        val e = eq ?: return defaultLabel(index)
        return try {
            val milliHz = e.getCenterFreq(index.toShort())
            if (milliHz >= 1_000_000) "${milliHz / 1_000_000}k" else "${milliHz / 1000}"
        } catch (_: Exception) { defaultLabel(index) }
    }

    private fun defaultLabel(i: Int) = listOf("60", "230", "910", "3.6k", "14k").getOrElse(i) { "${i + 1}" }

    /** Attach effects to a live audio session and apply current settings. */
    fun attach(sessionId: Int) {
        detach()
        try {
            val e = Equalizer(0, sessionId)
            try {
                bass = BassBoost(0, sessionId)
            } catch (t: Throwable) {
                e.release() // don't leak the equalizer when bass boost fails
                throw t
            }
            eq = e
        } catch (_: Exception) {
            eq = null; bass = null
        }
        apply()
    }

    fun detach() {
        eq?.release(); eq = null
        bass?.release(); bass = null
    }

    /** Re-apply current prefs to live effects (called on pref change). */
    fun apply() {
        if (!::prefs.isInitialized) return
        val e = eq ?: return
        try {
            e.enabled = prefs.eqEnabled
            val levels = prefs.eqBands
            for (i in 0 until e.numberOfBands) {
                val mb = levels.getOrElse(i) { 0 }
                    .coerceIn(e.bandLevelRange[0].toInt(), e.bandLevelRange[1].toInt())
                e.setBandLevel(i.toShort(), mb.toShort())
            }
            bass?.setEnabled(prefs.eqEnabled && prefs.eqBassBoost > 0)
            bass?.setStrength(prefs.eqBassBoost.coerceIn(0, 1000).toShort())
        } catch (_: Exception) { /* device flakiness — fail silent */ }
    }
}
