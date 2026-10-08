package com.videobridge.tv.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.videobridge.core.tvdesignsystem.TvKeyboard
import com.videobridge.core.tvdesignsystem.TvKeyboardType
import com.videobridge.feature.auth.AuthError
import com.videobridge.feature.auth.AuthMode
import com.videobridge.feature.auth.AuthUiState
import com.videobridge.tv.R

const val TV_AUTH_NAME_TAG = "tv_auth_name"
const val TV_AUTH_PHONE_TAG = "tv_auth_phone"
const val TV_AUTH_PASSWORD_TAG = "tv_auth_password"
const val TV_AUTH_EYE_TAG = "tv_auth_eye"
const val TV_AUTH_SUBMIT_TAG = "tv_auth_submit"
const val TV_AUTH_SWITCH_TAG = "tv_auth_switch"
const val TV_AUTH_ERROR_TAG = "tv_auth_error"

private val FIELD_WIDTH = 440.dp
private val EYE_WIDTH = 124.dp
private val ACCENT = Color(0xFFFFB74D)
private const val KEYBOARD_ANIMATION_MS = 180

private enum class Field { NAME, PHONE, PASSWORD }

/** Where focus should land next; [token] makes repeated requests for the same place distinct. */
private data class FocusRequest(val target: Any, val token: Int)

private const val ACTIONS = "actions"

/**
 * Sign-in on the TV, built for the D-pad. Branding and messages sit on the left, the form on the
 * right, and neither moves when the keyboard slides up from the bottom (it only covers the
 * buttons). Selecting a field opens the app's own keyboard ([TvKeyboard]):
 * - its Next key jumps straight to the next field with the keyboard still open; Done (last
 *   field) closes it and lands on the buttons;
 * - Down past its last row closes it and moves to the next field (or the buttons);
 * - Up past its first row closes it and returns to the field being edited, from where Up goes
 *   to the field above;
 * - Back closes it and returns to the field.
 */
@Composable
fun TvAuthScreen(
    uiState: AuthUiState,
    sessionEnded: Boolean,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePasswordVisible: () -> Unit,
    onModeChange: (AuthMode) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val creating = uiState.mode == AuthMode.CREATE_ACCOUNT
    val fields = if (creating) listOf(Field.NAME, Field.PHONE, Field.PASSWORD) else listOf(Field.PHONE, Field.PASSWORD)
    val fieldFocus = remember { Field.entries.associateWith { FocusRequester() } }
    val submitFocus = remember { FocusRequester() }
    val switchFocus = remember { FocusRequester() }

    var editing by remember { mutableStateOf<Field?>(null) }
    // Kept while the keyboard animates out, so it still has something to draw.
    var lastEdited by remember { mutableStateOf(Field.PHONE) }
    var focusRequest by remember { mutableStateOf(FocusRequest(Field.NAME, 0)) }
    var nextToken by remember { mutableIntStateOf(1) }

    fun focus(target: Any) {
        focusRequest = FocusRequest(target, nextToken++)
    }

    fun edit(field: Field) {
        lastEdited = field
        editing = field
    }

    fun closeKeyboard(thenFocus: Any) {
        editing = null
        focus(thenFocus)
    }

    fun nextAfter(field: Field): Field? = fields.getOrNull(fields.indexOf(field) + 1)

    // Start on the first field, also after switching between "create" and "sign in".
    LaunchedEffect(uiState.mode) { focus(fields.first()) }
    LaunchedEffect(focusRequest) {
        when (val target = focusRequest.target) {
            is Field -> fieldFocus.getValue(target).requestFocus()
            else -> (if (uiState.canSubmit) submitFocus else switchFocus).requestFocus()
        }
    }
    BackHandler(enabled = editing != null) { editing?.let(::closeKeyboard) }

    Box(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp)) {
            // Left: who we are and what to do.
            Column(modifier = Modifier.weight(0.42f).padding(top = 12.dp, end = 32.dp)) {
                Image(
                    painter = painterResource(R.drawable.logo_dekho),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(84.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(if (creating) R.string.auth_title_create else R.string.auth_title_sign_in),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(if (creating) R.string.auth_subtitle_create else R.string.auth_subtitle_sign_in),
                    fontSize = 18.sp,
                    color = Color(0xFFB8BEC9),
                )
                val message =
                    uiState.error?.let { errorText(it) }
                        ?: if (sessionEnded) stringResource(R.string.auth_session_ended) else null
                if (message != null) {
                    Spacer(Modifier.height(16.dp))
                    Text(text = message, color = Color(0xFFFFB4AB), fontSize = 18.sp, modifier = Modifier.testTag(TV_AUTH_ERROR_TAG))
                }
            }

            // Right: the form. Top-aligned so it stays put when the keyboard opens below it.
            Column(modifier = Modifier.weight(0.58f).padding(top = 12.dp)) {
                fields.forEach { field ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InputField(
                            label = stringResource(field.label()),
                            value =
                            when (field) {
                                Field.NAME -> uiState.name
                                Field.PHONE -> uiState.phone
                                Field.PASSWORD -> if (uiState.passwordVisible) uiState.password else "•".repeat(uiState.password.length)
                            },
                            editing = editing == field,
                            enabled = !uiState.submitting,
                            width = if (field == Field.PASSWORD) FIELD_WIDTH - EYE_WIDTH - 12.dp else FIELD_WIDTH,
                            onClick = { edit(field) },
                            modifier = Modifier.focusRequester(fieldFocus.getValue(field)).testTag(field.tag()),
                        )
                        if (field == Field.PASSWORD) {
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(
                                onClick = onTogglePasswordVisible,
                                modifier = Modifier.width(EYE_WIDTH).testTag(TV_AUTH_EYE_TAG),
                            ) {
                                Icon(
                                    painter =
                                    painterResource(
                                        if (uiState.passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(if (uiState.passwordVisible) R.string.auth_hide_short else R.string.auth_show_short))
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = onSubmit,
                        enabled = uiState.canSubmit && editing == null,
                        modifier = Modifier.focusRequester(submitFocus).testTag(TV_AUTH_SUBMIT_TAG),
                    ) {
                        Text(
                            text = stringResource(if (creating) R.string.auth_action_create else R.string.auth_action_sign_in),
                            fontSize = 20.sp,
                        )
                    }
                    OutlinedButton(
                        onClick = { onModeChange(if (creating) AuthMode.SIGN_IN else AuthMode.CREATE_ACCOUNT) },
                        enabled = !uiState.submitting && editing == null,
                        modifier = Modifier.focusRequester(switchFocus).testTag(TV_AUTH_SWITCH_TAG),
                    ) {
                        Text(
                            text = stringResource(if (creating) R.string.auth_switch_to_sign_in else R.string.auth_switch_to_create),
                            fontSize = 20.sp,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = editing != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(KEYBOARD_ANIMATION_MS)) { it } + fadeIn(tween(KEYBOARD_ANIMATION_MS)),
            exit = slideOutVertically(tween(KEYBOARD_ANIMATION_MS)) { it } + fadeOut(tween(KEYBOARD_ANIMATION_MS)),
        ) {
            val current = editing ?: lastEdited
            val value: String
            val onChange: (String) -> Unit
            when (current) {
                Field.NAME -> {
                    value = uiState.name
                    onChange = onNameChange
                }

                Field.PHONE -> {
                    value = uiState.phone
                    onChange = onPhoneChange
                }

                Field.PASSWORD -> {
                    value = uiState.password
                    onChange = onPasswordChange
                }
            }
            val next = nextAfter(current)
            // A fresh keyboard per field: it starts on its first key and in lower case.
            key(current) {
                TvKeyboard(
                    type = if (current == Field.PHONE) TvKeyboardType.NUMBER else TvKeyboardType.TEXT,
                    actionIsNext = next != null,
                    onCharacter = { onChange(value + it) },
                    onBackspace = { onChange(value.dropLast(1)) },
                    onAction = { if (next != null) edit(next) else closeKeyboard(ACTIONS) },
                    onDismiss = { closeKeyboard(current) },
                    onExitUp = { closeKeyboard(current) },
                    onExitDown = { closeKeyboard(next ?: ACTIONS) },
                )
            }
        }
    }
}

private fun Field.label(): Int = when (this) {
    Field.NAME -> R.string.auth_field_name
    Field.PHONE -> R.string.auth_field_phone
    Field.PASSWORD -> R.string.auth_field_password
}

private fun Field.tag(): String = when (this) {
    Field.NAME -> TV_AUTH_NAME_TAG
    Field.PHONE -> TV_AUTH_PHONE_TAG
    Field.PASSWORD -> TV_AUTH_PASSWORD_TAG
}

/** A text field for the D-pad: it shows its value and opens the keyboard when selected. */
@Composable
private fun InputField(
    label: String,
    value: String,
    editing: Boolean,
    enabled: Boolean,
    width: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.width(width).height(56.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        colors =
        ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF1F242D),
            contentColor = Color(0xFFE6E6EA),
            focusedContainerColor = Color(0xFF2C3442),
            focusedContentColor = Color.White,
        ),
        border =
        ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, if (editing) ACCENT else Color(0xFF4B5563)), shape = shape),
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                Text(text = label, fontSize = 13.sp, color = if (editing) ACCENT else Color(0xFFA0A6B4))
                // The bar marks where typing goes while the keyboard is open.
                Text(text = if (editing) "$value▏" else value, fontSize = 20.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun errorText(error: AuthError): String = when (error) {
    AuthError.Unreachable -> stringResource(R.string.auth_error_unreachable)
    AuthError.WrongCredentials -> stringResource(R.string.auth_error_wrong_credentials)
    AuthError.PhoneAlreadyRegistered -> stringResource(R.string.auth_error_phone_registered)
    AuthError.InvalidPhone -> stringResource(R.string.auth_error_invalid_phone)
    AuthError.TooManyAttempts -> stringResource(R.string.auth_error_too_many_attempts)
    is AuthError.Message -> error.text
    AuthError.Unexpected -> stringResource(R.string.auth_error_unexpected)
}
