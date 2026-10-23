import KeepApp
import Testing

struct ItemIconTests {
    let russian: [ItemIcon: String] = [.bank: "Банк", .mail: "Почта", .money: "Деньги и карты"]
    func label(_ icon: ItemIcon) -> String { russian[icon] ?? "\(icon)" }

    @Test func everyStandardNumberHasADrawing() {
        #expect(ItemIcon.allCases.allSatisfy { (0...68).contains($0.rawValue) })
        #expect(ItemIcon.forGroup(48) == .folder)
        #expect(ItemIcon.forGroup(49) == .folder, "an open folder is still a folder")
        #expect(ItemIcon.forGroup(500) == .folder, "a number outside the standard set")
        #expect(ItemIcon.forGroup(43) == .trash)
    }

    @Test func anEntryWithTheDefaultKeyShowsItsLetter() {
        #expect(ItemIcon.forEntry(0) == nil)
        #expect(ItemIcon.forEntry(37) == .bank)
        #expect(ItemIcon.forEntry(25) == .mail)
        #expect(ItemIcon.forEntry(999) == .key)
    }

    @Test func iconsAreFoundByNameInTheAppsLanguageAndByEnglishWords() {
        #expect(ItemIcon.matching("  ", label: label) == ItemIcon.allCases)
        #expect(ItemIcon.matching("банк", label: label) == [.bank])
        #expect(ItemIcon.matching("EMAIL", label: label) == [.mail])
        #expect(ItemIcon.matching("card pay", label: label) == [.money])
        #expect(ItemIcon.matching("zzz", label: label).isEmpty)
    }
}
