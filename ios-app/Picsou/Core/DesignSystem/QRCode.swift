import CoreImage.CIFilterBuiltins
import UIKit

/// Generates a crisp QR image from a string (used to render the TOTP enrolment `otpauth://` URL).
enum QRCode {
    static func image(from string: String, scale: CGFloat = 8) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: scale, y: scale)) else {
            return nil
        }
        let context = CIContext()
        guard let cgImage = context.createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
