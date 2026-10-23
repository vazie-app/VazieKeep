import Foundation
import Testing
@testable import KeepApp

private struct StubPrices: KeepPricesAPI {
    let value: KeepPrices?
    func fetch() async -> KeepPrices? { value }
}

@MainActor
@Suite("Keep+ on the site")
struct PlusModelTests {
    private let prices = KeepPrices(
        plans: [
            PlanPrice(id: "keep_plus_month", isLifetime: false, amountKopecks: 14_900, regularKopecks: nil, earlyRemaining: nil),
            PlanPrice(id: "keep_plus_year", isLifetime: false, amountKopecks: 149_000, regularKopecks: nil, earlyRemaining: nil),
            PlanPrice(id: "keep_plus_lifetime", isLifetime: true, amountKopecks: 199_000, regularKopecks: 349_000, earlyRemaining: 555),
        ],
        checkoutAvailable: true,
        providers: ["tochka"]
    )

    final class Counter { var value = 0 }

    private func model(activateAfter: Int = 3, checks: Counter = Counter(), pauses: Counter = Counter()) -> PlusModel {
        PlusModel(
            prices: StubPrices(value: prices),
            siteOrigin: URL(string: "https://vazie.app")!,
            status: { checks.value += 1; return checks.value >= activateAfter },
            pause: { _ in pauses.value += 1 }
        )
    }

    @Test func theYearIsChosenFirst() async {
        let model = model()
        await model.load()
        #expect(model.selectedPlan == "keep_plus_year")
    }

    @Test func subscribingOpensTheCheckoutOnTheSiteWithThePlan() async {
        let model = model()
        await model.load()
        model.selectedPlan = "keep_plus_lifetime"
        #expect(model.siteURL() == URL(string: "https://vazie.app/keep/plus?plan=lifetime"))
    }

    @Test func comingBackPollsUntilKeepPlusIsOn() async {
        let checks = Counter(), pauses = Counter()
        let model = model(checks: checks, pauses: pauses)
        await model.load()
        model.openedSite()
        await model.checkPayment()
        #expect(model.activated)
        #expect(checks.value == 3)
        #expect(pauses.value == 2)
    }

    @Test func aPaymentNotSeenYetIsSaidSoAndCheckAgainAsks() async {
        let checks = Counter()
        let model = model(activateAfter: 100, checks: checks)
        await model.load()
        model.openedSite()
        await model.checkPayment()
        #expect(!model.activated)
        #expect(model.notSeen)
        #expect(checks.value == 6)
    }

    @Test func comingBackWithoutAVisitToTheSiteAsksNothing() async {
        let checks = Counter()
        let model = model(checks: checks)
        await model.load()
        await model.checkPayment()
        #expect(checks.value == 0)
    }

    @Test func priceIDsMapToThePlanNamesTheSiteTakes() {
        #expect(sitePlan(forPriceID: "keep_plus_month") == "month")
        #expect(sitePlan(forPriceID: "keep_plus_year") == "year")
        #expect(sitePlan(forPriceID: "keep_plus_lifetime") == "lifetime")
    }
}
