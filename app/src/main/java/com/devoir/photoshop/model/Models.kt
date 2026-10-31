package com.devoir.photoshop.model

enum class SizeUnit(val label: String, val suffix: String) {
    Pixels("Pixels", "px"),
    Inches("Inches", "in"),
    Centimeters("Centimeters", "cm"),
    Millimeters("Millimeters", "mm"),
}

enum class CanvasOrientation(val label: String) { Portrait("Portrait"), Landscape("Landscape"), Square("Square") }

enum class BackgroundType(val label: String) { Transparent("Transparent"), White("White"), Custom("Custom") }

/**
 * The single source of truth for a document's size. Pixel size is what the canvas uses;
 * unit values + DPI are kept so the project can be shown/exported in physical units.
 */
data class ProjectConfig(
    val widthPx: Int,
    val heightPx: Int,
    val unit: SizeUnit = SizeUnit.Pixels,
    val widthInUnit: Double = widthPx.toDouble(),
    val heightInUnit: Double = heightPx.toDouble(),
    val dpi: Int = 72,
    val backgroundType: BackgroundType = BackgroundType.White,
    /** ARGB. Only meaningful when [backgroundType] is Custom. */
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
) {
    val orientation: CanvasOrientation
        get() = when {
            widthPx == heightPx -> CanvasOrientation.Square
            widthPx > heightPx -> CanvasOrientation.Landscape
            else -> CanvasOrientation.Portrait
        }

    companion object {
        val Default = ProjectConfig(1080, 1080)
    }
}

/** Describes an imported image. The bitmap itself lives in the engine, never in this model. */
data class ImportedImageInfo(
    val uri: String,
    val displayName: String?,
    val mimeType: String?,
    val originalWidth: Int,
    val originalHeight: Int,
    val loadedWidth: Int,
    val loadedHeight: Int,
    val sampleSize: Int,
)

data class Project(
    val id: String,
    val name: String,
    val subtitle: String,
    val colorSeed: Int,
    val config: ProjectConfig = ProjectConfig.Default,
    val importedImage: ImportedImageInfo? = null,
)

data class LayerItem(
    val id: Int,
    val name: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacityPercent: Int = 100,
)

/** Tool registry. Later phases attach real behaviour to each entry. */
enum class EditorTool(val label: String, val description: String) {
    Move("Move", "Reposition layers and selections."),
    Selection("Select", "Marquee, lasso and magic selection."),
    Crop("Crop", "Crop and straighten the canvas."),
    Brush("Brush", "Paint with configurable brushes."),
    Eraser("Eraser", "Erase pixels from the active layer."),
    Text("Text", "Add and edit text layers."),
    Shapes("Shapes", "Draw vector shapes."),
    Transform("Transform", "Scale, rotate, skew and flip."),
    Color("Color", "Pick and manage colors."),
    Filters("Filters", "Apply creative filters."),
    Adjustments("Adjust", "Brightness, contrast, curves and more."),
}
