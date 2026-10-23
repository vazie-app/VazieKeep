package app.vazie.keep.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.vazie.keep.R
import app.vazie.keep.ui.designsystem.KeepIconButton
import app.vazie.keep.ui.designsystem.KeepIconTone
import app.vazie.keep.ui.designsystem.KeepSecureField
import app.vazie.keep.ui.icons.VazieIcons

class FieldAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

// Compose publishes a secure field's plain text to accessibility services; this node replaces it
// with a masked copy while keeping focus, text entry and the actions reachable (ADR 0008).
@Composable
fun ProtectedTextField(
    state: TextFieldState,
    label: String,
    visible: Boolean,
    onToggleVisible: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: String? = null,
    focus: FocusRequester = remember { FocusRequester() },
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
    actions: List<FieldAction> = emptyList(),
    placeholder: String? = null,
    showLabel: Boolean = true,
    extraTrailing: (@Composable () -> Unit)? = null,
) {
    val toggleLabel = stringResource(if (visible) R.string.action_hide_password else R.string.action_show_password)
    val masked = "•".repeat(state.text.length)
    var focused by remember { mutableStateOf(false) }
    val fieldSemantics = Modifier.clearAndSetSemantics {
        contentDescription = label
        password()
        editableText = AnnotatedString(masked)
        if (!enabled) disabled()
        if (error != null) error(error)
        this.focused = focused
        onClick {
            focus.requestFocus()
            true
        }
        requestFocus {
            focus.requestFocus()
            true
        }
        setText {
            state.setTextAndPlaceCursorAtEnd(it.text)
            true
        }
        insertTextAtCursor { text ->
            state.edit { replace(selection.min, selection.max, text.text) }
            true
        }
    }
    Box(modifier.fillMaxWidth()) {
        KeepSecureField(
            state = state,
            label = if (showLabel) label else null,
            visible = visible,
            enabled = enabled,
            placeholder = placeholder,
            error = error,
            focus = focus,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction, autoCorrectEnabled = false),
            onKeyboardAction = onDone,
            onFocusChange = { focused = it },
            fieldModifier = fieldSemantics,
            trailing = {
                actions.forEach { action ->
                    KeepIconButton(action.icon, action.label, action.onClick, enabled = enabled, tone = KeepIconTone.Raised)
                }
                KeepIconButton(if (visible) VazieIcons.EyeOff else VazieIcons.Eye, toggleLabel, onToggleVisible, enabled = enabled)
                extraTrailing?.invoke()
            },
        )
    }
}
