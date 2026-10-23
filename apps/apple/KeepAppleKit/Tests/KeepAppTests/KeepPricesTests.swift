import Foundation
import Testing
@testable import KeepApp

@Suite("Keep+ prices")
struct KeepPricesTests {
    private let body = Data("""
    {"currency":"RUB","plans":[
      {"id":"keep_plus_month","kind":"subscription","amountKopecks":14900,"regularAmountKopecks":null,"earlyAccess":null},
      {"id":"keep_plus_lifetime","kind":"lifetime","amountKopecks":199000,"regularAmountKopecks":349000,"earlyAccess":{"limit":555,"remaining":540}}],
     "checkout":{"available":false,"providers":["tochka"]}}
    """.utf8)

    @Test func pricesAreReadAsTheBackendSentThem() throws {
        let prices = try #require(KeepPrices.parse(body))
        #expect(prices.plans.map(\.amountKopecks) == [14_900, 199_000])
        #expect(prices.plans[0].earlyRemaining == nil)
        #expect(prices.plans[1].earlyRemaining == 540)
        #expect(prices.plans[1].regularKopecks == 349_000)
        #expect(!prices.checkoutAvailable)
        #expect(prices.providers == ["tochka"])
    }

    @Test func aBrokenAnswerIsNotAPrice() {
        #expect(KeepPrices.parse(Data("{}".utf8)) == nil)
        #expect(KeepPrices.parse(Data("nope".utf8)) == nil)
    }

    @Test func rublesAreGroupedAndWholeAmountsHaveNoKopecks() {
        #expect(formatRubles(14_900) == "149\u{00A0}₽")
        #expect(formatRubles(149_000) == "1\u{00A0}490\u{00A0}₽")
        #expect(formatRubles(349_000) == "3\u{00A0}490\u{00A0}₽")
        #expect(formatRubles(14_950) == "149,50\u{00A0}₽")
    }
}
