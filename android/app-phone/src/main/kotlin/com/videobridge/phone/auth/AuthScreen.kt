package com.videobridge.phone.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.videobridge.feature.auth.AuthError
import com.videobridge.feature.auth.AuthMode
import com.videobridge.feature.auth.AuthUiState
import com.videobridge.phone.R

const val AUTH_NAME_TAG = "auth_name"
const val AUTH_PHONE_TAG = "auth_phone"
const val AUTH_PASSWORD_TAG = "auth_password"
const val AUTH_SUBMIT_TAG = "auth_submit"
const val AUTH_ERROR_TAG = "auth_error"

@Composable
fun AuthScreen(
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
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.logo_dekho),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(88.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(if (creating) R.string.auth_title_create else R.string.auth_title_sign_in),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(if (creating) R.string.auth_subtitle_create else R.string.auth_subtitle_sign_in),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            if (creating) {
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.auth_field_name)) },
                    singleLine = true,
                    enabled = !uiState.submitting,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag(AUTH_NAME_TAG),
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = uiState.phone,
                onValueChange = onPhoneChange,
                label = { Text(stringResource(R.string.auth_field_phone)) },
                singleLine = true,
                enabled = !uiState.submitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag(AUTH_PHONE_TAG),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.auth_field_password)) },
                singleLine = true,
                enabled = !uiState.submitting,
                visualTransformation = if (uiState.passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                trailingIcon = {
                    IconButton(onClick = onTogglePasswordVisible) {
                        Icon(
                            painter =
                            painterResource(
                                if (uiState.passwordVisible) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                            ),
                            contentDescription =
                            stringResource(
                                if (uiState.passwordVisible) R.string.auth_hide_password else R.string.auth_show_password,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag(AUTH_PASSWORD_TAG),
            )

            val message =
                uiState.error?.let { errorText(it) }
                    ?: if (sessionEnded) stringResource(R.string.auth_session_ended) else null
            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(AUTH_ERROR_TAG),
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onSubmit,
                enabled = uiState.canSubmit,
                modifier = Modifier.fillMaxWidth().testTag(AUTH_SUBMIT_TAG),
            ) {
                if (uiState.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(if (creating) R.string.auth_action_create else R.string.auth_action_sign_in))
                }
            }
            TextButton(
                onClick = { onModeChange(if (creating) AuthMode.SIGN_IN else AuthMode.CREATE_ACCOUNT) },
                enabled = !uiState.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (creating) R.string.auth_switch_to_sign_in else R.string.auth_switch_to_create))
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
