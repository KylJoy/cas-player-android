package com.example.casplayer

import android.content.ContentResolver
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PlayerScreen(contentResolver)
            }
        }
    }
}

enum class EncodingMode { FM_250, FM_500, FSK_1500 }
enum class BitOrder { MSB_FIRST, LSB_FIRST }

@Composable
fun PlayerScreen(cr: ContentResolver) {
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("(none)") }
    var mode by remember { mutableStateOf(EncodingMode.FM_500) }
    var bitOrder by remember { mutableStateOf(BitOrder.MSB_FIRST) }
    var sampleRate by remember { mutableStateOf(44100) }
    var leaderMs by remember { mutableStateOf(1500) }
    var tailMs by remember { mutableStateOf(300) }
    var amplitude by remember { mutableStateOf(0.9f) }
    var invert by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("Ready") }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            fileUri = uri
            fileName = uri.lastPathSegment ?: "(picked)"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("TRS‑80 .CAS Player", style = MaterialTheme.typography.headlineSmall)
        Text("File: $fileName", maxLines = 2, overflow = TextOverflow.Ellipsis)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pick .cas") }
            Spacer(Modifier.width(8.dp))
            ModeSelector(mode) { mode = it }
        }

        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Bit order")
            SegmentedButtons(
                options = listOf("MSB→LSB","LSB→MSB"),
                selected = if (bitOrder==BitOrder.MSB_FIRST) 0 else 1,
                onChange = { bitOrder = if (it==0) BitOrder.MSB_FIRST else BitOrder.LSB_FIRST }
            )
        }

        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Sample rate: ${sampleRate} Hz")
            SegmentedButtons(
                options = listOf("44100","48000","96000"),
                selected = when(sampleRate){44100->0;48000->1;96000->2;else->0},
                onChange = { sampleRate = listOf(44100,48000,96000)[it] }
            )
        }

        LabeledSlider("Leader (ms)", leaderMs.toFloat(), 0f, 4000f) { leaderMs = it.roundToInt() }
        LabeledSlider("Tail (ms)", tailMs.toFloat(), 0f, 4000f) { tailMs = it.roundToInt() }
        LabeledSlider("Amplitude", amplitude, 0.1f, 1.0f) { amplitude = it }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = invert, onCheckedChange = { invert = it })
            Text("Invert polarity (rarely needed)")
        }

        LinearProgressIndicator(progress, Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !isPlaying && fileUri != null,
                onClick = {
                    val uri = fileUri ?: return@Button
                    isPlaying = true
                    status = "Generating..."
                    scope.launch(Dispatchers.Default) {
                        try {
                            val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
                            val gen = CasSignalGenerator(sampleRate, amplitude, invert)
                            val pcm = gen.generate(
                                bytes = bytes,
                                mode = mode,
                                leaderMs = leaderMs,
                                tailMs = tailMs,
                                bitOrder = bitOrder,
                                onProgress = { p -> progress = p }
                            )
                            withContext(Dispatchers.Main) { status = "Playing audio..." }
                            playPcmMono16(sampleRate, pcm) {
                                isPlaying = false
                                progress = 0f
                                status = "Done"
                            }
                        } catch (t: Throwable) {
                            withContext(Dispatchers.Main) {
                                isPlaying = false
                                status = "Error: ${t.message}"
                            }
                        }
                    }
                }) { Text("Play") }

            Button(onClick = {
                AudioTrackSingleton.stop()
                isPlaying = false
                progress = 0f
                status = "Stopped"
            }) { Text("Stop") }
        }

        Text(
            "Tips: Disable EQ/Dolby, set phone volume ~75–85%, airplane mode ON. If loads fail, try the other bit order or speed.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun SegmentedButtons(options: List<String>, selected: Int, onChange: (Int)->Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { idx, label ->
            FilterChip(
                selected = selected == idx,
                onClick = { onChange(idx) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
fun ModeSelector(selected: EncodingMode, onChange: (EncodingMode)->Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Speed:")
        SegmentedButtons(
            options = listOf("250 FM","500 FM","1500 FSK"),
            selected = when(selected){
                EncodingMode.FM_250 -> 0
                EncodingMode.FM_500 -> 1
                EncodingMode.FSK_1500 -> 2
            },
            onChange = { sel ->
                onChange( when(sel){
                    0 -> EncodingMode.FM_250
                    1 -> EncodingMode.FM_500
                    else -> EncodingMode.FSK_1500
                })
            }
        )
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float)->Unit) {
    Column {
        Text("$label: ${"%.0f".format(value)}")
        Slider(value = value, onValueChange = onChange, valueRange = min..max )
    }
}

object AudioTrackSingleton {
    private var track: AudioTrack? = null

    fun play(sampleRate: Int, pcm: ShortArray, onEnd: ()->Unit) {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 4)
            .build()
        track = t
        t.play()
        var idx = 0
        val chunk = 2048
        Thread {
            while (idx < pcm.size && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                val end = min(idx + chunk, pcm.size)
                val len = end - idx
                t.write(pcm, idx, len, AudioTrack.WRITE_BLOCKING)
                idx = end
            }
            try { t.stop() } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
            track = null
            onEnd()
        }.start()
    }

    fun stop() {
        track?.let {
            try { it.pause(); it.flush(); it.stop() } catch (_: Throwable) {}
            try { it.release() } catch (_: Throwable) {}
        }
        track = null
    }
}

fun playPcmMono16(sampleRate: Int, pcm: ShortArray, onEnd: ()->Unit) {
    AudioTrackSingleton.play(sampleRate, pcm, onEnd)
}

class CasSignalGenerator(
    val sampleRate: Int,
    val amplitude: Float = 0.9f,
    val invert: Boolean = false
) {
    private val amp = (amplitude.coerceIn(0.05f,1.0f) * 32767f).toInt()
    private fun signed(level:Boolean) = if ( level.xor(invert) ) amp else -amp

    fun generate(
        bytes: ByteArray,
        mode: EncodingMode,
        leaderMs: Int,
        tailMs: Int,
        bitOrder: BitOrder,
        onProgress: (Float)->Unit
    ): ShortArray {
        return when(mode) {
            EncodingMode.FM_250 -> generateFM(bytes, 250, leaderMs, tailMs, bitOrder, onProgress)
            EncodingMode.FM_500 -> generateFM(bytes, 500, leaderMs, tailMs, bitOrder, onProgress)
            EncodingMode.FSK_1500 -> generateFSK(bytes, 1500, leaderMs, tailMs, bitOrder, onProgress)
        }
    }

    // FM: toggle at start of bit; if bit==1 also toggle mid-bit
    private fun generateFM(
        bytes: ByteArray, baud: Int, leaderMs: Int, tailMs: Int,
        bitOrder: BitOrder, onProgress: (Float)->Unit
    ): ShortArray {
        val bitSamples = sampleRate.toDouble() / baud.toDouble()
        val halfBit = bitSamples / 2.0
        val totalBits = bytes.size.toLong() * 8L
        val leaderSamples = (leaderMs / 1000.0 * sampleRate).toInt()
        val tailSamples = (tailMs / 1000.0 * sampleRate).toInt()
        val est = leaderSamples + tailSamples + (totalBits * bitSamples).toInt() + sampleRate
        val out = ShortArray(est)
        var idx = 0
        var level = false

        // leader as zeros (regular toggles)
        var acc = 0.0
        while (idx < leaderSamples) {
            out[idx++] = signed(level).toShort()
            acc += 1.0
            if (acc >= bitSamples) { acc -= bitSamples; level = !level }
        }

        val bitIdxMSB = intArrayOf(7,6,5,4,3,2,1,0)
        val bitIdxLSB = intArrayOf(0,1,2,3,4,5,6,7)
        var doneBits = 0L
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            val order = if (bitOrder==BitOrder.MSB_FIRST) bitIdxMSB else bitIdxLSB
            for (bi in order) {
                val bit = (b shr bi) and 1
                level = !level // start-of-bit
                var s = 0.0
                var mid = false
                var remain = bitSamples
                while (remain > 0.0) {
                    if (idx >= out.size) break
                    out[idx++] = signed(level).toShort()
                    s += 1.0; remain -= 1.0
                    if (!mid && bit==1 && s >= halfBit) { level = !level; mid = true }
                }
                doneBits++
            }
            if (doneBits % 512L == 0L) onProgress(min(0.95f, doneBits.toFloat()/totalBits.toFloat()))
        }

        for (i in 0 until tailSamples) {
            if (idx >= out.size) break
            out[idx++] = signed(level).toShort()
        }
        onProgress(1f)
        return if (idx == out.size) out else out.copyOf(idx)
    }

    // High-speed: crude FSK using two square-ish tones
    private fun generateFSK(
        bytes: ByteArray, baud: Int, leaderMs: Int, tailMs: Int,
        bitOrder: BitOrder, onProgress: (Float)->Unit
    ): ShortArray {
        val f0 = 1320.0; val f1 = 2680.0
        val spb = (sampleRate.toDouble() / baud).toInt()
        val leaderSamples = (leaderMs / 1000.0 * sampleRate).toInt()
        val tailSamples = (tailMs / 1000.0 * sampleRate).toInt()
        val totalBits = bytes.size.toLong() * 8L
        val est = leaderSamples + tailSamples + (totalBits * spb).toInt() + sampleRate
        val out = ShortArray(est)
        var idx = 0

        fun writeSquare(freq: Double, samples: Int) {
            val period = sampleRate.toDouble() / freq
            var acc = 0.0
            var level = false
            for (i in 0 until samples) {
                if (acc >= period/2.0) { level = !level; acc -= period/2.0 }
                out[idx++] = (if (invert) -1 else 1 * (if (level) amp else -amp)).toShort()
                if (idx >= out.size) return
                acc += 1.0
            }
        }

        writeSquare(f0, leaderSamples)
        val bitIdxMSB = intArrayOf(7,6,5,4,3,2,1,0)
        val bitIdxLSB = intArrayOf(0,1,2,3,4,5,6,7)
        var doneBits = 0L
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            val order = if (bitOrder==BitOrder.MSB_FIRST) bitIdxMSB else bitIdxLSB
            for (bi in order) {
                val bit = (b shr bi) and 1
                writeSquare(if (bit==0) f0 else f1, spb)
                doneBits++
            }
            if (doneBits % 512L == 0L) onProgress(min(0.95f, doneBits.toFloat()/totalBits.toFloat()))
        }
        writeSquare(f0, tailSamples)
        onProgress(1f)
        return if (idx == out.size) out else out.copyOf(idx)
    }
}
