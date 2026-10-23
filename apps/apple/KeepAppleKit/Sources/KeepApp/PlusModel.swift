import Foundation
import Observation

// Keep+ is bought on the site: the app shows what the backend sells and sends the person to vazie.app with the plan.
public func sitePlan(forPriceID id: String) -> String {
    switch id {
    case "keep_plus_month": "month"
    case "keep_plus_year": "year"
    default: "lifetime"
    }
}

@MainActor
@Observable
public final class PlusModel {
    public private(set) var prices: KeepPrices?
    public private(set) var loading = true
    public var selectedPlan: String?
    public private(set) var awaitingPayment = false
    public private(set) var checking = false
    public private(set) var notSeen = false
    public private(set) var activated = false

    private let pricesAPI: any KeepPricesAPI
    private let siteOrigin: URL
    private let status: () async -> Bool
    private let pause: (Duration) async -> Void

    public init(
        prices: any KeepPricesAPI,
        siteOrigin: URL,
        status: @escaping () async -> Bool,
        pause: @escaping (Duration) async -> Void = { try? await Task.sleep(for: $0) }
    ) {
        pricesAPI = prices
        self.siteOrigin = siteOrigin
        self.status = status
        self.pause = pause
    }

    public func load() async {
        loading = true
        let fetched = await pricesAPI.fetch()
        prices = fetched
        if selectedPlan == nil { selectedPlan = fetched?.plans.first { $0.id.hasSuffix("year") }?.id ?? fetched?.plans.first?.id }
        loading = false
    }

    /// The checkout page on the site for the chosen plan.
    public func siteURL() -> URL? {
        guard let plan = selectedPlan else { return nil }
        var components = URLComponents(url: siteOrigin.appending(path: "keep/plus"), resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "plan", value: sitePlan(forPriceID: plan))]
        return components?.url
    }

    public func openedSite() { awaitingPayment = true }

    /// The person is back from the site: ask for the right a few times, because the payment service tells Vazie a
    /// moment after the person sees "paid".
    public func checkPayment() async {
        guard awaitingPayment, !checking, !activated else { return }
        checking = true
        notSeen = false
        defer { checking = false }
        for attempt in 0..<Self.checks {
            if await status() { activated = true; return }
            if attempt < Self.checks - 1 { await pause(.seconds(5)) }
        }
        notSeen = true
    }

    private static let checks = 6
}
