package com.mv.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker

class FaceLandmarkDetector(context: Context) {

    private val faceLandmarker: FaceLandmarker

    init {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath("face_landmarker.task")
                    .build()
            )
            .setNumFaces(1)
            .setRunningMode(RunningMode.IMAGE)
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    /**
     * Returns the 2.7x scaled square crop rect
     * Returns null if crop goes outside frame bounds
     */
    fun detectFaceRect(bitmap: Bitmap): Rect? {
        val mpImage = BitmapImageBuilder(bitmap).build()
        val result = faceLandmarker.detect(mpImage)

        if (result.faceLandmarks().isEmpty()) {
            Log.d("FaceLandmarkDetector", "No face detected")
            return null
        }

        val landmarks = result.faceLandmarks()[0]
        val imageW = bitmap.width
        val imageH = bitmap.height

        Log.d("FaceLandmarkDetector", "Bitmap dimensions: ${imageW}x${imageH}")

        val xs = landmarks.map { (it.x() * imageW).toInt() }
        val ys = landmarks.map { (it.y() * imageH).toInt() }

        val xMin = xs.min()
        val xMax = xs.max()
        val yMin = ys.min()
        val yMax = ys.max()

        val cx = (xMin + xMax) / 2.0
        val cy = (yMin + yMax) / 2.0
        val side = maxOf(xMax - xMin, yMax - yMin)
        val scaledSide = (side * 2.7).toInt()

        Log.d("FaceLandmarkDetector", "cx=$cx cy=$cy side=$side scaledSide=$scaledSide")

        // Check fits in frame
        val maxAllowed = minOf(imageW, imageH)
        if (scaledSide > maxAllowed) {
            Log.d("FaceLandmarkDetector", "Too close, skipping. scaledSide=$scaledSide maxAllowed=$maxAllowed")
            return null
        }

        // Build square rect
        val half = scaledSide / 2
        val x1 = maxOf(0, (cx - half).toInt())
        val y1 = maxOf(0, (cy - half).toInt())
        val x2 = minOf(imageW, (cx + half).toInt())
        val y2 = minOf(imageH, (cy + half).toInt())

        // CRITICAL CHECK - must be a valid square-ish rect
        val rectW = x2 - x1
        val rectH = y2 - y1

        Log.d("FaceLandmarkDetector", "Rect: ($x1,$y1)-($x2,$y2) size: ${rectW}x${rectH}")

        if (rectW <= 10 || rectH <= 10) {
            Log.d("FaceLandmarkDetector", "Rect too small, skipping")
            return null
        }

        return Rect(x1, y1, x2, y2)
    }

    fun close() {
        faceLandmarker.close()
    }
}