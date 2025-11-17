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
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MedicineAnalysisScreen(httpClient)
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
    val isSmallScreen = getScreenSize().first < 700 // 640x320 is small
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(if (isSmallScreen) 12.dp else 18.dp))
            .padding(if (isSmallScreen) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Filled.MedicalServices,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(if (isSmallScreen) 20.dp else 28.dp)
            )
            Spacer(modifier = Modifier.width(if (isSmallScreen) 8.dp else 16.dp))
            if (!isSmallScreen) {
                Column {
                    Text(
                        text = "Medicine Checker",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                    Text(
                        text = "AI Medicine Recognition Assistant",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            } else {
                Text(
                    text = "Medicine Checker",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        StatusChip(text = if (isProcessing) "Analyzing…" else "Ready", isSmallScreen = isSmallScreen)
    }
}

@Composable
private fun StatusChip(text: String, modifier: Modifier = Modifier, isSmallScreen: Boolean = false) {
    Surface(
        modifier = modifier,
        color = Color.White.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = if (isSmallScreen) 10.dp else 14.dp, vertical = if (isSmallScreen) 4.dp else 6.dp),
            style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

@Composable
private fun MultiBottleSelector(
    bottles: List<MedicineBottle>,
    selectedBottleId: String?,
    onBottleSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSmallScreen = getScreenSize().first < 700
    val spacing = if (isSmallScreen) 6.dp else 8.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(if (isSmallScreen) 12.dp else 16.dp))
            .padding(if (isSmallScreen) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        Text(
            text = "Multiple Medicines Detected",
            style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = if (isSmallScreen) 4.dp else 6.dp)
        )

        bottles.forEach { bottle ->
            BottleButton(
                bottle = bottle,
                isSelected = selectedBottleId == bottle.id,
                onSelect = { onBottleSelected(bottle.id) },
                modifier = Modifier.heightIn(),
                isSmallScreen = isSmallScreen
            )
        }
    }
}

@Composable
private fun BottleButton(
    bottle: MedicineBottle,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    isSmallScreen: Boolean = false
) {
    val colorDesc = bottle.color?.let { "($it)" } ?: ""
    val position = bottle.position?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() } ?: ""
    val label = "$position $colorDesc ${bottle.name}"

    Button(
        onClick = onSelect,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (isSmallScreen) 8.dp else 12.dp))
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                else
                    Color.White.copy(alpha = 0.1f)
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (isSelected) Color.White else Color.White.copy(alpha = 0.7f)
        ),
        contentPadding = PaddingValues(if (isSmallScreen) 6.dp else 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = label,
                style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            Text(
                text = bottle.indication,
                style = MaterialTheme.typography.labelSmall,
                fontSize = if (isSmallScreen) 9.sp else 11.sp,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PhotoWithMedicineButtons(
    uri: Uri,
    context: Context,
    bottles: List<MedicineBottle>,
    onBottleSelected: (MedicineBottle) -> Unit,
    modifier: Modifier = Modifier
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = try {
            decodeBitmapForUpload(context, uri, maxDimension = 720)
        } catch (e: Exception) {
            Log.e("PhotoWithMedicineButtons", "Failed to load image: ${e.message}")
            null
        }
    }

    // Color palette for different medicines
    val medicineColors = listOf(
        Color(0xFFFF6B6B),  // Red
        Color(0xFF4ECDC4),  // Teal
        Color(0xFFFFE66D),  // Yellow
        Color(0xFF95E1D3),  // Mint
        Color(0xFFC7CEEA),  // Lavender
        Color(0xFFFF8B94),  // Pink
        Color(0xFFB4A7D6),  // Purple
        Color(0xFF73A580),  // Green
    )

    fun getPositionLabel(bottle: MedicineBottle, index: Int, totalCount: Int): String {
        // If only one medicine, no position label needed
        if (totalCount == 1) {
            return ""
        }

        val position = bottle.position?.lowercase() ?: ""
        val color = bottle.color?.lowercase() ?: ""

        return when {
            // For 2-3 medicines: use left/center/right descriptions
            totalCount in 2..3 -> {
                when (position) {
                    "left" -> "[Left] "
                    "center" -> "[Center] "
                    "right" -> "[Right] "
                    else -> "[Position ${index + 1}] "
                }
            }
            // For many medicines: use color as identifier
            totalCount > 3 -> {
                when {
                    color.isNotEmpty() && color != "unknown" -> "[${color.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }}] "
                    else -> "[Item ${index + 1}] "
                }
            }
            else -> "[Item ${index + 1}] "
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (bitmap != null) {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Image
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = "Selected medicine photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )

                // Medicine list
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Detected ${bottles.size} medicine(s):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    bottles.forEachIndexed { index, bottle ->
                        val colorIndex = index % medicineColors.size
                        val itemColor = medicineColors[colorIndex]
                        val positionLabel = getPositionLabel(bottle, index, bottles.size)

                        Button(
                            onClick = { onBottleSelected(bottle) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = itemColor.copy(alpha = 0.2f),
                                contentColor = itemColor
                            ),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.5.dp, itemColor.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = positionLabel + bottle.name,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                    bottle.indication?.let {
                                        if (it.isNotBlank()) {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = itemColor.copy(alpha = 0.7f),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "+",
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}

@Composable
private fun AnalysisResultCard(resultText: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    val isSmallScreen = getScreenSize().first < 700
    val cornerSize = if (isSmallScreen) 16.dp else 36.dp
    val shadowSize = if (isSmallScreen) 12.dp else 26.dp
    val padding = if (isSmallScreen) 12.dp else 28.dp
    val minHeight = if (isSmallScreen) 60.dp else 120.dp
    val maxHeight = if (isSmallScreen) 120.dp else 240.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(shadowSize, RoundedCornerShape(cornerSize), clip = false)
            .clip(RoundedCornerShape(cornerSize))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                BorderStroke(
                    1.2.dp,
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        )
                    )
                ),
                RoundedCornerShape(cornerSize)
            )
            .padding(padding)
            .heightIn(min = minHeight, max = maxHeight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = resultText,
                style = if (isSmallScreen) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
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
            .shadow(shadowSize, RoundedCornerShape(cornerSize), clip = false)
            .clip(RoundedCornerShape(cornerSize))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                BorderStroke(
                    1.2.dp,
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                        )
                    )
                ),
                RoundedCornerShape(cornerSize)
            )
            .padding(padding)
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
                            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
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
private fun ActionButtons(
    onCaptureImage: () -> Unit,
    onPickFromGallery: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val isSmallScreen = getScreenSize().first < 700
    val cornerRadius = if (isSmallScreen) 12.dp else 24.dp
    val padding = if (isSmallScreen) 8.dp else 16.dp
    val spacing = if (isSmallScreen) 8.dp else 16.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(cornerRadius))
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        GradientPrimaryButton(
            text = if (isSmallScreen) "Photo" else "Take Photo",
            icon = Icons.Filled.CameraAlt,
            onClick = onCaptureImage,
            enabled = enabled,
            isSmallScreen = isSmallScreen
        )
        GradientSecondaryButton(
            text = if (isSmallScreen) "Gallery" else "Pick from Gallery",
            icon = Icons.Filled.Image,
            onClick = onPickFromGallery,
            enabled = enabled,
            isSmallScreen = isSmallScreen
        )
    }
}

@Composable
private fun GradientPrimaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isSmallScreen: Boolean = false
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val gradient = remember(primaryColor, tertiaryColor) {
        Brush.linearGradient(
            colors = listOf(
                primaryColor,
                tertiaryColor
            )
        )
    }
    val cornerRadius = if (isSmallScreen) 12.dp else 24.dp
    val shadowSize = if (isSmallScreen) 8.dp else 20.dp
    val iconSize = if (isSmallScreen) 16.dp else 22.dp
    val spacerWidth = if (isSmallScreen) 6.dp else 12.dp
    val contentPaddingH = if (isSmallScreen) 12.dp else 24.dp
    val contentPaddingV = if (isSmallScreen) 10.dp else 18.dp

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .shadow(shadowSize, RoundedCornerShape(cornerRadius), clip = false)
            .clip(RoundedCornerShape(cornerRadius))
            .background(gradient),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.4f)
        ),
        contentPadding = PaddingValues(horizontal = contentPaddingH, vertical = contentPaddingV)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize))
        Spacer(modifier = Modifier.width(spacerWidth))
        Text(text = text, style = if (isSmallScreen) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun GradientSecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isSmallScreen: Boolean = false
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val borderBrush = remember(primaryColor, secondaryColor) {
        Brush.linearGradient(
            colors = listOf(
                primaryColor.copy(alpha = 0.8f),
                secondaryColor.copy(alpha = 0.8f)
            )
        )
    }
    val cornerRadius = if (isSmallScreen) 12.dp else 24.dp
    val borderWidth = if (isSmallScreen) 1.dp else 1.4.dp
    val iconSize = if (isSmallScreen) 16.dp else 22.dp
    val spacerWidth = if (isSmallScreen) 6.dp else 12.dp
    val contentPaddingH = if (isSmallScreen) 12.dp else 24.dp
    val contentPaddingV = if (isSmallScreen) 10.dp else 16.dp

    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius)),
        border = BorderStroke(borderWidth, borderBrush),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        ),
        contentPadding = PaddingValues(horizontal = contentPaddingH, vertical = contentPaddingV)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(iconSize))
        Spacer(modifier = Modifier.width(spacerWidth))
        Text(text = text, style = if (isSmallScreen) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ProcessingOverlay(modifier: Modifier = Modifier) {
    val isSmallScreen = getScreenSize().first < 700
    val cornerRadius = if (isSmallScreen) 12.dp else 24.dp
    val paddingH = if (isSmallScreen) 14.dp else 28.dp
    val paddingV = if (isSmallScreen) 12.dp else 20.dp
    val spacing = if (isSmallScreen) 10.dp else 20.dp
    val indicatorSize = if (isSmallScreen) 32.dp else 48.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(cornerRadius),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            tonalElevation = 12.dp,
            shadowElevation = 10.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = paddingH, vertical = paddingV),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing)
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(indicatorSize),
                    strokeWidth = if (isSmallScreen) 3.dp else 4.dp
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isSmallScreen) "Analyzing…" else "Analyzing photo…",
                        style = if (isSmallScreen) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!isSmallScreen) {
                        Text(
                            text = "Loading medicine information",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

enum class NavigationTab(val label: String, val icon: ImageVector) {
    SCAN("SCAN", Icons.Filled.Home),
    CAMERA("CAMERA", Icons.Filled.CameraAlt),
    DICTATION("DICTATION", Icons.Filled.Mic),
    MEDICAL_CONTROL("MEDICAL CONTROL", Icons.Filled.MedicalServices),
    HOSPITAL("HOSPITAL", Icons.Filled.MedicalServices)
}

@Composable
private fun BottomNavigationBar(
    selectedTab: NavigationTab,
    onTabSelected: (NavigationTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val isSmallScreen = getScreenSize().first < 700

    NavigationBar(
        modifier = modifier
            .fillMaxWidth(),
        containerColor = Color.Black.copy(alpha = 0.8f),
        tonalElevation = 8.dp
    ) {
        NavigationTab.entries.forEach { tab ->
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(if (isSmallScreen) 20.dp else 24.dp)
                    )
                },
                label = if (isSmallScreen) null else {
                    {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                },
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = Color.White.copy(alpha = 0.6f),
                    unselectedTextColor = Color.White.copy(alpha = 0.6f),
                    indicatorColor = Color.White.copy(alpha = 0.1f)
                )
            )
        }
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
    val isSmallScreen = getScreenSize().first < 700
    val textColor = MaterialTheme.colorScheme.onSurface
    val timeText = remember(history.timestamp) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        sdf.format(history.timestamp)
    }

    Button(
        onClick = onSelect,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (isSmallScreen) 8.dp else 12.dp))
            .background(
                if (isSelected)
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                else
                    Color.White.copy(alpha = 0.08f)
            ),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = textColor
        ),
        contentPadding = PaddingValues(if (isSmallScreen) 10.dp else 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isSmallScreen) 4.dp else 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${history.bottles.size} medicine(s)",
                    style = if (isSmallScreen) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = if (isSmallScreen) 9.sp else 10.sp,
                    color = textColor.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }

            if (history.bottles.isNotEmpty()) {
                Text(
                    text = history.bottles.joinToString(", ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
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

    var hasCameraPermission by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(NavigationTab.SCAN) }
    val imageCapture = remember { ImageCapture.Builder().build() }

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

    var isProcessing by remember { mutableStateOf(false) }

    // SCAN tab - On-demand scanning with history
    var scanHistories by remember { mutableStateOf<List<ScanHistory>>(emptyList()) }
    var currentScanResult by remember { mutableStateOf<MedicineAnalysis?>(null) }
    var currentScanImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedHistoryIndex by remember { mutableStateOf<Int?>(-1) }
    var scanSelectedBottleDetails by remember { mutableStateOf<MedicineBottle?>(null) }

    // CAMERA tab - Photo analysis
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var cameraAnalysisResult by remember { mutableStateOf<MedicineAnalysis?>(null) }
    var cameraDetectedBottles by remember { mutableStateOf<List<MedicineBottle>>(emptyList()) }
    var cameraSelectedBottleId by remember { mutableStateOf<String?>(null) }
    var cameraSelectedBottleDetails by remember { mutableStateOf<MedicineBottle?>(null) }

    val tts = remember {
        TextToSpeech(context, null).apply {
            language = Locale.ENGLISH
        }
    }

    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    // Play voice output when CAMERA analysis completes
    LaunchedEffect(cameraAnalysisResult) {
        cameraAnalysisResult?.for_voice?.let {
            if (it.isNotBlank()) {
                tts.speak(it, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    fun scanImageForAnalysis() {
        coroutineScope.launch {
            try {
                isProcessing = true
                // Reset state
                scanSelectedBottleDetails = null
                selectedHistoryIndex = -1

                val uri = imageCapture.takePicture(context)
                currentScanImageUri = uri
                Log.d("ScanAnalysis", "Starting to scan image: $uri")

                currentScanResult = runCatching {
                    Log.d("ScanAnalysis", "Decoding bitmap...")
                    val bitmap = decodeBitmapForUpload(context, uri)
                    Log.d("ScanAnalysis", "Bitmap decoded, size: ${bitmap.width}x${bitmap.height}")

                    Log.d("ScanAnalysis", "Sending to Gemini for medicine analysis...")
                    val result = processImageWithVisionModel(bitmap, httpClient)
                    Log.d("ScanAnalysis", "Gemini result: $result")

                    // Get outline coordinates for each bottle
                    if (result.bottles != null && result.bottles.isNotEmpty()) {
                        Log.d("ScanAnalysis", "Getting medicine outlines...")
                        val outlines = getMedicineOutlines(bitmap, httpClient)
                        Log.d("ScanAnalysis", "Got outlines for ${outlines.size} bottles")

                        // Add outlines to bottles
                        val bottlesWithOutlines = result.bottles.mapIndexed { index, bottle ->
                            val outlineKey = "bottle_${index + 1}"
                            val outline = outlines[outlineKey]
                            if (outline != null) {
                                bottle.copy(outline = outline)
                            } else {
                                bottle
                            }
                        }

                        result.copy(bottles = bottlesWithOutlines)
                    } else {
                        result
                    }
                }.getOrElse {
                    Log.e("ScanAnalysis", "Failed to process scan image: ${it.message}", it)
                    MedicineAnalysis(error = "Scan failed: ${it.localizedMessage ?: "Unknown error"}")
                }

                // Save to history (max 10 items, FIFO)
                if (currentScanResult?.bottles != null && currentScanResult?.bottles!!.isNotEmpty()) {
                    val newHistoryItem = ScanHistory(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        bottles = currentScanResult!!.bottles!!,
                        imageUri = uri.toString()
                    )
                    scanHistories = listOf(newHistoryItem) + scanHistories.take(9) // Max 10 items
                    Log.d("ScanAnalysis", "Added to history. Total items: ${scanHistories.size}")
                }

                cleanupTemporaryImage(context, uri)
                isProcessing = false
                Log.d("ScanAnalysis", "Scan complete. Result: $currentScanResult")
            } catch (e: Exception) {
                Log.e("ScanAnalysis", "Failed to capture image for scan", e)
                currentScanResult = MedicineAnalysis(error = "Capture failed: ${e.localizedMessage}")
                isProcessing = false
            }
        }
    }

    fun processImageForCamera(uri: Uri, fromCamera: Boolean) {
        coroutineScope.launch {
            isProcessing = true
            cameraSelectedBottleId = null
            cameraSelectedBottleDetails = null
            cameraDetectedBottles = emptyList()
            cameraPhotoUri = uri
            Log.d("CameraProcessing", "Starting to process image: $uri")

            cameraAnalysisResult = runCatching {
                Log.d("CameraProcessing", "Decoding bitmap...")
                val bitmap = decodeBitmapForUpload(context, uri)
                Log.d("CameraProcessing", "Bitmap decoded, size: ${bitmap.width}x${bitmap.height}")

                Log.d("CameraProcessing", "Sending to Gemini for medicine analysis...")
                val result = processImageWithVisionModel(bitmap, httpClient)
                Log.d("CameraProcessing", "Gemini result: $result")

                // Get outline coordinates for each bottle
                if (result.bottles != null && result.bottles.isNotEmpty()) {
                    Log.d("CameraProcessing", "Getting medicine outlines...")
                    val outlines = getMedicineOutlines(bitmap, httpClient)
                    Log.d("CameraProcessing", "Got outlines for ${outlines.size} bottles")

                    // Add outlines to bottles
                    val bottlesWithOutlines = result.bottles.mapIndexed { index, bottle ->
                        val outlineKey = "bottle_${index + 1}"
                        val outline = outlines[outlineKey]
                        if (outline != null) {
                            bottle.copy(outline = outline)
                        } else {
                            bottle
                        }
                    }

                    result.copy(bottles = bottlesWithOutlines)
                } else {
                    result
                }
            }.getOrElse {
                Log.e("CameraProcessing", "Failed to process camera image: ${it.message}", it)
                MedicineAnalysis(error = "Processing failed: ${it.localizedMessage ?: "Unknown error"}")
            }.also { result ->
                // Extract bottles from result for CAMERA tab
                if (result.bottles != null && result.bottles.isNotEmpty()) {
                    cameraDetectedBottles = result.bottles
                    Log.d("CameraProcessing", "Camera detected ${cameraDetectedBottles.size} bottles")
                } else {
                    Log.d("CameraProcessing", "No bottles detected or error: ${result.error}")
                }
            }

            if (fromCamera) {
                cleanupTemporaryImage(context, uri)
            }
            isProcessing = false
            Log.d("CameraProcessing", "Processing complete. Result: $cameraAnalysisResult")
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? -> uri?.let { processImageForCamera(it, fromCamera = false) } }
    )

    fun captureImageForCamera() {
        coroutineScope.launch {
            try {
                val uri = imageCapture.takePicture(context)
                processImageForCamera(uri, fromCamera = true)
            } catch (e: Exception) {
                Log.e("MedicineAnalysis", "Failed to capture image", e)
                cameraAnalysisResult = MedicineAnalysis(error = "Capture failed: ${e.localizedMessage}")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (hasCameraPermission) {
                    CameraPreview(lifecycleOwner, imageCapture)
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Camera permission not granted. Please enable it in app settings.",
                            color = Color.White,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                val isSmallScreen = getScreenSize().first < 700
                val horizontalPadding = if (isSmallScreen) 12.dp else 24.dp
                val verticalPadding = if (isSmallScreen) 8.dp else 16.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    HeaderSection(isProcessing = isProcessing)

                    when (selectedTab) {
                        NavigationTab.SCAN -> {
                            // SCAN tab - On-demand scanning with history
                            if (scanSelectedBottleDetails != null) {
                                // Show detailed medicine info when a bottle is selected
                                MedicineDetailCard(
                                    medicine = scanSelectedBottleDetails!!,
                                    onClose = {
                                        scanSelectedBottleDetails = null
                                    },
                                    onVoiceOutput = { voiceText ->
                                        tts.speak(voiceText, TextToSpeech.QUEUE_FLUSH, null, null)
                                    }
                                )
                            } else if (currentScanImageUri != null) {
                                // Scan result is being displayed
                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (currentScanResult != null) {
                                        // Analysis completed
                                        if (currentScanResult?.bottles?.isNotEmpty() == true) {
                                            // Show photo with medicine buttons
                                            PhotoWithMedicineButtons(
                                                uri = currentScanImageUri!!,
                                                context = context,
                                                bottles = currentScanResult!!.bottles!!,
                                                onBottleSelected = { bottle ->
                                                    scanSelectedBottleDetails = bottle
                                                }
                                            )
                                        } else if (currentScanResult?.error != null) {
                                            // Show error message
                                            AnalysisResultCard(resultText = currentScanResult?.error ?: "Scan failed")
                                        } else {
                                            // No medicines detected
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                                    .padding(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "No medicines detected in this image",
                                                    color = Color.White.copy(alpha = 0.7f),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    } else {
                                        // Still analyzing
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                        }
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    // "Scan Again" button
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        GradientPrimaryButton(
                                            text = if (getScreenSize().first < 700) "Scan" else "Scan Again",
                                            icon = Icons.Filled.CameraAlt,
                                            onClick = ::scanImageForAnalysis,
                                            enabled = !isProcessing && hasCameraPermission,
                                            isSmallScreen = getScreenSize().first < 700
                                        )
                                    }
                                }
                            } else if (scanHistories.isNotEmpty()) {
                                // Show history list
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Recent Scans (${scanHistories.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )

                                    scanHistories.forEachIndexed { index, history ->
                                        ScanHistoryItem(
                                            history = history,
                                            context = context,
                                            isSelected = selectedHistoryIndex == index,
                                            onSelect = {
                                                selectedHistoryIndex = index
                                                currentScanImageUri = Uri.parse(history.imageUri)
                                                currentScanResult = MedicineAnalysis(
                                                    bottles = history.bottles,
                                                    for_display = "Loaded from history",
                                                    for_voice = "Scan from ${history.timestamp}"
                                                )
                                            }
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                // "Tap to Scan" button
                                GradientPrimaryButton(
                                    text = if (getScreenSize().first < 700) "Scan" else "Tap to Scan",
                                    icon = Icons.Filled.CameraAlt,
                                    onClick = ::scanImageForAnalysis,
                                    enabled = !isProcessing && hasCameraPermission,
                                    isSmallScreen = getScreenSize().first < 700
                                )
                            } else {
                                // No history yet, show "Tap to Scan" button
                                Spacer(modifier = Modifier.weight(1f))

                                GradientPrimaryButton(
                                    text = if (getScreenSize().first < 700) "Scan" else "Tap to Scan",
                                    icon = Icons.Filled.CameraAlt,
                                    onClick = ::scanImageForAnalysis,
                                    enabled = !isProcessing && hasCameraPermission,
                                    isSmallScreen = getScreenSize().first < 700
                                )
                            }
                        }

                        NavigationTab.CAMERA -> {
                            // CAMERA tab - Photo selection and analysis
                            if (cameraSelectedBottleDetails != null) {
                                // Show detailed medicine info when a bottle is selected
                                MedicineDetailCard(
                                    medicine = cameraSelectedBottleDetails!!,
                                    onClose = {
                                        cameraSelectedBottleDetails = null
                                    },
                                    onVoiceOutput = { voiceText ->
                                        tts.speak(voiceText, TextToSpeech.QUEUE_FLUSH, null, null)
                                    }
                                )
                            } else if (cameraPhotoUri != null) {
                                // Photo has been selected
                                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (cameraAnalysisResult != null) {
                                        // Analysis completed
                                        if (cameraDetectedBottles.isNotEmpty()) {
                                            // Show photo with medicine buttons
                                            PhotoWithMedicineButtons(
                                                uri = cameraPhotoUri!!,
                                                context = context,
                                                bottles = cameraDetectedBottles,
                                                onBottleSelected = { bottle ->
                                                    cameraSelectedBottleDetails = bottle
                                                }
                                            )
                                        } else if (cameraAnalysisResult?.error != null) {
                                            // Show error message
                                            AnalysisResultCard(resultText = cameraAnalysisResult?.error ?: "Analysis failed")
                                        } else {
                                            // No medicines detected
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                                    .padding(16.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    "No medicines detected in this image",
                                                    color = Color.White.copy(alpha = 0.7f),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    } else {
                                        // Still analyzing
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                                .padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                        }
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    // Show action buttons to change photo
                                    ActionButtons(
                                        onCaptureImage = ::captureImageForCamera,
                                        onPickFromGallery = { imagePickerLauncher.launch("image/*") },
                                        enabled = !isProcessing && hasCameraPermission
                                    )
                                }
                            } else {
                                // No photo selected yet
                                Spacer(modifier = Modifier.weight(1f))

                                ActionButtons(
                                    onCaptureImage = ::captureImageForCamera,
                                    onPickFromGallery = { imagePickerLauncher.launch("image/*") },
                                    enabled = !isProcessing && hasCameraPermission
                                )
                            }
                        }

                        NavigationTab.DICTATION, NavigationTab.MEDICAL_CONTROL, NavigationTab.HOSPITAL -> {
                            // Placeholder for other tabs
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "${selectedTab.label} - Coming soon",
                                    color = Color.White.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Navigation Bar
            BottomNavigationBar(
                selectedTab = selectedTab,
                onTabSelected = { newTab ->
                    selectedTab = newTab
                }
            )
        }

        if (isProcessing) {
            ProcessingOverlay()
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
    val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()

    return suspendCancellableCoroutine { continuation ->
        takePicture(outputFileOptions, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                outputFileResults.savedUri?.let {
                    continuation.resume(it)
                } ?: continuation.resumeWithException(IOException("Failed to save image"))
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        })
    }
}

private suspend fun decodeBitmapForUpload(context: Context, uri: Uri, maxDimension: Int = 1024): Bitmap {
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

For EACH medicine bottle, provide a simple "for_voice" field with ONLY: name, clinical use (max 5 words), and dosage. Example: "Aspirin: pain relief. 500mg per dose."

Return JSON format - NO markdown code blocks:

{"error": null, "bottles": [{"id": "bottle_1", "name": "medicine name", "position": "left", "color": "color", "indication": "use", "appliedSituations": "conditions", "effects": "effects", "recommendedDosage": "dosage", "for_voice": "name: use. dosage."}]}

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
