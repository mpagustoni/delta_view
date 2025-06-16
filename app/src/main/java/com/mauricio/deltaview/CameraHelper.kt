package com.mauricio.deltaview

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.util.Size
import android.view.Surface
import android.view.TextureView
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.math.abs

class CameraHelper(private val context: Context, private val textureView: TextureView) {

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var imageReader: ImageReader
    private var lastBitmap: Bitmap? = null

    @SuppressLint("MissingPermission")
    fun startCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList[0]
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewSize = streamConfigurationMap?.getOutputSizes(ImageFormat.YUV_420_888)?.first() ?: Size(640, 480)

        imageReader = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.YUV_420_888, 2)
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = image.toBitmap()
            image.close()

            val diffBitmap = lastBitmap?.let { subtractBitmaps(bitmap, it) } ?: bitmap
            lastBitmap = bitmap

            val canvas = textureView.lockCanvas()
            canvas?.let {
                it.drawBitmap(diffBitmap, null, Rect(0, 0, textureView.width, textureView.height), null)
                textureView.unlockCanvasAndPost(it)
            }
        }, null)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                cameraDevice = device
                createPreviewSession(previewSize)
            }

            override fun onDisconnected(device: CameraDevice) = device.close()
            override fun onError(device: CameraDevice, error: Int) = device.close()
        }, null)
    }

    fun stopCamera() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader.close()
    }

    private fun createPreviewSession(previewSize: Size) {
        val texture = textureView.surfaceTexture!!
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        val previewSurface = Surface(texture)

        val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequestBuilder.addTarget(previewSurface)
        captureRequestBuilder.addTarget(imageReader.surface)

        cameraDevice?.createCaptureSession(listOf(previewSurface, imageReader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, null)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, null)
    }

    private fun Image.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val ySize = yBuffer.remaining()
        val yData = ByteArray(ySize)
        yBuffer.get(yData)

        val yuvImage = YuvImage(yData, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun subtractBitmaps(current: Bitmap, previous: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
        for (x in 0 until current.width step 2) {
            for (y in 0 until current.height step 2) {
                val cPixel = current.getPixel(x, y)
                val pPixel = previous.getPixel(x, y)
                val r = abs(Color.red(cPixel) - Color.red(pPixel))
                val g = abs(Color.green(cPixel) - Color.green(pPixel))
                val b = abs(Color.blue(cPixel) - Color.blue(pPixel))
                result.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return result
    }
}
