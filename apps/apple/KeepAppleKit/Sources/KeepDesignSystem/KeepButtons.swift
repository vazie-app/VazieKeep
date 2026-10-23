#if canImport(UIKit)
import SwiftUI

public enum KeepButtonKind: Sendable {
    /// Cream "act": once per screen.
    case action
    /// Secondary.
    case tonal
    /// Inside banners and other filled surfaces.
    case raised
    /// Low-stakes.
    case text
    /// Destructive: clay text, never a red fill.
    case caution
}

public enum KeepButtonSize: Sendable {
    case large, medium, pill

    var height: CGFloat {
        switch self {
        case .large: 64
        case .medium: 52
        case .pill: 40
        }
    }

    var radius: CGFloat {
        switch self {
        case .large: KeepRadius.button
        case .medium, .pill: KeepRadius.field
        }
    }

    var padding: CGFloat {
        switch self {
        case .large: 22
        case .medium: 20
        case .pill: 18
        }
    }
}

public struct KeepButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    let kind: KeepButtonKind
    let size: KeepButtonSize
    let fullWidth: Bool

    public init(_ kind: KeepButtonKind = .action, size: KeepButtonSize = .large, fullWidth: Bool? = nil) {
        self.kind = kind
        self.size = size
        self.fullWidth = fullWidth ?? (size != .pill && (kind == .action || kind == .tonal))
    }

    public func makeBody(configuration: Configuration) -> some View {
        let colors = palette
        configuration.label
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .font(KeepTextStyle.button.font)
            .tracking(0)
            .foregroundStyle(colors.content)
            .padding(.horizontal, size.padding)
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: size.height)
            .background(colors.container.opacity(configuration.isPressed ? 0.85 : 1), in: .rect(cornerRadius: size.radius, style: .continuous))
            .opacity(isEnabled ? 1 : 0.5)
            .contentShape(.rect(cornerRadius: size.radius))
            .frame(minHeight: 44)
    }

    private var palette: (container: Color, content: Color) {
        switch kind {
        case .action: (KeepColor.action, KeepColor.onAction)
        case .tonal: (KeepColor.surfaceField, KeepColor.text)
        case .raised: (KeepColor.surfaceRaised, KeepColor.text)
        case .text: (.clear, KeepColor.accent)
        case .caution: (.clear, KeepColor.caution)
        }
    }
}

public extension ButtonStyle where Self == KeepButtonStyle {
    static func keep(_ kind: KeepButtonKind = .action, size: KeepButtonSize = .large, fullWidth: Bool? = nil) -> KeepButtonStyle {
        KeepButtonStyle(kind, size: size, fullWidth: fullWidth)
    }
}

public enum KeepIconTone: Sendable { case plain, raised, action, accent }

/// A 44-point round (or inner-rounded) icon button with a 48-point target.
public struct KeepIconButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    let tone: KeepIconTone
    let size: CGFloat
    let radius: CGFloat?

    public init(_ tone: KeepIconTone = .plain, size: CGFloat = 44, radius: CGFloat? = nil) {
        self.tone = tone
        self.size = size
        self.radius = radius
    }

    public func makeBody(configuration: Configuration) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius ?? size / 2, style: .continuous)
        configuration.label
            .font(.system(size: 18, weight: .medium))
            .foregroundStyle(content)
            .frame(width: size, height: size)
            .background(container.opacity(configuration.isPressed ? 0.8 : 1), in: shape)
            .opacity(isEnabled ? 1 : 0.4)
            .frame(minWidth: 48, minHeight: 48)
            .contentShape(.rect)
    }

    private var container: Color {
        switch tone {
        case .plain: .clear
        case .raised: KeepColor.surfaceRaised
        case .action: KeepColor.action
        case .accent: KeepColor.accentContainer
        }
    }

    private var content: Color {
        switch tone {
        case .plain: KeepColor.textSecondary
        case .raised: KeepColor.text
        case .action: KeepColor.onAction
        case .accent: KeepColor.onAccentContainer
        }
    }
}

public extension ButtonStyle where Self == KeepIconButtonStyle {
    static func keepIcon(_ tone: KeepIconTone = .plain, size: CGFloat = 44, radius: CGFloat? = nil) -> KeepIconButtonStyle {
        KeepIconButtonStyle(tone, size: size, radius: radius)
    }
}
#endif
