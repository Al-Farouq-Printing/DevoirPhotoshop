package com.devoir.photoshop.engine.adjust

enum class FilterPreset(val label: String) {
    Original("Original"), Grayscale("Grayscale"), Sepia("Sepia"), Invert("Invert"),
    HighContrast("High Contrast"), Cool("Cool"), Warm("Warm"), Vintage("Vintage"), BlackWhite("Black & White"),
}

/** The part of the pipeline that needs real per-pixel work (see [AdjustmentProcessor]). */
data class TonalParams(val highlights: Int = 0, val shadows: Int = 0, val sharpness: Int = 0) {
    val isIdentity: Boolean get() = highlights == 0 && shadows == 0 && sharpness == 0
}

/**
 * Everything the user can change about an image's look. A plain immutable value: the original
 * bitmap is never touched, the renderer applies this on top of it. Because it is a value it can
 * be copied (duplicate layer) and recorded later (undo/redo) without any extra work.
 *
 * Processing order (fixed, documented):
 *  Stage A, pixel pass on the original pixels:  Highlights -> Shadows -> Sharpness
 *  Stage B, one colour matrix (GPU):            Filter -> Exposure -> Temperature -> Tint
 *                                               -> Brightness -> Contrast -> Saturation -> Hue
 */
data class AdjustmentState(
    val filter: FilterPreset = FilterPreset.Original,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val saturation: Int = 0,
    val exposure: Int = 0,
    /** Degrees, -180..180. */
    val hue: Int = 0,
    /** -100 cool .. +100 warm. */
    val temperature: Int = 0,
    /** -100 green .. +100 magenta. */
    val tint: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    /** 0..100. */
    val sharpness: Int = 0,
) {
    val isDefault: Boolean get() = this == DEFAULT
    fun tonal() = TonalParams(highlights, shadows, sharpness)

    /** True when no colour-matrix stage is needed (the layer can be drawn with no colour filter). */
    val isColorIdentity: Boolean
        get() = filter == FilterPreset.Original && brightness == 0 && contrast == 0 && saturation == 0 &&
            exposure == 0 && hue == 0 && temperature == 0 && tint == 0

    /** Same colour settings, tonal fields zeroed: the key for caching colour filters. */
    fun colorOnly() = copy(highlights = 0, shadows = 0, sharpness = 0)

    /** "Reset adjustments": every slider back to 0, the filter stays. */
    fun withoutValues() = AdjustmentState(filter = filter)

    /** "Reset filter": back to Original, the sliders stay. */
    fun withoutFilter() = copy(filter = FilterPreset.Original)

    fun sanitized() = copy(
        brightness = brightness.coerceIn(-100, 100), contrast = contrast.coerceIn(-100, 100),
        saturation = saturation.coerceIn(-100, 100), exposure = exposure.coerceIn(-100, 100),
        hue = hue.coerceIn(-180, 180), temperature = temperature.coerceIn(-100, 100), tint = tint.coerceIn(-100, 100),
        highlights = highlights.coerceIn(-100, 100), shadows = shadows.coerceIn(-100, 100), sharpness = sharpness.coerceIn(0, 100),
    )

    companion object { val DEFAULT = AdjustmentState() }
}
