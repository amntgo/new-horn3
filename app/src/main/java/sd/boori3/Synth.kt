package sd.boori3

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val TS = 2048
val TWO_PI = (2.0 * PI).toFloat()

/** وصف صوت واحد (يصل من الصفحة بصيغة JSON) مع جدول موجة جاهز */
class Preset(o: JSONObject) {
    val a = o.optDouble("a", 0.04).toFloat()
    val dec = o.optDouble("dec", 0.2).toFloat()
    val s = o.optDouble("s", 0.9).toFloat()
    val b0 = o.optDouble("b0", 4.0).toFloat()
    val b1 = o.optDouble("b1", 6.0).toFloat()
    val fa = o.optDouble("fa", 0.1).toFloat()
    val vib = o.optDouble("vib", 0.0).toFloat()
    val vr = o.optDouble("vr", 5.5).toFloat()
    val vd = o.optDouble("vd", 0.3).toFloat()
    val r = o.optDouble("r", 0.12).toFloat()
    val vol = o.optDouble("vol", 1.0).toFloat()
    val nz = o.optDouble("nz", 0.0).toFloat()
    val q = o.optDouble("q", 0.8).toFloat()
    val det: FloatArray
    val ip: Array<FloatArray>
    val table = FloatArray(TS + 1)

    init {
        val d = o.optJSONArray("det")
        det = if (d == null || d.length() == 0) floatArrayOf(0f) else FloatArray(d.length()) { d.getDouble(it).toFloat() }
        val p = o.optJSONArray("ip")
        ip = if (p == null) emptyArray() else Array(p.length()) {
            val e = p.getJSONArray(it)
            floatArrayOf(e.getDouble(0).toFloat(), e.getDouble(1).toFloat())
        }
        val ha = o.getJSONArray("h")
        val hs = DoubleArray(ha.length()) { ha.getDouble(it) }
        var mx = 0.0001f
        for (i in 0 until TS) {
            val ph = 2.0 * PI * i / TS
            var sum = 0.0
            for (k in hs.indices) sum += hs[k] * sin((k + 1) * ph)
            table[i] = sum.toFloat()
            mx = max(mx, abs(table[i]))
        }
        for (i in 0 until TS) table[i] /= mx
        table[TS] = table[0]
    }
}

/** نغمة واحدة: موجة + مغلّف + مرشّح + فيبراتو + هواء + أجزاء غير توافقية */
class Voice(val id: String, private val p: Preset, freq: Float, private val sr: Float) {
    private val nd = p.det.size
    private val inc = FloatArray(nd) { (freq * 2.0.pow(p.det[it] / 1200.0)).toFloat() / sr * TS }
    private val ph = FloatArray(nd)
    private val gain = 0.4f / sqrt(nd.toFloat())
    private var env = 0.0001f
    private var stage = 0
    private val sv = max(p.s * p.vol, 0.0002f)
    private val ra = (p.vol / 0.0001f).pow(1f / max(1f, p.a * sr))
    private val rd = (sv / p.vol).pow(1f / max(1f, p.dec * sr))
    private var rr = 1f
    var released = false
    var done = false
    private var t = 0
    private val c0 = min(freq * p.b0, 0.45f * sr)
    private val c1 = min(freq * p.b1, 0.45f * sr)
    private val sweep = max(1f, (p.a + p.fa) * sr)
    private var fb0 = 0f
    private var fb1 = 0f
    private var fb2 = 0f
    private var fa1 = 0f
    private var fa2 = 0f
    private var z1 = 0f
    private var z2 = 0f
    private var lfo = 0f
    private val vdS = p.vd * sr
    private val nip = p.ip.size
    private val ipEnv = FloatArray(nip) { p.ip[it][1] * 0.4f }
    private val ipFac = FloatArray(nip) {
        (0.0005f / max(p.ip[it][1] * 0.4f, 0.0006f)).pow(1f / max(1f, (p.a + p.dec * 0.6f) * sr))
    }
    private val ipPh = FloatArray(nip)
    private val ipInc = FloatArray(nip) { TWO_PI * freq * p.ip[it][0] / sr }
    private val ipEnd = ((p.a + p.dec * 0.6f) * sr).toInt()
    private var nl = 0f
    private val rnd = java.util.Random()

    private fun updateFilter() {
        val frac = min(1f, t / sweep)
        val fc = c0 * (c1 / c0).pow(frac)
        val w0 = TWO_PI * fc / sr
        val cw = cos(w0)
        val alpha = sin(w0) / (2f * p.q)
        val a0 = 1f + alpha
        fb0 = (1f - cw) / 2f / a0
        fb1 = (1f - cw) / a0
        fb2 = fb0
        fa1 = -2f * cw / a0
        fa2 = (1f - alpha) / a0
    }

    fun release() {
        if (released) return
        released = true
        rr = (0.0001f / max(env, 0.0002f)).pow(1f / max(1f, p.r * sr))
        stage = 3
    }

    fun render(out: FloatArray, n: Int) {
        val tab = p.table
        for (i in 0 until n) {
            if ((t and 31) == 0) updateFilter()
            var vm = 1f
            if (p.vib > 0f) {
                val ramp = ((t - vdS) / (0.4f * sr)).coerceIn(0f, 1f)
                vm = 1f + p.vib * ramp * sin(lfo) * 0.000578f
                lfo += TWO_PI * p.vr / sr
                if (lfo > TWO_PI) lfo -= TWO_PI
            }
            var x = 0f
            for (d in 0 until nd) {
                val pos = ph[d]
                val i0 = pos.toInt()
                val fr = pos - i0
                x += tab[i0] + (tab[i0 + 1] - tab[i0]) * fr
                var np = pos + inc[d] * vm
                if (np >= TS) np -= TS
                ph[d] = np
            }
            x *= gain
            var y = fb0 * x + z1
            z1 = fb1 * x - fa1 * y + z2
            z2 = fb2 * x - fa2 * y
            for (k in 0 until nip) {
                y += ipEnv[k] * sin(ipPh[k])
                ipPh[k] += ipInc[k]
                if (ipPh[k] > TWO_PI) ipPh[k] -= TWO_PI
                if (t < ipEnd) ipEnv[k] *= ipFac[k]
            }
            if (p.nz > 0f) {
                nl += 0.15f * ((rnd.nextFloat() * 2f - 1f) - nl)
                y += nl * p.nz * 3f
            }
            out[i] += y * env
            when (stage) {
                0 -> { env *= ra; if (env >= p.vol) { env = p.vol; stage = 1 } }
                1 -> { env *= rd; if (env <= sv) { env = sv; stage = 2 } }
                3 -> { env *= rr; if (env <= 0.00011f) { done = true; return } }
            }
            t++
        }
    }
}

/** المحرك: خيط صوتي مستقل بزمن استجابة منخفض */
class Synth(ctx: Context) {
    private class Cmd(val on: Boolean, val id: String, val freq: Float, val preset: Int)

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    private val chunk = max(64, am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192)
    @Volatile private var presets: Array<Preset> = emptyArray()
    @Volatile private var volume = 0.6f
    private val cmds = ConcurrentLinkedQueue<Cmd>()
    @Volatile private var running = false

    fun setPresets(json: String) {
        val a = JSONArray(json)
        presets = Array(a.length()) { Preset(a.getJSONObject(it)) }
    }

    fun setVolume(v: Float) { volume = v }
    fun noteOn(id: String, freq: Float, preset: Int) { cmds.add(Cmd(true, id, freq, preset)) }
    fun noteOff(id: String) { cmds.add(Cmd(false, id, 0f, 0)) }

    @Synchronized fun start() {
        if (running) return
        running = true
        Thread { loop() }.start()
    }

    @Synchronized fun stop() { running = false }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufBytes = max(minBuf, chunk * 4 * 2)
        val b = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= 26) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = b.build()
        track.play()
        val voices = ArrayList<Voice>()
        val mix = FloatArray(chunk)
        while (running) {
            while (true) {
                val c = cmds.poll() ?: break
                if (c.on) {
                    for (v in voices) if (v.id == c.id) v.release()
                    val ps = presets
                    if (c.preset >= 0 && c.preset < ps.size) {
                        voices.add(Voice(c.id, ps[c.preset], c.freq, sr.toFloat()))
                        if (voices.size > 16) {
                            for (v in voices) if (!v.released) { v.release(); break }
                        }
                    }
                } else {
                    for (v in voices) if (v.id == c.id) v.release()
                }
            }
            java.util.Arrays.fill(mix, 0f)
            var i = 0
            while (i < voices.size) {
                val v = voices[i]
                v.render(mix, chunk)
                if (v.done) voices.removeAt(i) else i++
            }
            val vol = volume
            for (k in 0 until chunk) {
                var x = mix[k] * vol
                x = if (x > 1f) 1f else if (x < -1f) -1f else x * (1.5f - 0.5f * x * x)
                mix[k] = x
            }
            val w = track.write(mix, 0, chunk, AudioTrack.WRITE_BLOCKING)
            if (w < 0) break
        }
        running = false
        track.stop()
        track.release()
    }
}
