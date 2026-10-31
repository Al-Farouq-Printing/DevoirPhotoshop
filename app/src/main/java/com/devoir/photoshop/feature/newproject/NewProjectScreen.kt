@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)

package com.devoir.photoshop.feature.newproject

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.devoir.photoshop.core.adaptive.LocalWindowSize
import com.devoir.photoshop.core.project.ConfigResult
import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.core.project.SizePreset
import com.devoir.photoshop.feature.editor.canvas.rememberCheckerBrush
import com.devoir.photoshop.model.BackgroundType
import com.devoir.photoshop.model.CanvasOrientation
import com.devoir.photoshop.model.ProjectConfig
import com.devoir.photoshop.model.SizeUnit
import java.util.Locale

private val Swatches = listOf("FFFFFF", "000000", "F44336", "FF9800", "FFEB3B", "4CAF50", "2196F3", "9C27B0", "607D8B")

@Composable
fun NewProjectScreen(
    onBack: () -> Unit,
    onCreate: (name: String, config: ProjectConfig) -> Unit,
    vm: NewProjectViewModel = viewModel(),
) {
    val form by vm.form.collectAsState()
    val result = remember(form) { form.result }
    val config = (result as? ConfigResult.Valid)?.config
    val errors = result as? ConfigResult.Invalid
    val wide = LocalWindowSize.current.widthSizeClass != WindowWidthSizeClass.Compact

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Project") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = { config?.let { onCreate(form.name.trim().ifBlank { "Untitled Project" }, it) } },
                        enabled = config != null,
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Create project") }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            FormFields(form, errors, vm)
                        }
                        PreviewCard(config, form, Modifier.width(300.dp))
                    }
                } else {
                    PreviewCard(config, form, Modifier.fillMaxWidth())
                    FormFields(form, errors, vm)
                }
            }
        }
    }
}

@Composable
private fun FormFields(form: NewProjectForm, errors: ConfigResult.Invalid?, vm: NewProjectViewModel) {
    OutlinedTextField(
        value = form.name, onValueChange = vm::setName, label = { Text("Project name") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )

    Text("Preset", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SizePreset.entries.forEach { p ->
            FilterChip(selected = form.preset == p, onClick = { vm.applyPreset(p) }, label = { Text(p.label) })
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberField("Width", form.widthText, vm::setWidth, errors?.widthError, Modifier.weight(1f))
        NumberField("Height", form.heightText, vm::setHeight, errors?.heightError, Modifier.weight(1f))
    }

    Text("Unit", style = MaterialTheme.typography.titleSmall)
    ChoiceRow(SizeUnit.entries, form.unit, { it.suffix }, vm::setUnit)

    NumberField("Resolution (DPI)", form.dpiText, vm::setDpi, errors?.dpiError, Modifier.fillMaxWidth(), integer = true)

    Text("Orientation", style = MaterialTheme.typography.titleSmall)
    ChoiceRow(CanvasOrientation.entries, form.orientation, { it.label }, vm::setOrientation)

    Text("Background", style = MaterialTheme.typography.titleSmall)
    ChoiceRow(BackgroundType.entries, form.background, { it.label }, vm::setBackground)
    if (form.background == BackgroundType.Custom) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Swatches.forEach { hex ->
                val selected = form.colorHex.equals(hex, ignoreCase = true)
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(Color(("FF$hex").toLong(16)))
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        )
                        .clickable { vm.setColor(hex) },
                )
            }
        }
        OutlinedTextField(
            value = form.colorHex, onValueChange = vm::setColor, label = { Text("Hex color (RRGGBB)") },
            prefix = { Text("#") }, singleLine = true, isError = errors?.colorError != null,
            supportingText = errors?.colorError?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun NumberField(
    label: String, value: String, onChange: (String) -> Unit, error: String?, modifier: Modifier,
    integer: Boolean = false,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal),
        isError = error != null, supportingText = error?.let { { Text(it) } }, modifier = modifier,
    )
}

@Composable
private fun <T> ChoiceRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label(option), maxLines = 1) }
        }
    }
}

@Composable
private fun PreviewCard(config: ProjectConfig?, form: NewProjectForm, modifier: Modifier) {
    val checker = rememberCheckerBrush(6.dp)
    Card(modifier) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (config == null) {
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Text("Enter valid size values", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .aspectRatio(config.widthPx.toFloat() / config.heightPx)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp))
                            .drawBehind {
                                when (form.background) {
                                    BackgroundType.Transparent -> drawRect(checker)
                                    BackgroundType.White -> drawRect(Color.White)
                                    BackgroundType.Custom -> drawRect(Color(config.backgroundColor))
                                }
                            },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("${config.widthPx} × ${config.heightPx} px", style = MaterialTheme.typography.titleSmall)
                Text(
                    String.format(
                        Locale.US, "%.2f × %.2f in at %d DPI",
                        config.widthPx.toDouble() / config.dpi, config.heightPx.toDouble() / config.dpi, config.dpi,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(config.orientation.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
