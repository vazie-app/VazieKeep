package app.vazie.keep.generator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.PasswordGeneratorException
import app.vazie.keep.core.PasswordOptions
import app.vazie.keep.core.SecretValue
import app.vazie.keep.data.SecretClipboard
import app.vazie.keep.editor.GeneratorState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

class GeneratorViewModel(private val core: KeepCore, private val clipboard: SecretClipboard) : ViewModel() {
    private val mutableState = MutableStateFlow(GeneratorState())
    val state: StateFlow<GeneratorState> = mutableState.asStateFlow()
    private var generated: SecretValue? = null
    private val copiedChannel = Channel<Int?>(Channel.BUFFERED)
    val copied: Flow<Int?> = copiedChannel.receiveAsFlow()

    init {
        generate()
    }

    fun setOptions(options: PasswordOptions) {
        mutableState.value = GeneratorState(options)
        generate()
    }

    fun generate() {
        val options = mutableState.value.options
        generated?.close()
        generated = null
        try {
            val secret = core.generatePassword(options)
            generated = secret
            mutableState.value = GeneratorState(options, generated = true, revealed = secret.useChars { String(it) })
        } catch (_: PasswordGeneratorException) {
            mutableState.value = GeneratorState(options)
        }
    }

    fun reveal() {
        val secret = generated ?: return
        mutableState.value = GeneratorState(mutableState.value.options, generated = true, revealed = secret.useChars { String(it) })
    }

    fun hide() {
        mutableState.value = GeneratorState(mutableState.value.options, generated = generated != null)
    }

    fun copy() {
        val secret = generated ?: return
        val result = clipboard.copy(secret)
        val seconds = result.clearsAfter?.inWholeSeconds?.toInt()
        if (!result.systemConfirms || seconds == null) copiedChannel.trySend(seconds)
    }

    fun clear() {
        generated?.close()
        generated = null
        mutableState.value = GeneratorState(mutableState.value.options)
    }

    override fun onCleared() {
        clear()
    }
}
