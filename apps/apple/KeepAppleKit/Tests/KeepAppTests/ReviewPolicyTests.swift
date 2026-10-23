import Foundation
import Testing
@testable import KeepApp

@Suite("Review policy")
struct ReviewPolicyTests {
    private func launched(_ times: Int) -> ReviewState { (0..<times).reduce(ReviewState()) { state, _ in state.afterLaunch() } }

    @Test func asksFromTheThirdLaunchAndOnlyForAStoreInstall() {
        #expect(!launched(2).isDue(storeInstall: true))
        #expect(launched(3).isDue(storeInstall: true))
        #expect(!launched(3).isDue(storeInstall: false))
    }

    @Test func asksAgainSevenLaunchesLaterAndStopsAfterTwoAsks() {
        let first = launched(3).afterAsk()
        #expect(!first.isDue(storeInstall: true))
        let second = (0..<7).reduce(first) { state, _ in state.afterLaunch() }
        #expect(second.isDue(storeInstall: true))
        #expect(second.afterAsk().finished)
    }

    @Test func theStoreKeepsTheCounters() {
        let store = ReviewStore(suiteName: "review-test-\(UUID().uuidString)")
        #expect(store.load() == ReviewState())
        let state = launched(4).afterAsk()
        store.save(state)
        #expect(store.load() == state)
    }
}
