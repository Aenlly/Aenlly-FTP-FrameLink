import Foundation

public enum ExposureTimeText {
  public static func seconds(_ seconds: Double) -> String? {
    guard seconds.isFinite, seconds > 0 else { return nil }
    let reciprocal = 1 / seconds; let denominator = reciprocal.rounded()
    if seconds < 1, denominator >= 2, denominator.isFinite, abs(1 / denominator - seconds) <= seconds * 0.000001 {
      return String(format: "1/%.0f 秒", locale: Locale(identifier: "en_US_POSIX"), denominator)
    }
    return String(format: "%.6g 秒", locale: Locale(identifier: "en_US_POSIX"), seconds)
  }
  public static func standardPTPShutter(_ raw: Int64) -> String {
    let value = Double(raw) / 10000
    // Keep existing handling outside positive subsecond durations; do not reinterpret special values.
    guard value > 0, value < 1, let text = seconds(value) else { return String(format: "%.1f秒", value) }
    return text.replacingOccurrences(of: " 秒", with: text.hasPrefix("1/") ? "" : "秒")
  }
}
