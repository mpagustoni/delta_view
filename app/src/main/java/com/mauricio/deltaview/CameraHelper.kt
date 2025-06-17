package com.mauricio.deltaview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.widget.ImageView

class CameraHelper(private val context: Context, private val imageView: ImageView) {

    private var cameraDevice: CameraDevice? = null
    private lateinit var cameraCaptureSession: CameraCaptureSession
    private lateinit var imageReader: ImageReader
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var handler: Handler
    private var backgroundThread: HandlerThread = HandlerThread("CameraThread")
    private var previousBitmap: Bitmap? = null

    init {
        backgroundThread.start()
        handler = Handler(backgroundThread.looper)
    }

    @SuppressLint("MissingPermission")
    fun startCamera() {
        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSize = streamConfigurationMap?.getOutputSizes(ImageFormat.JPEG)?.first() ?: Size(640, 480)

        imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.JPEG, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            image?.let {
                val buffer = it.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val currentBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                it.close()

                val processedBitmap = previousBitmap?.let { prev ->
                    subtractBitmaps(prev, currentBitmap)
                } ?: currentBitmap

                previousBitmap = currentBitmap

                imageView.post {
                    imageView.setImageBitmap(processedBitmap)
                }
            }
        }, handler)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                val surface = imageReader.surface
                val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                requestBuilder.addTarget(surface)

                camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        cameraCaptureSession = session
                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, handler)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
            }
        }, handler)
    }

    private fun subtractBitmaps(b1: Bitmap, b2: Bitmap): Bitmap {
        val width = b1.width.coerceAtMost(b2.width)
        val height = b1.height.coerceAtMost(b2.height)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels1 = IntArray(width * height)
        val pixels2 = IntArray(width * height)
        b1.getPixels(pixels1, 0, width, 0, 0, width, height)
        b2.getPixels(pixels2, 0, width, 0, 0, width, height)

        val resultPixels = IntArray(width * height)
        for (i in pixels1.indices) {
            val r1 = (pixels1[i] shr 16) and 0xff
            val g1 = (pixels1[i] shr 8) and 0xff
            val b1c = pixels1[i] and 0xff

            val r2 = (pixels2[i] shr 16) and 0xff
            val g2 = (pixels2[i] shr 8) and 0xff
            val b2c = pixels2[i] and 0xff

            val r = (r1 - r2).coerceIn(0, 255)
            val g = (g1 - g2).coerceIn(0, 255)
            val b = (b1c - b2c).coerceIn(0, 255)

            resultPixels[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)
        return result
    }

    fun stopCamera() {
        cameraCaptureSession.close()
        cameraDevice?.close()
        imageReader.close()
        backgroundThread.quitSafely()
    }
}
