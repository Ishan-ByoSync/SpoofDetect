package com.example.spoofdetect

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mv.engine.FaceBox
import com.mv.engine.FaceDetector
import com.mv.engine.Live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

@Composable
fun SpoofScreen() {
    val context = LocalContext.current

    // Native engine instances (remember so they aren't recreated every recomposition)
    val faceDetector = remember { FaceDetector() }
    val live = remember { Live() }
    var modelsLoadedText by remember { mutableStateOf("Loading...") }


    // Load models once
    var modelsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val detRes = faceDetector.loadModel(context.assets)
        val liveRes = live.loadModel(context.assets)
        Log.d("SpoofDetect", "FaceDetector loadModel=$detRes, Live loadModel=$liveRes")
        modelsLoadedText = "det=$detRes, live=$liveRes"
        modelsLoaded = (detRes == 0 && liveRes == 0)
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
                            score == 0f -> "No face / low confidence"
                            score > 0.9f -> "REAL FACE ✅"
                            else -> "Possible SPOOF ⚠️"
                        }
                    },
                    faceDetector = faceDetector,
                    live = live
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
                Text("Liveness score: ${"%.3f".format(livenessScore)}",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(statusText)
                if (!modelsLoaded) {
                    Spacer(Modifier.height(8.dp))
                    Text("Loading models...", style = MaterialTheme.typography.bodySmall)
                }

                Text("Models: $modelsLoadedText", style = MaterialTheme.typography.bodySmall)

            }
        }
    }
}

private fun hasCameraPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED


@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(
    onResult: (Float) -> Unit,
    faceDetector: FaceDetector,
    live: Live
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                this.scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(
                            Dispatchers.Default.asExecutor()
                        ) { imageProxy ->
                            try {
                                processFrame(imageProxy, faceDetector, live, onResult)
                            } catch (e: Exception) {
                                Log.e("SpoofDetect", "Analyzer error", e)
                            } finally {
                                imageProxy.close()
                            }
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analyzer
                    )
                } catch (exc: Exception) {
                    Log.e("SpoofDetect", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    )
}


@androidx.annotation.OptIn(ExperimentalGetImage::class)
@OptIn(ExperimentalGetImage::class)
private fun processFrame(
    imageProxy: ImageProxy,
    faceDetector: FaceDetector,
    live: Live,
    onResult: (Float) -> Unit
) {
    val width = imageProxy.width
    val height = imageProxy.height
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

    val nv21 = yuv420888ToNv21(imageProxy)

    // Sanity check on buffer size
    if (nv21.size != width * height * 3 / 2) {
        Log.e("SpoofDetect", "NV21 size mismatch: ${nv21.size} vs expected ${width * height * 3 / 2}")
        onResult(0f)
        return
    }

    // 1️⃣ Face detection
    val faces: List<com.mv.engine.FaceBox> = try {
        faceDetector.detect(
            nv21,
            width,
            height,
            rotationDegrees
        )
    } catch (e: Exception) {
        Log.e("SpoofDetect", "Face detect failed", e)
        emptyList()
    }

    Log.d("SpoofDetect", "Faces detected: ${faces.size}")

    if (faces.isEmpty()) {
        onResult(0f)
        return
    }

    val face = faces[0]
    Log.d("SpoofDetect", "First face: left=${face.left}, top=${face.top}, right=${face.right}, bottom=${face.bottom}")

    // 2️⃣ Liveness
    val score: Float = try {
        live.detect(
            nv21,
            width,
            height,
            rotationDegrees,
            face
        )
    } catch (e: Exception) {
        Log.e("SpoofDetect", "Live detect failed", e)
        0f
    }

    Log.d("SpoofDetect", "Liveness score (raw): $score")

    onResult(score)
}




private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val ySize = width * height
    val uvSize = width * height / 2

    val nv21 = ByteArray(ySize + uvSize)

    // 1️⃣ Copy Y plane row by row, respecting rowStride
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    var outPos = 0

    val yRow = ByteArray(yRowStride)

    for (row in 0 until height) {
        // Read one row from Y plane
        yBuffer.get(yRow, 0, yRowStride)
        // Copy only 'width' bytes (actual pixels), ignore padding
        System.arraycopy(yRow, 0, nv21, outPos, width)
        outPos += width
    }

    // 2️⃣ Interleave V and U into NV21 (VU VU VU...), again respecting strides
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    // We will use absolute indexing on the buffers
    val uBasePos = uBuffer.position()
    val vBasePos = vBuffer.position()

    outPos = ySize

    val chromaHeight = height / 2
    val chromaWidth = width / 2

    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val uIndex = uBasePos + row * uRowStride + col * uPixelStride
            val vIndex = vBasePos + row * vRowStride + col * vPixelStride

            val v = vBuffer.get(vIndex)
            val u = uBuffer.get(uIndex)

            // NV21 expects V then U
            nv21[outPos++] = v
            nv21[outPos++] = u
        }
    }

    return nv21
}

