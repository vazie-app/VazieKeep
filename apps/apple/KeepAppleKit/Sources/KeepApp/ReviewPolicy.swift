import Foundation

public struct ReviewState: Equatable, Sendable {
    public static let firstAskAtLaunch = 3
    public static let launchesBetweenAsks = 7
    public static let maxAsks = 2

    public var launches = 0
    public var nextAskAt = ReviewState.firstAskAtLaunch
    public var asks = 0
    public var finished = false

    public init() {}

    public func afterLaunch() -> ReviewState {
        var next = self
        next.launches += 1
        return next
    }

    public func isDue(storeInstall: Bool) -> Bool {
        storeInstall && !finished && launches >= nextAskAt
    }

    /// The system decides whether the prompt is shown and never says how it ended, so every request counts as an ask.
    public func afterAsk() -> ReviewState {
        var next = self
        next.asks += 1
        if next.asks >= Self.maxAsks { next.finished = true } else { next.nextAskAt = launches + Self.launchesBetweenAsks }
        return next
    }
}

public struct ReviewStore: Sendable {
    private let defaultsName: String?

    public init(suiteName: String? = nil) { defaultsName = suiteName }

    private var defaults: UserDefaults { defaultsName.flatMap(UserDefaults.init(suiteName:)) ?? .standard }

    public func load() -> ReviewState {
        var state = ReviewState()
        let stored = defaults
        if stored.object(forKey: "review.launches") != nil {
            state.launches = stored.integer(forKey: "review.launches")
            state.nextAskAt = stored.integer(forKey: "review.nextAskAt")
            state.asks = stored.integer(forKey: "review.asks")
            state.finished = stored.bool(forKey: "review.finished")
        }
        return state
    }

    public func save(_ state: ReviewState) {
        let stored = defaults
        stored.set(state.launches, forKey: "review.launches")
        stored.set(state.nextAskAt, forKey: "review.nextAskAt")
        stored.set(state.asks, forKey: "review.asks")
        stored.set(state.finished, forKey: "review.finished")
    }
}
