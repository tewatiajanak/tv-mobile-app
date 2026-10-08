package com.videobridge.core.data.auth

import com.videobridge.core.datastore.StoredSession
import com.videobridge.core.datastore.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionManagerTest {
    private class FakeTokenStore(initial: StoredSession? = null) : TokenStore {
        override val session = MutableStateFlow(initial)

        override suspend fun save(session: StoredSession) {
            this.session.value = session
        }

        override suspend fun clear() {
            session.value = null
        }
    }

    private val saved = StoredSession("access", "refresh", "u1", "d1", "s1", "Janak", "+91******3210")
    private val loggedIn = AuthState.LoggedIn("u1", "Janak", "+91******3210")

    private fun TestScope.manager(store: TokenStore) = SessionManager(store, TestScope(UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `a saved session means signed in at launch, with no network involved`() = runTest {
        val manager = manager(FakeTokenStore(saved))

        assertEquals(loggedIn, manager.authState.value)
        assertEquals("access", manager.accessToken())
    }

    @Test
    fun `no saved session means signed out`() = runTest {
        assertEquals(AuthState.LoggedOut(), manager(FakeTokenStore()).authState.value)
    }

    @Test
    fun `signing in saves the session, signing out clears it`() = runTest {
        val store = FakeTokenStore()
        val manager = manager(store)

        manager.onSignedIn(saved)
        assertEquals(loggedIn, manager.authState.value)
        assertEquals(saved, store.session.value)

        manager.signOutLocally()
        assertEquals(AuthState.LoggedOut(), manager.authState.value)
        assertNull(store.session.value)
        assertNull(manager.accessToken())
    }

    @Test
    fun `refreshed tokens are persisted so the next launch is still signed in`() = runTest {
        val store = FakeTokenStore(saved)
        val manager = manager(store)

        manager.onTokensRefreshed("access-2", "refresh-2")

        assertEquals("access-2", manager.accessToken())
        assertEquals(saved.copy(accessToken = "access-2", refreshToken = "refresh-2"), store.session.value)
        assertEquals(loggedIn, manager.authState.value)
    }

    @Test
    fun `a session ended by the backend signs out and says so`() = runTest {
        val store = FakeTokenStore(saved)
        val manager = manager(store)

        manager.onSessionEnded()

        assertEquals(AuthState.LoggedOut(sessionEnded = true), manager.authState.value)
        assertNull(store.session.value)
    }
}
