package com.example.badmintontracker

import android.Manifest
import android.content.Context
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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
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

internal data class TrainingSession(
    val completedAtMillis: Long,
    val durationMillis: Long,
    val hitCount: Int,
)

private data class TrainingHistoryLoad(
    val sessions: List<TrainingSession>,
    val error: String?,
)

internal data class CourtPoint(val x: Float, val y: Float)

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
    val detectedCourt: DetectedCourt?,
)

private const val TRAINING_HISTORY_PREFERENCES = "training_history"
private const val TRAINING_HISTORY_KEY = "sessions"

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
    var historyOpened by remember { mutableStateOf(false) }
    val playerNames = remember { mutableStateListOf("Player 1", "Player 2", "Player 3", "Player 4") }
    val hitLabels = remember { mutableStateListOf<HitLabel>() }
    var sessionStartMillis by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val historyLoad = remember(context) { loadTrainingHistory(context) }
    val trainingHistory = remember {
        mutableStateListOf<TrainingSession>().apply { addAll(historyLoad.sessions) }
    }
    var historyError by remember { mutableStateOf(historyLoad.error) }

    if (sessionStarted) {
        SessionScreen(
            playerNames = playerNames,
            hitLabels = hitLabels,
            onEndSession = {
                val session = TrainingSession(
                    completedAtMillis = System.currentTimeMillis(),
                    durationMillis = (
                        android.os.SystemClock.elapsedRealtime() - sessionStartMillis
                        ).coerceAtLeast(0L),
                    hitCount = hitLabels.size,
                )
                trainingHistory.add(0, session)
                historyError = if (saveTrainingHistory(context, trainingHistory.toList())) {
                    null
                } else {
                    "Could not save this session to training history."
                }
                sessionStarted = false
                historyOpened = true
            },
            onStartSession = { sessionStartMillis = android.os.SystemClock.elapsedRealtime() },
            sessionStartMillis = sessionStartMillis,
        )
    } else if (analysisOpened) {
        VideoAnalysisScreen(onBack = { analysisOpened = false })
    } else if (historyOpened) {
        TrainingHistoryScreen(
            sessions = trainingHistory,
            error = historyError,
            onBack = { historyOpened = false },
        )
    } else {
        SetupScreen(
            playerNames = playerNames,
            onAnalyzeVideo = { analysisOpened = true },
            onShowHistory = { historyOpened = true },
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
    onShowHistory: () -> Unit,
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
                    "session. You can also analyze a local video or direct HTTPS " +
                    "video-file URL. " +
                    "A YouTube page URL is not a video file and cannot be analyzed here.",
                modifier = Modifier.padding(16.dp),
            )
        }
        OutlinedButton(onClick = onAnalyzeVideo, modifier = Modifier.fillMaxWidth()) {
            Text("Analyze a local video or video URL")
        }
        OutlinedButton(onClick = onShowHistory, modifier = Modifier.fillMaxWidth()) {
            Text("Training progress")
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Start live camera session")
        }
    }
}

@Composable
private fun TrainingHistoryScreen(
    sessions: List<TrainingSession>,
    error: String?,
    onBack: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Training progress", style = MaterialTheme.typography.headlineMedium)
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Completed sessions: ${sessions.size}")
                Text("Total training time: ${formatElapsed(sessions.sumOf { it.durationMillis })}")
                Text("Tagged hits: ${sessions.sumOf { it.hitCount }}")
            }
        }
        TrainingTreemap(sessions.take(20))
        Text("Recent sessions", style = MaterialTheme.typography.titleLarge)
        if (sessions.isEmpty()) {
            Text("No completed training sessions yet. End a live session to add it here.")
        } else {
            sessions.take(20).forEach { session ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(dateFormat.format(Date(session.completedAtMillis)))
                        Text("Duration: ${formatElapsed(session.durationMillis)}")
                        Text("Tagged hits: ${session.hitCount}")
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
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
    fun analyze(uri: Uri, sourceName: String) {
        analyzing = true
        status = "Extracting frames and comparing motion…"
        selectedVideoName = sourceName
        analysisResult = null
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
            )
        }
    }
}

@Composable
private fun VideoAnalysisSummary(
    sourceName: String,
    result: VideoAnalysisResult,
) {
    val courtCorners = remember(result) {
        mutableStateListOf<CourtPoint>().apply {
            result.detectedCourt?.corners?.let(::addAll)
        }
    }
    var autoDetected by remember(result) {
        mutableStateOf(result.detectedCourt != null)
    }
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
                if (autoDetected) {
                    val detection = result.detectedCourt
                    if (detection != null && detection.visibleCorners.any { !it } &&
                        detection.hasNetReference
                    ) {
                        "Court estimated from visible markings and a net-line reference. " +
                            "Missing corners are projected using standard court geometry. " +
                            "Tap an estimate to adjust it."
                    } else {
                        "Court estimated from visible lines using standard doubles-court " +
                            "geometry. Tap an estimated corner to adjust it."
                    }
                } else {
                    "Court was not detected automatically. Tap its four corners in order " +
                        "(top-left, top-right, bottom-right, bottom-left) to calibrate."
                },
            )
            result.detectedCourt?.let { detection ->
                Text(
                    "Visible court corners: ${detection.visibleCorners.count { it }}/4.",
                )
                Text("Auto-detection confidence: ${(detection.confidence * 100).toInt()}%")
            }
            CourtCalibrationCanvas(
                bitmap = result.previewFrame,
                courtCorners = courtCorners,
                onManualAdjustment = { autoDetected = false },
                showCourtDimensions = autoDetected && courtCorners.size == 4,
            )
            if (autoDetected && courtCorners.size == 4) {
                Text(
                    "Estimated standard doubles-court dimensions: 13.4 m × 6.1 m; " +
                        "not measured directly from image pixels.",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Corners: ${courtCorners.size}/4")
                OutlinedButton(
                    onClick = {
                        courtCorners.clear()
                        autoDetected = false
                    },
                    enabled = courtCorners.isNotEmpty(),
                ) {
                    Text(if (autoDetected) "Reset" else "Clear")
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
    onManualAdjustment: () -> Unit,
    showCourtDimensions: Boolean = false,
) {
    DisposableEffect(bitmap) {
        onDispose { bitmap.recycle() }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(bitmap, courtCorners.size) {
                detectTapGestures { tap ->
                    val scale = min(size.width / bitmap.width, size.height / bitmap.height)
                    val left = (size.width - bitmap.width * scale) / 2f
                    val top = (size.height - bitmap.height * scale) / 2f
                    val x = (tap.x - left) / (bitmap.width * scale)
                    val y = (tap.y - top) / (bitmap.height * scale)
                    if (x in 0f..1f && y in 0f..1f) {
                        onManualAdjustment()
                        if (courtCorners.size < 4) {
                            courtCorners.add(CourtPoint(x, y))
                        } else {
                            val nearest = courtCorners.indices.minBy { index ->
                                val dx = courtCorners[index].x - x
                                val dy = courtCorners[index].y - y
                                dx * dx + dy * dy
                            }
                            courtCorners[nearest] = CourtPoint(x, y)
                        }
                    }
                }
            },
    ) {
        drawRect(color = androidx.compose.ui.graphics.Color.DarkGray)
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
        if (courtCorners.size == 4) {
            courtCorners.indices.forEach { index ->
                val start = courtCorners[index]
                val end = courtCorners[(index + 1) % courtCorners.size]
                drawLine(
                    color = androidx.compose.ui.graphics.Color.Yellow,
                    start = Offset(left + start.x * width, top + start.y * height),
                    end = Offset(left + end.x * width, top + end.y * height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
        }
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
        if (showCourtDimensions && courtCorners.size == 4) {
            val topLeft = courtCorners[0]
            val topRight = courtCorners[1]
            val bottomLeft = courtCorners[3]
            val topCenterX = left + (topLeft.x + topRight.x) * width / 2f
            val topCenterY = top + (topLeft.y + topRight.y) * height / 2f
            val sideCenterX = left + (topLeft.x + bottomLeft.x) * width / 2f
            val sideCenterY = top + (topLeft.y + bottomLeft.y) * height / 2f
            val courtCenterX = left + courtCorners.sumOf { it.x.toDouble() }.toFloat() * width / 4f
            val courtCenterY = top + courtCorners.sumOf { it.y.toDouble() }.toFloat() * height / 4f
            val topInwardLength = hypot(courtCenterX - topCenterX, courtCenterY - topCenterY)
                .coerceAtLeast(1f)
            val topLabelX = topCenterX +
                (courtCenterX - topCenterX) / topInwardLength * 18.dp.toPx()
            val topLabelY = topCenterY +
                (courtCenterY - topCenterY) / topInwardLength * 18.dp.toPx()
            val sideInwardLength = hypot(courtCenterX - sideCenterX, courtCenterY - sideCenterY)
                .coerceAtLeast(1f)
            val sideLabelX = sideCenterX +
                (courtCenterX - sideCenterX) / sideInwardLength * 18.dp.toPx()
            val sideLabelY = sideCenterY +
                (courtCenterY - sideCenterY) / sideInwardLength * 18.dp.toPx()
            val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14.dp.toPx()
                textAlign = Paint.Align.CENTER
                style = Paint.Style.STROKE
                strokeWidth = 4.dp.toPx()
                color = android.graphics.Color.BLACK
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14.dp.toPx()
                textAlign = Paint.Align.CENTER
                color = android.graphics.Color.YELLOW
            }
            val nativeCanvas = drawContext.canvas.nativeCanvas
            nativeCanvas.drawText("13.4 m", topLabelX, topLabelY, outlinePaint)
            nativeCanvas.drawText("13.4 m", topLabelX, topLabelY, labelPaint)
            val sideAngle = Math.toDegrees(
                kotlin.math.atan2(
                    (bottomLeft.y - topLeft.y) * height,
                    (bottomLeft.x - topLeft.x) * width,
                ).toDouble(),
            ).toFloat()
            nativeCanvas.save()
            nativeCanvas.rotate(sideAngle, sideLabelX, sideLabelY)
            nativeCanvas.drawText("6.1 m", sideLabelX, sideLabelY, outlinePaint)
            nativeCanvas.drawText("6.1 m", sideLabelX, sideLabelY, labelPaint)
            nativeCanvas.restore()
        }
    }
}

private fun analyzeVideo(context: android.content.Context, uri: Uri): VideoAnalysisResult {
    val retriever = MediaMetadataRetriever()
    var localVideoFile: File? = null
    try {
        if (uri.scheme == "http" || uri.scheme == "https") {
            retriever.setDataSource(uri.toString(), emptyMap())
        } else {
            val tempFile = File.createTempFile("badminton-analysis-", ".mp4", context.cacheDir)
            localVideoFile = tempFile
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Could not open the selected video. Choose another local video file.")
            input.use { source ->
                tempFile.outputStream().use { destination ->
                    source.copyTo(destination)
                }
            }
            retriever.setDataSource(tempFile.absolutePath)
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
        var courtPreviewFrame: Bitmap? = null
        var detectedCourt: DetectedCourt? = null
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
            if (frameIndex < 30 && frameIndex % 3 == 0) {
                val courtWidth = 160
                val courtHeight = 90
                val candidate = detectCourt(
                    luminance = bitmapLuminance(frame, courtWidth, courtHeight),
                    width = courtWidth,
                    height = courtHeight,
                    courtLineMask = bitmapCourtLineMask(frame, courtWidth, courtHeight),
                )
                if (candidate != null &&
                    (detectedCourt == null || candidate.confidence > detectedCourt.confidence)
                ) {
                    detectedCourt = candidate
                    courtPreviewFrame?.recycle()
                    courtPreviewFrame = frame.copy(Bitmap.Config.ARGB_8888, false)
                }
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
            previewFrame = courtPreviewFrame ?: previewFrame
                ?: error("No readable video frames were found."),
            detectedCourt = detectedCourt,
        )
    } finally {
        retriever.release()
        localVideoFile?.delete()
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
    onEndSession: () -> Unit,
    onStartSession: () -> Unit,
    sessionStartMillis: Long,
) {
    val context = LocalContext.current
    var selectedPlayer by remember { mutableStateOf(0) }
    val liveCourtCorners = remember { mutableStateListOf<CourtPoint>() }
    var liveCourtFrame by remember { mutableStateOf<Bitmap?>(null) }
    var liveCourtManuallyAdjusted by remember { mutableStateOf(false) }
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
                onCourtFrame = { frame, detection ->
                    liveCourtFrame = frame
                    if (!liveCourtManuallyAdjusted && detection != null) {
                        liveCourtCorners.clear()
                        liveCourtCorners.addAll(detection.corners)
                    }
                },
            )
            liveCourtFrame?.let { frame ->
                Text(
                    if (liveCourtCorners.size == 4) {
                        "Live court estimate. Tap a corner to adjust it."
                    } else {
                        "Searching for court lines. If needed, tap the four corners to set them."
                    },
                )
                CourtCalibrationCanvas(
                    bitmap = frame,
                    courtCorners = liveCourtCorners,
                    onManualAdjustment = { liveCourtManuallyAdjusted = true },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live corners: ${liveCourtCorners.size}/4")
                    OutlinedButton(
                        onClick = {
                            liveCourtManuallyAdjusted = false
                            liveCourtCorners.clear()
                        },
                        enabled = liveCourtManuallyAdjusted || liveCourtCorners.isNotEmpty(),
                    ) {
                        Text("Reset auto-detection")
                    }
                }
            }
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
        OutlinedButton(onClick = onEndSession, modifier = Modifier.fillMaxWidth()) {
            Text("End session")
        }
    }
}

private fun loadTrainingHistory(context: Context): TrainingHistoryLoad {
    val preferences = context.getSharedPreferences(
        TRAINING_HISTORY_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    val encodedSessions = preferences.getString(TRAINING_HISTORY_KEY, "[]") ?: "[]"
    return try {
        val sessions = JSONArray(encodedSessions)
        val history = List(sessions.length()) { index ->
            val item = sessions.getJSONObject(index)
            TrainingSession(
                completedAtMillis = item.getLong("completedAtMillis"),
                durationMillis = item.getLong("durationMillis"),
                hitCount = item.getInt("hitCount"),
            ).also {
                if (it.completedAtMillis <= 0L || it.durationMillis < 0L || it.hitCount < 0) {
                    throw JSONException("Invalid training session values.")
                }
            }
        }.sortedByDescending { it.completedAtMillis }
        TrainingHistoryLoad(history, null)
    } catch (exception: JSONException) {
        TrainingHistoryLoad(emptyList(), "Saved training history could not be read.")
    }
}

private fun saveTrainingHistory(context: Context, sessions: List<TrainingSession>): Boolean {
    val encodedSessions = JSONArray().apply {
        sessions.forEach { session ->
            put(
                JSONObject()
                    .put("completedAtMillis", session.completedAtMillis)
                    .put("durationMillis", session.durationMillis)
                    .put("hitCount", session.hitCount),
            )
        }
    }.toString()
    return context.getSharedPreferences(
        TRAINING_HISTORY_PREFERENCES,
        Context.MODE_PRIVATE,
    )
        .edit()
        .putString(TRAINING_HISTORY_KEY, encodedSessions)
        .commit()
}

@Composable
private fun CameraRecorder(
    modifier: Modifier = Modifier,
    onCourtFrame: (Bitmap, DetectedCourt?) -> Unit,
) {
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
    val currentOnCourtFrame = rememberUpdatedState(onCourtFrame)
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
                            var lastCourtDetectionMillis = 0L
                            imageAnalysis.setAnalyzer(analysisExecutor) { image ->
                                try {
                                    val current = imageProxyLuminance(image)
                                    val previous = previousLuminance
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (previous != null) {
                                        val motion = compareMotion(
                                            previous,
                                            current,
                                            image.imageInfo.timestamp / 1_000_000L,
                                        )
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
                                    if (now - lastCourtDetectionMillis >= 1_000) {
                                        lastCourtDetectionMillis = now
                                        val courtWidth = 160
                                        val courtHeight = 90
                                        val courtLuminance = imageProxyLuminance(
                                            image,
                                            courtWidth,
                                            courtHeight,
                                        )
                                        val courtLineMask = imageProxyCourtLineMask(
                                            image,
                                            courtWidth,
                                            courtHeight,
                                        )
                                        val courtFrame = grayscaleBitmap(
                                            courtLuminance,
                                            courtWidth,
                                            courtHeight,
                                        )
                                        val detection = detectCourt(
                                            courtLuminance,
                                            courtWidth,
                                            courtHeight,
                                            courtLineMask,
                                        )
                                        mainExecutor.execute {
                                            currentOnCourtFrame.value(courtFrame, detection)
                                        }
                                    }
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

private fun imageProxyLuminance(
    image: ImageProxy,
    sampleWidth: Int = 64,
    sampleHeight: Int = 36,
): IntArray {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val result = IntArray(sampleWidth * sampleHeight)
    for (y in 0 until sampleHeight) {
        val sourceY = y * image.height / sampleHeight
        for (x in 0 until sampleWidth) {
            val sourceX = x * image.width / sampleWidth
            val index = sourceY * plane.rowStride + sourceX * plane.pixelStride
            result[y * sampleWidth + x] = buffer.get(index).toInt() and 0xff
        }
    }
    return result
}

private fun bitmapCourtLineMask(bitmap: Bitmap, sampleWidth: Int, sampleHeight: Int): BooleanArray? {
    val pixels = IntArray(sampleWidth * sampleHeight)
    for (y in 0 until sampleHeight) {
        for (x in 0 until sampleWidth) {
            pixels[y * sampleWidth + x] = bitmap.getPixel(
                x * bitmap.width / sampleWidth,
                y * bitmap.height / sampleHeight,
            )
        }
    }
    return courtLineMaskFromRgb(pixels, sampleWidth, sampleHeight)
}

private fun imageProxyCourtLineMask(
    image: ImageProxy,
    sampleWidth: Int,
    sampleHeight: Int,
): BooleanArray? {
    if (image.planes.size < 3) return null
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val pixels = IntArray(sampleWidth * sampleHeight)
    for (y in 0 until sampleHeight) {
        val sourceY = y * image.height / sampleHeight
        for (x in 0 until sampleWidth) {
            val sourceX = x * image.width / sampleWidth
            val luminance = yBuffer.get(
                sourceY * yPlane.rowStride + sourceX * yPlane.pixelStride,
            ).toInt() and 0xff
            val chromaIndex = (sourceY / 2) * uPlane.rowStride +
                (sourceX / 2) * uPlane.pixelStride
            val u = (uBuffer.get(chromaIndex).toInt() and 0xff) - 128
            val v = (vBuffer.get(chromaIndex).toInt() and 0xff) - 128
            val red = (luminance + 1.402 * v).toInt().coerceIn(0, 255)
            val green = (luminance - 0.344136 * u - 0.714136 * v).toInt().coerceIn(0, 255)
            val blue = (luminance + 1.772 * u).toInt().coerceIn(0, 255)
            pixels[y * sampleWidth + x] = android.graphics.Color.rgb(red, green, blue)
        }
    }
    return courtLineMaskFromRgb(pixels, sampleWidth, sampleHeight)
}

private fun grayscaleBitmap(luminance: IntArray, width: Int, height: Int): Bitmap {
    val pixels = IntArray(luminance.size) { index ->
        val value = luminance[index].coerceIn(0, 255)
        android.graphics.Color.rgb(value, value, value)
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

internal fun formatElapsed(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (elapsedMillis % 1_000) / 100
    return "%02d:%02d.%d".format(Locale.US, minutes, seconds, tenths)
}
