package com.example.spoofdetect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mv.engine.FaceLandmarkDetector
import com.mv.engine.LiveDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import java.io.ByteArrayOutputStream


@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(
    onResult: (Float) -> Unit,
    faceLandmarkDetector: FaceLandmarkDetector,
    liveDetector: LiveDetector
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
                val preview = Preview.Builder().build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(Dispatchers.Default.asExecutor()) { imageProxy ->
                            try {
                                processFrame(imageProxy, faceLandmarkDetector, liveDetector, onResult)
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
    faceLandmarkDetector: FaceLandmarkDetector,
    liveDetector: LiveDetector,
    onResult: (Float) -> Unit
) {
    val width = imageProxy.width
    val height = imageProxy.height
    val rotationDegrees = imageProxy.imageInfo.rotationDegrees

    //  NV21 → Bitmap with rotation
    val bitmap = nv21ToBitmap(yuv420888ToNv21(imageProxy), width, height, rotationDegrees)

    //  MediaPipe → faceRect
    val faceRect = faceLandmarkDetector.detectFaceRect(bitmap)

    if (faceRect == null) {
        Log.d("SpoofDetect", "No face found")
        bitmap.recycle()
        onResult(0f)
        return
    }

    //  ONNX liveness
    val score = try {
        liveDetector.getLivenessScore(bitmap, faceRect)
    } catch (e: Exception) {
        Log.e("SpoofDetect", "Liveness failed", e)
        0f
    }

    bitmap.recycle()
    onResult(score)
}


private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int, rotationDegrees: Int): Bitmap {
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
    val jpegBytes = out.toByteArray()
    var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

    if (rotationDegrees != 0) {
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    return bitmap
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

    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    var outPos = 0
    val yRow = ByteArray(yRowStride)

    for (row in 0 until height) {
        yBuffer.get(yRow, 0, yRowStride)
        System.arraycopy(yRow, 0, nv21, outPos, width)
        outPos += width
    }

    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride
    val uBasePos = uBuffer.position()
    val vBasePos = vBuffer.position()

    outPos = ySize
    val chromaHeight = height / 2
    val chromaWidth = width / 2

    for (row in 0 until chromaHeight) {
        for (col in 0 until chromaWidth) {
            val uIndex = uBasePos + row * uRowStride + col * uPixelStride
            val vIndex = vBasePos + row * vRowStride + col * vPixelStride
            nv21[outPos++] = vBuffer.get(vIndex)
            nv21[outPos++] = uBuffer.get(uIndex)
        }
    }

    return nv21
}
