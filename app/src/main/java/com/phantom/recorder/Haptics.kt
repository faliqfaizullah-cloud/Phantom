package com.phantom.recorder

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

object Haptics {
    private fun vib(c: Context): Vibrator? =
        c.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }

    private fun one(c: Context, ms: Long, amp: Int) {
        val v = vib(c) ?: return
        val a = if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(ms, a))
    }

    /** Light pulse for level peaks and small buttons. */
    fun tick(c: Context) = one(c, 10, 50)

    /** Standard button press. */
    fun click(c: Context) {
        val v = vib(c) ?: return
        if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        else one(c, 22, 150)
    }

    /** Recording started: rising double pulse. */
    fun start(c: Context) {
        val v = vib(c) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 50, 45), intArrayOf(0, 120, 0, 255), -1))
    }

    /** Recording stopped: single heavy thud. */
    fun stop(c: Context) = one(c, 60, 255)

    /** Bookmark added: quick double tap. */
    fun mark(c: Context) {
        val v = vib(c) ?: return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 18, 60, 18), intArrayOf(0, 200, 0, 200), -1))
    }
}
