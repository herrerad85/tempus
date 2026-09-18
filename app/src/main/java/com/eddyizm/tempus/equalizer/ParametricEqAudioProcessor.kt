package com.eddyizm.tempus.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class FilterType(val code: String) { PEAK("PK"), LOW_SHELF("LSC"), HIGH_SHELF("HSC") }

data class ParametricFilter(val type: FilterType, val fc: Double, val gainDb: Double, val q: Double)

/** An AutoEq ParametricEQ.txt profile: a preamp and up to [MAX_FILTERS] biquad filters. */
class ParametricProfile(val preampDb: Double, val filters: List<ParametricFilter>) {

    fun toText(): String = buildString {
        append("Preamp: ").append(plain(preampDb)).append(" dB\n")
        filters.forEachIndexed { i, f ->
            append("Filter ${i + 1}: ON ${f.type.code} Fc ${plain(f.fc)} Hz Gain ${plain(f.gainDb)} dB Q ${plain(f.q)}\n")
        }
    }

    private fun plain(x: Double): String = BigDecimal.valueOf(x).toPlainString()

    companion object {
        // squig.link exports up to 20 bands.
        const val MAX_FILTERS = 20
        const val MAX_GAIN_DB = 20.0
        const val MIN_FC_HZ = 10.0
        // A shelf overshoots its gain more as Q rises; 10 is squig.link's own input limit.
        const val MAX_SHELF_Q = 10.0

        private val PREAMP = Regex("""^Preamp:\s*([-+]?\d+(?:\.\d+)?)\s*dB""", RegexOption.IGNORE_CASE)
        private val FILTER = Regex(
            """^Filter\s*\d*:\s*(ON|OFF)\s+(\w+)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+([-+]?\d+(?:\.\d+)?)\s*dB\s+Q\s+(\d+(?:\.\d+)?)\s*$""",
            RegexOption.IGNORE_CASE
        )

        @JvmStatic
        fun isPerEarExport(text: String): Boolean =
            text.lineSequence().any { it.trim().startsWith("Channel:", ignoreCase = true) }

        /**
         * Returns null when the text holds no valid filter, more than [MAX_FILTERS], a Channel line
         * (a per ear export, whose two sets of filters would otherwise stack on both ears), or a
         * preamp line that is unreadable or beyond [MAX_GAIN_DB]. OFF filters, unknown filter types and
         * other lines are ignored.
         */
        @JvmStatic
        fun parse(text: String): ParametricProfile? {
            if (isPerEarExport(text)) return null
            var preamp = 0.0
            val filters = ArrayList<ParametricFilter>()
            for (raw in text.removePrefix("\uFEFF").lineSequence()) {
                val line = raw.trim()
                val p = PREAMP.find(line)
                if (p != null) {
                    preamp = p.groupValues[1].toDouble()
                    if (abs(preamp) > MAX_GAIN_DB) return null
                } else if (line.startsWith("preamp", ignoreCase = true)) {
                    return null
                }
                val m = FILTER.find(line) ?: continue
                if (m.groupValues[1].equals("OFF", ignoreCase = true)) continue
                val type = when (m.groupValues[2].uppercase()) {
                    "PK" -> FilterType.PEAK
                    "LSC", "LS", "LSQ" -> FilterType.LOW_SHELF
                    "HSC", "HS", "HSQ" -> FilterType.HIGH_SHELF
                    else -> continue
                }
                val fc = m.groupValues[3].toDouble()
                val gain = m.groupValues[4].toDouble()
                val q = m.groupValues[5].toDouble()
                if (!fc.isFinite() || !q.isFinite() || fc < MIN_FC_HZ || q < 0.01 || abs(gain) > MAX_GAIN_DB) continue
                if (type != FilterType.PEAK && q > MAX_SHELF_Q) continue
                filters.add(ParametricFilter(type, fc, gain, q))
            }
            if (filters.isEmpty() || filters.size > MAX_FILTERS) return null
            return ParametricProfile(preamp, filters)
        }
    }
}

/** Normalized RBJ cookbook biquad coefficients, with a0 divided out. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    companion object {
        // Shelves use alpha = sin(w0)/(2Q), as AutoEq's peq.py does.
        fun of(f: ParametricFilter, sampleRate: Int): Biquad {
            val a = 10.0.pow(f.gainDb / 40.0)
            val w0 = 2.0 * PI * f.fc / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * f.q)
            val s = 2.0 * sqrt(a) * alpha
            val c = when (f.type) {
                FilterType.PEAK -> doubleArrayOf(
                    1 + alpha * a, -2 * cw, 1 - alpha * a,
                    1 + alpha / a, -2 * cw, 1 - alpha / a
                )
                FilterType.LOW_SHELF -> doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                    (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s
                )
                FilterType.HIGH_SHELF -> doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                    (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s
                )
            }
            val a0 = c[3]
            return Biquad(c[0] / a0, c[1] / a0, c[2] / a0, c[4] / a0, c[5] / a0)
        }
    }
}

/**
 * The preamp and filter cascade for one sample rate and channel count, in double precision.
 * Filters at or above 0.45 of the sample rate are skipped.
 */
class ParametricEqDsp(profile: ParametricProfile, sampleRate: Int, private val channels: Int) {
    private val preamp = 10.0.pow(profile.preampDb / 20.0)
    private val stages = profile.filters
        .filter { it.fc < 0.45 * sampleRate }
        .map { Biquad.of(it, sampleRate) }
        .toTypedArray()
    // Transposed direct form II state, two values per stage per channel.
    private val state = DoubleArray(stages.size * channels * 2)

    fun process(x: Double, channel: Int): Double {
        var y = x * preamp
        var i = channel * stages.size * 2
        for (bq in stages) {
            val input = y
            y = bq.b0 * input + state[i]
            state[i] = bq.b1 * input - bq.a1 * y + state[i + 1]
            state[i + 1] = bq.b2 * input - bq.a2 * y
            i += 2
        }
        return y
    }

    fun process16(x: Short, channel: Int): Short = saturate16(process(x.toDouble(), channel))

    companion object {
        fun saturate16(x: Double): Short =
            x.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

/**
 * Applies an imported AutoEq profile to PCM inside ExoPlayer's pipeline. It stays active for
 * every 16 bit and float format and bypasses inside [queueInput] while no profile is set, so a
 * profile set mid queue takes effect without waiting for a format change.
 */
@UnstableApi
class ParametricEqAudioProcessor : BaseAudioProcessor() {
    @Volatile private var profile: ParametricProfile? = null
    private var builtFor: ParametricProfile? = null
    private var dsp: ParametricEqDsp? = null
    private var builtFormat = AudioFormat.NOT_SET

    fun setProfile(profile: ParametricProfile?) {
        this.profile = profile
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    // media3 flushes at every gapless transition, so keep the filter state unless something changed.
    override fun onFlush() {
        if (dsp == null || profile !== builtFor || inputAudioFormat != builtFormat) rebuild()
    }

    override fun onReset() {
        builtFor = null
        dsp = null
    }

    private fun rebuild() {
        val current = profile
        builtFor = current
        builtFormat = inputAudioFormat
        dsp = if (current == null || inputAudioFormat == AudioFormat.NOT_SET) null
        else ParametricEqDsp(current, inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (profile !== builtFor) rebuild()

        val output = replaceOutputBuffer(remaining)
        val eq = dsp
        if (eq == null) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        output.order(ByteOrder.nativeOrder())
        val channels = inputAudioFormat.channelCount
        var ch = 0
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            while (inputBuffer.remaining() >= 2) {
                output.putShort(eq.process16(inputBuffer.getShort(), ch))
                if (++ch == channels) ch = 0
            }
        } else {
            while (inputBuffer.remaining() >= 4) {
                output.putFloat(eq.process(inputBuffer.getFloat().toDouble(), ch).toFloat())
                if (++ch == channels) ch = 0
            }
        }
        output.flip()
    }
}
