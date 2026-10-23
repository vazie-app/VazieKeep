import CoreText
import Foundation

/// Geologica for all interface text, Martian Mono for technical data (secrets, paths). Static
/// instances cut from the OFL variable fonts, bundled with this package.
public enum KeepFonts {
    static let files = [
        "Geologica-Regular", "Geologica-Medium", "Geologica-Body", "Geologica-SemiBold", "Geologica-Display",
        "MartianMono-Regular", "MartianMono-SemiBold",
    ]

    private static let registration: Void = {
        for name in files {
            guard let url = Bundle.module.url(forResource: name, withExtension: "ttf", subdirectory: "Fonts") else { continue }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }()

    /// Safe to call as often as needed.
    public static func register() {
        _ = registration
    }
}
