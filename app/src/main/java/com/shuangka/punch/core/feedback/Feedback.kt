package com.shuangka.punch.core.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchType
import java.util.Locale

/** 打卡成功的那一下反馈：提示音 + 语音 + 震动 */
object Feedback {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pending = StringBuilder()

    /** 播报时临时抬高的媒体音量，播完还原 */
    private var appCtx: Context? = null
    private var savedVolume = -1
    private var speakingNow = false
    private val main = Handler(Looper.getMainLooper())
    private val restoreTask: Runnable = object : Runnable {
        override fun run() {
            // 语音还在说就先别降，等说完
            if (speakingNow) main.postDelayed(this, 1200L) else restoreVolume()
        }
    }

    /** 可选的提示音。system=true 表示用系统默认通知铃声 */
    data class SoundStyle(
        val label: String,
        val tone: Int,
        val durationMs: Long,
        val system: Boolean = false
    )

    val SOUND_STYLES = listOf(
        SoundStyle("经典叮咚", ToneGenerator.TONE_PROP_ACK, 420L),
        SoundStyle("清脆双响", ToneGenerator.TONE_PROP_BEEP2, 560L),
        SoundStyle("电子确认", ToneGenerator.TONE_CDMA_CONFIRM, 460L),
        SoundStyle("上扬提示", ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1100L),
        SoundStyle("系统通知音", 0, 900L, system = true)
    )

    fun styleLabel(index: Int): String = SOUND_STYLES.getOrElse(index) { SOUND_STYLES[0] }.label

    fun success(ctx: Context, type: PunchType, time: String) {
        if (PunchConfig.soundOn()) playSound(ctx)
        if (PunchConfig.vibrateOn()) vibrate(ctx)
        if (PunchConfig.voiceOn()) {
            // 时间读成「早上九点二十二分」，避免引擎把 09:22 读成「零九 冒号 二二」
            speak(ctx, "${type.label}打卡成功，现在是${SpokenTime.of(time)}")
        }
    }

    fun playSound(ctx: Context) {
        val style = SOUND_STYLES.getOrElse(PunchConfig.soundStyle()) { SOUND_STYLES[0] }
        if (style.system) {
            playSystemSound(ctx)
            return
        }
        boostVolume(ctx)
        scheduleRestore(style.durationMs + 650)
        Thread {
            var tone: ToneGenerator? = null
            val ok = try {
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
                tone.startTone(style.tone, style.durationMs.toInt())
                Thread.sleep(style.durationMs + 120)
                true
            } catch (e: Exception) {
                false
            } finally {
                try {
                    tone?.release()
                } catch (e: Exception) {
                }
            }
            if (!ok) {
                playSystemSound(ctx)
                if (!speakingNow) restoreVolume()
            }
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
        speakingNow = true
        boostVolume(ctx)
        scheduleRestore(15000L)
        try {
            val ret = engine.speak(
                text,
                if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "shuangka_" + System.currentTimeMillis()
            )
            if (ret != TextToSpeech.SUCCESS) {
                speakingNow = false
                restoreVolume()
            }
        } catch (e: Exception) {
            speakingNow = false
            restoreVolume()
        }
    }

    /** 上下班两张卡都打上时，单独再来一句 */
    fun allDone(ctx: Context) {
        if (!PunchConfig.voiceOn()) return
        speak(ctx, "今天上班和下班都打上卡了，辛苦啦", flush = false)
    }

    private fun ensureTts(ctx: Context): TextToSpeech? {
        appCtx = ctx.applicationContext
        tts?.let { return it }
        val engine = TextToSpeech(ctx.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                try {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    // 明确走媒体通道，这样「播报音量」调的是同一个流
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    // 稍微慢一点，报时的字更容易听清
                    tts?.setSpeechRate(0.95f)
                    tts?.setPitch(1.0f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            afterUtterance()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            afterUtterance()
                        }
                    })
                } catch (e: Exception) {
                }
                if (pending.isNotEmpty()) {
                    val text = pending.toString()
                    pending.setLength(0)
                    try {
                        boostVolume(ctx)
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "shuangka_pending")
                    } catch (e: Exception) {
                        restoreVolume()
                    }
                }
            }
        }
        tts = engine
        return engine
    }

    /**
     * 把媒体音量临时抬到设置里的百分比，播完还原。
     * TTS 本身没有音量档，只有把系统媒体流推到目标档位才真能变响。
     */
    private fun boostVolume(ctx: Context) {
        try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val target = Math.round(max * PunchConfig.voiceVolume() / 100f).coerceIn(0, max)
            if (target <= cur) return
            if (savedVolume < 0) savedVolume = cur
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
            savedVolume = -1
            return
        }
        // 兜底：引擎没回调 onDone 也不能一直把音量顶着
        scheduleRestore(20000L)
    }

    private fun scheduleRestore(delayMs: Long) {
        main.removeCallbacks(restoreTask)
        main.postDelayed(restoreTask, delayMs)
    }

    /** 一句说完：后面还有排队的话就别降音量，全部说完再还原 */
    private fun afterUtterance() {
        main.post {
            speakingNow = try {
                tts?.isSpeaking == true
            } catch (e: Exception) {
                false
            }
            if (!speakingNow) restoreVolume()
        }
    }

    private fun restoreVolume() {
        main.removeCallbacks(restoreTask)
        val saved = savedVolume
        savedVolume = -1
        if (saved < 0) return
        try {
            appCtx?.getSystemService(AudioManager::class.java)
                ?.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        } catch (e: Exception) {
        }
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
