package com.shuangka.punch.core.feedback

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchType
import java.util.Locale

/** 打卡成功的那一下反馈：提示音 + 语音 + 震动 */
object Feedback {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = StringBuilder()

    fun success(ctx: Context, type: PunchType, time: String) {
        if (PunchConfig.soundOn()) playSound(ctx)
        if (PunchConfig.vibrateOn()) vibrate(ctx)
        if (PunchConfig.voiceOn()) {
            // 时间读成「早上九点二十二分」，避免引擎把 09:22 读成「零九 冒号 二二」
            speak(ctx, "${type.label}打卡成功，现在是${SpokenTime.of(time)}")
        }
    }

    fun playSound(ctx: Context) {
        Thread {
            var tone: ToneGenerator? = null
            val ok = try {
                tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 320)
                Thread.sleep(420L)
                true
            } catch (e: Exception) {
                false
            } finally {
                try {
                    tone?.release()
                } catch (e: Exception) {
                }
            }
            if (!ok) playSystemSound(ctx)
        }.start()
    }

    fun vibrate(ctx: Context) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                ctx.getSystemService(Vibrator::class.java)
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 130L, 80L, 130L), -1))
        } catch (e: Exception) {
            // 没震动权限就算了
        }
    }

    fun speak(ctx: Context, text: String, flush: Boolean = true) {
        val engine = ensureTts(ctx) ?: return
        if (!ttsReady) {
            pending.append(text).append('，')
            return
        }
        try {
            engine.speak(
                text,
                if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "shuangka_" + System.currentTimeMillis()
            )
        } catch (e: Exception) {
        }
    }

    /** 上下班两张卡都打上时，单独再来一句 */
    fun allDone(ctx: Context) {
        if (!PunchConfig.voiceOn()) return
        speak(ctx, "今天上班和下班都打上卡了，辛苦啦", flush = false)
    }

    private fun ensureTts(ctx: Context): TextToSpeech? {
        tts?.let { return it }
        val engine = TextToSpeech(ctx.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                try {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    // 稍微慢一点、音量拉满，报时的字更容易听清
                    tts?.setSpeechRate(0.95f)
                    tts?.setPitch(1.0f)
                } catch (e: Exception) {
                }
                if (pending.isNotEmpty()) {
                    val text = pending.toString()
                    pending.setLength(0)
                    try {
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "shuangka_pending")
                    } catch (e: Exception) {
                    }
                }
            }
        }
        tts = engine
        return engine
    }

    /** 用系统通知音兜底（ToneGenerator 被占用时） */
    fun playSystemSound(ctx: Context) {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) ?: return
            RingtoneManager.getRingtone(ctx, uri)?.play()
        } catch (e: Exception) {
        }
    }
}
