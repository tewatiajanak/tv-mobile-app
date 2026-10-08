package com.videobridge.tv.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.videobridge.feature.auth.TvPairingUiState
import com.videobridge.tv.R

const val TV_PAIR_CODE_TAG = "tv_pair_code"
const val TV_PAIR_STATUS_TAG = "tv_pair_status"
const val TV_PAIR_PASSWORD_TAG = "tv_pair_use_password"

private const val QR_SIZE_PX = 560
private const val SECONDS_PER_MINUTE = 60
private val MUTED = Color(0xFFB8BEC9)
private val ACCENT = Color(0xFFFFB74D)

/**
 * What a signed-out TV shows: nothing to type. The phone scans the QR code (or the code is typed
 * on the phone) and the TV signs itself in. The one button offers the keyboard route instead.
 */
@Composable
fun TvPairingScreen(uiState: TvPairingUiState, onUsePassword: () -> Unit, modifier: Modifier = Modifier) {
    val buttonFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { buttonFocus.requestFocus() }

    Row(
        modifier = modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 27.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(0.55f).padding(end = 32.dp)) {
            Image(
                painter = painterResource(R.drawable.logo_dekho),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(text = stringResource(R.string.pair_title), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            listOf(R.string.pair_step_1, R.string.pair_step_2, R.string.pair_step_3).forEach { step ->
                Text(text = stringResource(step), fontSize = 20.sp, color = MUTED)
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onUsePassword, modifier = Modifier.focusRequester(buttonFocus).testTag(TV_PAIR_PASSWORD_TAG)) {
                Text(text = stringResource(R.string.pair_use_password), fontSize = 18.sp)
            }
        }

        Column(
            modifier = Modifier.weight(0.45f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (uiState) {
                TvPairingUiState.Loading -> StatusText(stringResource(R.string.pair_loading))
                TvPairingUiState.Unreachable -> StatusText(stringResource(R.string.pair_unreachable))
                is TvPairingUiState.ShowingCode -> CodePanel(uiState)
            }
        }
    }
}

@Composable
private fun CodePanel(state: TvPairingUiState.ShowingCode) {
    // Re-encoded only when the code changes, not on every countdown tick.
    val qr = remember(state.qrPayload) { qrCodeBitmap(state.qrPayload, QR_SIZE_PX) }
    Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp)) {
        Image(bitmap = qr, contentDescription = stringResource(R.string.pair_qr_description), modifier = Modifier.size(230.dp))
    }
    Spacer(Modifier.height(14.dp))
    Text(text = stringResource(R.string.pair_code_label), fontSize = 16.sp, color = MUTED)
    Text(
        text = state.code,
        fontSize = 64.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 12.sp,
        modifier = Modifier.testTag(TV_PAIR_CODE_TAG),
    )
    Spacer(Modifier.height(8.dp))
    val status =
        when {
            state.confirmOnPhone -> stringResource(R.string.pair_confirm_on_phone)

            state.wasRejected -> stringResource(R.string.pair_rejected)

            else ->
                stringResource(
                    R.string.pair_refreshes_in,
                    "%d:%02d".format(state.secondsLeft / SECONDS_PER_MINUTE, state.secondsLeft % SECONDS_PER_MINUTE),
                )
        }
    Text(
        text = status,
        fontSize = 18.sp,
        color = if (state.confirmOnPhone) ACCENT else MUTED,
        modifier = Modifier.width(360.dp).testTag(TV_PAIR_STATUS_TAG),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun StatusText(text: String) {
    Text(text = text, fontSize = 22.sp, color = MUTED, modifier = Modifier.testTag(TV_PAIR_STATUS_TAG))
}
