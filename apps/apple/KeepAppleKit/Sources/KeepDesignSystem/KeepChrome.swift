#if canImport(UIKit)
import SwiftUI

/// The floating dock of the mockups: two tabs and, optionally, the cream add button.
public struct KeepDock: View {
    public enum Tab: Sendable { case keep, generator }

    let selected: Tab
    let keepTitle: String
    let generatorTitle: String
    let addLabel: String?
    let onKeep: () -> Void
    let onGenerator: () -> Void
    let onAdd: (() -> Void)?

    public init(
        selected: Tab,
        keepTitle: String,
        generatorTitle: String,
        addLabel: String? = nil,
        onKeep: @escaping () -> Void,
        onGenerator: @escaping () -> Void,
        onAdd: (() -> Void)? = nil
    ) {
        self.selected = selected
        self.keepTitle = keepTitle
        self.generatorTitle = generatorTitle
        self.addLabel = addLabel
        self.onKeep = onKeep
        self.onGenerator = onGenerator
        self.onAdd = onAdd
    }

    public var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 4) {
                item(keepTitle, symbol: "archivebox", tab: .keep, action: onKeep)
                item(generatorTitle, symbol: "dice", tab: .generator, action: onGenerator)
            }
            .padding(6)
            .background(KeepColor.surfaceRaised, in: .capsule)
            .shadow(color: KeepColor.floatingShadow, radius: 16, y: 10)
            if let onAdd, let addLabel {
                Button(action: onAdd) {
                    Image(systemName: "plus")
                        .font(.system(size: 24, weight: .medium))
                        .foregroundStyle(KeepColor.onAction)
                        .frame(width: 64, height: 64)
                        .background(KeepColor.action, in: .rect(cornerRadius: KeepRadius.card, style: .continuous))
                        .shadow(color: KeepColor.floatingShadow, radius: 16, y: 10)
                }
                .accessibilityLabel(Text(addLabel))
                .accessibilityIdentifier("add")
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
        .padding(.top, 8)
    }

    private func item(_ title: String, symbol: String, tab: Tab, action: @escaping () -> Void) -> some View {
        let on = selected == tab
        return Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: symbol).font(.system(size: 16, weight: .medium))
                Text(title).font(KeepTextStyle.button.font).lineLimit(1).minimumScaleFactor(0.6)
            }
            // Like a system tab bar, the dock stops growing before its labels stop fitting.
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            .foregroundStyle(on ? KeepColor.onAccentContainer : KeepColor.textSecondary)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(on ? KeepColor.accentContainer : .clear, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier(tab == .keep ? "dock.keep" : "dock.generator")
    }
}

/// Copy feedback: rises from the bottom, the line drains for the clipboard timeout.
public struct KeepToast: View {
    let message: String

    public init(_ message: String) {
        self.message = message
    }

    public var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "checkmark").font(.system(size: 16, weight: .semibold)).foregroundStyle(KeepColor.accent)
            Text(message).font(KeepTextStyle.rowSubtitle.font).foregroundStyle(KeepColor.text)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(KeepColor.surfaceToast, in: .rect(cornerRadius: KeepRadius.field, style: .continuous))
        .shadow(color: KeepColor.floatingShadow, radius: 12, y: 6)
        .accessibilityElement(children: .combine)
    }
}

/// A screen title in the display scale, used instead of the large navigation title.
public struct KeepScreenTitle: View {
    let text: String

    public init(_ text: String) {
        self.text = text
    }

    public var body: some View {
        Text(text)
            .keepText(.headline)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}
#endif
