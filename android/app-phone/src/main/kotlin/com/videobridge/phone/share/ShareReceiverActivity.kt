package com.videobridge.phone.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.videobridge.core.common.AppResult
import com.videobridge.core.data.auth.AuthState
import com.videobridge.core.data.auth.SessionManager
import com.videobridge.core.data.videos.VideosRepository
import com.videobridge.feature.library.LibraryError
import com.videobridge.feature.library.ShareTextParser
import com.videobridge.feature.library.toLibraryError
import com.videobridge.phone.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives "Share → Dekho" from other apps. It has no screen of its own: it saves the first
 * link found in the shared text, says what happened in a toast, and goes away, leaving the
 * user in the app they were sharing from. Shared text is untrusted and is never opened.
 */
@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    @Inject
    lateinit var sessionManager: SessionManager

    @Inject
    lateinit var videos: VideosRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url =
            intent
                ?.takeIf { it.action == Intent.ACTION_SEND }
                ?.let { ShareTextParser.firstUrl(it.getStringExtra(Intent.EXTRA_TEXT), it.getStringExtra(Intent.EXTRA_SUBJECT)) }
        if (url == null) {
            finishWith(R.string.share_no_link)
            return
        }
        lifecycleScope.launch {
            // The saved session takes a moment to load in a freshly started process.
            if (sessionManager.authState.first { it != AuthState.Unknown } !is AuthState.LoggedIn) {
                finishWith(R.string.share_sign_in_first)
                return@launch
            }
            Toast.makeText(this@ShareReceiverActivity, R.string.share_saving, Toast.LENGTH_SHORT).show()
            when (val result = videos.add(url)) {
                is AppResult.Success -> finishWith(getString(R.string.library_saved, result.data.title))

                is AppResult.Failure ->
                    finishWith(
                        when (val error = result.error.toLibraryError()) {
                            LibraryError.Unreachable -> getString(R.string.library_error_unreachable)
                            LibraryError.InvalidLink -> getString(R.string.library_error_invalid)
                            LibraryError.AlreadySaved -> getString(R.string.library_error_duplicate)
                            LibraryError.LimitReached -> getString(R.string.library_error_limit)
                            is LibraryError.Message -> error.text.ifBlank { getString(R.string.library_error_unexpected) }
                        },
                    )
            }
        }
    }

    private fun finishWith(messageRes: Int) = finishWith(getString(messageRes))

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
