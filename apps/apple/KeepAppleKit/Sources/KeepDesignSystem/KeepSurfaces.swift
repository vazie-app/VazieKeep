#if canImport(UIKit)
import SwiftUI

public extension View {
    /// The ground of every screen.
    func keepScreen() -> some View {
        background(KeepColor.background.ignoresSafeArea())
    }
}

/// Rounded list container (24) on the list surface; rows are separated by hairlines.
public struct KeepCard<Content: View>: View {
    let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        VStack(spacing: 0) { content }
            .frame(maxWidth: .infinity)
            .background(KeepColor.surfaceList, in: .rect(cornerRadius: KeepRadius.card, style: .continuous))
            .clipShape(.rect(cornerRadius: KeepRadius.card, style: .continuous))
    }
}

public struct KeepHairline: View {
    let inset: CGFloat

    public init(inset: CGFloat = 0) {
        self.inset = inset
    }

    public var body: some View {
        Rectangle().fill(KeepColor.hairline).frame(height: 1).padding(.leading, inset)
            .accessibilityHidden(true)
    }
}

public struct KeepMonogram: View {
    let title: String
    let size: CGFloat
    let radius: CGFloat
    let tone: MonogramTone
    let fontSize: CGFloat

    public init(_ title: String, size: CGFloat = 42, radius: CGFloat = 13, tone: MonogramTone? = nil, fontSize: CGFloat = 18) {
        self.title = title
        self.size = size
        self.radius = radius
        self.tone = tone ?? KeepColor.tone(for: title)
        self.fontSize = fontSize
        KeepFonts.register()
    }

    public var body: some View {
        let letter = title.first { $0.isLetter || $0.isNumber }.map { String($0).uppercased() } ?? "•"
        Text(letter)
            .font(.custom("Geologica-SemiBold", fixedSize: fontSize))
            .foregroundStyle(tone.content)
            .frame(width: size, height: size)
            .background(tone.container, in: .rect(cornerRadius: radius, style: .continuous))
            .accessibilityHidden(true)
    }
}

public struct KeepChip: View {
    let title: String
    let count: String?
    let systemImage: String?
    let selected: Bool

    public init(_ title: String, selected: Bool, count: String? = nil, systemImage: String? = nil) {
        self.title = title
        self.selected = selected
        self.count = count
        self.systemImage = systemImage
    }

    public var body: some View {
        HStack(spacing: 7) {
            if let systemImage { Image(systemName: systemImage).font(.system(size: 13)) }
            Text(title).font(KeepTextStyle.button.font).lineLimit(1)
            if let count { Text(count).font(KeepTextStyle.caption.font).foregroundStyle(selected ? KeepColor.onAccentContainer : KeepColor.textTertiary) }
        }
        .foregroundStyle(selected ? KeepColor.onAccentContainer : KeepColor.text)
        .padding(.horizontal, 14)
        .frame(minHeight: 40)
        .background(selected ? KeepColor.accentContainer : KeepColor.surfaceField, in: .rect(cornerRadius: KeepRadius.chip, style: .continuous))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

public enum KeepBadgeTone: Sendable { case neutral, accent, caution }

public struct KeepBadge: View {
    let text: String
    let systemImage: String?
    let tone: KeepBadgeTone

    public init(_ text: String, systemImage: String? = nil, tone: KeepBadgeTone = .neutral) {
        self.text = text
        self.systemImage = systemImage
        self.tone = tone
    }

    public var body: some View {
        HStack(spacing: 5) {
            if let systemImage { Image(systemName: systemImage).font(.system(size: 11, weight: .semibold)) }
            Text(text).font(KeepTextStyle.badge.font).lineLimit(1)
        }
        .foregroundStyle(content)
        .padding(.horizontal, 9)
        .frame(minHeight: 24)
        .background(container, in: .rect(cornerRadius: KeepRadius.badge, style: .continuous))
    }

    private var container: Color { tone == .accent ? KeepColor.accentContainer : KeepColor.surfaceRaised }

    private var content: Color {
        switch tone {
        case .neutral: KeepColor.textSecondary
        case .accent: KeepColor.onAccentContainer
        case .caution: KeepColor.caution
        }
    }
}

public enum KeepBannerTone: Sendable { case info, error, caution }

public struct KeepBanner<Actions: View>: View {
    let message: String
    let tone: KeepBannerTone
    let systemImage: String?
    let actions: Actions

    public init(_ message: String, tone: KeepBannerTone = .info, systemImage: String? = nil, @ViewBuilder actions: () -> Actions) {
        self.message = message
        self.tone = tone
        self.systemImage = systemImage
        self.actions = actions()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: systemImage ?? (tone == .info ? "info.circle" : "exclamationmark.triangle"))
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(accent)
                    .padding(.top, 2)
                    .accessibilityHidden(true)
                Text(message)
                    .font(KeepTextStyle.rowSubtitle.font)
                    .foregroundStyle(tone == .info ? KeepColor.text : accent)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if Actions.self != EmptyView.self {
                HStack(spacing: 8) {
                    Spacer(minLength: 0)
                    actions
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(KeepColor.surfaceField, in: .rect(cornerRadius: KeepRadius.field, style: .continuous))
    }

    private var accent: Color {
        switch tone {
        case .info: KeepColor.accent
        case .error: KeepColor.danger
        case .caution: KeepColor.caution
        }
    }
}

public extension KeepBanner where Actions == EmptyView {
    init(_ message: String, tone: KeepBannerTone = .info, systemImage: String? = nil) {
        self.init(message, tone: tone, systemImage: systemImage) { EmptyView() }
    }
}

public struct KeepSectionLabel: View {
    let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        Text(text)
            .font(KeepTextStyle.label.font)
            .foregroundStyle(KeepColor.textSecondary)
            .padding(.leading, 4)
            .padding(.top, 12)
            .padding(.bottom, 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A settings row: an icon tile, a title, a quiet subtitle and a trailing control.
public struct KeepSettingRow<Trailing: View>: View {
    let title: String
    let subtitle: String?
    let systemImage: String?
    let trailing: Trailing
    @Environment(\.dynamicTypeSize) private var typeSize

    public init(_ title: String, subtitle: String? = nil, systemImage: String? = nil, @ViewBuilder trailing: () -> Trailing) {
        self.title = title
        self.subtitle = subtitle
        self.systemImage = systemImage
        self.trailing = trailing()
    }

    public var body: some View {
        HStack(spacing: 14) {
            if let systemImage {
                Image(systemName: systemImage)
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(KeepColor.onAccentContainer)
                    .frame(width: 40, height: 40)
                    .background(KeepColor.accentContainer, in: .rect(cornerRadius: KeepRadius.tile, style: .continuous))
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(KeepTextStyle.rowTitle.font).foregroundStyle(KeepColor.text)
                if let subtitle {
                    Text(subtitle).font(KeepTextStyle.rowSubtitle.font).foregroundStyle(KeepColor.textSecondary)
                }
                // At the accessibility text sizes the value goes under the title: side by side they break words apart.
                if typeSize.isAccessibilitySize { trailing.padding(.top, 4) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if !typeSize.isAccessibilitySize { trailing }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minHeight: 64)
        .contentShape(.rect)
    }
}

public extension KeepSettingRow where Trailing == EmptyView {
    init(_ title: String, subtitle: String? = nil, systemImage: String? = nil) {
        self.init(title, subtitle: subtitle, systemImage: systemImage) { EmptyView() }
    }
}
#endif
