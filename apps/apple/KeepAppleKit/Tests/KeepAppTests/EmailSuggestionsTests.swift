import Testing
@testable import KeepApp

@Suite("Email suggestions")
struct EmailSuggestionsTests {
    @Test func aBareNameOffersEveryDomain() {
        #expect(EmailSuggestions.completions(for: "anna") == ["anna@gmail.com", "anna@mail.ru", "anna@yandex.ru", "anna@proton.me", "anna@icloud.com"])
    }

    @Test func aTypedDomainPrefixNarrowsTheList() {
        #expect(EmailSuggestions.completions(for: "anna@m") == ["anna@mail.ru"])
        #expect(EmailSuggestions.completions(for: "anna@G") == ["anna@gmail.com"])
    }

    @Test func nothingIsOfferedForAnEmptyCompleteOrOddAddress() {
        for input in ["", "anna@gmail.com", "@gm", "a@b@c", "anna@x"] {
            #expect(EmailSuggestions.completions(for: input).isEmpty)
        }
    }
}
