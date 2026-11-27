package com.shuangka.punch.core.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
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
import com.shuangka.punch.R
import com.shuangka.punch.data.PunchConfig
import com.shuangka.punch.data.PunchType
import java.io.File
import java.util.Locale

/**
 * 打卡成功的那一下反馈：提示音 + 语音 + 震动。
 *
 * 音量只在本 App 内部调：先把语音合成成 wav，再用 MediaPlayer 播放，
 * 用 setVolume 控制这一路音频的幅度，系统媒体音量一点都不动。
 */
object Feedback {

    /** 合成语音的基础放大量：TTS 默认偏小，先整体抬 1.8 倍，再叠用户设的百分比（0~1000%） */
    private const val PCM_BASE_GAIN = 1.8

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    /** 待播的句子（QUEUE_ADD 会追加在后面） */
    private val queue = ArrayDeque<String>()

    /** 正在合成或正在播放，同一时刻只处理一句 */
    private var busy = false
    private var player: MediaPlayer? = null
    private val files = HashMap<String, File>()

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
            // 内置的豆包风格配音（归一化到满幅，干净不破音）
            playClip(ctx, if (type == PunchType.IN) R.raw.voice_in else R.raw.voice_out)
        }
    }

    /** 播内置语音文件：先复制到缓存，按设置百分比放大波形（tanh 软限幅，不破音），再交给 MediaPlayer */
    fun playClip(ctx: Context, resId: Int) {
        appCtx = ctx.applicationContext
        if (busy) return
        busy = true
        Thread {
            val tmp = try {
                val f = File.createTempFile("skclip", ".wav", ctx.cacheDir)
                val input = ctx.resources.openRawResource(resId)
                val output = f.outputStream()
                try {
                    input.copyTo(output)
                } finally {
                    input.close()
                    output.close()
                }
                f
            } catch (e: Exception) {
                null
            }
            if (tmp == null) {
                busy = false
                return@Thread
            }
            // 滑块 0~1000% 真实作用在波形上（0~10 倍增益），超限部分走 tanh 软限幅，避免硬削波
            val clipGain = (PunchConfig.voiceVolume() / 100.0).coerceIn(0.0, 10.0)
            amplify(tmp, clipGain)
            main.post { playFile(tmp) }
        }.start()
    }

    fun playSound(ctx: Context) {
        val style = SOUND_STYLES.getOrElse(PunchConfig.soundStyle()) { SOUND_STYLES[0] }
        if (style.system) {
            playSystemSound(ctx)
            return
        }
        // ToneGenerator 的幅度上限就是 100，超过也只能到顶，不碰系统音量
        val amp = PunchConfig.voiceVolume().coerceIn(0, 100)
        Thread {
            var tone: ToneGenerator? = null
            val ok = try {
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, amp)
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

    /** 说一句。flush=true 会打断正在说的、清掉排队的 */
    fun speak(ctx: Context, text: String, flush: Boolean = true) {
        appCtx = ctx.applicationContext
        val engine = ensureTts(ctx) ?: return
        if (!ttsReady) {
            queue.addLast(text)
            return
        }
        if (flush) {
            queue.clear()
            stopPlayback()
            busy = false
        }
        queue.addLast(text)
        pump()
    }

    /** 上下班两张卡都打上时，单独再来一句 */
    fun allDone(ctx: Context) {
        if (!PunchConfig.voiceOn()) return
        playClip(ctx, R.raw.voice_alldone)
    }

    private fun ensureTts(ctx: Context): TextToSpeech? {
        appCtx = ctx.applicationContext
        tts?.let { return it }
        val engine = TextToSpeech(ctx.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                try {
                    tts?.language = Locale.SIMPLIFIED_CHINESE
                    tts?.setSpeechRate(0.95f)
                    tts?.setPitch(1.0f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            val id = utteranceId ?: return
                            val f = synchronized(files) { files.remove(id) }
                            if (f != null && f.exists() && f.length() > 0L) {
                                // 系统 TTS 的兜底路径：基础增益叠加滑块百分比（0~1000%），tanh 软限幅兜底
                                amplify(f, PCM_BASE_GAIN * (PunchConfig.voiceVolume() / 100.0).coerceIn(0.0, 10.0))
                                main.post { playFile(f) }
                            } else {
                                // 没合成到文件，说明走的是引擎直接朗读，读完了就继续下一句
                                main.post {
                                    busy = false
                                    pump()
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            main.post {
                                busy = false
                                pump()
                            }
                        }
                    })
                } catch (e: Exception) {
                }
                pump()
            }
        }
        tts = engine
        return engine
    }

    /** 取队列里的第一句去合成（引擎不支持合成到文件就退回直接朗读） */
    private fun pump() {
        if (busy) return
        val engine = tts ?: return
        if (!ttsReady) return
        val ctx = appCtx ?: return
        val text = queue.firstOrNull() ?: return
        queue.removeFirst()

        busy = true
        val id = "sk" + System.currentTimeMillis()
        val out = File(ctx.cacheDir, "$id.wav")
        val ret = try {
            engine.synthesizeToFile(text, null, out, id)
        } catch (e: Exception) {
            TextToSpeech.ERROR
        }
        if (ret == TextToSpeech.SUCCESS) {
            synchronized(files) { files[id] = out }
            // 引擎不回调 onDone 时别一直卡住
            main.postDelayed({
                val pending = synchronized(files) { files.remove(id) }
                if (pending != null) {
                    pending.delete()
                    busy = false
                    pump()
                }
            }, 12000L)
        } else {
            // 极少数引擎不支持「合成到文件」，退回直接读，此时音量由系统媒体音量决定
            busy = false
            try {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id + "_raw")
                busy = true
            } catch (e: Exception) {
            }
        }
    }

    /** 用 MediaPlayer 播合成好的 wav（音量已在波形上处理过） */
    private fun playFile(file: File) {
        val ctx = appCtx ?: return
        try {
            stopPlayback()
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { done ->
                try {
                    done.release()
                } catch (e: Exception) {
                }
                file.delete()
                player = null
                busy = false
                pump()
            }
            mp.setOnErrorListener { bad, _, _ ->
                try {
                    bad.release()
                } catch (e: Exception) {
                }
                file.delete()
                player = null
                busy = false
                pump()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            file.delete()
            busy = false
            pump()
        }
    }

    private fun stopPlayback() {
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
    }

    /**
     * 把 wav（16bit PCM）按 gain 整体缩放，全程 tanh 软限幅：
     * 超过上限的部分平滑饱和，听感偏闷但不会出「撕拉」的削顶声。
     */
    private fun amplify(file: File, gain: Double) {
        if (gain <= 0.0) {
            file.delete()
            return
        }
        try {
            val bytes = file.readBytes()
            if (bytes.size < 44) return

            // 找 data 块（16bit PCM；data 长度写 0xFFFFFFFF 的按文件末尾算）
            var dataStart = -1
            var dataLen = 0
            var pos = 12
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4, Charsets.US_ASCII)
                val size = readLeInt(bytes, pos + 4)
                if (id == "data") {
                    dataStart = pos + 8
                    dataLen = if (size > 0) minOf(size, bytes.size - dataStart) else bytes.size - dataStart
                    break
                }
                if (size < 0) return
                pos += 8 + size + (size and 1)
            }
            if (dataStart < 0 || dataLen < 2) return

            val limit = 32767.0
            val knee = 28000.0
            val out = bytes.copyOf()
            val samples = dataLen / 2
            var i = 0
            while (i < samples) {
                val idx = dataStart + i * 2
                var sample = (out[idx].toInt() and 0xFF) or (out[idx + 1].toInt() shl 8)
                if (sample >= 0x8000) sample -= 0x10000
                val scaled = sample * gain
                val v = if (kotlin.math.abs(scaled) <= knee) {
                    scaled.toInt()
                } else {
                    val s = kotlin.math.sign(scaled)
                    val extra = (limit - knee) * kotlin.math.tanh((kotlin.math.abs(scaled) - knee) / (limit - knee))
                    (s * (knee + extra)).toInt()
                }
                out[idx] = (v and 0xFF).toByte()
                out[idx + 1] = ((v shr 8) and 0xFF).toByte()
                i++
            }
            file.writeBytes(out)
        } catch (e: Exception) {
        }
    }

    private fun readLeInt(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return -1
        return (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)
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
