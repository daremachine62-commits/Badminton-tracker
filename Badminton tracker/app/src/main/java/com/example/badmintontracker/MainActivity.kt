package com.example.badmintontracker

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HitLabel(
    val player: String,
    val elapsedMillis: Long,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TrackerApp()
                }
            }
        }
    }
}

@Composable
private fun TrackerApp() {
    var sessionStarted by remember { mutableStateOf(false) }
    val playerNames = remember { mutableStateListOf("Player 1", "Player 2", "Player 3", "Player 4") }
    val hitLabels = remember { mutableStateListOf<HitLabel>() }
    var sessionStartMillis by remember { mutableLongStateOf(0L) }

    if (sessionStarted) {
        SessionScreen(
            playerNames = playerNames,
            hitLabels = hitLabels,
            onBack = { sessionStarted = false },
            onStartSession = { sessionStartMillis = android.os.SystemClock.elapsedRealtime() },
            sessionStartMillis = sessionStartMillis,
        )
    } else {
        SetupScreen(
            playerNames = playerNames,
            onStart = {
                hitLabels.clear()
                sessionStartMillis = android.os.SystemClock.elapsedRealtime()
                sessionStarted = true
            },
        )
    }
}

@Composable
private fun SetupScreen(
    playerNames: MutableList<String>,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Badminton Tracker", style = MaterialTheme.typography.headlineMedium)
        Text("Set up the four players for this doubles session.")
        playerNames.indices.forEach { index ->
            OutlinedTextField(
                value = playerNames[index],
                onValueChange = { playerNames[index] = it },
                label = { Text("Player ${index + 1}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "This first build records video and timestamped hit labels. Shuttle speed, " +
                    "player tracking, and missed-shot distance need a tested video-analysis model " +
                    "and court calibration; this app does not estimate them yet.",
                modifier = Modifier.padding(16.dp),
            )
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start session")
        }
    }
}

@Composable
private fun SessionScreen(
    playerNames: List<String>,
    hitLabels: MutableList<HitLabel>,
    onBack: () -> Unit,
    onStartSession: () -> Unit,
    sessionStartMillis: Long,
) {
    val context = LocalContext.current
    var selectedPlayer by remember { mutableStateOf(0) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        onStartSession()
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Match recording", style = MaterialTheme.typography.headlineSmall)
        if (hasCameraPermission) {
            CameraRecorder(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
            )
        } else {
            Text("Camera permission is required to record the match.")
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Allow camera")
            }
        }

        Text("Select the player who hit the shuttle:")
        playerNames.forEachIndexed { index, name ->
            val label = name.ifBlank { "Player ${index + 1}" }
            if (index == selectedPlayer) {
                Button(
                    onClick = { selectedPlayer = index },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label)
                }
            } else {
                OutlinedButton(
                    onClick = { selectedPlayer = index },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label)
                }
            }
        }
        Button(
            onClick = {
                hitLabels.add(
                    HitLabel(
                        player = playerNames[selectedPlayer].ifBlank {
                            "Player ${selectedPlayer + 1}"
                        },
                        elapsedMillis = android.os.SystemClock.elapsedRealtime() - sessionStartMillis,
                    ),
                )
            },
            enabled = hasCameraPermission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Tag hit")
        }
        Text("Hit labels: ${hitLabels.size}")
        hitLabels.takeLast(5).reversed().forEach { hit ->
            Text("${formatElapsed(hit.elapsedMillis)}  ·  ${hit.player}")
        }
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("End session")
        }
    }
}

@Composable
private fun CameraRecorder(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraProviderFuture = remember(context) {
        ProcessCameraProvider.getInstance(context)
    }
    var boundCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var recordingStatus by remember { mutableStateOf("Preparing camera…") }
    var lastSavedVideo by remember { mutableStateOf<Uri?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                cameraProviderFuture.addListener(
                    {
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = surfaceProvider
                            }
                            val recorder = Recorder.Builder()
                                .setQualitySelector(
                                    QualitySelector.fromOrderedList(
                                        listOf(Quality.FHD, Quality.HD, Quality.SD),
                                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                                    ),
                                )
                                .build()
                            val capture = VideoCapture.withOutput(recorder)
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                            )
                            boundCameraProvider = cameraProvider
                            videoCapture = capture
                            recordingStatus = "Camera ready"
                        } catch (exception: Exception) {
                            recordingStatus = "Camera setup failed: ${exception.localizedMessage}"
                        }
                    },
                    mainExecutor,
                )
            }
        },
    )
    val currentRecording = rememberUpdatedState(activeRecording)
    val currentCameraProvider = rememberUpdatedState(boundCameraProvider)
    DisposableEffect(cameraProviderFuture) {
        onDispose {
            currentRecording.value?.stop()
            currentCameraProvider.value?.unbindAll()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                val capture = videoCapture ?: return@Button
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "badminton_$timestamp")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/BadmintonTracker")
                }
                val outputOptions = MediaStoreOutputOptions.Builder(
                    context.contentResolver,
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                )
                    .setContentValues(contentValues)
                    .build()

                    try {
                        activeRecording = capture.output
                            .prepareRecording(context, outputOptions)
                            .start(mainExecutor) { event ->
                                when (event) {
                                    is VideoRecordEvent.Start -> recordingStatus = "Recording"
                                    is VideoRecordEvent.Finalize -> {
                                        activeRecording = null
                                        if (event.hasError()) {
                                            recordingStatus =
                                                "Recording failed: ${event.error}"
                                        } else {
                                            lastSavedVideo = event.outputResults.outputUri
                                            recordingStatus =
                                                "Video saved to Movies/BadmintonTracker"
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        recordingStatus = "Starting recording…"
                    } catch (exception: IllegalStateException) {
                        recordingStatus =
                            "Recording couldn't start: ${exception.localizedMessage}"
                    }
            },
            enabled = videoCapture != null && activeRecording == null,
        ) {
            Text("Record")
        }
        OutlinedButton(
            onClick = {
                activeRecording?.stop()
                recordingStatus = "Saving video…"
            },
            enabled = activeRecording != null,
        ) {
            Text("Stop")
        }
    }
    Text(recordingStatus)
    if (lastSavedVideo != null) {
        Text("Saved video is available in Movies/BadmintonTracker.")
    }
}

private fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (elapsedMillis % 1_000) / 100
    return "%02d:%02d.%d".format(Locale.US, minutes, seconds, tenths)
}
