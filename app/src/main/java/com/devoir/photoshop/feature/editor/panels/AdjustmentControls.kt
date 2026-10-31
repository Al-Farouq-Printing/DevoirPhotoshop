package com.devoir.photoshop.feature.editor.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.devoir.photoshop.engine.adjust.AdjustmentState
import com.devoir.photoshop.engine.adjust.FilterPreset
import com.devoir.photoshop.engine.layers.Layer
import com.devoir.photoshop.engine.layers.LayerContent
import com.devoir.photoshop.feature.editor.LayerActions
import com.devoir.photoshop.feature.editor.canvas.toColorFilter
import kotlin.math.roundToInt

private class SliderDef(
    val label: String, val lo: String, val hi: String, val range: IntRange, val unit: String,
    val get: (AdjustmentState) -> Int, val set: (AdjustmentState, Int) -> AdjustmentState,
)

private val SliderDefs = listOf(
    SliderDef("Brightness", "-100", "+100", -100..100, "", { it.brightness }, { s, v -> s.copy(brightness = v) }),
    SliderDef("Contrast", "-100", "+100", -100..100, "", { it.contrast }, { s, v -> s.copy(contrast = v) }),
    SliderDef("Saturation", "-100", "+100", -100..100, "", { it.saturation }, { s, v -> s.copy(saturation = v) }),
    SliderDef("Exposure", "-100", "+100", -100..100, "", { it.exposure }, { s, v -> s.copy(exposure = v) }),
    SliderDef("Hue", "-180°", "+180°", -180..180, "°", { it.hue }, { s, v -> s.copy(hue = v) }),
    SliderDef("Temperature", "Cool", "Warm", -100..100, "", { it.temperature }, { s, v -> s.copy(temperature = v) }),
    SliderDef("Tint", "Green", "Magenta", -100..100, "", { it.tint }, { s, v -> s.copy(tint = v) }),
    SliderDef("Highlights", "-100", "+100", -100..100, "", { it.highlights }, { s, v -> s.copy(highlights = v) }),
    SliderDef("Shadows", "-100", "+100", -100..100, "", { it.shadows }, { s, v -> s.copy(shadows = v) }),
    SliderDef("Sharpness", "0", "100", 0..100, "", { it.sharpness }, { s, v -> s.copy(sharpness = v) }),
)

@Composable
private fun CompatMessage(layer: Layer?) {
    Text(
        if (layer == null) "Select an image layer to use adjustments and filters."
        else "Adjustments and filters apply to image layers. \"${layer.name}\" is a ${layer.type.label.lowercase()} layer and is left untouched.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Ten live sliders for the selected image layer, plus the reset actions. Tap a value to reset that slider. */
@Composable
fun AdjustmentSliders(layer: Layer?, actions: LayerActions) {
    val content = layer?.content as? LayerContent.Image
    if (layer == null || content == null) { CompatMessage(layer); return }
    val adj = content.adjustments
    val enabled = !layer.locked
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (layer.locked) {
            Text("Layer is locked — unlock it to adjust it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        SliderDefs.forEach { d ->
            AdjustSlider(d, d.get(adj), enabled) { v -> actions.onSetAdjustments(layer.id, d.set(adj, v)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { actions.onResetAdjustValues(layer.id) }, enabled = enabled && adj != adj.withoutValues(),
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Reset adjustments", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            OutlinedButton(
                onClick = { actions.onResetFilter(layer.id) }, enabled = enabled && adj.filter != FilterPreset.Original,
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Reset filter", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { actions.onResetAllAdjust(layer.id) }, enabled = enabled && !adj.isDefault,
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Reset all", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            OutlinedButton(
                onClick = { actions.onRevertAdjust(layer.id) }, enabled = enabled,
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Revert changes", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun AdjustSlider(def: SliderDef, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(def.label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            val shown = if (value > 0 && def.range.first < 0) "+$value${def.unit}" else "$value${def.unit}"
            Text(
                shown,
                Modifier.clickable(enabled = enabled && value != 0) { onChange(0) },
                style = MaterialTheme.typography.labelMedium,
                color = if (value != 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
            valueRange = def.range.first.toFloat()..def.range.last.toFloat(), enabled = enabled,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(def.lo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(def.hi, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Filter presets with previews. Each preview is the layer's small (<=160 px) thumbnail drawn with that
 * filter's colour matrix on the GPU: no bitmap is created for any tile.
 */
@Composable
fun FilterStrip(layer: Layer?, actions: LayerActions) {
    val content = layer?.content as? LayerContent.Image
    if (layer == null || content == null) { CompatMessage(layer); return }
    val res = content.resource
    if (res.isReleased) return
    val enabled = !layer.locked
    val image: ImageBitmap = remember(res) { (res.thumbnail ?: res.bitmap).asImageBitmap() }
    val adj = content.adjustments
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (layer.locked) {
            Text("Layer is locked — unlock it to apply a filter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterPreset.entries.forEach { f ->
                FilterTile(f, image, selected = adj.filter == f, enabled = enabled) {
                    actions.onSetAdjustments(layer.id, adj.copy(filter = f))
                }
            }
        }
        OutlinedButton(
            onClick = { actions.onResetFilter(layer.id) },
            enabled = enabled && adj.filter != FilterPreset.Original,
        ) { Text("Reset filter (keep adjustments)", style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun FilterTile(preset: FilterPreset, image: ImageBitmap, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = remember(preset) { AdjustmentState(filter = preset).toColorFilter() }
    Column(
        Modifier.width(72.dp).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).border(
                if (selected) 3.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(8.dp),
            ),
        ) {
            Image(image, preset.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, colorFilter = tint)
        }
        Text(preset.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** Compact-phone strip for the Adjustments tool. */
@Composable
fun AdjustQuickBar(layer: Layer?, onOpen: () -> Unit, actions: LayerActions) {
    val content = layer?.content as? LayerContent.Image
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (layer == null || content == null) {
            Text("Select an image layer to adjust it", style = MaterialTheme.typography.labelMedium)
        } else {
            Button(onClick = onOpen) { Text("Open adjustments") }
            OutlinedButton(
                onClick = { actions.onResetAllAdjust(layer.id) },
                enabled = !layer.locked && !content.adjustments.isDefault,
            ) { Text("Reset all") }
        }
    }
}
