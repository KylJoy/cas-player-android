package com.example.casplayer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { PlayerScreen(contentResolver, this) } }
    }
}

enum class EncodingMode { FM_250, FM_500, FSK_1500 }
enum class BitOrder { MSB_FIRST, LSB_FIRST }

@Composable
fun PlayerScreen(cr: ContentResolver, ctx: Context) {
    var fileUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var fileName by remember { mutableStateOf("(none)") }
    var mode by remember { mutableStateOf(EncodingMode.FM_500) }
    var bitOrder by remember { mutableStateOf(BitOrder.MSB_FIRST) }
    var sampleRate by remember { mutableStateOf(44100) }
    var leaderMs by remember { mutableStateOf(1500) }
    var tailMs by remember { mutableStateOf(300) }
    var amplitude by remember { mutableStateOf(0.9f) }
    var invert by remember { mutableStateOf(false) }

    var monitorSpeaker by remember { mutableStateOf(false) }
    var monitorVol by remember { mutableStateOf(0.35f) } // 0..1 speaker monitor volume

    var isPlaying by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready") }

    // time-based progress
    var totalMs by remember { mutableStateOf(0) }
    var elapsedMs by remember { mutableStateOf(0) }
    val progress: Float = if (totalMs > 0) elapsedMs.toFloat() / totalMs else 0f

    // routed device labels
    var primaryRoute by remember { mutableStateOf("—") } // main (USB) route
    var monitorRoute by remember { mutableStateOf("—") } // speaker monitor route

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

    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text("TRS-80 .CAS Player", style = MaterialTheme.typography.titleMedium)
                Text(fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pick .cas") }
        }

        // Speed + Bit order
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed:", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                SegmentedButtons(
                    options = listOf("250 FM", "500 FM", "1500 FSK"),
                    selected = when (mode) { EncodingMode.FM_250 -> 0; EncodingMode.FM_500 -> 1; EncodingMode.FSK_1500 -> 2 }
                ) { sel -> mode = when (sel) { 0 -> EncodingMode.FM_250; 1 -> EncodingMode.FM_500; else -> EncodingMode.FSK_1500 } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bit:", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                SegmentedButtons(
                    options = listOf("MSB→LSB", "LSB→MSB"),
                    selected = if (bitOrder == BitOrder.MSB_FIRST) 0 else 1
                ) { bitOrder = if (it == 0) BitOrder.MSB_FIRST else BitOrder.LSB_FIRST }
            }
        }

        // Rate + amplitude
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rate:", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                SegmentedButtons(
                    options = listOf("44100", "48000", "96000"),
                    selected = when (sampleRate) { 44100 -> 0; 48000 -> 1; 96000 -> 2; else -> 0 }
                ) { sampleRate = listOf(44100, 48000, 96000)[it] }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Amp", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(6.dp))
                Slider(
                    value = amplitude,
                    onValueChange = { amplitude = it },
                    valueRange = 0.1f..1.0f,
                    modifier = Modifier.width(180.dp)
                )
            }
        }

        // Leader/Tail
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            LabeledSliderMini("Leader", leaderMs.toFloat(), 0f, 4000f) { leaderMs = it.roundToInt() }
            Spacer(Modifier.width(8.dp))
            LabeledSliderMini("Tail", tailMs.toFloat(), 0f, 4000f) { tailMs = it.roundToInt() }
        }

        // Polarity
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = invert, onCheckedChange = { invert = it })
            Text("Invert polarity", style = MaterialTheme.typography.bodySmall)
        }

        // Monitor toggle + volume
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = monitorSpeaker, onCheckedChange = { monitorSpeaker = it })
            Text("Monitor on speaker", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Text("Vol", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = monitorVol,
                onValueChange = { monitorVol = it },
                valueRange = 0f..1f,
                enabled = monitorSpeaker,
                modifier = Modifier.width(160.dp)
            )
            Text("${(monitorVol * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }

        // Progress
        LinearProgressIndicator(progress.coerceIn(0f, 1f), Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${formatMs(elapsedMs)} / ${formatMs(totalMs)}", style = MaterialTheme.typography.bodySmall)
            Text("${(progress * 100).coerceIn(0f, 100f).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }

        // Actual routed outputs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Output: $primaryRoute", style = MaterialTheme.typography.bodySmall)
            Text("Monitor: $monitorRoute", style = MaterialTheme.typography.bodySmall)
        }

        // Controls
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !isPlaying && fileUri != null,
                onClick = {
                    val uri = fileUri ?: return@Button
                    isPlaying = true
                    status = "Generating…"
                    scope.launch(Dispatchers.Default) {
                        try {
                            val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
                            totalMs = estimateTotalMs(bytes.size, mode, leaderMs, tailMs)
                            elapsedMs = 0

                            val gen = CasSignalGenerator(sampleRate, amplitude, invert)
                            val pcm = gen.generate(
                                bytes = bytes,
                                mode = mode,
                                leaderMs = leaderMs,
                                tailMs = tailMs,
                                bitOrder = bitOrder
                            ) { /* generation progress unused in UI */ }

                            withContext(Dispatchers.Main) { status = "Playing…" }

                            // IMPORTANT: no trailing lambda; pass onEnd inside parentheses
                       playPcmToUsbAndSpeaker(... ) { /* onEnd */ }

                            ctx = ctx,
                            sampleRate = sampleRate,
                            pcm = pcm,
                            mirrorToSpeaker = monitorSpeaker,
                            monitorVolume = monitorVol,
                            onRoute = { p, m ->
                                scope.launch(Dispatchers.Main) {
                                    primaryRoute = p
                                    monitorRoute = m
                                }
                            },
                            onProgress = { frac ->
                                elapsedMs = (frac * totalMs).toInt()
                            },
                            onEnd = {
                                isPlaying = false
                                elapsedMs = totalMs
                                status = "Done"
                            }
                        )

                        } catch (t: Throwable) {
                            withContext(Dispatchers.Main) {
                                isPlaying = false
                                status = "Error: ${t.message}"
                            }
                        }
                    }
                }
            ) { Text("Play") }

            Button(onClick = {
                AudioTrackRouter.stop()
                isPlaying = false
                status = "Stopped"
            }) { Text("Stop") }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        Text(
            "Tips: Disable EQ/Dolby • Vol ~75–85% • Try other bit order/speed if load fails.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/* ---------- Compact UI helpers ---------- */

@Composable
fun SegmentedButtons(options: List<String>, selected: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { idx, label ->
            FilterChip(selected = selected == idx, onClick = { onChange(idx) }, label = { Text(label) })
        }
    }
}

@Composable
fun LabeledSliderMini(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.weight(1f)) {
        Text("$label ${"%.0f".format(value)} ms", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = min..max)
    }
}

/* ---------- Dual-route audio with progress + monitor volume + route labels ---------- */

object AudioTrackRouter {
    fun play(
        context: Context,
        sampleRate: Int,
        pcm: ShortArray,
        mirrorToSpeaker: Boolean,
        monitorVolume: Float,
        onRoute: (String, String) -> Unit = { _, _ -> },
        onProgress: (Float) -> Unit = {},
        onEnd: () -> Unit
    ) { /* ... */ }
}

        val am = context.getSystemService(AudioManager::class.java)
        val outputs = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val usb = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        val spk = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        fun newTrack(): AudioTrack =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 4)
                .build()

        val t1 = newTrack()
        if (usb != null) try { t1.setPreferredDevice(usb) } catch (_: Throwable) {}
        t1.play()
        primary = t1

        if (mirrorToSpeaker && spk != null) {
            val t2 = newTrack()
            try { t2.setPreferredDevice(spk) } catch (_: Throwable) {}
            try { t2.setVolume(monitorVolume.coerceIn(0f, 1f)) } catch (_: Throwable) {}
            t2.play()
            monitor = t2
        }

        // Report initial routes (actual, not just preferred)
        onRoute(labelForDevice(t1.routedDevice), labelForDevice(monitor?.routedDevice))

        Thread {
            val total = pcm.size
            var idx = 0
            val chunk = 2048
            var chunkCount = 0
            val t2 = monitor
            try {
                while (idx < total && t1.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    val end = min(idx + chunk, total)
                    val len = end - idx
                    t1.write(pcm, idx, len, AudioTrack.WRITE_BLOCKING)
                    if (t2 != null && t2.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        t2.write(pcm, idx, len, AudioTrack.WRITE_BLOCKING)
                    }
                    idx = end
                    onProgress(idx.toFloat() / total.toFloat())

                    // Refresh routed device labels occasionally
                    chunkCount++
                    if (chunkCount % 20 == 0) {
                        onRoute(labelForDevice(t1.routedDevice), labelForDevice(monitor?.routedDevice))
                    }
                }
            } finally {
                try { t1.stop() } catch (_: Throwable) {}
                try { t1.release() } catch (_: Throwable) {}
                primary = null
                if (t2 != null) {
                    try { t2.stop() } catch (_: Throwable) {}
                    try { t2.release() } catch (_: Throwable) {}
                    monitor = null
                }
                onEnd()
            }
        }.start()
    }

    fun stop() {
        listOfNotNull(primary, monitor).forEach { t ->
            try { t.pause(); t.flush(); t.stop() } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
        }
        primary = null
        monitor = null
    }

    private fun labelForDevice(d: AudioDeviceInfo?): String {
        if (d == null) return "—"
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line out"
            AudioDeviceInfo.TYPE_AUX_LINE -> "Aux line"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_DOCK -> "Dock"
            else -> "Other"
        }
        val name = try { d.productName?.toString() } catch (_: Throwable) { null }
        return if (!name.isNullOrBlank()) "$type ($name)" else type
    }
}

fun playPcmToUsbAndSpeaker(
    ctx: Context,
    sampleRate: Int,
    pcm: ShortArray,
    mirrorToSpeaker: Boolean,
    monitorVolume: Float,
    onRoute: (String, String) -> Unit = { _, _ -> },
    onProgress: (Float) -> Unit = {},
    onEnd: () -> Unit
) = AudioTrackRouter.play(ctx, sampleRate, pcm, mirrorToSpeaker, monitorVolume, onRoute, onProgress, onEnd)


/* ---------- Duration estimate & generator ---------- */

private fun estimateTotalMs(byteCount: Int, mode: EncodingMode, leaderMs: Int, tailMs: Int): Int {
    val baud = when (mode) {
        EncodingMode.FM_250 -> 250
        EncodingMode.FM_500 -> 500
        EncodingMode.FSK_1500 -> 1500
    }
    val bits = byteCount * 8.0
    val bodyMs = (bits / baud) * 1000.0
    return (leaderMs + bodyMs + tailMs).toInt()
}

class CasSignalGenerator(
    val sampleRate: Int,
    val amplitude: Float = 0.9f,
    val invert: Boolean = false
) {
    private val amp = (amplitude.coerceIn(0.05f, 1.0f) * 32767f).toInt()
    private fun signed(level: Boolean) = if (level.xor(invert)) amp else -amp

    fun generate(
        bytes: ByteArray,
        mode: EncodingMode,
        leaderMs: Int,
        tailMs: Int,
        bitOrder: BitOrder,
        onProgress: (Float) -> Unit
    ): ShortArray =
        when (mode) {
            EncodingMode.FM_250 -> generateFM(bytes, 250, leaderMs, tailMs, bitOrder, onProgress)
            EncodingMode.FM_500 -> generateFM(bytes, 500, leaderMs, tailMs, bitOrder, onProgress)
            EncodingMode.FSK_1500 -> generateFSK(bytes, 1500, leaderMs, tailMs, bitOrder, onProgress)
        }

    // FM: toggle at start of bit; if bit==1 also toggle mid-bit
    private fun generateFM(
        bytes: ByteArray, baud: Int, leaderMs: Int, tailMs: Int,
        bitOrder: BitOrder, onProgress: (Float) -> Unit
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
            val order = if (bitOrder == BitOrder.MSB_FIRST) bitIdxMSB else bitIdxLSB
            for (bi in order) {
                val bit = (b shr bi) and 1
                level = !level
                var s = 0.0
                var mid = false
                var remain = bitSamples
                while (remain > 0.0) {
                    if (idx >= out.size) break
                    out[idx++] = signed(level).toShort()
                    s += 1.0; remain -= 1.0
                    if (!mid && bit == 1 && s >= halfBit) { level = !level; mid = true }
                }
                doneBits++
            }
            if (doneBits % 512L == 0L) onProgress(min(0.95f, doneBits.toFloat() / totalBits.toFloat()))
        }

        repeat(tailSamples) {
            if (idx < out.size) out[idx++] = signed(level).toShort()
        }
        onProgress(1f)
        return if (idx == out.size) out else out.copyOf(idx)
    }

    // High-speed: crude FSK using two square-ish tones
    private fun generateFSK(
        bytes: ByteArray, baud: Int, leaderMs: Int, tailMs: Int,
        bitOrder: BitOrder, onProgress: (Float) -> Unit
    ): ShortArray {
        val f0 = 1320.0
        val f1 = 2680.0
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
            val sign = if (invert) -1 else 1
            for (i in 0 until samples) {
                if (acc >= period / 2.0) { level = !level; acc -= period / 2.0 }
                out[idx++] = (sign * (if (level) amp else -amp)).toShort()
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
            val order = if (bitOrder == BitOrder.MSB_FIRST) bitIdxMSB else bitIdxLSB
            for (bi in order) {
                val bit = (b shr bi) and 1
                writeSquare(if (bit == 0) f0 else f1, spb)
                doneBits++
            }
            if (doneBits % 512L == 0L) onProgress(min(0.95f, doneBits.toFloat() / totalBits.toFloat()))
        }
        writeSquare(f0, tailSamples)
        onProgress(1f)
        return if (idx == out.size) out else out.copyOf(idx)
    }
}

/* ---------- Tiny helpers ---------- */

private fun formatMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
