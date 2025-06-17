package com.mauricio.camerapreviewapp
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type

class YuvToRgbConverter(context: Context) {
    private val rs = RenderScript.create(context)
    private var scriptYuvToRgb: ScriptIntrinsicYuvToRGB = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    private var yuvBits: ByteArray? = null
    private var inAllocation: Allocation? = null
    private var outAllocation: Allocation? = null

    fun yuvToRgb(image: Image, output: Bitmap) {
        val yuvBuffer = imageToByteArray(image)
        if (inAllocation == null || inAllocation!!.bytesSize != yuvBuffer.size) {
            val elemType = Type.Builder(rs, Element.U8(rs)).setX(yuvBuffer.size)
            inAllocation = Allocation.createTyped(rs, elemType.create(), Allocation.USAGE_SCRIPT)
        }
        if (outAllocation == null || outAllocation!!.type.x != output.width || outAllocation!!.type.y != output.height) {
            outAllocation = Allocation.createFromBitmap(rs, output)
        }
        inAllocation!!.copyFrom(yuvBuffer)
        scriptYuvToRgb.setInput(inAllocation)
        scriptYuvToRgb.forEach(outAllocation)
        outAllocation!!.copyTo(output)
    }

    private fun imageToByteArray(image: Image): ByteArray {
        val ySize = image.planes[0].buffer.remaining()
        val uSize = image.planes[1].buffer.remaining()
        val vSize = image.planes[2].buffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        image.planes[0].buffer.get(nv21, 0, ySize)
        image.planes[2].buffer.get(nv21, ySize, vSize)
        image.planes[1].buffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }
}
