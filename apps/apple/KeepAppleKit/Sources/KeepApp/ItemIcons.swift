import Foundation

/// The icons a group or an entry can have. Each stands for one standard KeePass icon number, so
/// KeePassXC and other apps show their own drawing of the same thing. A KDBX file may name any of
/// the 69 standard icons: the ones outside this set are shown as the nearest of these.
public enum ItemIcon: Int, CaseIterable, Identifiable, Sendable {
    case folder = 48
    case key = 0
    case web = 1
    case mail = 19
    case bank = 37
    case money = 66
    case home = 60
    case star = 61
    case person = 9
    case chat = 5
    case phone = 68
    case server = 3
    case computer = 18
    case terminal = 30
    case settings = 34
    case archive = 36
    case clock = 39
    case book = 56
    case note = 7
    case certificate = 67
    case work = 59
    case shopping = 47
    case camera = 11
    case lock = 52
    case warning = 2
    case info = 46
    case trash = 43

    public var id: Int { rawValue }

    /// The SF Symbol that draws it.
    public var symbol: String {
        switch self {
        case .folder: "folder"
        case .key: "key"
        case .web: "globe"
        case .mail: "envelope"
        case .bank: "building.columns"
        case .money: "creditcard"
        case .home: "house"
        case .star: "star"
        case .person: "person"
        case .chat: "bubble.left"
        case .phone: "iphone"
        case .server: "server.rack"
        case .computer: "desktopcomputer"
        case .terminal: "terminal"
        case .settings: "slider.horizontal.3"
        case .archive: "archivebox"
        case .clock: "clock"
        case .book: "book"
        case .note: "doc.text"
        case .certificate: "checkmark.seal"
        case .work: "briefcase"
        case .shopping: "shippingbox"
        case .camera: "camera"
        case .lock: "lock"
        case .warning: "exclamationmark.triangle"
        case .info: "info.circle"
        case .trash: "trash"
        }
    }

    /// English words an icon is also found by, whatever the app's language.
    public var keywords: String {
        switch self {
        case .folder: "folder group directory"
        case .key: "key password login"
        case .web: "web site internet globe world browser"
        case .mail: "mail email letter inbox"
        case .bank: "bank finance account"
        case .money: "money card payment pay wallet"
        case .home: "home house family"
        case .star: "star favorite favourite important"
        case .person: "person identity id profile user"
        case .chat: "chat social message messenger forum"
        case .phone: "phone mobile sim app"
        case .server: "server hosting network cloud"
        case .computer: "computer desktop monitor pc mac"
        case .terminal: "terminal console ssh shell code developer"
        case .settings: "settings config admin router"
        case .archive: "archive storage disk backup old"
        case .clock: "clock time temporary"
        case .book: "book study school library education"
        case .note: "note document text paper"
        case .certificate: "certificate license document official government"
        case .work: "work job office business tool"
        case .shopping: "shopping shop store package delivery order"
        case .camera: "camera photo video media"
        case .lock: "lock secure secret safe private"
        case .warning: "warning important attention"
        case .info: "info information help"
        case .trash: "trash bin deleted"
        }
    }

    private static let nearest: [Int: ItemIcon] = [
        4: .folder, 49: .folder, 50: .folder,
        13: .key, 58: .person,
        8: .web, 16: .web, 35: .web,
        25: .mail, 40: .mail,
        12: .chat,
        15: .computer, 23: .computer, 31: .computer, 32: .computer, 42: .computer, 62: .computer, 64: .computer,
        29: .terminal, 33: .terminal,
        20: .settings,
        17: .archive, 26: .archive, 27: .archive, 38: .archive,
        45: .clock,
        65: .book,
        10: .note, 21: .note, 22: .note, 28: .note, 41: .note, 44: .note, 54: .note, 57: .note, 63: .note,
        53: .certificate,
        6: .work, 14: .work,
        55: .camera,
        51: .lock,
        24: .warning,
    ]

    public static func forGroup(_ number: Int) -> ItemIcon {
        ItemIcon(rawValue: number) ?? nearest[number] ?? .folder
    }

    /// Nil for the default key: an entry without an icon of its own shows its letter.
    public static func forEntry(_ number: Int) -> ItemIcon? {
        if number == ItemIcon.key.rawValue { return nil }
        return ItemIcon(rawValue: number) ?? nearest[number] ?? .key
    }

    /// Icons whose name (in the app's language) or English keywords hold every word of the query.
    public static func matching(_ query: String, label: (ItemIcon) -> String) -> [ItemIcon] {
        let words = query.lowercased().split(whereSeparator: \.isWhitespace)
        guard !words.isEmpty else { return allCases }
        return allCases.filter { icon in
            let text = (label(icon) + " " + icon.keywords).lowercased()
            return words.allSatisfy { text.contains($0) }
        }
    }
}
