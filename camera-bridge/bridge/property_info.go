package bridge

import "github.com/knallli/alpha2go/propdb"

// Sony exposure properties use vendor codes on modern cameras. The exact enum
// and writable flag are still supplied and checked by the connected camera.
func cameraPropertyInfo(code uint16) (propdb.Info, bool) {
	switch code {
	case 0xD20D:
		return propdb.Info{Code: code, Name: "Shutter speed", Group: "Exposure", Widget: "select"}, true
	case 0xD21E:
		return propdb.Info{Code: code, Name: "ISO", Group: "Exposure", Widget: "select"}, true
	case sonyCreativeLook:
		return propdb.Info{Code: code, Name: "Creative Look", Group: "Creative Look", Widget: "select", Labels: creativeLookLabels}, true
	default:
		return propdb.Lookup(code)
	}
}
