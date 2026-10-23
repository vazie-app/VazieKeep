#if canImport(UIKit)
import SwiftUI
import UIKit

/// Semantic colour tokens taken from `design/` (Foundations). Dark is warm near-black plum, light is
/// a milky off-white. Surfaces are told apart by tone, not by borders.
public enum KeepColor {
    public static let background = dynamic(light: 0xFAF8F4, dark: 0x121014)
    /// Lists and cards.
    public static let surfaceList = dynamic(light: 0xFFFEFC, dark: 0x1A161D)
    /// Fields.
    public static let surfaceField = dynamic(light: 0xF1EEE9, dark: 0x231E27)
    /// Chips, dock, raised buttons.
    public static let surfaceRaised = dynamic(light: 0xE8E4DE, dark: 0x2D2732)
    /// Toast.
    public static let surfaceToast = dynamic(light: 0xDCD7D0, dark: 0x393140)

    public static let text = dynamic(light: 0x211A25, dark: 0xF2ECE4)
    public static let textSecondary = dynamic(light: 0x5B5160, dark: 0xB9AFBB)
    public static let textTertiary = dynamic(light: 0x6B6170, dark: 0x958B98)

    /// Lavender: state, selection, links.
    public static let accent = dynamic(light: 0x5D43A8, dark: 0xC9B6F2)
    public static let accentContainer = dynamic(light: 0xE6DDFA, dark: 0x3A2F4F)
    public static let onAccentContainer = dynamic(light: 0x2B1A5C, dark: 0xE8DEFF)

    /// Cream "act": the single action colour of a screen.
    public static let action = dynamic(light: 0x2A2030, dark: 0xF1E3C8)
    public static let onAction = dynamic(light: 0xF6EEDD, dark: 0x2A2018)

    /// Clay: symbols, caution, destructive text (never a red fill).
    public static let caution = dynamic(light: 0xA6532E, dark: 0xE9A77E)
    /// Only for real failures.
    public static let danger = dynamic(light: 0xA23B32, dark: 0xF2A9A1)

    public static let hairline = dynamicAlpha(light: (0x221B26, 0.08), dark: (0xFFFFFF, 0.07))
    public static let scrim = Color.black.opacity(0.55)
    public static let floatingShadow = dynamicAlpha(light: (0x342446, 0.16), dark: (0x000000, 0.45))

    /// Pairs for entry monograms, in the order of the mockups.
    public static let monograms: [MonogramTone] = [
        MonogramTone(container: dynamic(light: 0xF6DFD2, dark: 0x3B271F), content: dynamic(light: 0x86421F, dark: 0xEFB896)),
        MonogramTone(container: dynamic(light: 0xF6DCE7, dark: 0x3A2131), content: dynamic(light: 0x872D57, dark: 0xEDB5CF)),
        MonogramTone(container: dynamic(light: 0xE6DDFA, dark: 0x302748), content: dynamic(light: 0x4B3590, dark: 0xD2C0F7)),
        MonogramTone(container: dynamic(light: 0xDAEADF, dark: 0x213229), content: dynamic(light: 0x2E5A40, dark: 0xA9D3BA)),
        MonogramTone(container: dynamic(light: 0xF1E5C9, dark: 0x342C1E), content: dynamic(light: 0x6A511C, dark: 0xE6CE9B)),
        MonogramTone(container: dynamic(light: 0xDCE6F5, dark: 0x1E2A3A), content: dynamic(light: 0x2A4C7A, dark: 0xAEC7EA)),
    ]

    /// Tiles for databases, the same in both themes.
    public static let vaultTiles: [MonogramTone] = [
        MonogramTone(container: fixed(0x644894), content: fixed(0xC7B0F5)),
        MonogramTone(container: fixed(0x50715F), content: fixed(0xB5DBC5)),
        MonogramTone(container: fixed(0xAB6647), content: fixed(0xF6C6A8)),
        MonogramTone(container: fixed(0x384870), content: fixed(0xAFC2EE)),
        MonogramTone(container: fixed(0xBDA67F), content: fixed(0xFFF3DA)),
    ]

    public static func tone(for key: String) -> MonogramTone {
        var hash = 5381
        for scalar in key.lowercased().unicodeScalars { hash = (hash &* 33) &+ Int(scalar.value) }
        return monograms[abs(hash % monograms.count)]
    }

    private static func fixed(_ rgb: UInt32) -> Color { Color(uiColor: UIColor(rgb: rgb)) }

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { UIColor(rgb: $0.userInterfaceStyle == .dark ? dark : light) })
    }

    private static func dynamicAlpha(light: (UInt32, Double), dark: (UInt32, Double)) -> Color {
        Color(uiColor: UIColor {
            let pick = $0.userInterfaceStyle == .dark ? dark : light
            return UIColor(rgb: pick.0, alpha: pick.1)
        })
    }
}

public struct MonogramTone: Sendable {
    public let container: Color
    public let content: Color
}

extension UIColor {
    convenience init(rgb: UInt32, alpha: Double = 1) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255,
            green: CGFloat((rgb >> 8) & 0xFF) / 255,
            blue: CGFloat(rgb & 0xFF) / 255,
            alpha: alpha
        )
    }
}

/// Radii in points, as the mockups name them.
public enum KeepRadius {
    public static let badge: CGFloat = 8
    public static let tile: CGFloat = 12
    public static let innerButton: CGFloat = 16
    public static let chip: CGFloat = 14
    public static let key: CGFloat = 18
    public static let field: CGFloat = 20
    public static let button: CGFloat = 22
    public static let card: CGFloat = 24
    public static let hero: CGFloat = 28
}
#endif
