package app.vazie.keep

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.core.KeepAccessState
import app.vazie.keep.core.PlusFeature
import app.vazie.keep.data.CreateRequest
import app.vazie.keep.data.CreateResult
import app.vazie.keep.data.UnlockResult
import app.vazie.keep.data.VaultState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Free is the default: no account, no network call, and a vault can be made, opened and used.
@RunWith(AndroidJUnit4::class)
class AnonymousFreeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as KeepApplication).container
    private lateinit var file: File

    @Before
    fun setUp() = runBlocking {
        container.repository.lock()
        container.forgetAllVaults()
        container.entitlements.signOut()
        file = File(context.cacheDir, "anonymous-free.kdbx").also { it.delete() }
    }

    @After
    fun tearDown() {
        container.repository.lock()
        container.forgetAllVaults()
        file.delete()
    }

    @Test
    fun withoutAnAccountTheAppIsFreeAndAVaultStillWorks() = runBlocking {
        val state = container.entitlements.state.value
        assertNull(state.account)
        assertEquals(KeepAccessState.Free, state.access.state)
        assertFalse(container.featureAccess.canUse(PlusFeature.MultiVault))

        // An unreachable backend changes nothing: with no account there is nothing to ask it.
        container.entitlements.refresh(force = true)
        assertNull(container.entitlements.state.value.problem)
        assertNull(container.entitlements.state.value.account)

        val uri = file.toURI().toString().replace("file:/", "file:///")
        val created = container.vaultManager.create(
            CreateRequest("Anonymous", container.core.balancedPreset.copy(memoryKib = 8192, iterations = 2), "pw-1234".toByteArray(), null, null, null),
            uri,
        )
        assertIs<CreateResult.Created>(created)
        assertEquals(UnlockResult.Success, container.repository.unlock("pw-1234".toByteArray()))
        assertIs<VaultState.Unlocked>(container.repository.state.value)
        container.repository.lock()
        assertEquals(VaultState.Locked, container.repository.state.value)
        assertNull(container.entitlements.state.value.account)
        assertTrue(file.length() > 0)
    }
}
