// Package propdb is the catalog of camera property codes: for each documented
// code a name, a plain-English description, a group, a unit and, where the
// wire protocol defines them, value labels. It is data only and does not talk
// to a camera. A code that is not in the catalog is undocumented; a missing
// label means the number should be shown as it is. The catalog is described
// in docs/properties.md, and codes must be documented there first.
package propdb

// Danger is how careful the user should be before changing a property.
type Danger uint8

const (
	None      Danger = iota
	Caution          // changes behaviour or wears something, but is recoverable
	Dangerous        // can lose shots or break the setup
)

// Tier is the Danger level as a short word for JSON and CSS.
func (d Danger) Tier() string {
	switch d {
	case Caution:
		return "caution"
	case Dangerous:
		return "danger"
	}
	return "none"
}

// Info describes one property code.
type Info struct {
	Code        uint16
	Name, Group string
	Desc        string
	Widget      string // hint: switch, select, slider, number, text, readonly; the UI decides from the camera's form
	Danger      Danger
	Note        string  // why it is dangerous
	Unit        string  // shown after the value
	Scale       float64 // display = raw / Scale; 0 = none
	Labels      map[int64]string
}

// Groups is the display order of the groups.
var Groups = []string{"Exposure", "Focus", "Drive", "White balance", "Image", "Movie", "Display", "Transfer", "Buttons & dials", "Device", "Undocumented"}

// Lookup returns the description of a code.
func Lookup(code uint16) (Info, bool) {
	i, ok := byCode[code]
	return i, ok
}

// All returns every documented property in table order.
func All() []Info { return table }

var byCode = func() map[uint16]Info {
	m := make(map[uint16]Info, len(table))
	for _, i := range table {
		if _, dup := m[i.Code]; !dup {
			m[i.Code] = i
		}
	}
	return m
}()

const reported = " Values are shown as the camera reports them."
const action = "Driven by the shutter, movie and button actions, never set as a value."

var table = []Info{
	// Standard device properties (PIMA 15740, 0x5001-0x501F).
	{Code: 0x5001, Name: "Battery level", Group: "Device", Widget: "readonly", Desc: "Remaining battery charge as the camera reports it."},
	{Code: 0x5002, Name: "Functional mode", Group: "Device", Widget: "select", Desc: "Whether the camera is in its standard mode or in a sleep state.",
		Labels: map[int64]string{0: "Standard", 1: "Sleep"}},
	{Code: 0x5003, Name: "Image size", Group: "Image", Widget: "select", Desc: "Pixel size of still images." + reported},
	{Code: 0x5004, Name: "Compression setting", Group: "Image", Widget: "select", Desc: "How strongly still images are compressed." + reported},
	{Code: 0x5005, Name: "White balance", Group: "White balance", Widget: "select", Desc: "How the camera corrects the colour cast of the light." + reported,
		Labels: map[int64]string{1: "Manual", 2: "Automatic", 3: "One-push automatic", 4: "Daylight", 5: "Fluorescent", 6: "Tungsten", 7: "Flash"}},
	{Code: 0x5006, Name: "RGB gain", Group: "White balance", Widget: "text", Desc: "Red, green and blue gain used for manual white balance."},
	{Code: 0x5007, Name: "F-number", Group: "Exposure", Widget: "select", Scale: 100, Desc: "Lens aperture as an f-stop." + reported},
	{Code: 0x5008, Name: "Focal length", Group: "Exposure", Widget: "select", Scale: 100, Unit: "mm", Desc: "Focal length of the lens; zoom lenses can be changed."},
	{Code: 0x5009, Name: "Focus distance", Group: "Focus", Widget: "number", Desc: "Distance to the focus point as the camera reports it."},
	{Code: 0x500A, Name: "Focus mode", Group: "Focus", Widget: "select", Desc: "Manual or automatic focus." + reported,
		Labels: map[int64]string{1: "Manual", 2: "Automatic", 3: "Automatic macro"}},
	{Code: 0x500B, Name: "Metering mode", Group: "Exposure", Widget: "select", Desc: "Which part of the image is measured for exposure." + reported,
		Labels: map[int64]string{1: "Average", 2: "Center-weighted average", 3: "Multi-spot", 4: "Center-spot"}},
	{Code: 0x500C, Name: "Flash mode", Group: "Exposure", Widget: "select", Desc: "When and how the flash fires." + reported,
		Labels: map[int64]string{1: "Auto", 2: "Off", 3: "Fill", 4: "Red-eye auto", 5: "Red-eye fill", 6: "External sync"}},
	{Code: 0x500D, Name: "Exposure time", Group: "Exposure", Widget: "select", Scale: 10000, Unit: "s", Desc: "Shutter speed." + reported},
	{Code: 0x500E, Name: "Exposure program", Group: "Exposure", Widget: "select", Desc: "Which exposure mode is active (manual, priority modes, program)." + reported,
		Labels: map[int64]string{1: "Manual", 2: "Automatic", 3: "Aperture priority", 4: "Shutter priority", 5: "Program creative", 6: "Program action", 7: "Portrait"}},
	{Code: 0x500F, Name: "ISO", Group: "Exposure", Widget: "select", Desc: "Sensor sensitivity." + reported},
	{Code: 0x5010, Name: "Exposure compensation", Group: "Exposure", Widget: "select", Scale: 1000, Unit: "EV", Desc: "Shifts the automatic exposure brighter or darker."},
	{Code: 0x5011, Name: "Date and time", Group: "Device", Widget: "text", Danger: Caution,
		Note: "Changes the camera clock, which changes file timestamps and the order of shots in Immich.",
		Desc: "The camera clock."},
	{Code: 0x5012, Name: "Capture delay", Group: "Drive", Widget: "number", Desc: "Delay between the shutter press and the exposure." + reported},
	{Code: 0x5013, Name: "Still capture mode", Group: "Drive", Widget: "select", Desc: "Single shot, burst or time lapse." + reported,
		Labels: map[int64]string{1: "Normal", 2: "Burst", 3: "Time lapse"}},
	{Code: 0x5014, Name: "Contrast", Group: "Image", Widget: "slider", Desc: "Image contrast."},
	{Code: 0x5015, Name: "Sharpness", Group: "Image", Widget: "slider", Desc: "Image sharpening."},
	{Code: 0x5016, Name: "Digital zoom", Group: "Image", Widget: "select", Desc: "Digital zoom factor."},
	{Code: 0x5017, Name: "Effect mode", Group: "Image", Widget: "select", Desc: "Picture effect such as black and white." + reported},
	{Code: 0x5018, Name: "Burst number", Group: "Drive", Widget: "number", Desc: "Number of frames in a burst."},
	{Code: 0x5019, Name: "Burst interval", Group: "Drive", Widget: "number", Desc: "Time between frames in a burst."},
	{Code: 0x501A, Name: "Time-lapse number", Group: "Drive", Widget: "number", Desc: "Number of frames in a time lapse."},
	{Code: 0x501B, Name: "Time-lapse interval", Group: "Drive", Widget: "number", Desc: "Time between frames in a time lapse."},
	{Code: 0x501C, Name: "Focus metering mode", Group: "Focus", Widget: "select", Desc: "Which focus points are used." + reported},
	{Code: 0x501D, Name: "Upload URL", Group: "Transfer", Widget: "text", Desc: "Address the camera uploads to."},
	{Code: 0x501E, Name: "Artist", Group: "Device", Widget: "text", Desc: "Artist name stored in the files."},
	{Code: 0x501F, Name: "Copyright", Group: "Device", Widget: "text", Desc: "Copyright text stored in the files."},

	// Sony properties documented in the alpha2go protocol notes.
	{Code: 0xD207, Name: "Overlay image mode", Group: "Display", Widget: "switch", Labels: map[int64]string{1: "On"},
		Desc: "Whether the camera shows the overlay image sent by the host. The mode resets when the connection is made again."},
	{Code: 0xD208, Name: "Usable buttons", Group: "Buttons & dials", Widget: "readonly",
		Desc: "The camera buttons that can be pressed over the network; the settable values are the button numbers."},
	{Code: 0xD20A, Name: "Usable dials", Group: "Buttons & dials", Widget: "readonly",
		Desc: "The dials that can be turned over the network; the settable values are the dial numbers."},
	{Code: 0xD20C, Name: "Camera idle", Group: "Device", Widget: "readonly", Labels: map[int64]string{1: "Idle"},
		Desc: "1 while the camera is idle and accepts button presses."},
	{Code: 0xD215, Name: "Captured image ready", Group: "Transfer", Widget: "readonly",
		Desc: "Bit 15 is set when a new shot is ready for the host; the low bits are the number of shots."},
	{Code: 0xD222, Name: "Still image destination", Group: "Transfer", Widget: "select", Danger: Dangerous,
		Note:   "With the host-only value shots are NOT written to the memory card.",
		Desc:   "Where new still images go: the host, the memory card or both.",
		Labels: map[int64]string{1: "Host only", 0x10: "Memory card only", 0x11: "Host and memory card"}},
	{Code: 0xD22C, Name: "Focus area", Group: "Focus", Widget: "select",
		Desc:   "Which part of the frame is used for autofocus. Some values depend on the focus mode.",
		Labels: map[int64]string{1: "Wide", 0x0102: "Spot M"}},
	{Code: 0xD268, Name: "Image size sent to host", Group: "Transfer", Widget: "select", Danger: Caution,
		Note:   "Changes what the host receives for new shots.",
		Desc:   "Size of the image copy sent to the host after a shot.",
		Labels: map[int64]string{1: "Original", 2: "Small"}},
	{Code: 0xD278, Name: "Live-view URL", Group: "Display", Widget: "readonly",
		Desc: "Address of the live-view stream. The host in it is always localhost."},
	{Code: 0xD284, Name: "Remote touch usable", Group: "Focus", Widget: "readonly", Labels: map[int64]string{1: "Usable"},
		Desc: "1 while a remote touch (start tracking at a point) is accepted."},
	{Code: 0xD285, Name: "Touch cancel usable", Group: "Focus", Widget: "readonly", Labels: map[int64]string{1: "Usable"},
		Desc: "1 while a remote touch can be cancelled."},

	// Control codes: they trigger actions and have no value of their own.
	{Code: 0xD2C1, Name: "Shutter half-press", Group: "Drive", Widget: "readonly", Danger: Caution,
		Note: "Each press counts as shutter use.", Desc: "Half-press of the shutter: autofocus and exposure lock. " + action},
	{Code: 0xD2C2, Name: "Shutter full-press", Group: "Drive", Widget: "readonly", Danger: Caution,
		Note: "Takes a picture, which uses card space and shutter life.", Desc: "Full press of the shutter. " + action},
	{Code: 0xD2C8, Name: "Movie record button", Group: "Movie", Widget: "readonly", Danger: Caution,
		Note: "Starts or stops a recording, which uses card space.", Desc: "Press and release toggles movie recording. " + action},
	{Code: 0xD2DC, Name: "AF area position", Group: "Focus", Widget: "readonly", Danger: Caution,
		Note: "Moves the autofocus area on the camera.", Desc: "Moves the AF area; needs a spot or zone focus area. " + action},
	{Code: 0xD2E4, Name: "Remote touch", Group: "Focus", Widget: "readonly", Danger: Caution,
		Note: "Starts tracking at a point of the frame.", Desc: "Touch at a position of the live view. " + action},
	{Code: 0xD2E5, Name: "Remote touch cancel", Group: "Focus", Widget: "readonly", Danger: Caution,
		Note: "Cancels tracking.", Desc: "Cancels a remote touch. " + action},
	{Code: 0xD309, Name: "Camera button", Group: "Buttons & dials", Widget: "readonly", Danger: Caution,
		Note: "Button presses can change camera state; using the menu was seen to switch the exposure mode.",
		Desc: "Presses a camera button, one at a time and only while the camera is idle. " + action},
	{Code: 0xD30B, Name: "Camera dial", Group: "Buttons & dials", Widget: "readonly", Danger: Caution,
		Note: "Turning a dial changes the setting it is assigned to, such as aperture.",
		Desc: "Turns a dial by steps; positive is clockwise. " + action},
}
