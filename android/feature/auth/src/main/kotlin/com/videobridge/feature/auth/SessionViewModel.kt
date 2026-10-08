package com.videobridge.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.videobridge.core.data.auth.AuthRepository
import com.videobridge.core.data.auth.AuthState
import com.videobridge.core.data.auth.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Decides which top-level screen each app shows: sign-in or home. */
@HiltViewModel
class SessionViewModel
@Inject
constructor(sessionManager: SessionManager, private val repository: AuthRepository) : ViewModel() {
    val authState: StateFlow<AuthState> = sessionManager.authState

    fun signOut() {
        viewModelScope.launch { repository.logout() }
    }
}
