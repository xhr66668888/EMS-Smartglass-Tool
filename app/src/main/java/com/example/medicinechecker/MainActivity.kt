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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    val dosage: String? = null
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
private fun MedicineDetailCard(medicine: MedicineBottle, modifier: Modifier = Modifier, onClose: () -> Unit = {}) {
    val scrollState = rememberScrollState()
    val isSmallScreen = getScreenSize().first < 700
    val cornerSize = if (isSmallScreen) 16.dp else 36.dp
    val shadowSize = if (isSmallScreen) 12.dp else 26.dp
    val padding = if (isSmallScreen) 12.dp else 28.dp

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

    // SCAN tab - Real-time detection
    var scanDetectedBottles by remember { mutableStateOf<List<MedicineBottle>>(emptyList()) }
    var scanSelectedBottleId by remember { mutableStateOf<String?>(null) }
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

    // Auto-detection loop for SCAN tab - every 2 seconds
    LaunchedEffect(selectedTab, hasCameraPermission) {
        if (selectedTab == NavigationTab.SCAN && hasCameraPermission && !isProcessing) {
            while (true) {
                try {
                    kotlinx.coroutines.delay(2000) // 2 seconds
                    if (selectedTab == NavigationTab.SCAN && hasCameraPermission && !isProcessing) {
                        try {
                            val uri = imageCapture.takePicture(context)
                            isProcessing = true
                            scanSelectedBottleId = null
                            scanSelectedBottleDetails = null
                            scanDetectedBottles = emptyList()

                            val result = runCatching {
                                val bitmap = decodeBitmapForUpload(context, uri)
                                processImageWithVisionModel(bitmap, httpClient)
                            }.getOrElse {
                                Log.e("MedicineAnalysis", "Failed to process image in auto-detection", it)
                                MedicineAnalysis(error = "Auto-detection failed")
                            }

                            // Extract and update bottles for SCAN
                            if (result.bottles != null && result.bottles.isNotEmpty()) {
                                scanDetectedBottles = result.bottles
                                Log.d("MedicineAnalysis", "Auto-detected ${scanDetectedBottles.size} bottles")
                            }

                            cleanupTemporaryImage(context, uri)
                            isProcessing = false
                        } catch (e: Exception) {
                            Log.e("MedicineAnalysis", "Auto-detection capture failed", e)
                            isProcessing = false
                        }
                    } else {
                        break
                    }
                } catch (e: Exception) {
                    Log.e("MedicineAnalysis", "Auto-detection loop error", e)
                    break
                }
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

            cameraAnalysisResult = runCatching {
                val bitmap = decodeBitmapForUpload(context, uri)
                processImageWithVisionModel(bitmap, httpClient)
            }.getOrElse {
                Log.e("MedicineAnalysis", "Failed to process camera image", it)
                MedicineAnalysis(error = "Processing failed: ${it.localizedMessage ?: "Unknown error"}")
            }.also { result ->
                // Extract bottles from result for CAMERA tab
                if (result.bottles != null && result.bottles.isNotEmpty()) {
                    cameraDetectedBottles = result.bottles
                    Log.d("MedicineAnalysis", "Camera detected ${cameraDetectedBottles.size} bottles")
                }
            }

            if (fromCamera) {
                cleanupTemporaryImage(context, uri)
            }
            isProcessing = false
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
                            // SCAN tab - Automatic real-time detection with "+" buttons
                            if (scanSelectedBottleDetails != null) {
                                // Show detailed medicine info when a bottle is selected
                                MedicineDetailCard(medicine = scanSelectedBottleDetails!!, onClose = {
                                    scanSelectedBottleDetails = null
                                })
                            } else if (scanDetectedBottles.isNotEmpty()) {
                                // Show bottle selector with "+" buttons
                                MultiBottleSelector(
                                    bottles = scanDetectedBottles,
                                    selectedBottleId = scanSelectedBottleId,
                                    onBottleSelected = { bottleId ->
                                        scanSelectedBottleId = bottleId
                                        scanSelectedBottleDetails = scanDetectedBottles.find { it.id == bottleId }
                                    }
                                )
                            } else {
                                // Waiting for detection
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "Point camera at medicines - Auto-detecting every 2 seconds",
                                        color = Color.White.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        NavigationTab.CAMERA -> {
                            // CAMERA tab - Photo selection and analysis
                            if (cameraSelectedBottleDetails != null) {
                                // Show detailed medicine info when a bottle is selected
                                MedicineDetailCard(medicine = cameraSelectedBottleDetails!!, onClose = {
                                    cameraSelectedBottleDetails = null
                                })
                            } else if (cameraPhotoUri != null) {
                                // Show photo with detected medicines
                                if (cameraDetectedBottles.isNotEmpty()) {
                                    MultiBottleSelector(
                                        bottles = cameraDetectedBottles,
                                        selectedBottleId = cameraSelectedBottleId,
                                        onBottleSelected = { bottleId ->
                                            cameraSelectedBottleId = bottleId
                                            cameraSelectedBottleDetails = cameraDetectedBottles.find { it.id == bottleId }
                                        }
                                    )
                                } else if (cameraAnalysisResult?.error != null) {
                                    AnalysisResultCard(resultText = cameraAnalysisResult?.error ?: "Analysis failed")
                                } else {
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
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            ActionButtons(
                                onCaptureImage = ::captureImageForCamera,
                                onPickFromGallery = { imagePickerLauncher.launch("image/*") },
                                enabled = !isProcessing && hasCameraPermission
                            )
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
You are an expert medicine identification assistant. Analyze medicine bottle images and extract structured information.

CRITICAL RULES:
- Always return VALID JSON. Never return markdown, code blocks, or explanations
- If image shows no medicine: return {"error": "not a medicine"}
- Always fill ALL required fields for each medicine

SINGLE MEDICINE FORMAT:
{
  "for_voice": "[Drug Name]: [main use in 5 words]. Dosage: [typical dose].",
  "for_display": "Medicine: [Drug Name]\n\nIndication: [what it treats]\n\nEffects: [what it does]\n\nDosage: [how to use]",
  "error": null,
  "bottles": [{
    "id": "bottle_1",
    "name": "[Drug Name - full name]",
    "position": "[left/center/right]",
    "color": "[color]",
    "indication": "[brief use]",
    "appliedSituations": "[conditions it treats]",
    "effects": "[therapeutic effects]",
    "recommendedDosage": "[dosage information]"
  }]
}

MULTIPLE MEDICINES FORMAT:
{
  "for_voice": "[Drug 1]. [Drug 2]. [Optional: Drug 3]",
  "for_display": "Multiple Medicines Detected:\n\n[Drug 1]\nIndication: [use]\nDosage: [dose]\n\n[Drug 2]\nIndication: [use]\nDosage: [dose]",
  "error": null,
  "bottles": [
    {"id": "bottle_1", "name": "[Drug Name]", "position": "left", "color": "[color]", "indication": "[brief]", "appliedSituations": "[conditions]", "effects": "[effects]", "recommendedDosage": "[dosage]"},
    {"id": "bottle_2", "name": "[Drug Name]", "position": "center", "color": "[color]", "indication": "[brief]", "appliedSituations": "[conditions]", "effects": "[effects]", "recommendedDosage": "[dosage]"}
  ]
}

FIELD DEFINITIONS:
- name: Full medicine name (e.g., "Ibuprofen 400mg")
- position: Visual location - "left", "center", or "right"
- color: Bottle color (e.g., "White", "Orange", "Blue")
- indication: ONE sentence, max 10 words - quick reference
- appliedSituations: List of diseases/conditions (e.g., "Fever, inflammation, headache, pain")
- effects: What it does in body (e.g., "Anti-inflammatory, pain relief, fever reduction")
- recommendedDosage: Usage info (e.g., "400-600mg every 4-6 hours", "2 tablets with water", "10ml three times daily")
- for_voice: SHORT - max 15 seconds speech. Format: "[Name]: [5-word use]. Dosage: [dose]." Example: "Aspirin: Pain relief and blood thinner. Dosage: 500mg to 1000mg."
- for_display: DETAILED - clear formatting with newlines, full information about medicine

TASK:
1. Identify all visible medicines in image
2. For EACH medicine, extract position, color, name, clinical information
3. Return ONLY the JSON structure above - NO other text
4. If uncertain about any field, use reasonable medical knowledge
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
            val response: GeminiResponse = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.body()

            val rawText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (rawText.isNullOrBlank()) {
                return@withContext MedicineAnalysis(error = "Gemini returned an empty or invalid response.")
            }

            try {
                val json = Json { ignoreUnknownKeys = true }

                // Try to parse as MedicineAnalysis first (single medicine)
                return@withContext try {
                    json.decodeFromString<MedicineAnalysis>(rawText)
                } catch (e: Exception) {
                    // If single format fails, try multi-bottle format
                    try {
                        val multiBottle = json.decodeFromString<MultibottleAnalysis>(rawText)
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
                        Log.e("ProcessingError", "Failed to parse JSON response from model: $rawText", e)
                        MedicineAnalysis(error = "Failed to parse AI response. Raw result:\n$rawText")
                    }
                }
            } catch (e: Exception) {
                Log.e("ProcessingError", "Failed to parse JSON response from model: $rawText", e)
                MedicineAnalysis(error = "Failed to parse AI response. Raw result:\n$rawText")
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

private fun cleanupTemporaryImage(context: Context, uri: Uri) {
    runCatching { context.contentResolver.delete(uri, null, null) }
        .onFailure { Log.w("MedicineAnalysis", "Failed to remove temp image", it) }
}
