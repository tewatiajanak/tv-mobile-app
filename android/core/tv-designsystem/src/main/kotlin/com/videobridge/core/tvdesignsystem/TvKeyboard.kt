package com.videobridge.core.tvdesignsystem

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

enum class TvKeyboardType { TEXT, NUMBER }

const val TV_KEYBOARD_TAG = "tv_keyboard"

/** Test tag of one key, e.g. `tvKeyTag("q")`, `tvKeyTag("done")`. */
fun tvKeyTag(id: String) = "tv_key_$id"

private sealed interface KeySpec {
    val id: String
    val width: Dp

    data class Character(val char: Char) : KeySpec {
        override val id = char.toString()
        override val width = KEY_WIDTH
    }

    data class Action(override val id: String, override val width: Dp) : KeySpec
}

private val KEY_WIDTH = 52.dp
private val KEY_HEIGHT = 40.dp
private val KEY_GAP = 6.dp
private const val ID_SHIFT = "shift"
private const val ID_SYMBOLS = "symbols"
private const val ID_SPACE = "space"
private const val ID_BACKSPACE = "backspace"
private const val ID_DONE = "done"

// The familiar Android TV keyboard look: a dark panel, slate keys, the focused key white.
private val PANEL_COLOR = Color(0xFF1B2228)
private val KEY_COLOR = Color(0xFF2D3840)
private val ACTION_KEY_COLOR = Color(0xFF3A5166)
private val KEY_TEXT = Color(0xFFE8EAED)

private val LETTER_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
private val SYMBOL_ROWS = listOf("1234567890", "@#$%&*-+()", "!\"':;/?=_", ".,<>[]{}")
private val NUMBER_ROWS = listOf("123", "456", "789", "+0")
private const val MAX_KEYS_PER_ROW = 10

private fun textRows(symbols: Boolean, shifted: Boolean): List<List<KeySpec>> {
    val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
    val characters = rows.map { row -> row.map { KeySpec.Character(if (shifted && !symbols) it.uppercaseChar() else it) } }
    val actions =
        listOf(
            KeySpec.Action(ID_SHIFT, 84.dp),
            KeySpec.Action(ID_SYMBOLS, 84.dp),
            KeySpec.Action(ID_SPACE, 180.dp),
            KeySpec.Action(ID_BACKSPACE, 84.dp),
            KeySpec.Action(ID_DONE, 110.dp),
        )
    return characters + listOf(actions)
}

private fun numberRows(): List<List<KeySpec>> = NUMBER_ROWS.map { row -> row.map<KeySpec> { KeySpec.Character(it) } }.let { rows ->
    rows.dropLast(1) +
        listOf(rows.last() + KeySpec.Action(ID_BACKSPACE, KEY_WIDTH)) +
        listOf(listOf(KeySpec.Action(ID_DONE, KEY_WIDTH * 3 + KEY_GAP * 2)))
}

/**
 * An on-screen keyboard that looks like the standard Android TV one but is part of the app, so
 * the app decides what happens at its edges: pressing Up on the top row calls [onExitUp] and
 * pressing Down on the bottom row calls [onExitDown]. Back calls [onDismiss]. The action key
 * calls [onAction] and is labelled "Next" or "Done" ([actionIsNext]). It takes focus when it
 * appears, and also accepts a remote's number keys or a plugged-in keyboard.
 */
@Composable
fun TvKeyboard(
    type: TvKeyboardType,
    onCharacter: (Char) -> Unit,
    onBackspace: () -> Unit,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    onExitUp: () -> Unit,
    onExitDown: () -> Unit,
    modifier: Modifier = Modifier,
    actionIsNext: Boolean = false,
) {
    var shifted by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }
    val rows = if (type == TvKeyboardType.NUMBER) numberRows() else textRows(symbols, shifted)
    // One requester per position, stable while keys relabel (shift / symbols).
    val requesters = remember(type) { List(rows.size) { List(MAX_KEYS_PER_ROW) { FocusRequester() } } }

    // Letters start on "q" (the row under the digits); the number pad on "1".
    val startRow = if (type == TvKeyboardType.TEXT) 1 else 0
    LaunchedEffect(type) { requesters[startRow][0].requestFocus() }

    fun moveVertically(row: Int, column: Int, delta: Int) {
        val target = row + delta
        when {
            target < 0 -> onExitUp()

            target > rows.lastIndex -> onExitDown()

            else -> {
                // Keep roughly the same horizontal position between rows of different lengths.
                val from = rows[row].size
                val to = rows[target].size
                val targetColumn = if (from <= 1) 0 else (column * (to - 1) + (from - 1) / 2) / (from - 1)
                requesters[target][targetColumn.coerceIn(0, to - 1)].requestFocus()
            }
        }
    }

    fun press(key: KeySpec) {
        when (key) {
            is KeySpec.Character -> {
                onCharacter(key.char)
                shifted = false
            }

            is KeySpec.Action ->
                when (key.id) {
                    ID_SHIFT -> shifted = !shifted
                    ID_SYMBOLS -> symbols = !symbols
                    ID_SPACE -> onCharacter(' ')
                    ID_BACKSPACE -> onBackspace()
                    ID_DONE -> onAction()
                }
        }
    }

    /** Remote number keys and hardware keyboards type directly. True when the key was used. */
    fun typeHardwareKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DEL) {
            onBackspace()
            return true
        }
        val char = event.unicodeChar.toChar()
        val typed = event.unicodeChar != 0 && !char.isISOControl() && (type == TvKeyboardType.TEXT || char.isDigit() || char == '+')
        if (typed) onCharacter(char)
        return typed
    }

    Column(
        modifier =
        modifier
            .background(PANEL_COLOR, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag(TV_KEYBOARD_TAG),
        verticalArrangement = Arrangement.spacedBy(KEY_GAP),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                row.forEachIndexed { columnIndex, key ->
                    KeyButton(
                        label = keyLabel(key, symbols, shifted, actionIsNext),
                        width = key.width,
                        isAction = key is KeySpec.Action && key.id == ID_DONE,
                        onClick = { press(key) },
                        modifier =
                        Modifier
                            .focusRequester(requesters[rowIndex][columnIndex])
                            .testTag(tvKeyTag(key.id.lowercase()))
                            .onPreviewKeyEvent { event ->
                                val delta =
                                    when (event.key) {
                                        Key.DirectionUp -> -1
                                        Key.DirectionDown -> 1
                                        else -> 0
                                    }
                                val isSelect =
                                    event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                                when {
                                    event.key == Key.Back -> {
                                        if (event.type == KeyEventType.KeyUp) onDismiss()
                                        true
                                    }

                                    delta != 0 -> {
                                        if (event.type == KeyEventType.KeyDown) moveVertically(rowIndex, columnIndex, delta)
                                        true
                                    }

                                    isSelect || event.key == Key.DirectionLeft || event.key == Key.DirectionRight -> false

                                    event.type == KeyEventType.KeyDown -> typeHardwareKey(event.nativeKeyEvent)

                                    else -> false
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun keyLabel(key: KeySpec, symbols: Boolean, shifted: Boolean, actionIsNext: Boolean): String = when (key) {
    is KeySpec.Character -> key.char.toString()

    is KeySpec.Action ->
        when (key.id) {
            ID_SHIFT -> if (shifted) "⬆" else "⇧"
            ID_SYMBOLS -> stringResource(if (symbols) R.string.tv_key_letters else R.string.tv_key_symbols)
            ID_SPACE -> "⎵"
            ID_BACKSPACE -> "⌫"
            else -> stringResource(if (actionIsNext) R.string.tv_key_next else R.string.tv_key_done)
        }
}

@Composable
private fun KeyButton(label: String, width: Dp, isAction: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(width).height(KEY_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors =
        ClickableSurfaceDefaults.colors(
            containerColor = if (isAction) ACTION_KEY_COLOR else KEY_COLOR,
            contentColor = KEY_TEXT,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color(0xFFCFD8DC),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, fontSize = 20.sp, maxLines = 1)
        }
    }
}
