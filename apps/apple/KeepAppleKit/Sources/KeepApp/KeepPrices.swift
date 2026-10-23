import Foundation

public struct PlanPrice: Equatable, Sendable, Identifiable {
    public var id: String
    public var isLifetime: Bool
    public var amountKopecks: Int64
    public var regularKopecks: Int64?
    public var earlyRemaining: Int?
}

public struct KeepPrices: Equatable, Sendable {
    public var plans: [PlanPrice]
    public var checkoutAvailable: Bool
    public var providers: [String]

    public init(plans: [PlanPrice], checkoutAvailable: Bool, providers: [String]) {
        self.plans = plans
        self.checkoutAvailable = checkoutAvailable
        self.providers = providers
    }

    public static func parse(_ data: Data) -> KeepPrices? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawPlans = root["plans"] as? [[String: Any]],
              let checkout = root["checkout"] as? [String: Any],
              let available = checkout["available"] as? Bool,
              let providers = checkout["providers"] as? [String]
        else { return nil }
        var plans: [PlanPrice] = []
        for raw in rawPlans {
            guard let id = raw["id"] as? String, let kind = raw["kind"] as? String, let amount = (raw["amountKopecks"] as? NSNumber)?.int64Value else { return nil }
            plans.append(PlanPrice(
                id: id,
                isLifetime: kind == "lifetime",
                amountKopecks: amount,
                regularKopecks: (raw["regularAmountKopecks"] as? NSNumber)?.int64Value,
                earlyRemaining: ((raw["earlyAccess"] as? [String: Any])?["remaining"] as? NSNumber)?.intValue
            ))
        }
        return KeepPrices(plans: plans, checkoutAvailable: available, providers: providers)
    }
}

public func formatRubles(_ kopecks: Int64) -> String {
    let rubles = kopecks / 100
    let rest = kopecks % 100
    let digits = Array(String(rubles))
    var grouped = ""
    for (index, digit) in digits.enumerated() {
        if index > 0, (digits.count - index) % 3 == 0 { grouped += "\u{00A0}" }
        grouped.append(digit)
    }
    return rest == 0 ? "\(grouped)\u{00A0}₽" : "\(grouped),\(String(format: "%02d", Int(rest)))\u{00A0}₽"
}

public protocol KeepPricesAPI: Sendable {
    func fetch() async -> KeepPrices?
}

public struct HTTPKeepPricesAPI: KeepPricesAPI {
    private let origin: URL

    public init(origin: URL) { self.origin = origin }

    public func fetch() async -> KeepPrices? {
        var request = URLRequest(url: origin.appending(path: "v1/prices"), timeoutInterval: 15)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        guard let (data, response) = try? await URLSession.shared.data(for: request), (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return KeepPrices.parse(data)
    }
}
