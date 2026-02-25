package com.mv.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import java.nio.FloatBuffer

class LiveDetector(context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std  = floatArrayOf(0.229f, 0.224f, 0.225f)

    companion object {
        const val SCALE_FACTOR = 2.7f   // expand bounding box by this
        const val MODEL_SIZE   = 80     // model input = 80x80
    }

    init {
        val modelBytes = context.assets.open("2.7_80x80_MiniFASNetV2.onnx").readBytes()
        session = env.createSession(modelBytes)
    }


    fun getLivenessScore(fullFrame: Bitmap, faceRect: Rect): Float {

        Log.d("LiveDetector", "Input rect: $faceRect, size: ${faceRect.width()}x${faceRect.height()}")
        Log.d("LiveDetector", "Frame size: ${fullFrame.width}x${fullFrame.height}")

        // Rect is already the 2.7x expanded square — just crop it
        val crop = Bitmap.createBitmap(
            fullFrame,
            faceRect.left,
            faceRect.top,
            faceRect.width(),
            faceRect.height()
        )

        // Resize to 80x80
        val resized = Bitmap.createScaledBitmap(crop, MODEL_SIZE, MODEL_SIZE, true)

        // Convert to tensor — NO ImageNet normalization, just 0-1
        val tensor = bitmapToTensor(resized)

        val inputName = session.inputNames.iterator().next()
        val onnxTensor = OnnxTensor.createTensor(
            env, tensor, longArrayOf(1, 3, MODEL_SIZE.toLong(), MODEL_SIZE.toLong())
        )
        val output = session.run(mapOf(inputName to onnxTensor))

        val logits = (output[0].value as Array<FloatArray>)[0]
        Log.d("LiveDetector", "Raw logits: [${logits[0]}, ${logits[1]}, ${logits[2]}]")

        val probs = softmax(logits)
        Log.d("LiveDetector", "Probs: [${probs[0]}, ${probs[1]}, ${probs[2]}]")
        Log.d("LiveDetector", "Decision: ${if (probs[1] >= 0.5f) "✅ LIVE" else "❌ SPOOF"}")

        onnxTensor.close()
        output.close()
        crop.recycle()
        resized.recycle()

        return probs[1]
    }

    private fun bitmapToTensor(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(3 * MODEL_SIZE * MODEL_SIZE)
        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        bitmap.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        val samplePixel = pixels[pixels.size / 2]
        Log.d("LiveDetector", "Sample pixel RGB: r=${(samplePixel shr 16) and 0xFF}, g=${(samplePixel shr 8) and 0xFF}, b=${samplePixel and 0xFF}")

        // RAW values 0-255, no normalization
        for (pixel in pixels) buffer.put(((pixel shr 16) and 0xFF).toFloat())  // R
        for (pixel in pixels) buffer.put(((pixel shr 8) and 0xFF).toFloat())   // G
        for (pixel in pixels) buffer.put((pixel and 0xFF).toFloat())            // B

        buffer.rewind()
        return buffer
    }


    private fun softmax(logits: FloatArray): FloatArray {
        val maxVal = logits.max()
        val exp = logits.map { Math.exp((it - maxVal).toDouble()).toFloat() }
        val sum = exp.sum()
        return exp.map { it / sum }.toFloatArray()
    }

    private fun clampRect(rect: Rect, maxW: Int, maxH: Int): Rect {
        val left = rect.left.coerceIn(0, maxW - 1)
        val top = rect.top.coerceIn(0, maxH - 1)
        val right = rect.right.coerceIn(left + 1, maxW)
        val bottom = rect.bottom.coerceIn(top + 1, maxH)
        return Rect(left, top, right, bottom)
    }


    fun close() {
        session.close()
        env.close()
    }
}