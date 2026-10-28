import AppKit

// Render the existing Android aperture mark as a square iOS icon; the OS supplies the corner mask.
let destination = CommandLine.arguments[1]
let size = 1024
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
  bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
  colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
let context = NSGraphicsContext.current!.cgContext
context.setFillColor(CGColor(red: 41/255, green: 67/255, blue: 92/255, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: size, height: size))
context.translateBy(x: 0, y: CGFloat(size))
context.scaleBy(x: CGFloat(size)/48, y: -CGFloat(size)/48)
context.setStrokeColor(NSColor.white.cgColor)
context.setLineWidth(1.8)
context.setLineCap(.round)
context.strokeEllipse(in: CGRect(x: 11, y: 11, width: 26, height: 26))
for segment in [[24.0,11,32,25],[35,18,21,18],[36,30,28,16],[24,37,16,23],[13,30,27,30],[12,18,20,32]] {
  context.move(to: CGPoint(x:segment[0],y:segment[1]))
  context.addLine(to: CGPoint(x:segment[2],y:segment[3]))
  context.strokePath()
}
NSGraphicsContext.restoreGraphicsState()
try bitmap.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath:destination))
