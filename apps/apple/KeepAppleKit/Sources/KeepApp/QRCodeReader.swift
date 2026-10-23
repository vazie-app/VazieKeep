import CoreImage
import Foundation

public enum QRCodeReader {
    /// The text of the first QR code in an image, or nil when there is none.
    public static func decode(imageData: Data) -> String? {
        guard let image = CIImage(data: imageData) else { return nil }
        return decode(image)
    }

    public static func decode(_ image: CIImage) -> String? {
        let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        return detector?.features(in: image).lazy.compactMap { ($0 as? CIQRCodeFeature)?.messageString }.first
    }
}
