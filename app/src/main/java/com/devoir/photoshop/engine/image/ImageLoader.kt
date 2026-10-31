package com.devoir.photoshop.engine.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.devoir.photoshop.model.ImportedImageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decodes images from content URIs locally. Never uploads anything. The decode is bounded
 * (max side + pixel budget tied to heap size) and retried at a lower resolution on OOM.
 */
class ImageLoader(private val context: Context) {

    class LoadedImage(val bitmap: Bitmap, val thumbnail: Bitmap?, val info: ImportedImageInfo)

    sealed interface Result {
        data class Success(val image: LoadedImage) : Result
        data class Failure(val message: String) : Result
    }

    /** Size after applying EXIF orientation, plus raw header data. */
    class SourceInfo(val rawWidth: Int, val rawHeight: Int, val mimeType: String?, val exifOrientation: Int) {
        private val swap = exifOrientation in SWAPPING
        val width: Int get() = if (swap) rawHeight else rawWidth
        val height: Int get() = if (swap) rawWidth else rawHeight
    }

    suspend fun readSize(uri: Uri): SourceInfo? = withContext(Dispatchers.IO) { readSourceInfo(uri) }

    suspend fun displayName(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    /** [maxPixelsOverride] lets the caller cap the decode to the remaining memory budget. */
    suspend fun load(uri: Uri, maxPixelsOverride: Long = Long.MAX_VALUE): Result = withContext(Dispatchers.IO) {
        try {
            val src = readSourceInfo(uri)
                ?: return@withContext Result.Failure("Couldn't read this image. The file may be unsupported or corrupted.")
            val maxPixels = minOf(MAX_SIDE.toLong() * MAX_SIDE, Runtime.getRuntime().maxMemory() / 16, maxPixelsOverride)
            var sample = ImageSampling.sampleSize(src.rawWidth, src.rawHeight, MAX_SIDE, maxPixels)

            var decoded: Bitmap? = null
            var attempts = 0
            while (decoded == null) {
                coroutineContext.ensureActive()
                try {
                    decoded = decode(uri, sample)
                        ?: return@withContext Result.Failure("Couldn't decode this image.")
                } catch (e: OutOfMemoryError) {
                    if (++attempts > 3) throw e
                    sample *= 2
                }
            }
            coroutineContext.ensureActive()
            val oriented = applyExif(decoded!!, src.exifOrientation)
            Result.Success(
                LoadedImage(
                    oriented,
                    makeThumbnail(oriented),
                    ImportedImageInfo(
                        uri = uri.toString(),
                        displayName = displayNameBlocking(uri),
                        mimeType = src.mimeType,
                        originalWidth = src.width,
                        originalHeight = src.height,
                        loadedWidth = oriented.width,
                        loadedHeight = oriented.height,
                        sampleSize = sample,
                    ),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            Result.Failure("This image is too large for this device's memory.")
        } catch (e: Exception) {
            Result.Failure("Couldn't open this image.")
        }
    }

    private fun displayNameBlocking(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private fun readSourceInfo(uri: Uri): SourceInfo? {
        val resolver = context.contentResolver
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return SourceInfo(opts.outWidth, opts.outHeight, opts.outMimeType, orientation)
    }

    private fun decode(uri: Uri, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Small preview for the layers panel, built once on the IO thread. Null if the image is already small. */
    private fun makeThumbnail(src: Bitmap): Bitmap? {
        val longest = max(src.width, src.height)
        if (longest <= THUMB_SIDE) return null
        val f = THUMB_SIDE.toFloat() / longest
        return try {
            val t = Bitmap.createScaledBitmap(
                src, max(1, (src.width * f).roundToInt()), max(1, (src.height * f).roundToInt()), true,
            )
            if (t === src) null else t
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun applyExif(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    companion object {
        /** Stays under typical GPU texture limits. */
        const val MAX_SIDE = 4096
        private const val THUMB_SIDE = 160
        private val SWAPPING = setOf(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        )
    }
}
