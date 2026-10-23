#if canImport(UIKit)
import SwiftUI
import UIKit

/// Draws a revealed secret without creating a text view, so the string never becomes an
/// accessibility element, not even a hidden one. Digits are lavender and symbols clay. A secret is
/// always one line: a long one scrolls sideways instead of wrapping or being cut.
public struct KeepSecretText: View {
    let value: String?
    let fontSize: CGFloat
    @ScaledMetric(relativeTo: .body) private var scale: CGFloat = 1

    public init(value: String?, fontSize: CGFloat = 17.5) {
        self.value = value
        self.fontSize = fontSize
    }

    public var body: some View {
        let font = UIFont(name: "MartianMono-Regular", size: fontSize * scale) ?? .monospacedSystemFont(ofSize: fontSize * scale, weight: .regular)
        Group {
            if let value {
                let width = ceil((value as NSString).size(withAttributes: [.font: font]).width) + 2
                let height = font.lineHeight * 1.2
                ScrollView(.horizontal, showsIndicators: false) {
                    Canvas { context, _ in
                        context.draw(context.resolve(Self.colored(value, font: font)), at: .zero, anchor: .topLeading)
                    }
                    .frame(width: width, height: height)
                }
                .frame(height: height)
            } else {
                KeepHiddenDots()
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .privacySensitive()
        .accessibilityHidden(true)
    }

    static func colored(_ line: String, font: UIFont) -> Text {
        var result = Text("")
        for character in line {
            let color: Color = if character.isNumber { KeepColor.accent } else if !(character.isLetter || character.isWhitespace) { KeepColor.caution } else { KeepColor.text }
            result = result + Text(String(character)).font(Font(font)).foregroundStyle(color)
        }
        return result
    }
}

/// Twelve dots, whatever the length of the secret.
public struct KeepHiddenDots: View {
    let count: Int

    public init(count: Int = 12) {
        self.count = count
    }

    public var body: some View {
        HStack(spacing: 3) {
            ForEach(0..<count, id: \.self) { _ in
                Circle().fill(KeepColor.text).frame(width: 8, height: 8)
            }
        }
        .frame(height: 28, alignment: .leading)
        .accessibilityHidden(true)
    }
}

public struct KeepStrengthMeter: View {
    let level: Int
    let caption: String
    let detail: String?

    /// `level` runs 0 (fragile) to 4 (formidable); a negative level draws five empty segments.
    public init(level: Int, caption: String, detail: String? = nil) {
        self.level = level
        self.caption = caption
        self.detail = detail
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 5) {
                ForEach(0..<5, id: \.self) { index in
                    Capsule()
                        .fill(index <= level ? (level == 0 ? KeepColor.caution : KeepColor.accent) : KeepColor.surfaceToast)
                        .frame(height: 6)
                }
            }
            if !caption.isEmpty {
                HStack(spacing: 6) {
                    Text(caption).font(KeepTextStyle.label.font).foregroundStyle(KeepColor.text)
                    if let detail { Text("· \(detail)").font(KeepTextStyle.caption.font).foregroundStyle(KeepColor.textSecondary) }
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A rough, local estimate for the strength hint: character classes times length. It never leaves
/// the device and is not a substitute for choosing a generated password.
public enum KeepPasswordStrength {
    public struct Estimate: Sendable {
        public let level: Int
        public let bits: Int
    }

    public static func estimate(_ password: String) -> Estimate? {
        guard !password.isEmpty else { return nil }
        var lower = false, upper = false, digit = false, symbol = false, other = false
        for scalar in password.unicodeScalars {
            switch scalar.value {
            case 0x61...0x7A: lower = true
            case 0x41...0x5A: upper = true
            case 0x30...0x39: digit = true
            case 0..<0x80: symbol = true
            default: other = true
            }
        }
        let pool = (lower ? 26 : 0) + (upper ? 26 : 0) + (digit ? 10 : 0) + (symbol ? 33 : 0) + (other ? 64 : 0)
        let bits = Double(password.count) * log2(Double(max(pool, 2)))
        let level = switch bits {
        case ..<40: 0
        case ..<60: 1
        case ..<80: 2
        case ..<100: 3
        default: 4
        }
        return Estimate(level: level, bits: Int(bits.rounded()))
    }
}
#endif
