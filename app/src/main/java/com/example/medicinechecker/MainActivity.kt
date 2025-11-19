package com.example.medicinechecker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalConfiguration
import com.example.medicinechecker.ui.theme.MedicineCheckerTheme
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

private const val GEMINI_API_KEY = "AIzaSyBwynbKL2bLl13avn2hfTzlDJsVXvdlYrk"

//region Data Classes for Gemini API
@Serializable
data class GeminiVisionRequest(val contents: List<Content>)

@Serializable
data class Content(val parts: List<Part>)

@Serializable
data class Part(
    val text: String? = null,
    val inline_data: InlineData? = null
)

@Serializable
data class InlineData(val mime_type: String, val data: String)

@Serializable
data class GeminiResponse(val candidates: List<Candidate>? = null, val error: ApiError? = null)

@Serializable
data class Candidate(val content: Content?)

@Serializable
data class ApiError(val message: String)

@Serializable
data class MedicineAnalysis(
    val for_voice: String? = null,
    val for_display: String? = null,
    val error: String? = null,
    val bottles: List<MedicineBottle>? = null
)

@Serializable
data class MedicineBottle(
    val id: String,
    val position: String? = null,
    val name: String,
    val color: String? = null,
    val indication: String,
    val effects: String? = null,
    val appliedSituations: String? = null,
    val recommendedDosage: String? = null,
    val dosage: String? = null,
    val for_voice: String? = null,
    val outline: List<OutlinePoint>? = null
)

@Serializable
data class OutlinePoint(
    val x: Float,  // 0.0 to 1.0 representing percentage of image width
    val y: Float   // 0.0 to 1.0 representing percentage of image height
)

@Serializable
data class ScanHistory(
    val id: String = "",
    val timestamp: Long = 0L,
    val bottles: List<MedicineBottle> = emptyList(),
    val imageUri: String = ""  // Image file path
)

@Serializable
data class MultibottleAnalysis(
    val bottles: List<MedicineBottle>? = null,
    val summary: String? = null,
    val error: String? = null
)
//endregion

// Helper function to get screen size
@Composable
fun getScreenSize(): Pair<Int, Int> {
    val configuration = LocalConfiguration.current
    return Pair(configuration.screenWidthDp, configuration.screenHeightDp)
}

class MainActivity : ComponentActivity() {
    private val httpClient: HttpClient by lazy(LazyThreadSafetyMode.NONE) {
        HttpClient {
            install(ContentNegotiation) {
                json(Json { isLenient = true; ignoreUnknownKeys = true })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MedicineCheckerTheme {
                // Force 2:1 aspect ratio scaling (640x320)
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black), // Letterbox color
                    contentAlignment = Alignment.Center
                ) {
                    val screenAspectRatio = maxWidth / maxHeight
                    val targetAspectRatio = 640f / 320f // 2.0f
                    
                    val contentModifier = if (screenAspectRatio > targetAspectRatio) {
                        // Screen is wider than target -> Fit Height, adjust Width (Pillarbox)
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(targetAspectRatio)
                    } else {
                        // Screen is taller than target -> Fit Width, adjust Height (Letterbox)
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(targetAspectRatio)
                    }
                    
                    Box(modifier = contentModifier) {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            MedicineAnalysisScreen(httpClient)
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        httpClient.close()
    }
}

@Composable
private fun HeaderSection(isProcessing: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Filled.MedicalServices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "EMS GLASS OS",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        }
        StatusChip(text = if (isProcessing) "ANALYZING" else "READY")
    }
}

@Composable
private fun StatusChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = if (text == "READY") MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
        shape = RectangleShape,
        border = BorderStroke(1.dp, if (text == "READY") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (text == "READY") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
        )
    }
}







@Composable
private fun AnalysisResultCard(resultText: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .border(1.dp, MaterialTheme.colorScheme.primary, RectangleShape)
            .padding(12.dp)
            .heightIn(min = 60.dp, max = 120.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = resultText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun MedicineDetailCard(
    medicine: MedicineBottle,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    onVoiceOutput: ((String) -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    val isSmallScreen = getScreenSize().first < 700
    val cornerSize = if (isSmallScreen) 16.dp else 36.dp
    val shadowSize = if (isSmallScreen) 12.dp else 26.dp
    val padding = if (isSmallScreen) 12.dp else 28.dp

    // Play voice output when card is shown
    LaunchedEffect(medicine) {
        medicine.for_voice?.let { voiceText ->
            if (voiceText.isNotBlank()) {
                onVoiceOutput?.invoke(voiceText)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primary, RectangleShape)
            .padding(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(if (isSmallScreen) 8.dp else 12.dp)) {
                // Medicine Name
                Text(
                    text = medicine.name,
                    style = if (isSmallScreen) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )

                // Position and Color
                if (!medicine.position.isNullOrBlank() || !medicine.color.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        medicine.position?.let {
                            Text(
                                text = "${it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString() }}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        medicine.color?.let {
                            Text(
                                text = "$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                // Applied Situations
                if (!medicine.appliedSituations.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Applied Situations",
                            style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = medicine.appliedSituations,
                            style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }

                // Effects
                if (!medicine.effects.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Effects",
                            style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = medicine.effects,
                            style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }

                // Recommended Dosage
                if (!medicine.recommendedDosage.isNullOrBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Recommended Dosage & Concentration",
                            style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = medicine.recommendedDosage,
                            style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }

                // Close Button
                Button(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "Back",
                        style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}



@Composable
private fun IndustrialPrimaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f)
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun IndustrialSecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary, RectangleShape),
        shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}





@Composable
private fun ScanHistoryItem(
    history: ScanHistory,
    context: Context,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val timeText = remember(history.timestamp) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        sdf.format(history.timestamp)
    }

    Button(
        onClick = onSelect,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RectangleShape),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color.Transparent,
            contentColor = textColor
        ),
        shape = RectangleShape,
        contentPadding = PaddingValues(8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${history.bottles.size} MEDICINE(S)",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }

            if (history.bottles.isNotEmpty()) {
                Text(
                    text = history.bottles.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun MedicineAnalysisScreen(httpClient: HttpClient) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    // State Management
    var hasCameraPermission by remember { mutableStateOf(false) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    
    // Core State
    var currentImageUri by remember { mutableStateOf<Uri?>(null) } // Null = Camera Mode, Not Null = Result Mode
    var analysisResult by remember { mutableStateOf<MedicineAnalysis?>(null) }
    var selectedMedicine by remember { mutableStateOf<MedicineBottle?>(null) } // Null = List, Not Null = Detail
    var isProcessing by remember { mutableStateOf(false) }
    var scanHistories by remember { mutableStateOf<List<ScanHistory>>(emptyList()) }

    // Permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasCameraPermission = isGranted }
    )

    LaunchedEffect(Unit) {
        when (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> hasCameraPermission = true
            else -> permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // TTS
    val tts = remember {
        TextToSpeech(context, null).apply {
            language = Locale.ENGLISH
        }
    }
    
    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    // TTS Effect
    LaunchedEffect(selectedMedicine) {
        selectedMedicine?.for_voice?.let { voiceText ->
            if (voiceText.isNotBlank()) {
                tts.speak(voiceText, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }
    
    // Scan Function
    fun scanImage() {
        coroutineScope.launch {
            try {
                isProcessing = true
                selectedMedicine = null
                analysisResult = null
                
                val uri = imageCapture.takePicture(context)
                currentImageUri = uri
                Log.d("Scan", "Image captured: $uri")

                analysisResult = runCatching {
                    val bitmap = decodeBitmapForUpload(context, uri)
                    val result = processImageWithVisionModel(bitmap, httpClient)
                    
                    // Get outlines if bottles detected
                    if (result.bottles != null && result.bottles.isNotEmpty()) {
                        val outlines = getMedicineOutlines(bitmap, httpClient)
                        val bottlesWithOutlines = result.bottles.mapIndexed { index, bottle ->
                            val outlineKey = "bottle_${index + 1}"
                            outlines[outlineKey]?.let { bottle.copy(outline = it) } ?: bottle
                        }
                        result.copy(bottles = bottlesWithOutlines)
                    } else {
                        result
                    }
                }.getOrElse {
                    Log.e("Scan", "Analysis failed", it)
                    MedicineAnalysis(error = "Scan failed: ${it.localizedMessage}")
                }

                // Save to history
                if (analysisResult?.bottles?.isNotEmpty() == true) {
                    val newHistory = ScanHistory(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        bottles = analysisResult!!.bottles!!,
                        imageUri = uri.toString()
                    )
                    scanHistories = (listOf(newHistory) + scanHistories).take(10)
                }

                isProcessing = false
            } catch (e: Exception) {
                Log.e("Scan", "Capture failed", e)
                analysisResult = MedicineAnalysis(error = "Capture failed: ${e.localizedMessage}")
                isProcessing = false
            }
        }
    }

    // Load from History
    fun loadHistory(history: ScanHistory) {
        currentImageUri = Uri.parse(history.imageUri)
        analysisResult = MedicineAnalysis(
            bottles = history.bottles,
            for_display = "Loaded from history"
        )
        selectedMedicine = null
    }

    // Reset
    fun resetToCamera() {
        currentImageUri = null
        analysisResult = null
        selectedMedicine = null
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // LEFT COLUMN: Visuals (Camera / Image)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                    .background(Color.Black)
            ) {
                if (currentImageUri == null) {
                    // Camera Mode
                    if (hasCameraPermission) {
                        CameraPreview(lifecycleOwner, imageCapture)
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Camera Permission Required", color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    // Result Mode - Show Image
                    val bitmap = remember(currentImageUri) {
                        try {
                            if (Build.VERSION.SDK_INT >= 28) {
                                val source = ImageDecoder.createSource(context.contentResolver, currentImageUri!!)
                                ImageDecoder.decodeBitmap(source)
                            } else {
                                MediaStore.Images.Media.getBitmap(context.contentResolver, currentImageUri)
                            }
                        } catch (e: Exception) {
                            null
                        }
                    }
                    
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Captured Image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                // Error Overlay (Top Center)
                if (analysisResult?.error != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .background(Color.Black.copy(alpha = 0.8f))
                            .border(1.dp, MaterialTheme.colorScheme.error, RectangleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = analysisResult!!.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // Processing Overlay
                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // RIGHT COLUMN: Data & Interaction
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HeaderSection(isProcessing = isProcessing)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(8.dp)
                ) {
                    if (currentImageUri == null) {
                        // Camera Mode: Show History
                         Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "RECENT SCANS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (scanHistories.isEmpty()) {
                                    Text(
                                        "No recent scans",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                    )
                                } else {
                                    scanHistories.forEach { history ->
                                        ScanHistoryItem(
                                            history = history,
                                            context = context,
                                            isSelected = false,
                                            onSelect = { loadHistory(history) }
                                        )
                                    }
                                }
                            }
                        }
                    } else if (selectedMedicine != null) {
                        // Detail Mode: Show Medicine Details
                        MedicineDetailCard(
                            medicine = selectedMedicine!!,
                            onClose = { selectedMedicine = null }, // Back to List
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Result Mode: Show Medicine List
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "DETECTED MEDICINES",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            
                            val bottles = analysisResult?.bottles
                            if (bottles.isNullOrEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (analysisResult?.error != null) "Scan Error" else "No medicines detected",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    bottles.forEach { bottle ->
                                        IndustrialSecondaryButton(
                                            text = bottle.name,
                                            icon = Icons.Filled.MedicalServices,
                                            onClick = { selectedMedicine = bottle },
                                            enabled = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Action Button
                if (currentImageUri == null) {
                    IndustrialPrimaryButton(
                        text = "SCAN MEDICINES",
                        icon = Icons.Filled.CameraAlt,
                        onClick = ::scanImage,
                        enabled = !isProcessing && hasCameraPermission
                    )
                } else {
                    IndustrialSecondaryButton(
                        text = "BACK TO CAMERA",
                        icon = Icons.Filled.CameraAlt,
                        onClick = ::resetToCamera,
                        enabled = !isProcessing
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(lifecycleOwner: androidx.lifecycle.LifecycleOwner, imageCapture: ImageCapture) {
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    Log.e("CameraPreview", "Use case binding failed", e)
                }
            }, executor)
            previewView
        }
    )
}

private suspend fun ImageCapture.takePicture(context: Context): Uri {
    val file = File.createTempFile("captured_image_${System.currentTimeMillis()}", ".jpg", context.cacheDir)
    return suspendCancellableCoroutine { continuation ->
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        this.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let {
                        continuation.resume(it)
                    } ?: continuation.resumeWithException(IOException("Failed to save image"))
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraX", "Photo capture failed: ${exc.message}", exc)
                    continuation.resumeWithException(exc)
                }
            }
        )
    }
}

private suspend fun decodeBitmapForUpload(context: Context, uri: Uri, maxDimension: Int = 640): Bitmap {
    return withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val largestSide = max(width, height).coerceAtLeast(1)
                if (largestSide > maxDimension) {
                    val ratio = largestSide.toFloat() / maxDimension.toFloat()
                    val targetWidth = max(1, (width / ratio).roundToInt())
                    val targetHeight = max(1, (height / ratio).roundToInt())
                    decoder.setTargetSize(targetWidth, targetHeight)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val contentResolver = context.contentResolver
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }

            val originalWidth = boundsOptions.outWidth.takeIf { it > 0 } ?: maxDimension
            val originalHeight = boundsOptions.outHeight.takeIf { it > 0 } ?: maxDimension
            val largestSide = max(originalWidth, originalHeight)
            var sampleSize = 1
            while (largestSide / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: throw IOException("Could not decode image")
        }
    }
}

suspend fun processImageWithVisionModel(bitmap: Bitmap, httpClient: HttpClient): MedicineAnalysis {
    return withContext(Dispatchers.IO) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val prompt = """
Identify pharmaceutical products in this image. Return valid JSON only.

For EACH medicine bottle, provide a simple "for_voice" field with ONLY: name, clinical use (max 5 words), dosage, and key side effects. Example: "Aspirin: pain relief. 500mg per dose. Warning: stomach bleeding."

For "dosage" field: Provide strength + quantity (e.g., "500mg, 1 tablet"). Infer from standard usage if not strictly visible. Do NOT return "label not visible".
For "effects" field: List key adverse reactions/side effects concisely (max 5 words).

Return JSON format - NO markdown code blocks:

{"error": null, "bottles": [{"id": "bottle_1", "name": "medicine name", "position": "left", "color": "color", "indication": "use", "appliedSituations": "conditions", "effects": "side effects", "recommendedDosage": "dosage", "for_voice": "name: use. dosage. side effects."}]}

If no products: {"error": "not a medicine"}

CRITICAL: Each bottle object MUST have "for_voice" field. This is what will be spoken aloud.
Return ONLY JSON, no markdown, no explanation.
            """.trimIndent()

            val requestBody = GeminiVisionRequest(
                contents = listOf(
                    Content(
                        parts = listOf(
                            Part(text = prompt),
                            Part(inline_data = InlineData(mime_type = "image/jpeg", data = base64Image))
                        )
                    )
                )
            )

            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY"
            Log.d("GeminiAPI", "Calling Gemini API")

            val response: GeminiResponse = try {
                httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }.body()
            } catch (e: Exception) {
                Log.e("GeminiAPI", "HTTP request failed: ${e.message}", e)
                return@withContext MedicineAnalysis(error = "API request failed: ${e.message}")
            }

            Log.d("GeminiResponse", "Response received: $response")

            // Check if API returned an error
            if (response.error != null) {
                Log.e("GeminiResponse", "Gemini API error: ${response.error.message}")
                return@withContext MedicineAnalysis(error = "Gemini error: ${response.error.message}")
            }

            val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            Log.d("GeminiResponse", "Raw text: $rawText")
            Log.d("GeminiResponse", "Candidates count: ${response.candidates?.size}")

            if (rawText.isNullOrBlank()) {
                Log.e("GeminiResponse", "Empty response text")
                return@withContext MedicineAnalysis(error = "Gemini returned an empty response. This might be due to safety filtering.")
            }

            // Clean up the response - remove markdown code blocks if present
            var cleanedText = rawText
            if (cleanedText.contains("```")) {
                // Extract JSON from markdown code block
                val jsonMatch = Regex("""```(?:json)?\s*([\s\S]*?)```""").find(cleanedText)
                if (jsonMatch != null) {
                    cleanedText = jsonMatch.groupValues[1].trim()
                    Log.d("GeminiResponse", "Extracted JSON from markdown: $cleanedText")
                }
            }
            if (cleanedText.contains("'''")) {
                // Extract JSON from triple quote code block
                val jsonMatch = Regex("""'''(?:json)?\s*([\s\S]*?)'''""").find(cleanedText)
                if (jsonMatch != null) {
                    cleanedText = jsonMatch.groupValues[1].trim()
                    Log.d("GeminiResponse", "Extracted JSON from triple quotes: $cleanedText")
                }
            }

            try {
                val json = Json { ignoreUnknownKeys = true }

                // Try to parse as MedicineAnalysis first (single medicine)
                return@withContext try {
                    json.decodeFromString<MedicineAnalysis>(cleanedText)
                } catch (e: Exception) {
                    // If single format fails, try multi-bottle format
                    try {
                        val multiBottle = json.decodeFromString<MultibottleAnalysis>(cleanedText)
                        // Convert multi-bottle response to MedicineAnalysis for display
                        MedicineAnalysis(
                            for_voice = multiBottle.summary,
                            for_display = if (multiBottle.bottles != null && multiBottle.bottles.isNotEmpty()) {
                                multiBottle.bottles.joinToString("\n") { bottle ->
                                    val colorDesc = bottle.color?.let { "$it bottle" } ?: "bottle"
                                    val position = bottle.position?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                                    "$position $colorDesc: ${bottle.name}"
                                }
                            } else {
                                multiBottle.summary
                            },
                            error = multiBottle.error,
                            bottles = multiBottle.bottles
                        )
                    } catch (e2: Exception) {
                        Log.e("ProcessingError", "Failed to parse JSON response from model: $cleanedText", e2)
                        MedicineAnalysis(error = "Failed to parse AI response. Raw result:\n$cleanedText")
                    }
                }
            } catch (e: Exception) {
                Log.e("ProcessingError", "Failed to parse JSON response from model: $cleanedText", e)
                MedicineAnalysis(error = "Failed to parse AI response. Raw result:\n$cleanedText")
            }
        } catch (e: Exception) {
            Log.e("ProcessingError", "An error occurred", e)
            MedicineAnalysis(error = "Fatal Error: ${e.message}")
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }
}

suspend fun getMedicineOutlines(bitmap: Bitmap, httpClient: HttpClient): Map<String, List<OutlinePoint>> {
    return withContext(Dispatchers.IO) {
        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val outlinePrompt = """
For each medicine bottle in this image, provide its outline as a list of points.

Return ONLY valid JSON with outline coordinates as percentage of image dimensions (0.0 to 1.0):

{"outlines": {"bottle_1": [{"x": 0.1, "y": 0.2}, {"x": 0.15, "y": 0.25}, ...], "bottle_2": [...]}}

For each bottle:
- Start from top-left corner of the bottle
- Go clockwise around the bottle perimeter
- Use 6-10 points to define the shape
- x: horizontal position (0=left edge, 1=right edge)
- y: vertical position (0=top edge, 1=bottom edge)

If no bottles visible: {"outlines": {}}

Return ONLY JSON, no markdown, no explanation.
            """.trimIndent()

            val requestBody = GeminiVisionRequest(
                contents = listOf(
                    Content(
                        parts = listOf(
                            Part(text = outlinePrompt),
                            Part(inline_data = InlineData(mime_type = "image/jpeg", data = base64Image))
                        )
                    )
                )
            )

            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$GEMINI_API_KEY"
            Log.d("MedicineOutline", "Calling Gemini for outline extraction")

            val response: GeminiResponse = try {
                httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }.body()
            } catch (e: Exception) {
                Log.e("MedicineOutline", "HTTP request failed: ${e.message}", e)
                return@withContext emptyMap()
            }

            val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            Log.d("MedicineOutline", "Raw outline response: $rawText")

            if (rawText.isNullOrBlank()) {
                Log.w("MedicineOutline", "Empty outline response")
                return@withContext emptyMap()
            }

            // Clean up markdown if present
            var cleanedText = rawText
            if (cleanedText.contains("```")) {
                val jsonMatch = Regex("""```(?:json)?\s*([\s\S]*?)```""").find(cleanedText)
                if (jsonMatch != null) {
                    cleanedText = jsonMatch.groupValues[1].trim()
                }
            }
            if (cleanedText.contains("'''")) {
                val jsonMatch = Regex("""'''(?:json)?\s*([\s\S]*?)'''""").find(cleanedText)
                if (jsonMatch != null) {
                    cleanedText = jsonMatch.groupValues[1].trim()
                }
            }

            try {
                val json = Json { ignoreUnknownKeys = true }
                @Serializable
                data class OutlineResponse(val outlines: Map<String, List<OutlinePoint>>? = null)

                val result = json.decodeFromString<OutlineResponse>(cleanedText)
                Log.d("MedicineOutline", "Successfully parsed outlines: ${result.outlines?.keys}")
                return@withContext result.outlines ?: emptyMap()
            } catch (e: Exception) {
                Log.e("MedicineOutline", "Failed to parse outline JSON: $cleanedText", e)
                return@withContext emptyMap()
            }
        } catch (e: Exception) {
            Log.e("MedicineOutline", "Error getting outlines: ${e.message}", e)
            return@withContext emptyMap()
        }
    }
}

private fun cleanupTemporaryImage(context: Context, uri: Uri) {
    runCatching { context.contentResolver.delete(uri, null, null) }
        .onFailure { Log.w("MedicineAnalysis", "Failed to remove temp image", it) }
}
