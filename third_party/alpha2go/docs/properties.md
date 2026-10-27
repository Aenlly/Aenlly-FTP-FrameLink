# Property catalog

The package `propdb` describes the device property codes this library knows:
a name, a description, a group, a unit and value labels where the wire
protocol defines them, and how careful a caller should be. The camera
reports the current value, the datatype and the allowed values itself (see
[protocol.md](protocol.md#device-properties-0x9209--0x9205--0x9251)); the
catalog only adds meaning.

- The standard codes 0x5001-0x501F follow the PTP device property list. The
  camera may redefine their values; descriptions say "as reported by the
  camera" where it does, and no label is given for values the standard does
  not define.
- Sony codes are the ones verified on the a6700 and listed in protocol.md.
- Control codes (0xD2xx, 0xD3xx) trigger actions through 0x9207. They have no
  value of their own and are not set through 0x9205.
- Unit: the displayed value is the raw value divided by the scale.
- Danger tiers: `caution` changes behaviour or wears something but is
  recoverable; `danger` can lose shots or break the setup.

The Type column is the datatype of the standard definition, or the one found
by testing for Sony codes. The camera's own datatype in each property entry
always wins.

### Exposure

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0x5007 | F-number | u16 | Lens aperture as an f-stop. Values are shown as the camera reports them. | raw / 100  | - |
| 0x5008 | Focal length | u32 | Focal length of the lens; zoom lenses can be changed. | raw / 100 mm | - |
| 0x500B | Metering mode | u16 | Which part of the image is measured for exposure. Values are shown as the camera reports them. | 0x1 = Average, 0x2 = Center-weighted average, 0x3 = Multi-spot, 0x4 = Center-spot | - |
| 0x500C | Flash mode | u16 | When and how the flash fires. Values are shown as the camera reports them. | 0x1 = Auto, 0x2 = Off, 0x3 = Fill, 0x4 = Red-eye auto, 0x5 = Red-eye fill, 0x6 = External sync | - |
| 0x500D | Exposure time | u32 | Shutter speed. Values are shown as the camera reports them. | raw / 10000 s | - |
| 0x500E | Exposure program | u16 | Which exposure mode is active (manual, priority modes, program). Values are shown as the camera reports them. | 0x1 = Manual, 0x2 = Automatic, 0x3 = Aperture priority, 0x4 = Shutter priority, 0x5 = Program creative, 0x6 = Program action, 0x7 = Portrait | - |
| 0x500F | ISO | u16 | Sensor sensitivity. Values are shown as the camera reports them. | number | - |
| 0x5010 | Exposure compensation | i16 | Shifts the automatic exposure brighter or darker. | raw / 1000 EV | - |

### Focus

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0x5009 | Focus distance | u16 | Distance to the focus point as the camera reports it. | number | - |
| 0x500A | Focus mode | u16 | Manual or automatic focus. Values are shown as the camera reports them. | 0x1 = Manual, 0x2 = Automatic, 0x3 = Automatic macro | - |
| 0x501C | Focus metering mode | u16 | Which focus points are used. Values are shown as the camera reports them. | number | - |
| 0xD22C | Focus area | u16 | Which part of the frame is used for autofocus. Some values depend on the focus mode. | 0x1 = Wide, 0x102 = Spot M | - |
| 0xD284 | Remote touch usable | u8 | 1 while a remote touch (start tracking at a point) is accepted. | 0x1 = Usable | - |
| 0xD285 | Touch cancel usable | u8 | 1 while a remote touch can be cancelled. | 0x1 = Usable | - |
| 0xD2DC | AF area position | u32 | Moves the AF area; needs a spot or zone focus area. Driven by the shutter, movie and button actions, never set as a value. | number | caution: Moves the autofocus area on the camera. |
| 0xD2E4 | Remote touch | u32 | Touch at a position of the live view. Driven by the shutter, movie and button actions, never set as a value. | number | caution: Starts tracking at a point of the frame. |
| 0xD2E5 | Remote touch cancel | u16 | Cancels a remote touch. Driven by the shutter, movie and button actions, never set as a value. | number | caution: Cancels tracking. |

### Drive

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0x5012 | Capture delay | u32 | Delay between the shutter press and the exposure. Values are shown as the camera reports them. | number | - |
| 0x5013 | Still capture mode | u16 | Single shot, burst or time lapse. Values are shown as the camera reports them. | 0x1 = Normal, 0x2 = Burst, 0x3 = Time lapse | - |
| 0x5018 | Burst number | u16 | Number of frames in a burst. | number | - |
| 0x5019 | Burst interval | u16 | Time between frames in a burst. | number | - |
| 0x501A | Time-lapse number | u32 | Number of frames in a time lapse. | number | - |
| 0x501B | Time-lapse interval | u32 | Time between frames in a time lapse. | number | - |
| 0xD2C1 | Shutter half-press | u16 | Half-press of the shutter: autofocus and exposure lock. Driven by the shutter, movie and button actions, never set as a value. | number | caution: Each press counts as shutter use. |
| 0xD2C2 | Shutter full-press | u16 | Full press of the shutter. Driven by the shutter, movie and button actions, never set as a value. | number | caution: Takes a picture, which uses card space and shutter life. |

### White balance

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0x5005 | White balance | u16 | How the camera corrects the colour cast of the light. Values are shown as the camera reports them. | 0x1 = Manual, 0x2 = Automatic, 0x3 = One-push automatic, 0x4 = Daylight, 0x5 = Fluorescent, 0x6 = Tungsten, 0x7 = Flash | - |
| 0x5006 | RGB gain | string | Red, green and blue gain used for manual white balance. | number | - |

### Image

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0x5003 | Image size | string | Pixel size of still images. Values are shown as the camera reports them. | number | - |
| 0x5004 | Compression setting | u8 | How strongly still images are compressed. Values are shown as the camera reports them. | number | - |
| 0x5014 | Contrast | u8 | Image contrast. | number | - |
| 0x5015 | Sharpness | u8 | Image sharpening. | number | - |
| 0x5016 | Digital zoom | u8 | Digital zoom factor. | number | - |
| 0x5017 | Effect mode | u16 | Picture effect such as black and white. Values are shown as the camera reports them. | number | - |

### Movie

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0xD2C8 | Movie record button | u16 | Press and release toggles movie recording. Driven by the shutter, movie and button actions, never set as a value. | number | caution: Starts or stops a recording, which uses card space. |

### Display

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0xD207 | Overlay image mode | u8 | Whether the camera shows the overlay image sent by the host. The mode resets when the connection is made again. | 0x1 = On | - |
| 0xD278 | Live-view URL | string | Address of the live-view stream. The host in it is always localhost. | number | - |

### Transfer

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0x501D | Upload URL | string | Address the camera uploads to. | number | - |
| 0xD215 | Captured image ready | u16 | Bit 15 is set when a new shot is ready for the host; the low bits are the number of shots. | number | - |
| 0xD222 | Still image destination | u16 | Where new still images go: the host, the memory card or both. | 0x1 = Host only, 0x10 = Memory card only, 0x11 = Host and memory card | danger: With the host-only value shots are NOT written to the memory card. |
| 0xD268 | Image size sent to host | u8 | Size of the image copy sent to the host after a shot. | 0x1 = Original, 0x2 = Small | caution: Changes what the host receives for new shots. |

### Buttons & dials

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0xD208 | Usable buttons | u32 | The camera buttons that can be pressed over the network; the settable values are the button numbers. | number | - |
| 0xD20A | Usable dials | u32 | The dials that can be turned over the network; the settable values are the dial numbers. | number | - |
| 0xD309 | Camera button | u32 | Presses a camera button, one at a time and only while the camera is idle. Driven by the shutter, movie and button actions, never set as a value. | number | caution: Button presses can change camera state; using the menu was seen to switch the exposure mode. |
| 0xD30B | Camera dial | i32 | Turns a dial by steps; positive is clockwise. Driven by the shutter, movie and button actions, never set as a value. | number | caution: Turning a dial changes the setting it is assigned to, such as aperture. |

### Device

| Code | Name | Type | Meaning | Values | Danger |
|---|---|---|---|---|---|
| 0x5001 | Battery level | u8 | Remaining battery charge as the camera reports it. | number | - |
| 0x5002 | Functional mode | u16 | Whether the camera is in its standard mode or in a sleep state. | 0x0 = Standard, 0x1 = Sleep | - |
| 0x5011 | Date and time | string | The camera clock. | number | caution: Changes the camera clock, which changes file timestamps and the order of shots in Immich. |
| 0x501E | Artist | string | Artist name stored in the files. | number | - |
| 0x501F | Copyright | string | Copyright text stored in the files. | number | - |
| 0xD20C | Camera idle | u8 | 1 while the camera is idle and accepts button presses. | 0x1 = Idle | - |

## Undocumented codes

A code that is not in the table above is "undocumented": it has no name,
description or labels and a UI should group it as such, show the raw number
and require an explicit opt-in before writing it. A code must be documented
here and added to `propdb` (with a test) before a front end treats it as
known. Do not add a label that has not been verified on a camera.
