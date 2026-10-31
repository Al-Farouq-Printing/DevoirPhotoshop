package com.devoir.photoshop.feature.newproject

import androidx.lifecycle.ViewModel
import com.devoir.photoshop.core.project.ConfigResult
import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.core.project.SizePreset
import com.devoir.photoshop.model.BackgroundType
import com.devoir.photoshop.model.CanvasOrientation
import com.devoir.photoshop.model.SizeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NewProjectForm(
    val name: String = "Untitled Project",
    val preset: SizePreset = SizePreset.Square,
    val widthText: String = "1080",
    val heightText: String = "1080",
    val unit: SizeUnit = SizeUnit.Pixels,
    val dpiText: String = ProjectMath.DEFAULT_DPI.toString(),
    val background: BackgroundType = BackgroundType.White,
    val colorHex: String = "FF5722",
) {
    val result: ConfigResult
        get() = ProjectMath.buildConfig(widthText, heightText, unit, dpiText, background, colorHex)

    /** Orientation implied by the numbers currently typed (null if they aren't numbers yet). */
    val orientation: CanvasOrientation?
        get() {
            val w = ProjectMath.parseNumber(widthText) ?: return null
            val h = ProjectMath.parseNumber(heightText) ?: return null
            return when {
                w == h -> CanvasOrientation.Square
                w > h -> CanvasOrientation.Landscape
                else -> CanvasOrientation.Portrait
            }
        }
}

class NewProjectViewModel : ViewModel() {
    private val _form = MutableStateFlow(NewProjectForm())
    val form: StateFlow<NewProjectForm> = _form.asStateFlow()

    fun setName(v: String) = _form.update { it.copy(name = v) }
    fun setWidth(v: String) = _form.update { it.copy(widthText = v, preset = SizePreset.Custom) }
    fun setHeight(v: String) = _form.update { it.copy(heightText = v, preset = SizePreset.Custom) }
    fun setDpi(v: String) = _form.update { it.copy(dpiText = v) }
    fun setBackground(v: BackgroundType) = _form.update { it.copy(background = v) }
    fun setColor(v: String) = _form.update { it.copy(colorHex = v.removePrefix("#").take(6)) }

    fun applyPreset(p: SizePreset) = _form.update {
        if (p == SizePreset.Custom) it.copy(preset = p)
        else it.copy(
            preset = p, unit = p.unit,
            widthText = ProjectMath.formatValue(p.width, p.unit),
            heightText = ProjectMath.formatValue(p.height, p.unit),
        )
    }

    /** Changing the unit keeps the canvas pixel size (using the current DPI) and re-expresses it. */
    fun setUnit(unit: SizeUnit) = _form.update { f ->
        val r = f.result
        if (r is ConfigResult.Valid) {
            val c = r.config
            f.copy(
                unit = unit,
                widthText = ProjectMath.formatValue(ProjectMath.fromPixels(c.widthPx, unit, c.dpi), unit),
                heightText = ProjectMath.formatValue(ProjectMath.fromPixels(c.heightPx, unit, c.dpi), unit),
            )
        } else f.copy(unit = unit)
    }

    fun setOrientation(o: CanvasOrientation) = _form.update { f ->
        val w = ProjectMath.parseNumber(f.widthText)
        val h = ProjectMath.parseNumber(f.heightText)
        if (w == null || h == null) f
        else when (o) {
            CanvasOrientation.Square -> f.copy(heightText = f.widthText, preset = SizePreset.Custom)
            CanvasOrientation.Portrait ->
                if (w > h) f.copy(widthText = f.heightText, heightText = f.widthText, preset = SizePreset.Custom) else f
            CanvasOrientation.Landscape ->
                if (h > w) f.copy(widthText = f.heightText, heightText = f.widthText, preset = SizePreset.Custom) else f
        }
    }
}
