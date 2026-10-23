#if canImport(UIKit)
import SwiftUI

/// The chrome of every field: a label above, a filled 20-point container, a 2-point lavender ring
/// on focus and a clay-rose ring with a message on error.
public struct KeepFieldChrome<Field: View, Trailing: View>: View {
    let label: String?
    let focused: Bool
    let error: String?
    let height: CGFloat
    let leadingSymbol: String?
    let supporting: String?
    let multiline: Bool
    let errorIdentifier: String?
    let field: Field
    let trailing: Trailing

    public init(
        label: String?,
        focused: Bool,
        error: String? = nil,
        height: CGFloat = 60,
        leadingSymbol: String? = nil,
        supporting: String? = nil,
        multiline: Bool = false,
        errorIdentifier: String? = nil,
        @ViewBuilder field: () -> Field,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.label = label
        self.focused = focused
        self.error = error
        self.height = height
        self.leadingSymbol = leadingSymbol
        self.supporting = supporting
        self.multiline = multiline
        self.errorIdentifier = errorIdentifier
        self.field = field()
        self.trailing = trailing()
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let label {
                Text(label)
                    .font(KeepTextStyle.label.font)
                    .foregroundStyle(KeepColor.textSecondary)
                    .padding(.leading, 4)
                    .accessibilityHidden(true)
            }
            HStack(alignment: multiline ? .top : .center, spacing: 12) {
                if let leadingSymbol {
                    Image(systemName: leadingSymbol)
                        .font(.system(size: 16))
                        .foregroundStyle(KeepColor.textTertiary)
                        .accessibilityHidden(true)
                }
                field
                    .font(KeepTextStyle.body.font)
                    .foregroundStyle(KeepColor.text)
                    .tint(KeepColor.accent)
                    .frame(maxWidth: .infinity, alignment: .leading)
                trailing
            }
            .padding(.leading, 16)
            .padding(.trailing, Trailing.self == EmptyView.self ? 16 : 8)
            .padding(.vertical, multiline ? 14 : 0)
            .frame(minHeight: height)
            .background(KeepColor.surfaceField, in: .rect(cornerRadius: KeepRadius.field, style: .continuous))
            .overlay {
                if let ring {
                    RoundedRectangle(cornerRadius: KeepRadius.field, style: .continuous).strokeBorder(ring, lineWidth: 2)
                }
            }
            if let error {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "info.circle").font(.system(size: 13)).padding(.top, 2)
                    Text(error).fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier(errorIdentifier ?? "fieldError")
                }
                .font(KeepTextStyle.caption.font)
                .foregroundStyle(KeepColor.danger)
                .padding(.horizontal, 4)
            } else if let supporting {
                Text(supporting)
                    .font(KeepTextStyle.caption.font)
                    .foregroundStyle(KeepColor.textSecondary)
                    .padding(.horizontal, 4)
            }
        }
    }

    private var ring: Color? {
        if error != nil { return KeepColor.danger }
        return focused ? KeepColor.accent : nil
    }
}

public extension KeepFieldChrome where Trailing == EmptyView {
    init(
        label: String?,
        focused: Bool,
        error: String? = nil,
        height: CGFloat = 60,
        leadingSymbol: String? = nil,
        supporting: String? = nil,
        multiline: Bool = false,
        errorIdentifier: String? = nil,
        @ViewBuilder field: () -> Field
    ) {
        self.init(label: label, focused: focused, error: error, height: height, leadingSymbol: leadingSymbol, supporting: supporting, multiline: multiline, errorIdentifier: errorIdentifier, field: field) { EmptyView() }
    }
}

/// A plain text field in the design's chrome. Accessibility identifiers go on the field itself.
public struct KeepTextField<Trailing: View>: View {
    let label: String?
    let placeholder: String
    @Binding var text: String
    let error: String?
    let height: CGFloat
    let leadingSymbol: String?
    let supporting: String?
    let axis: Axis
    let identifier: String?
    let autofocus: Bool
    let trailing: Trailing
    @FocusState private var focused: Bool

    public init(
        _ placeholder: String,
        text: Binding<String>,
        label: String? = nil,
        error: String? = nil,
        height: CGFloat = 60,
        leadingSymbol: String? = nil,
        supporting: String? = nil,
        axis: Axis = .horizontal,
        identifier: String? = nil,
        autofocus: Bool = false,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.placeholder = placeholder
        _text = text
        self.label = label
        self.error = error
        self.height = height
        self.leadingSymbol = leadingSymbol
        self.supporting = supporting
        self.axis = axis
        self.identifier = identifier
        self.autofocus = autofocus
        self.trailing = trailing()
    }

    public var body: some View {
        KeepFieldChrome(label: label, focused: focused, error: error, height: height, leadingSymbol: leadingSymbol, supporting: supporting, multiline: axis == .vertical) {
            TextField(placeholder, text: $text, prompt: Text(placeholder).foregroundStyle(KeepColor.textTertiary), axis: axis)
                .focused($focused)
                .accessibilityIdentifier(identifier ?? "")
        } trailing: {
            trailing
        }
        // The whole field takes the tap, not only the line of text inside it.
        .contentShape(.rect)
        .onTapGesture { focused = true }
        .onAppear { if autofocus { focused = true } }
    }
}

public extension KeepTextField where Trailing == EmptyView {
    init(
        _ placeholder: String,
        text: Binding<String>,
        label: String? = nil,
        error: String? = nil,
        height: CGFloat = 60,
        leadingSymbol: String? = nil,
        supporting: String? = nil,
        axis: Axis = .horizontal,
        identifier: String? = nil,
        autofocus: Bool = false
    ) {
        self.init(placeholder, text: text, label: label, error: error, height: height, leadingSymbol: leadingSymbol, supporting: supporting, axis: axis, identifier: identifier, autofocus: autofocus) { EmptyView() }
    }
}
#endif
