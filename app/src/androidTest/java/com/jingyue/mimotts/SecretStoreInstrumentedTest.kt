package com.jingyue.mimotts

import com.jingyue.mimotts.data.SecretStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreInstrumentedTest {
    @Test
    fun api_key_round_trip_works_on_the_device() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val storageName = "mimo_secret_store_test_$suffix"
        val keyAlias = "mimotts.test.secret.$suffix"
        val store = SecretStore(context, storageName, keyAlias)

        try {
            assertTrue(store.putApiKey(TEST_SECRET))
            assertTrue(store.hasStoredApiKey())
            assertEquals(TEST_SECRET, store.getApiKey())
            assertEquals(TEST_SECRET, SecretStore(context, storageName, keyAlias).getApiKey())
        } finally {
            store.clear()
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
            context.deleteSharedPreferences(storageName)
        }
    }

    companion object {
        private const val TEST_SECRET = "mimotts-device-test-secret"
    }
}
