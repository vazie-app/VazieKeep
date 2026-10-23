package app.vazie.keep.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewPolicyTest {
    private fun launched(times: Int) = (1..times).fold(ReviewState()) { state, _ -> state.afterLaunch() }

    @Test
    fun `asks from the third launch and only for a store install`() {
        assertFalse(launched(2).isDue(InstallStore.GooglePlay))
        assertTrue(launched(3).isDue(InstallStore.GooglePlay))
        assertTrue(launched(3).isDue(InstallStore.RuStore))
        assertFalse(launched(3).isDue(null))
    }

    @Test
    fun `later asks again seven launches on, and once more at most`() {
        val first = launched(3).postponed()
        assertFalse(first.isDue(InstallStore.GooglePlay))
        assertEquals(10, first.nextAskAt)
        val second = (1..7).fold(first) { state, _ -> state.afterLaunch() }
        assertTrue(second.isDue(InstallStore.GooglePlay))
        assertFalse(second.postponed().isDue(InstallStore.GooglePlay))
        assertTrue(second.postponed().finished)
    }

    @Test
    fun `an answer ends the asking for good`() {
        val done = launched(3).answered()
        assertFalse(launched(50).copy(finished = done.finished).isDue(InstallStore.GooglePlay))
    }
}
