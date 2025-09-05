package com.example.casplayer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
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
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("(none)") }

    var mode by remember { mutableStateOf(EncodingMode.FM_500) }
    var bitOrder by remember { mutableStateOf(BitOrder.MSB_FIRST) }
    var sampleRate by remember { mutableStateOf(44100) }
    var leaderMs by remember { mutableStateOf(1500) }
    var tailMs by remember { mutableStateOf(300) }
    var amplitude by remember { mutableStateOf(0.9f) }
    var invert by remember { mutableStateOf(false) }

    var monitorSpeaker by remember { mutableStateOf(false) }   // mirror to speaker?
    var monitorVol by remember { mutableStateOf(0.35f) }       // 0..1 speaker monitor volume

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
