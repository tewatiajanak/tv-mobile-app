package com.videobridge.core.data.auth

import com.videobridge.core.datastore.StoredSession
import com.videobridge.core.datastore.TokenStore
import com.videobridge.core.network.AuthTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    /** The saved session has not been read yet (first moments after launch). */
    data object Unknown : AuthState

    data class LoggedOut(
        /** True when the backend ended the session, as opposed to the user signing out. */
        val sessionEnded: Boolean = false,
    ) : AuthState

    data class LoggedIn(val userId: String, val displayName: String, val phoneMasked: String) : AuthState
}

/**
 * Owns "who is signed in". The saved session is read once at startup, so a returning user goes
 * straight in without the network; after that this in-memory copy is the source the HTTP layer
 * reads on every request, and every change is written back to the encrypted store.
 */
@Singleton
class SessionManager
@Inject
constructor(private val store: TokenStore, @ApplicationScope private val scope: CoroutineScope) :
    AuthTokenSource {
    @Volatile
    private var current: StoredSession? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        scope.launch {
            val saved = store.session.first()
            // A sign-in that completed while the store was still loading wins.
            if (_authState.value == AuthState.Unknown) {
                current = saved
                _authState.value = saved?.toLoggedIn() ?: AuthState.LoggedOut()
            }
        }
    }

    suspend fun onSignedIn(session: StoredSession) {
        current = session
        store.save(session)
        _authState.value = session.toLoggedIn()
    }

    suspend fun signOutLocally() {
        current = null
        store.clear()
        _authState.value = AuthState.LoggedOut()
    }

    override fun accessToken(): String? = current?.accessToken

    override fun refreshToken(): String? = current?.refreshToken

    override fun onTokensRefreshed(accessToken: String, refreshToken: String) {
        val updated = current?.copy(accessToken = accessToken, refreshToken = refreshToken) ?: return
        current = updated
        // The old refresh token is already dead on the server, so losing this write would
        // sign the user out at next launch; it is small and the app scope outlives the request.
        scope.launch { store.save(updated) }
    }

    override fun onSessionEnded() {
        if (current == null) return
        current = null
        _authState.value = AuthState.LoggedOut(sessionEnded = true)
        scope.launch { store.clear() }
    }

    private fun StoredSession.toLoggedIn() = AuthState.LoggedIn(userId, displayName, phoneMasked)
}
