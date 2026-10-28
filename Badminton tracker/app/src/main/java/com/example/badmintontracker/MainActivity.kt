package com.example.badmintontracker

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.media.MediaMetadataRetriever
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private data class HitLabel(
    val player: String,
    val elapsedMillis: Long,
)

private data class CourtPoint(val x: Float, val y: Float)

private data class MotionSample(
    val timeMillis: Long,
    val changePercent: Float,
    val center: CourtPoint?,
)

private data class VideoAnalysisResult(
    val durationMillis: Long,
    val samplingIntervalMillis: Long,
    val samples: List<MotionSample>,
    val previewFrame: Bitmap,
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
    var analysisOpened by remember { mutableStateOf(false) }
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
    } else if (analysisOpened) {
        VideoAnalysisScreen(onBack = { analysisOpened = false })
    } else {
        SetupScreen(
            playerNames = playerNames,
            onAnalyzeVideo = { analysisOpened = true },
            onStart = {
                hitLabels.clear()
                sessionStartMillis = android.os.SystemClock.elapsedRealtime()
                sessionStarted = true
            },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun SetupScreen(
    playerNames: MutableList<String>,
    onAnalyzeVideo: () -> Unit,
    onStart: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Badminton Tracker", style = MaterialTheme.typography.headlineMedium)
        Text("Set up the four players for this doubles session.")
        playerNames.indices.forEach { index ->
            val bringIntoViewRequester = remember { BringIntoViewRequester() }
            OutlinedTextField(
                value = playerNames[index],
                onValueChange = { playerNames[index] = it },
                label = { Text("Player ${index + 1}") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoViewRequester)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            scope.launch {
                                delay(250)
                                bringIntoViewRequester.bringIntoView()
                            }
                        }
                    },
            )
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Live camera recording and experimental motion tracking are available in a " +
                    "session. You can also analyze a local video or a direct HTTPS video-file URL. " +
                    "A YouTube page URL is not a video file and cannot be analyzed here.",
                modifier = Modifier.padding(16.dp),
            )
        }
        OutlinedButton(onClick = onAnalyzeVideo, modifier = Modifier.fillMaxWidth()) {
            Text("Analyze a local video or video URL")
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start live camera session")
        }
    }
}

@Composable
private fun VideoAnalysisScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Video analysis", style = MaterialTheme.typography.headlineMedium)
        VideoAnalysisPanel()
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun VideoAnalysisPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val urlBringIntoViewRequester = remember { BringIntoViewRequester() }
    var videoUrl by remember { mutableStateOf("") }
    var selectedVideoName by remember { mutableStateOf("") }
    var analysisResult by remember { mutableStateOf<VideoAnalysisResult?>(null) }
    var status by remember { mutableStateOf("") }
    var analyzing by remember { mutableStateOf(false) }
    val courtCorners = remember { mutableStateListOf<CourtPoint>() }

    fun analyze(uri: Uri, sourceName: String) {
        analyzing = true
        status = "Extracting frames and comparing motion…"
        selectedVideoName = sourceName
        analysisResult = null
        courtCorners.clear()
        scope.launch {
            try {
                analysisResult = withContext(Dispatchers.IO) {
                    analyzeVideo(context, uri)
                }
                status = "Analysis complete. Review the motion candidates below."
            } catch (exception: Exception) {
                status = exception.localizedMessage
                    ?: "Could not read this video. Try a local MP4 or a direct HTTPS video URL."
            } finally {
                analyzing = false
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            analyze(uri, uri.lastPathSegment ?: "Selected video")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Analyze a video", style = MaterialTheme.typography.titleLarge)
        Text("Choose a local video, or enter a direct HTTPS link to a video file.")
        OutlinedButton(
            onClick = { videoPicker.launch(arrayOf("video/*")) },
            enabled = !analyzing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Choose local video")
        }
        OutlinedTextField(
            value = videoUrl,
            onValueChange = { videoUrl = it },
            label = { Text("Direct video URL") },
            placeholder = { Text("https://example.com/match.mp4") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(urlBringIntoViewRequester)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        scope.launch {
                            delay(250)
                            urlBringIntoViewRequester.bringIntoView()
                        }
                    }
                },
        )
        Button(
            onClick = {
                val uri = Uri.parse(videoUrl.trim())
                val host = uri.host.orEmpty().lowercase(Locale.ROOT)
                if (host == "youtube.com" || host.endsWith(".youtube.com") ||
                    host == "youtu.be" || host.endsWith(".youtu.be")
                ) {
                    status =
                        "YouTube page links are not direct video files. Use a video file " +
                            "you are authorized to analyze."
                } else if (uri.scheme != "https" || host.isBlank()) {
                    status = "Enter a valid HTTPS link to a directly accessible video file."
                } else {
                    analyze(uri, uri.lastPathSegment ?: "Video URL")
                }
            },
            enabled = !analyzing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (analyzing) "Analyzing…" else "Analyze video URL")
        }
        if (status.isNotBlank()) {
            Text(status)
        }
        analysisResult?.let { result ->
            VideoAnalysisSummary(
                sourceName = selectedVideoName,
                result = result,
                courtCorners = courtCorners,
            )
        }
    }
}

@Composable
private fun VideoAnalysisSummary(
    sourceName: String,
    result: VideoAnalysisResult,
    courtCorners: MutableList<CourtPoint>,
) {
    val maxChange = result.samples.maxOfOrNull { it.changePercent } ?: 0f
    val motionSamples = result.samples.count { it.center != null }
    val calibratedSpeed = if (courtCorners.size == 4) {
        estimatePeakSpeed(result.samples, courtCorners)
    } else {
        null
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Video analysis: $sourceName", style = MaterialTheme.typography.titleMedium)
            Text("Duration: ${formatElapsed(result.durationMillis)}")
            Text(
                "Extracted ${result.samples.size} frames at about " +
                    "${result.samplingIntervalMillis} ms intervals.",
            )
            Text("Motion candidates: $motionSamples; peak frame change: %.1f%%".format(maxChange))
            Text(
                "Shuttle, racket, and player detections are not available: this build has no " +
                    "trained detection model. Motion candidates may be camera shake or players.",
            )
            Text(
                "Tap the four doubles-court corners in order (near the top-left, top-right, " +
                    "bottom-right, bottom-left of the video frame) to calibrate.",
            )
            CourtCalibrationCanvas(result.previewFrame, courtCorners)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Corners: ${courtCorners.size}/4")
                OutlinedButton(
                    onClick = courtCorners::clear,
                    enabled = courtCorners.isNotEmpty(),
                ) {
                    Text("Reset")
                }
            }
            if (calibratedSpeed != null) {
                Text(
                    "Peak calibrated motion-cluster speed: %.1f m/s (not shuttle speed)."
                        .format(calibratedSpeed),
                )
            } else {
                Text(
                    "Speed is unavailable until all four corners are marked and at least two " +
                        "motion candidates are found.",
                )
            }
        }
    }
}

@Composable
private fun CourtCalibrationCanvas(
    bitmap: Bitmap,
    courtCorners: MutableList<CourtPoint>,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(bitmap, courtCorners.size) {
                detectTapGestures { tap ->
                    if (courtCorners.size >= 4) return@detectTapGestures
                    val scale = min(size.width / bitmap.width, size.height / bitmap.height)
                    val left = (size.width - bitmap.width * scale) / 2f
                    val top = (size.height - bitmap.height * scale) / 2f
                    val x = (tap.x - left) / (bitmap.width * scale)
                    val y = (tap.y - top) / (bitmap.height * scale)
                    if (x in 0f..1f && y in 0f..1f) {
                        courtCorners.add(CourtPoint(x, y))
                    }
                }
            },
    ) {
        drawContext.canvas.nativeCanvas.drawColor(android.graphics.Color.DKGRAY)
        val scale = min(size.width / bitmap.width, size.height / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (size.width - width) / 2f
        val top = (size.height - height) / 2f
        drawContext.canvas.nativeCanvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        courtCorners.forEachIndexed { index, point ->
            drawCircle(
                color = androidx.compose.ui.graphics.Color.Yellow,
                radius = 8.dp.toPx(),
                center = Offset(left + point.x * width, top + point.y * height),
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${index + 1}",
                left + point.x * width + 10f,
                top + point.y * height - 10f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.YELLOW
                    textSize = 36f
                },
            )
        }
    }
}

private fun analyzeVideo(context: android.content.Context, uri: Uri): VideoAnalysisResult {
    val retriever = MediaMetadataRetriever()
    try {
        if (uri.scheme == "http" || uri.scheme == "https") {
            retriever.setDataSource(uri.toString(), emptyMap())
        } else {
            retriever.setDataSource(context, uri)
        }
        val durationMillis = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: error("Could not read the video duration.")
        val intervalMillis = max(200L, durationMillis / 299L)
        val frameCount = min(300, (durationMillis / intervalMillis + 1).toInt())
        var previousLuminance: IntArray? = null
        var previewFrame: Bitmap? = null
        val samples = ArrayList<MotionSample>(frameCount)
        for (frameIndex in 0 until frameCount) {
            val timeMillis = min(durationMillis - 1, frameIndex * intervalMillis)
            val frame = retriever.getScaledFrameAtTime(
                timeMillis * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
                320,
                180,
            ) ?: continue
            if (previewFrame == null) {
                previewFrame = frame.copy(Bitmap.Config.ARGB_8888, false)
            }
            val current = bitmapLuminance(frame, 64, 36)
            frame.recycle()
            val previous = previousLuminance
            if (previous == null) {
                samples.add(MotionSample(timeMillis, 0f, null))
            } else {
                samples.add(compareMotion(previous, current, timeMillis))
            }
            previousLuminance = current
        }
        return VideoAnalysisResult(
            durationMillis = durationMillis,
            samplingIntervalMillis = intervalMillis,
            samples = samples,
            previewFrame = previewFrame ?: error("No readable video frames were found."),
        )
    } finally {
        retriever.release()
    }
}

private fun bitmapLuminance(bitmap: Bitmap, sampleWidth: Int, sampleHeight: Int): IntArray {
    val pixels = IntArray(sampleWidth * sampleHeight)
    for (y in 0 until sampleHeight) {
        for (x in 0 until sampleWidth) {
            val color = bitmap.getPixel(
                x * bitmap.width / sampleWidth,
                y * bitmap.height / sampleHeight,
            )
            pixels[y * sampleWidth + x] = (
            77 * ((color shr 16) and 0xff) +
                150 * ((color shr 8) and 0xff) +
                29 * (color and 0xff)
            ) shr 8
        }
    }
    return pixels
}

private fun compareMotion(
    previous: IntArray,
    current: IntArray,
    timeMillis: Long,
): MotionSample {
    var changed = 0
    var sumX = 0
    var sumY = 0
    val width = 64
    val height = 36
    for (index in current.indices) {
        if (abs(current[index] - previous[index]) >= 36) {
            changed++
            sumX += index % width
            sumY += index / width
        }
    }
    val percent = changed * 100f / current.size
    val center = if (changed >= 3 && percent <= 65f) {
        CourtPoint(
            x = sumX.toFloat() / changed / (width - 1),
            y = sumY.toFloat() / changed / (height - 1),
        )
    } else {
        null
    }
    return MotionSample(timeMillis, percent, center)
}

private fun estimatePeakSpeed(
    samples: List<MotionSample>,
    corners: List<CourtPoint>,
): Double? {
    val transform = courtHomography(corners) ?: return null
    var previous: Pair<MotionSample, CourtPoint>? = null
    var peakSpeed: Double? = null
    samples.forEach { sample ->
        val center = sample.center ?: return@forEach
        val mapped = mapToCourt(center, transform) ?: return@forEach
        val prior = previous
        if (prior != null) {
            val elapsedSeconds = (sample.timeMillis - prior.first.timeMillis) / 1_000.0
            if (elapsedSeconds > 0) {
                val distance = hypot(
                    (mapped.x - prior.second.x).toDouble(),
                    (mapped.y - prior.second.y).toDouble(),
                )
                val speed = distance / elapsedSeconds
                if (speed <= 250.0 && (peakSpeed == null || speed > peakSpeed)) {
                    peakSpeed = speed
                }
            }
        }
        previous = sample to mapped
    }
    return peakSpeed
}

private fun courtHomography(corners: List<CourtPoint>): DoubleArray? {
    if (corners.size != 4) return null
    val destinations = listOf(
        0.0 to 0.0,
        13.4 to 0.0,
        13.4 to 6.1,
        0.0 to 6.1,
    )
    val matrix = Array(8) { DoubleArray(9) }
    corners.forEachIndexed { index, point ->
        val x = point.x.toDouble()
        val y = point.y.toDouble()
        val (u, v) = destinations[index]
        val row = index * 2
        matrix[row] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
        matrix[row + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
    }
    for (column in 0 until 8) {
        val pivot = (column until 8).maxByOrNull { abs(matrix[it][column]) } ?: return null
        if (abs(matrix[pivot][column]) < 1e-10) return null
        val swap = matrix[column]
        matrix[column] = matrix[pivot]
        matrix[pivot] = swap
        val divisor = matrix[column][column]
        for (j in column..8) matrix[column][j] /= divisor
        for (row in 0 until 8) {
            if (row == column) continue
            val factor = matrix[row][column]
            for (j in column..8) matrix[row][j] -= factor * matrix[column][j]
        }
    }
    return DoubleArray(8) { matrix[it][8] }
}

private fun mapToCourt(point: CourtPoint, h: DoubleArray): CourtPoint? {
    val x = point.x.toDouble()
    val y = point.y.toDouble()
    val denominator = h[6] * x + h[7] * y + 1.0
    if (abs(denominator) < 1e-10) return null
    val courtX = (h[0] * x + h[1] * y + h[2]) / denominator
    val courtY = (h[3] * x + h[4] * y + h[5]) / denominator
    if (!courtX.isFinite() || !courtY.isFinite()) return null
    return CourtPoint(courtX.toFloat(), courtY.toFloat())
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
            .imePadding()
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
    var liveMotionStatus by remember { mutableStateOf("Waiting for camera frames…") }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

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
                            val imageAnalysis = ImageAnalysis.Builder()
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(320, 180),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                            ),
                                        )
                                        .build(),
                                )
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            var previousLuminance: IntArray? = null
                                var lastMotionStatusUpdateMillis = 0L
                                imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                                    try {
                                        val current = imageProxyLuminance(image)
                                        val previous = previousLuminance
                                        if (previous != null) {
                                        val motion = compareMotion(
                                            previous,
                                            current,
                                            image.imageInfo.timestamp / 1_000_000L,
                                        )
                                        val now = android.os.SystemClock.elapsedRealtime()
                                        if (now - lastMotionStatusUpdateMillis >= 500) {
                                            lastMotionStatusUpdateMillis = now
                                            mainExecutor.execute {
                                                liveMotionStatus =
                                                    "Live motion candidate: %.1f%% frame change."
                                                        .format(motion.changePercent)
                                            }
                                        }
                                    }
                                    previousLuminance = current
                                } finally {
                                    image.close()
                                }
                            }
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                                imageAnalysis,
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
            analysisExecutor.shutdown()
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
    Text(liveMotionStatus)
    Text(
        "Live motion is a frame-change candidate only; shuttle, racket, and player " +
            "detection require a trained model.",
    )
    if (lastSavedVideo != null) {
        Text("Saved video is available in Movies/BadmintonTracker.")
    }
}

private fun imageProxyLuminance(image: ImageProxy): IntArray {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val result = IntArray(64 * 36)
    for (y in 0 until 36) {
        val sourceY = y * image.height / 36
        for (x in 0 until 64) {
            val sourceX = x * image.width / 64
            val index = sourceY * plane.rowStride + sourceX * plane.pixelStride
            result[y * 64 + x] = buffer.get(index).toInt() and 0xff
        }
    }
    return result
}

private fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (elapsedMillis % 1_000) / 100
    return "%02d:%02d.%d".format(Locale.US, minutes, seconds, tenths)
}
