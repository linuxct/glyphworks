package space.linuxct.glyphworks.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import space.linuxct.glyphworks.core.DebugLog
import space.linuxct.glyphworks.core.ai.ChatWire
import space.linuxct.glyphworks.core.ai.ImageQuantiser
import space.linuxct.glyphworks.core.ai.ImageScale
import space.linuxct.glyphworks.core.ai.SourceImage
import java.io.ByteArrayOutputStream

/** One photo the user picked, built at attach time so a bad image is reported at once. */
class AttachedImage(
    val id: Long,
    val dataUrl: String,
    val thumbnail: Bitmap?,
    val source: SourceImage?,
)

/** A picked image as a JPEG data URL, or null if it cannot be read. Never throws. */
internal fun readAttachment(context: Context, uri: Uri, id: Long): AttachedImage? = try {
    val bounds = decodeBounds(context, uri)
    val bitmap = decodeScaled(context, uri, bounds)
    if (bitmap == null) {
        null
    } else {
        val oriented = applyExifRotation(context, uri, bitmap)
        val sized = scaleToCap(oriented)
        val base64 = ByteArrayOutputStream().use { out ->
            if (!sized.compress(Bitmap.CompressFormat.JPEG, ImageScale.JPEG_QUALITY, out)) {
                return null
            }
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        val thumb = thumbnailOf(sized)
        AttachedImage(
            id = id,
            dataUrl = ChatWire.imageDataUrl(base64),
            thumbnail = thumb,
            source = luminanceOf(sized),
        )
    }
} catch (e: Exception) {
    DebugLog.w(TAG, "could not attach an image: ${e.javaClass.simpleName}: ${e.message}")
    null
} catch (e: OutOfMemoryError) {
    DebugLog.w(TAG, "out of memory decoding an attachment")
    null
}

private fun decodeBounds(context: Context, uri: Uri): BitmapFactory.Options {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    return options
}

private fun decodeScaled(context: Context, uri: Uri, bounds: BitmapFactory.Options): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inSampleSize = ImageScale.sampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, options)
    }
}

// BitmapFactory drops the EXIF orientation tag, so a portrait photo arrives on its side.
// The platform ExifInterface is fully qualified so the suppression covers the reference.
@Suppress("ExifInterface")
private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = try {
        context.contentResolver.openInputStream(uri)?.use {
            android.media.ExifInterface(it).getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION,
                android.media.ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: android.media.ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        android.media.ExifInterface.ORIENTATION_NORMAL
    }
    val matrix = Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        else -> return bitmap
    }
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: Exception) {
        bitmap
    } catch (e: OutOfMemoryError) {
        bitmap
    }
}

private fun scaleToCap(bitmap: Bitmap): Bitmap {
    if (!ImageScale.needsScaling(bitmap.width, bitmap.height)) return bitmap
    val (w, h) = ImageScale.targetSize(bitmap.width, bitmap.height)
    return Bitmap.createScaledBitmap(bitmap, w, h, true)
}

private fun luminanceOf(bitmap: Bitmap): SourceImage? = try {
    val (w, h) = ImageScale.targetSize(bitmap.width, bitmap.height, ImageQuantiser.SOURCE_EDGE)
    val small = if (w == bitmap.width && h == bitmap.height) {
        bitmap
    } else {
        Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
    val pixels = IntArray(w * h)
    small.getPixels(pixels, 0, w, 0, 0, w, h)
    val luminance = IntArray(pixels.size)
    for (i in pixels.indices) {
        val p = pixels[i]
        val red = (p shr 16) and 0xFF
        val green = (p shr 8) and 0xFF
        val blue = p and 0xFF
        luminance[i] =
            (REC601_RED * red + REC601_GREEN * green + REC601_BLUE * blue) shr REC601_SHIFT
    }
    SourceImage(width = w, height = h, luminance = luminance)
} catch (e: Exception) {
    DebugLog.w(TAG, "could not measure an attachment's brightness: ${e.message}")
    null
} catch (e: OutOfMemoryError) {
    null
}

private fun thumbnailOf(bitmap: Bitmap): Bitmap? = try {
    val (w, h) = ImageScale.targetSize(bitmap.width, bitmap.height, THUMBNAIL_EDGE_PX)
    Bitmap.createScaledBitmap(bitmap, w, h, true)
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

private const val THUMBNAIL_EDGE_PX = 192

// Rec. 601 luma weights, as 8-bit fixed point. A plain channel mean makes a blue sky read
// as bright as a green field.
private const val REC601_RED = 77
private const val REC601_GREEN = 151
private const val REC601_BLUE = 28
private const val REC601_SHIFT = 8

private const val TAG = "GlyphAiImages"
