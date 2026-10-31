package com.devoir.photoshop.engine.adjust

import android.graphics.Bitmap
import com.devoir.photoshop.engine.layers.BitmapResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Stage A of the pipeline (highlights, shadows, sharpness). The colour stage runs on the GPU with no
 * bitmap at all; this stage needs real pixel work, so it is cached and kept off the main thread:
 *
 *  1. On every change a small preview (max 768 px) is processed into one of two reusable buffers and
 *     shown at once (tens of ms). The original is never modified.
 *  2. After the sliders settle (~300 ms) the full-resolution result is produced in horizontal bands
 *     (so only a few MB of temporary arrays exist) into ONE new bitmap, which replaces the old one.
 *
 * It holds its own reference to the source image, never copies it, frees everything when the effect is
 * switched off or the layer goes away, and refuses full-size work that does not fit the memory budget.
 * Public methods are main-thread only.
 */
class AdjustmentProcessor(
    private val scope: CoroutineScope,
    private val remainingBytes: () -> Long,
    private val onChanged: () -> Unit,
    private val notice: (String) -> Unit,
) {
    class Processed(val bitmap: Bitmap, val resource: BitmapResource, val key: TonalParams, val full: Boolean)

    private class Entry(val source: BitmapResource) {
        var wanted: TonalParams? = null
        var job: Job? = null
        var base: BitmapResource? = null
        var bufA: BitmapResource? = null
        var bufB: BitmapResource? = null
        var fullRes: BitmapResource? = null
        var current: Processed? = null
        var warnedMemory = false
    }

    private val entries = HashMap<String, Entry>()

    val bytes: Long
        get() = entries.values.sumOf { e ->
            listOfNotNull(e.base, e.bufA, e.bufB, e.fullRes).filter { !it.isReleased }.sumOf { it.byteCount }
        }

    fun processed(layerId: String): Processed? = entries[layerId]?.current?.takeIf { !it.resource.isReleased }

    /** Idempotent: asking again for what is already shown or in flight does nothing. */
    fun request(layerId: String, source: BitmapResource, params: TonalParams) {
        if (params.isIdentity || source.isReleased) { forget(layerId); return }
        var e = entries[layerId]
        if (e != null && e.source !== source) { dispose(e); entries.remove(layerId); e = null }
        if (e == null) { e = Entry(source.retain()); entries[layerId] = e }
        if (e.wanted == params) return
        e.wanted = params
        e.job?.cancel()
        val entry = e
        entry.job = scope.launch { run(layerId, entry, params) }
    }

    fun retainOnly(live: Set<String>) {
        entries.keys.filter { it !in live }.forEach { forget(it) }
    }

    fun forget(layerId: String) {
        val e = entries.remove(layerId) ?: return
        dispose(e)
        onChanged()
    }

    fun releaseAll() {
        entries.values.forEach { dispose(it) }
        entries.clear()
    }

    private fun dispose(e: Entry) {
        e.job?.cancel()
        e.wanted = null
        e.current = null
        listOfNotNull(e.base, e.bufA, e.bufB, e.fullRes).forEach { it.release() }
        e.base = null; e.bufA = null; e.bufB = null; e.fullRes = null
        e.source.release()
    }

    private suspend fun run(layerId: String, e: Entry, p: TonalParams) {
        try {
            val src = e.source.bitmap
            val smallSource = max(src.width, src.height) <= PREVIEW_SIDE

            // 1. fast preview
            var baseBmp: Bitmap = src
            if (!smallSource) {
                val existing = e.base
                if (existing != null && !existing.isReleased) {
                    baseBmp = existing.bitmap
                } else {
                    val scaled = withContext(Dispatchers.Default) { scaleDown(src) }
                    if (scaled == null) { notice("Not enough memory to preview this adjustment."); return }
                    e.base = BitmapResource(scaled)
                    baseBmp = scaled
                }
            }
            val buf = obtainBuffer(e, baseBmp.width, baseBmp.height)
            if (buf == null) { notice("Not enough memory to preview this adjustment."); return }
            val ok = withContext(Dispatchers.Default) { process(baseBmp, buf.bitmap, p) { isActive } }
            if (!ok) return
            publish(layerId, e, Processed(buf.bitmap, buf, p, full = smallSource))
            if (smallSource) return

            // 2. full resolution, once the sliders have settled
            delay(FULL_DELAY_MS)
            if (remainingBytes() < e.source.byteCount) {
                if (!e.warnedMemory) {
                    e.warnedMemory = true
                    notice("Not enough memory for full-quality tone/sharpness; showing a reduced-resolution result.")
                }
                return
            }
            val full = withContext(Dispatchers.Default) { processToNew(src, p) { isActive } } ?: return
            publish(layerId, e, Processed(full.bitmap, full, p, full = true))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            // out of memory or a source recycled mid-way: keep showing whatever is already there
        }
    }

    private fun publish(layerId: String, e: Entry, p: Processed) {
        if (entries[layerId] !== e || e.wanted != p.key) return // superseded or disposed
        val isBuffer = p.resource === e.bufA || p.resource === e.bufB
        val oldFull = e.fullRes
        if (!isBuffer) {
            e.fullRes = p.resource
            if (oldFull !== p.resource) oldFull?.release()
        } else if (oldFull != null) {
            // back to a preview: the stale full-size bitmap is no longer needed
            oldFull.release()
            e.fullRes = null
        }
        e.current = p
        onChanged()
    }

    /** Alternates two buffers so the one being displayed is never the one being written. */
    private fun obtainBuffer(e: Entry, w: Int, h: Int): BitmapResource? {
        val useB = e.bufA != null && e.current?.resource === e.bufA
        val existing = if (useB) e.bufB else e.bufA
        if (existing != null && !existing.isReleased && existing.bitmap.width == w && existing.bitmap.height == h) return existing
        existing?.release()
        val created = try {
            BitmapResource(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        } catch (oom: OutOfMemoryError) {
            null
        }
        if (useB) e.bufB = created else e.bufA = created
        return created
    }

    private fun scaleDown(src: Bitmap): Bitmap? {
        val f = PREVIEW_SIDE.toFloat() / max(src.width, src.height)
        return try {
            Bitmap.createScaledBitmap(src, max(1, (src.width * f).toInt()), max(1, (src.height * f).toInt()), true)
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    private fun processToNew(src: Bitmap, p: TonalParams, active: () -> Boolean): BitmapResource? {
        val out = try {
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            return null
        }
        if (!process(src, out, p, active)) { out.recycle(); return null }
        return BitmapResource(out)
    }

    /** Band-wise: only ~1 MB of temporary arrays regardless of image size. Returns false if cancelled. */
    private fun process(src: Bitmap, dst: Bitmap, p: TonalParams, active: () -> Boolean): Boolean {
        val w = src.width
        val h = src.height
        val lut = PixelOps.tonalLut(p.highlights, p.shadows)
        val tonal = p.highlights != 0 || p.shadows != 0
        val sharpen = p.sharpness != 0
        val amount = PixelOps.sharpAmount(p.sharpness)
        val halo = if (sharpen) 1 else 0
        val band = max(8, BAND_PIXELS / w)
        val inBuf = IntArray((band + 2 * halo) * w)
        val outBuf = IntArray(band * w)
        var y0 = 0
        while (y0 < h) {
            if (!active()) return false
            val rows = min(band, h - y0)
            val top = max(0, y0 - halo)
            val bottom = min(h, y0 + rows + halo)
            val srcRows = bottom - top
            src.getPixels(inBuf, 0, w, 0, top, w, srcRows)
            if (tonal) PixelOps.applyTonal(inBuf, srcRows * w, lut)
            if (sharpen) PixelOps.sharpenRows(inBuf, w, srcRows, y0 - top, rows, amount, outBuf)
            else System.arraycopy(inBuf, (y0 - top) * w, outBuf, 0, rows * w)
            dst.setPixels(outBuf, 0, w, 0, y0, w, rows)
            y0 += rows
        }
        return true
    }

    private companion object {
        const val PREVIEW_SIDE = 768
        const val FULL_DELAY_MS = 300L
        const val BAND_PIXELS = 262_144
    }
}
