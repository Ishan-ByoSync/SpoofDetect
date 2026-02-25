package com.example.spoofdetect

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mv.engine.FaceLandmarkDetector
import com.mv.engine.LiveDetector


@Composable
fun SpoofScreen() {
    val context = LocalContext.current

    val faceLandmarkDetector = remember { FaceLandmarkDetector(context) }
    val liveDetector = remember { LiveDetector(context) }

    DisposableEffect(Unit) {
        onDispose {
            faceLandmarkDetector.close()
            liveDetector.close()
        }
    }

    var livenessScore by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("Waiting for face...") }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (hasCameraPermission(context)) {
                CameraPreview(
                    onResult = { score ->
                        livenessScore = score
                        statusText = when {
                            score == 0f -> "No face detected"
                            score > 0.5f -> "REAL FACE ✅"
                            else -> "Possible SPOOF ⚠️"
                        }
                    },
                    faceLandmarkDetector = faceLandmarkDetector,
                    liveDetector = liveDetector
                )
            } else {
                Text("Camera permission not granted")
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Liveness score: ${"%.3f".format(livenessScore)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(statusText)
            }
        }
    }
}

private fun hasCameraPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED