import Foundation

public enum EmailSuggestions {
    private static let domains = ["gmail.com", "mail.ru", "yandex.ru", "proton.me", "icloud.com"]

    public static func completions(for input: String) -> [String] {
        let text = input.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty, !text.hasPrefix("@"), text.filter({ $0 == "@" }).count <= 1 else { return [] }
        let parts = text.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false)
        let name = String(parts[0])
        let typed = parts.count > 1 ? parts[1].lowercased() : ""
        return domains.filter { $0.hasPrefix(typed) && $0 != typed }.map { "\(name)@\($0)" }
    }
}
