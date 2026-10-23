#if canImport(UIKit)
import SwiftUI

/// The type scale of `design/` (Foundations): Geologica 650/600/550/400 and Martian Mono 450, each
/// style scaling with Dynamic Type.
public enum KeepTextStyle {
    case display, headline, title, bodyLarge, body, rowTitle, rowSubtitle, label, caption, badge, button, secret, code, mono

    private var spec: (name: String, size: CGFloat, relativeTo: Font.TextStyle, tracking: CGFloat) {
        switch self {
        case .display: ("Geologica-Display", 38.5, .largeTitle, -0.03)
        case .headline: ("Geologica-Display", 29, .title, -0.02)
        case .title: ("Geologica-SemiBold", 22, .title2, -0.012)
        case .bodyLarge: ("Geologica-Body", 17, .headline, 0)
        case .body: ("Geologica-Regular", 16, .body, 0)
        case .rowTitle: ("Geologica-Body", 16, .body, 0)
        case .rowSubtitle: ("Geologica-Regular", 13.5, .subheadline, 0)
        case .label: ("Geologica-SemiBold", 13, .footnote, 0)
        case .caption: ("Geologica-Regular", 13, .footnote, 0)
        case .badge: ("Geologica-SemiBold", 12, .caption, 0)
        case .button: ("Geologica-SemiBold", 16, .headline, 0)
        case .secret: ("MartianMono-Regular", 18.5, .body, 0.02)
        case .code: ("MartianMono-Regular", 24, .title2, 0)
        case .mono: ("MartianMono-Regular", 12, .caption, 0)
        }
    }

    public var font: Font {
        KeepFonts.register()
        return .custom(spec.name, size: spec.size, relativeTo: spec.relativeTo)
    }

    public var uiFont: UIFont {
        KeepFonts.register()
        let base = UIFont(name: spec.name, size: spec.size) ?? .systemFont(ofSize: spec.size)
        return UIFontMetrics(forTextStyle: spec.relativeTo.uiTextStyle).scaledFont(for: base)
    }

    var trackingPoints: CGFloat { spec.tracking * spec.size }
}

extension Font.TextStyle {
    var uiTextStyle: UIFont.TextStyle {
        switch self {
        case .largeTitle: .largeTitle
        case .title: .title1
        case .title2: .title2
        case .title3: .title3
        case .headline: .headline
        case .subheadline: .subheadline
        case .callout: .callout
        case .footnote: .footnote
        case .caption: .caption1
        case .caption2: .caption2
        default: .body
        }
    }
}

public extension View {
    func keepText(_ style: KeepTextStyle, color: Color = KeepColor.text) -> some View {
        font(style.font)
            .tracking(style.trackingPoints)
            .foregroundStyle(color)
    }
}
#endif
