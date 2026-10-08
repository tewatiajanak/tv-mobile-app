package com.videobridge.phone.devices

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.videobridge.feature.auth.ConnectTvError
import com.videobridge.feature.auth.ConnectTvUiState
import com.videobridge.phone.R

const val CONNECT_CODE_TAG = "connect_code"
const val CONNECT_CONTINUE_TAG = "connect_continue"
const val CONNECT_APPROVE_TAG = "connect_approve"
const val CONNECT_ERROR_TAG = "connect_error"

/**
 * Connect a TV in three steps on one screen: give the code (scan or type), confirm the TV that
 * was found, done. The TV signs itself in the moment Connect is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectTvScreen(
    uiState: ConnectTvUiState,
    scannerFailed: Boolean,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onScan: () -> Unit,
    onTvNameChange: (String) -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.connect_title)) },
                navigationIcon = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val tv = uiState.tv
            when {
                uiState.connected -> {
                    Text(text = stringResource(R.string.connect_done_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(text = stringResource(R.string.connect_done_body, uiState.tvName.ifBlank { tv?.name.orEmpty() }))
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect_done)) }
                }

                tv != null -> {
                    Text(text = stringResource(R.string.connect_confirm_title), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.connect_confirm_body,
                            listOfNotNull(tv.name, tv.model).distinct().joinToString(" · "),
                        ),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    OutlinedTextField(
                        value = uiState.tvName,
                        onValueChange = onTvNameChange,
                        label = { Text(stringResource(R.string.connect_tv_name)) },
                        singleLine = true,
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ErrorText(uiState.error, scannerFailed = false)
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onApprove,
                        enabled = !uiState.busy,
                        modifier = Modifier.fillMaxWidth().testTag(CONNECT_APPROVE_TAG),
                    ) { Text(stringResource(R.string.connect_approve)) }
                    TextButton(onClick = onReject, enabled = !uiState.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.connect_reject))
                    }
                }

                else -> {
                    Text(text = stringResource(R.string.connect_intro), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onScan, enabled = !uiState.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.connect_scan))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(text = stringResource(R.string.connect_or_type), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.code,
                        onValueChange = onCodeChange,
                        label = { Text(stringResource(R.string.connect_code_label)) },
                        placeholder = { Text("1234") },
                        singleLine = true,
                        enabled = !uiState.busy,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSubmitCode() }),
                        modifier = Modifier.fillMaxWidth().testTag(CONNECT_CODE_TAG),
                    )
                    ErrorText(uiState.error, scannerFailed)
                    Spacer(Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = onSubmitCode,
                        enabled = uiState.canSubmitCode,
                        modifier = Modifier.fillMaxWidth().testTag(CONNECT_CONTINUE_TAG),
                    ) { Text(stringResource(R.string.connect_continue)) }
                }
            }
        }
    }
}

@Composable
private fun ErrorText(error: ConnectTvError?, scannerFailed: Boolean) {
    val message =
        when (error) {
            null -> if (scannerFailed) stringResource(R.string.connect_error_scanner) else return
            ConnectTvError.Unreachable -> stringResource(R.string.connect_error_unreachable)
            ConnectTvError.InvalidCode -> stringResource(R.string.connect_error_invalid)
            ConnectTvError.AlreadyClaimed -> stringResource(R.string.connect_error_claimed)
            ConnectTvError.Expired -> stringResource(R.string.connect_error_expired)
            ConnectTvError.DeviceLimit -> stringResource(R.string.connect_error_limit)
            ConnectTvError.TooManyAttempts -> stringResource(R.string.connect_error_too_many)
            is ConnectTvError.Message -> error.text.ifBlank { stringResource(R.string.connect_error_unexpected) }
        }
    Spacer(Modifier.height(12.dp))
    Text(text = message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CONNECT_ERROR_TAG))
}
