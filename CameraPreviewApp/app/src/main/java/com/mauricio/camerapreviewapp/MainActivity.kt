package com.mauricio.camerapreviewapp

import android.graphics.*
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView
    private lateinit var fpsTextView: TextView
    private var previousFrame: Bitmap? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var yuvToRgbConverter: YuvToRgbConverter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        imageView = findViewById(R.id.imageView)
        fpsTextView = findViewById(R.id.fpsTextView)
        cameraExecutor = Executors.newSingleThreadExecutor()
        yuvToRgbConverter = YuvToRgbConverter(this)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), 1001)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // FPS counter
            val frameCount = AtomicInteger(0)
            val startTime = longArrayOf(System.currentTimeMillis())

            imageAnalysis.setAnalyzer(cameraExecutor, { imageProxy ->
                frameCount.incrementAndGet()
                val now = System.currentTimeMillis()
                val elapsed = now - startTime[0]
                if (elapsed >= 1000) {
                    val fps = frameCount.get()
                    runOnUiThread {
                        fpsTextView.text = "FPS: $fps"
                    }
                    frameCount.set(0)
                    startTime[0] = now
                }

                val bitmap = imageProxyToRgbBitmap(imageProxy)
                if (bitmap != null) {
                    val subtracted = previousFrame?.let { subtractBitmaps(bitmap, it) } ?: bitmap
                    //val mixed = previousFrame?.let { subtractBitmaps(inverted, it) } ?: inverted
                    previousFrame = bitmap

                    // Corrigir rotação conforme o frame (o CameraX dá a rotação exata do frame)
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val correctedBitmap = rotateBitmap(subtracted, rotationDegrees)

                    runOnUiThread {
                        imageView.setImageBitmap(correctedBitmap)
                    }
                }
                imageProxy.close()
            })

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToRgbBitmap(image: ImageProxy): Bitmap? {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            yuvToRgbConverter.yuvToRgb(image.image!!, bitmap)
        } catch (e: Exception) {
            return null
        }
        return bitmap
    }

    private fun invertBitmapColors(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val inverted = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = 255 - Color.red(c)
            val g = 255 - Color.green(c)
            val b = 255 - Color.blue(c)
            pixels[i] = Color.argb(Color.alpha(c), r, g, b)
        }
        inverted.setPixels(pixels, 0, width, 0, 0, width, height)
        return inverted
    }

    private fun mixBitmaps(b1: Bitmap, b2: Bitmap): Bitmap {
        val width = minOf(b1.width, b2.width)
        val height = minOf(b1.height, b2.height)
        val mixed = Bitmap.createBitmap(width, height, b1.config ?: Bitmap.Config.ARGB_8888)
        val p1 = IntArray(width * height)
        val p2 = IntArray(width * height)
        b1.getPixels(p1, 0, width, 0, 0, width, height)
        b2.getPixels(p2, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)
        for (i in p1.indices) {
            val r = (Color.red(p1[i]) + Color.red(p2[i])) / 2
            val g = (Color.green(p1[i]) + Color.green(p2[i])) / 2
            val b = (Color.blue(p1[i]) + Color.blue(p2[i])) / 2
            val a = (Color.alpha(p1[i]) + Color.alpha(p2[i])) / 2
            out[i] = Color.argb(a, r, g, b)
        }
        mixed.setPixels(out, 0, width, 0, 0, width, height)
        return mixed
    }

    private fun subtractBitmaps(b1: Bitmap, b2: Bitmap): Bitmap {
        val width = minOf(b1.width, b2.width)
        val height = minOf(b1.height, b2.height)
        val result = Bitmap.createBitmap(width, height, b1.config ?: Bitmap.Config.ARGB_8888)
        val p1 = IntArray(width * height)
        val p2 = IntArray(width * height)
        b1.getPixels(p1, 0, width, 0, 0, width, height)
        b2.getPixels(p2, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)
        for (i in p1.indices) {
            val r = kotlin.math.abs(Color.red(p1[i]) - Color.red(p2[i]))
            val g = kotlin.math.abs(Color.green(p1[i]) - Color.green(p2[i]))
            val b = kotlin.math.abs(Color.blue(p1[i]) - Color.blue(p2[i]))
            val a = (Color.alpha(p1[i]) + Color.alpha(p2[i])) / 2 // média do alfa, mas pode ser sempre 255
            out[i] = Color.argb(a, r, g, b)
        }
        result.setPixels(out, 0, width, 0, 0, width, height)
        return result
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }
}
