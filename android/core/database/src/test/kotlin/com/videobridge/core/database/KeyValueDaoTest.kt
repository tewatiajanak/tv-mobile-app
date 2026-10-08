package com.videobridge.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyValueDaoTest {
    private lateinit var database: VideoBridgeDatabase
    private lateinit var dao: KeyValueDao

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VideoBridgeDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = database.keyValueDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `upsert inserts then replaces`() = runTest {
        dao.upsert(KeyValueEntity("lastSeq", "1", updatedAt = 10))
        dao.upsert(KeyValueEntity("lastSeq", "2", updatedAt = 20))

        assertEquals(KeyValueEntity("lastSeq", "2", updatedAt = 20), dao.get("lastSeq"))
    }

    @Test
    fun `get returns null for a missing key and after delete`() = runTest {
        assertNull(dao.get("missing"))

        dao.upsert(KeyValueEntity("k", "v", updatedAt = 1))
        dao.delete("k")

        assertNull(dao.get("k"))
    }
}
