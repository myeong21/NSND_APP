package com.jsm.nsnd.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Owned only by the foreground service. Activity destruction never releases it. */
class AlarmOutput(private val context: Context) {
    private var player: MediaPlayer? = null
    private var vibratingStage = 0
    private val vibrator: Vibrator get() = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)

    fun start(stage: Int) {
        if (player == null) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val candidate = MediaPlayer()
            try {
                candidate.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                candidate.setDataSource(context,uri)
                candidate.isLooping = true
                candidate.prepare()
                candidate.start()
                player = candidate
            } catch (error: Exception) { candidate.release(); throw error }
        }
        if (stage != vibratingStage) {
            vibrator.cancel()
            val pattern = when(stage) {
                1 -> longArrayOf(0,250,500)
                2 -> longArrayOf(0,400,250,600,350)
                else -> longArrayOf(0,700,200,900,200,1100)
            }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern,0))
            vibratingStage = stage
        }
    }

    fun stop() {
        player?.runCatching { if (isPlaying) stop(); release() }
        player = null
        vibrator.cancel()
        vibratingStage = 0
    }
}
