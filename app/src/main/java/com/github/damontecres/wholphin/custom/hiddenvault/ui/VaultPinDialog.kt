package com.github.damontecres.wholphin.custom.hiddenvault.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.custom.hiddenvault.session.VaultPinStore
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.setup.PinArrowRow
import com.github.damontecres.wholphin.ui.setup.PinEntryDots
import com.github.damontecres.wholphin.ui.tryRequestFocus

/**
 * The vault's PIN pad, in Wholphin's PIN style: the D-pad directions and the number keys are the
 * symbols, so it works with any remote. Submits by itself after four symbols.
 *
 * @param attempt changes after every submit, which clears the input for the next try
 */
@Composable
fun VaultPinDialog(
    title: String,
    message: String?,
    attempt: Int,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var input by remember(attempt) { mutableStateOf("") }
    LaunchedEffect(Unit) { focusRequester.tryRequestFocus() }
    BasicDialog(onDismissRequest = onDismissRequest) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .padding(24.dp)
                    .widthIn(min = 320.dp)
                    .focusRequester(focusRequester)
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyUp || !enabled) return@onKeyEvent false
                        val symbol = symbolFor(event.key)
                        when {
                            symbol != null -> {
                                if (input.length < VaultPinStore.LENGTH) {
                                    input += symbol
                                    if (input.length == VaultPinStore.LENGTH) onSubmit(input)
                                }
                                true
                            }

                            event.key == Key.Backspace || event.key == Key.Delete || event.key == Key.Clear -> {
                                input = input.dropLast(1)
                                true
                            }

                            else -> {
                                false
                            }
                        }
                    }.focusable(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            PinArrowRow()
            PinEntryDots(input.length)
            Text(
                text = message ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun symbolFor(key: Key): Char? =
    when (key) {
        Key.DirectionUp -> 'U'
        Key.DirectionRight -> 'R'
        Key.DirectionDown -> 'D'
        Key.DirectionLeft -> 'L'
        Key.Zero, Key.NumPad0 -> '0'
        Key.One, Key.NumPad1 -> '1'
        Key.Two, Key.NumPad2 -> '2'
        Key.Three, Key.NumPad3 -> '3'
        Key.Four, Key.NumPad4 -> '4'
        Key.Five, Key.NumPad5 -> '5'
        Key.Six, Key.NumPad6 -> '6'
        Key.Seven, Key.NumPad7 -> '7'
        Key.Eight, Key.NumPad8 -> '8'
        Key.Nine, Key.NumPad9 -> '9'
        else -> null
    }
