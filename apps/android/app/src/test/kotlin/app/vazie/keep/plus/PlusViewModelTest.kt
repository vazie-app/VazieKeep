package app.vazie.keep.plus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlusViewModelTest {
    private val prices = KeepPrices(
        plans = listOf(
            PlanPrice("keep_plus_month", false, 14_900, null, null),
            PlanPrice("keep_plus_year", false, 149_000, null, null),
            PlanPrice("keep_plus_lifetime", true, 199_000, 349_000, 555),
        ),
        checkoutAvailable = true,
        providers = listOf("tochka"),
    )
    private var activateAfter = 3
    private var checks = 0
    private val pauses = mutableListOf<Long>()

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = PlusViewModel(
        prices = { prices },
        siteOrigin = "https://vazie.app",
        status = { ++checks >= activateAfter },
        pause = { pauses += it },
    )

    @Test
    fun `the year is chosen first`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("keep_plus_year", vm.state.value.selectedPlan)
    }

    @Test
    fun `subscribing opens the checkout on the site with the chosen plan`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.selectPlan("keep_plus_lifetime")
        vm.openSite()
        assertEquals("https://vazie.app/keep/plus?plan=lifetime", vm.openUrl.first())
        assertTrue(vm.state.value.awaitingPayment)
    }

    @Test
    fun `coming back from the site polls until Keep+ is on`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.openSite()
        vm.onResumed(); advanceUntilIdle()
        assertTrue(vm.state.value.activated)
        assertEquals(3, checks)
        assertEquals(2, pauses.size)
    }

    @Test
    fun `resuming without a visit to the site asks nothing`() = runTest {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onResumed(); advanceUntilIdle()
        assertEquals(0, checks)
        assertFalse(vm.state.value.activated)
    }

    @Test
    fun `a payment the backend has not heard of yet is said to be not seen yet, and Check payment asks again`() = runTest {
        activateAfter = 100
        val vm = viewModel()
        advanceUntilIdle()
        vm.openSite()
        vm.onResumed(); advanceUntilIdle()
        assertFalse(vm.state.value.activated)
        assertTrue(vm.state.value.notSeen)
        assertEquals(6, checks)

        activateAfter = checks + 1
        vm.checkNow(); advanceUntilIdle()
        assertTrue(vm.state.value.activated)
        assertFalse(vm.state.value.notSeen)
    }

    @Test
    fun `price ids map to the plan names the site takes`() {
        assertEquals("month", sitePlan("keep_plus_month"))
        assertEquals("year", sitePlan("keep_plus_year"))
        assertEquals("lifetime", sitePlan("keep_plus_lifetime"))
    }
}
