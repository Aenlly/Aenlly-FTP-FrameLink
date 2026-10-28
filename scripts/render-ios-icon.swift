import AppKit

// Platform sizing only; the shared brand artwork remains the source of truth.
let project = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
let source = project.appendingPathComponent("design/brand/framelink-icon.png")
let destination = CommandLine.arguments[1]
guard let image=NSImage(contentsOf:source) else {fatalError("Brand artwork is missing")}
let output=NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:1024,pixelsHigh:1024,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current=NSGraphicsContext(bitmapImageRep:output)
image.draw(in:NSRect(x:0,y:0,width:1024,height:1024))
NSGraphicsContext.restoreGraphicsState()
try output.representation(using:.png,properties:[:])!.write(to:URL(fileURLWithPath:destination))
