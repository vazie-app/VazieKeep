package app.vazie.keep.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vazie.keep.ui.icons.VazieIcons

@Composable
fun FieldError(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(VazieIcons.Info, contentDescription = null, tint = KeepDesign.colors.danger, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Text(message, style = KeepDesign.type.caption, color = KeepDesign.colors.danger)
    }
}

@Composable
internal fun FieldChrome(
    label: String?,
    focused: Boolean,
    error: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    height: Int = 60,
    multiline: Boolean = false,
    supporting: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    field: @Composable () -> Unit,
) {
    val colors = KeepDesign.colors
    val ring = when {
        error != null -> colors.danger
        focused -> colors.accent
        else -> null
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (label != null) {
            Text(label, style = KeepDesign.type.label, color = colors.textSecondary, modifier = Modifier.padding(start = 4.dp).clearAndSetSemantics {})
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = height.dp)
                .clip(KeepShapes.field)
                .background(colors.surfaceField)
                .then(if (ring != null) Modifier.border(2.dp, ring, KeepShapes.field) else Modifier)
                .padding(start = 16.dp, end = if (trailing != null) 8.dp else 16.dp, top = if (multiline) 14.dp else 0.dp, bottom = if (multiline) 14.dp else 0.dp),
            verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leadingIcon != null) Icon(leadingIcon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f), contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) { field() }
            if (trailing != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        if (error != null) FieldError(error)
        else if (supporting != null) Text(supporting, style = KeepDesign.type.caption, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
fun KeepTextField(
    state: TextFieldState,
    label: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    error: String? = null,
    supporting: String? = null,
    multiline: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: (() -> Unit)? = null,
    focus: FocusRequester = remember { FocusRequester() },
    height: Int = 60,
    inputTransformation: InputTransformation? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = KeepDesign.colors
    var focused by remember { mutableStateOf(false) }
    FieldChrome(
        label = label,
        focused = focused,
        error = error,
        enabled = enabled,
        modifier = modifier,
        leadingIcon = leadingIcon,
        height = height,
        multiline = multiline,
        supporting = supporting,
        trailing = trailing,
    ) {
        BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    (label ?: placeholder)?.let { text = AnnotatedString(it) }
                    if (error != null) error(error)
                },
            enabled = enabled,
            textStyle = KeepDesign.type.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction?.let { action -> { action() } },
            lineLimits = if (multiline) TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 10) else TextFieldLineLimits.SingleLine,
            inputTransformation = inputTransformation,
            decorator = { inner ->
                Box(Modifier.heightIn(min = 24.dp), contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                    // A screen reader takes a field's name from a child of the field: the field's own text is its value.
                    (label ?: placeholder)?.let { name -> Box(Modifier.matchParentSize().semantics { contentDescription = name }) }
                    if (state.text.isEmpty() && placeholder != null) {
                        Text(placeholder, style = KeepDesign.type.body, color = colors.textTertiary, modifier = Modifier.clearAndSetSemantics {})
                    }
                    inner()
                }
            },
        )
    }
}

@Composable
fun KeepSecureField(
    state: TextFieldState,
    label: String?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    error: String? = null,
    focus: FocusRequester = remember { FocusRequester() },
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    onKeyboardAction: (() -> Unit)? = null,
    height: Int = 60,
    onFocusChange: (Boolean) -> Unit = {},
    fieldModifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = KeepDesign.colors
    var focused by remember { mutableStateOf(false) }
    FieldChrome(
        label = label,
        focused = focused,
        error = error,
        enabled = enabled,
        modifier = modifier,
        height = height,
        trailing = trailing,
    ) {
        BasicSecureTextField(
            state = state,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                focused = it.isFocused
                onFocusChange(it.isFocused)
            }.then(fieldModifier),
            enabled = enabled,
            textStyle = (if (visible) KeepDesign.type.secret.copy(fontSize = 15.5.sp, lineHeight = 22.sp) else KeepDesign.type.body)
                .copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction?.let { action -> { action() } },
            decorator = { inner ->
                Box(Modifier.heightIn(min = 24.dp), contentAlignment = Alignment.CenterStart) {
                    if (state.text.isEmpty() && placeholder != null) {
                        Text(placeholder, style = KeepDesign.type.body, color = colors.textTertiary)
                    }
                    inner()
                }
            },
        )
    }
}
