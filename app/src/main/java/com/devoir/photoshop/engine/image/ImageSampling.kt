package com.devoir.photoshop.engine.image

object ImageSampling {
    /** Smallest power-of-two sample size so the decoded bitmap fits both limits. */
    fun sampleSize(width: Int, height: Int, maxSide: Int, maxPixels: Long): Int {
        var s = 1
        while (s < (1 shl 16)) {
            val sw = (width + s - 1) / s
            val sh = (height + s - 1) / s
            if (sw <= maxSide && sh <= maxSide && sw.toLong() * sh <= maxPixels) return s
            s *= 2
        }
        return s
    }
}
