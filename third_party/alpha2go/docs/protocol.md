# Sony PTP/IP wire protocol

This page describes what a Sony Alpha camera sends and accepts over PTP/IP, as
observed on an **ILCE-6700 (α6700), firmware 2.00**. Each entry was tested on
that camera unless it is marked *not verified*. For a feature list, see
[a6700.md](a6700.md).

PTP/IP itself is specified in CIPA DC-005 and PTP in ISO 15740. This page only
covers what goes beyond those specs. All integers are little-endian.

- [Connection](#connection)
- [Session modes](#session-modes)
- [Device properties](#device-properties-0x9209--0x9205--0x9251)
- [Controls](#controls-0x9207)
- [Live view](#live-view)
- [OSD image](#osd-image)
- [Contents: list, download, thumbnails](#contents-0x923b0x923e)
- [Captured images to the host](#captured-images-to-the-host)
- [Information operations](#information-operations)
- [Write operations](#write-operations)
- [Events](#events)
- [MTP object properties](#mtp-object-properties-0x98010x9805)
- [Rejected by the a6700](#rejected-by-the-a6700)
- [Safety](#safety)

## Connection

### Access Authentication on

- TCP 15740 is closed. Only the camera's SSH server on port 22 is open (`SSH-2.0-OpenSSH_7.9`, ECDSA host key).
- The fingerprint shown on the camera (MENU → Network → Network Option → Access Authen. Info) has the same format as `ssh-keygen -lf`: `SHA256:` plus unpadded base64 of the SHA-256 of the host key. Check it **before** sending the password.
- Login:
  - User and password are shown on the camera.
  - The camera only accepts **keyboard-interactive** authentication. Answer every prompt with the password.
- Port forwarding:
  - `direct-tcpip` to **`localhost:15740`** works. `127.0.0.1:15740` is rejected ("administratively prohibited").
  - Open the command and event connections as two channels in one SSH session.
  - The live-view port works the same way (see [Live view](#live-view)).

### Handshake

1. PTP/IP InitCommand / InitEvent. The responder name is `ILCE-6700`.
2. GetDeviceInfo. Vendor extension 0x11, "Sony PTP Extensions".
3. OpenSession.
4. Sony handshake: `0x9201` phase 1, `0x9201` phase 2, `0x9202 (0x12C, 1)`, `0x9201` phase 3.
   The 0x9202 reply holds the extended operation, property and control code lists. On the a6700: protocol 0x12C, 371 properties, 57 controls.

## Session modes

| Mode | How | Camera UI |
|---|---|---|
| Remote | the handshake only | usable |
| Contents transfer | additionally `0x9212 (2, 1, 0)`; `(2, 0, 1)` ends it | **locked**: the camera shows it is importing |

- In the remote mode the card is reachable through 0x923B–0x923E. Shooting, menus and playback stay usable on the camera.
- Without 0x9212, the standard storage operations return StoreNotAvailable (0x2013).
- After 0x9212 the camera sends `DevicePropChanged` and `StoreAdded(0x00010001)`. Then GetStorageIDs, GetObjectHandles, GetObjectInfo and GetObject work. GetPartialObject (0x101B) returns InvalidObjectHandle.
- GetObjectInfo before any GetObjectHandles in the session closed the connection.
- `0x9211 (handle, offset low, offset high, max bytes)` reads a part of an object. The data phase holds the bytes and response parameter 0 the count. With 4 MiB chunks it reaches 10–14 MB/s over Wi-Fi + SSH.

## Device properties: 0x9209 / 0x9205 / 0x9251

**0x9209 (mode)** reads properties. Mode `0` returns all of them (352 entries, 8.8 KB on the a6700). Mode `1` returns only the ones changed since the last call, plus a few read-only ones that are always included (0xD057, 0xD059, 0xD278, …).

```
u64 count
count × {
  u16 code, u16 datatype        PTP types 1..8, 0x4000|t array, 0xFFFF string
  u8  writable (1 = yes)
  u8  enabled                   0 off, 1 on, 2 display only
  value default, value current
  u8  form                      0 none
                                1 range: min, max, step
                                2 enum: u16 n, n × value (settable), u16 m, m × value (reportable)
}
```

- The a6700 uses int8, uint8, int16, uint16, uint32, uint64 and string, no arrays.
- Strings are PTP strings. The current value of 0xD278 comes **without** a trailing NUL.

**0x9251 (code)** returns one property in the same entry layout, without the count.

**0x9205 (code)** sets a property. The value is in the data phase.
- Send only values from the property's own settable list or range.
- Afterwards 0x9209(1) shows dependent properties changing `enabled`. Setting 0x500A (focus mode) toggles 0xD22C and 0xD254, for example.

Properties used by alpha2go:

| Code | Type | Meaning |
|---|---|---|
| 0x500A | u16 | focus mode (1 = MF) |
| 0xD207 | u8 | OSD image mode (1 = on) |
| 0xD208 | u32 | buttons usable with 0xD309 (settable values) |
| 0xD20A | u32 | dials usable with 0xD30B |
| 0xD20C | u8 | 1 = camera idle, buttons accepted |
| 0xD215 | u16 | captured image ready: bit 15 = ready, low bits = count |
| 0xD222 | u16 | still image destination: 0x01 host, 0x10 card, 0x11 both |
| 0xD22C | u16 | focus area (1 = wide, 0x0102 = spot M) |
| 0xD268 | u8 | image size sent to the host: 1 original, 2 small |
| 0xD278 | string | live-view URL |
| 0xD284 / 0xD285 | u8 | remote touch usable / touch cancel usable |

## Controls: 0x9207

**0x9207 (code)** with the value in the data phase triggers an action. The camera lists its control codes in the 0x9202 reply but returns no descriptor for them (0x9206 returns no data), so the datatypes below are known from testing.

| Code | Type | Value | Verified behaviour |
|---|---|---|---|
| 0xD2C1 | u16 | 2 press / 1 release | half-press shutter (AF + AE lock) |
| 0xD2C2 | u16 | 2 / 1 | full-press shutter |
| 0xD2C8 | u16 | 2 / 1 | press + release toggles movie recording |
| 0xD2DC | u32 | `x<<16 \| y` | moves the AF area; needs a spot or zone focus area |
| 0xD2E4 | u32 | `x<<16 \| y` | remote touch; starts tracking at that point. Usable while 0xD284 = 1 |
| 0xD2E5 | u16 | 2 / 1 | cancels the touch. Usable while 0xD285 = 1 |
| 0xD309 | u32 | `button<<16 \| 2 down / 1 up` | presses a camera button. One at a time, only while 0xD20C = 1 |
| 0xD30B | i32 | `dial<<16 \| int16 steps` | turns a dial; positive is clockwise |

- Positions use a 640×480 grid. On the a6700, (160,120) moved the AF frame to exactly 0.25/0.25 of the live-view image.
- Capture with AF: 0xD2C1 down, 500 ms, 0xD2C2 down, 35 ms, 0xD2C2 up, 1 s, 0xD2C1 up.
- Capture without AF: 0xD2C2 down, 35 ms, up. The a6700 accepts this in every mode but only fires in MF.
- Buttons on the a6700 (from 0xD208): 1–4 up/down/left/right, 5 enter, 6 menu, 0x10 Fn, 0x11 playback, 0x14–0x16 C1–C3, 0x1A movie, 0x1C AF-On. Tested: menu down + up opens the menu.
- Dials on the a6700 (from 0xD20A): 0x4001 control wheel, 0x4002 front, 0x4003 rear. Tested: in photo M, front +1 (0x40020001) changed the aperture from f/5.6 to f/6.3, and −1 (0x4002FFFF) changed it back.
- Open: in Movie Auto, after opening and closing the menu through 0xD309, front +1 then −1 left the exposure mode at Movie M.

## Live view

- Property 0xD278 holds the stream URL, e.g. `http://localhost:60152/liveviewstream?…`. The host is always `localhost`. Connect to the camera IP instead, or through SSH with `direct-tcpip` to `localhost:60152`.
- `GET <path> HTTP/1.1` returns a chunked body: a sequence of frames. No property has to be set first.
- Each frame starts with four u32 values. Offsets count from the frame start:
  ```
  u32 image offset, u32 image size, u32 metadata offset, u32 metadata size
  ```
  The frame ends at the larger of image end and metadata end.
- On the a6700: a 1024×680 JPEG, padded with 0xFF to a multiple of 128 bytes; 136 bytes of metadata; about 30 frames/s.

### Metadata (version 0x68)

```
0x00 u16 version                0x68 on the a6700
0x10 level: u32 state, u32 reserved, i32 x, i32 y, i32 z   (block padded to 0x18 bytes)
0x28 frame block (contents unknown, skipped)
     focus frame block
     face frame block
     tracking frame block
```

Frame block:

```
u32 x max, u32 y max, u16 count, padding to 16 bytes
count × 24 bytes: u16 type, u16 state, u32 reserved, u32 centre x, u32 centre y, u32 height, u32 width
```

- x max / y max are 655360 × 491520 (640 × 480 × 1024) on the a6700.
- Level: state 2; x and y follow the camera's tilt; z is 32767 (no value).
- Faces: one frame per face. The face in focus has type 2, the others type 1.
- Focus: with the subject in focus, nine frames formed a 3 × 3 grid on it (type 2, state 2 = focused).
- No tracking frames were observed yet.

## OSD image

1. Set 0xD207 to 1.
2. `0x9238` (no parameters) returns one frame in the live-view frame layout: a 640×480 PNG with a transparent background, showing the camera's display overlay, plus 36 bytes of metadata (u32 version 101, then sizes).

The mode is back at 0 after reconnecting. **Do not send 0x9238 while the mode is off**, see [Safety](#safety).

## Contents: 0x923B–0x923E

These operations work in the remote mode. The camera UI stays usable.

| Op | Parameters | Reply |
|---|---|---|
| 0x923B | slot | `u32 n`, `n × u64` capture dates (ms since 1970, camera local time) |
| 0x923C | cursor low, cursor high, 100, slot, 0 | contents list, see below |
| 0x923D | content ID, `slot<<24 \| file ID`, offset low, offset high, `size \| last<<31` | file bytes |
| 0x923E | content ID, `slot<<24 \| file ID`, 1 thumbnail / 2 screennail | the image, no header |

- Slot 2 returns InvalidParameter on the single-slot a6700. alpha2go wraps InvalidParameter and StoreNotAvailable of the date list (0x923B) in `ErrSlotEmpty`; on 0x923C pages they stay plain errors, so a stale cursor never looks like an empty card.
- In some camera states all four operations answer OperationNotSupported (0x2005) for both slots although the camera lists them; the session stays usable. Seen in the field, cause not pinned down yet (candidates: menu or playback open on the camera, card being written, another app connected). Retrying at once gets the same answer, so do not loop. alpha2go wraps OperationNotSupported and DeviceBusy in `ErrCardUnavailable`.
- When 0x923B reports `n = 0`, `SonyListAllContents` sends no 0x923C (the contents list is where a slot without contents stalls).
- **0x923C paging:** the cursor 0 starts at the beginning. The next page uses the creation UTC (ms) of the last content as cursor. alpha2go treats a page with fewer than 100 contents as the last one.
- **0x923D:** bit 31 of the size marks the last chunk (offset + size ≥ file size). The response parameters are a u64 ms timestamp of unknown meaning.
- **0x923E on the a6700:** `.ARW` thumbnail = 160×120 JPEG; `.HIF` thumbnail = 320×212 HEIF; screennail = 1616×1080 HEIF.

### 0x923C reply

```
0x00..0x8F  header               starts with u32 100, then a u64 ms timestamp; not needed
0x90  u32   content count
0x94        contents

content
  u32  type                 1 still, 4 movie clip
  u32  content ID           e.g. 0x00020001
  u32  directory number     100 for 100MSDCF, 0 for clips
  u32  file number          DSC00001 → 1, 0 for clips
  u32  group type, u32 group ID
  u32  representative       != 0
  u64  created UTC          ms since 1970
  u64  modified UTC
  u64  created local        camera wall-clock time, encoded as if UTC
  u64  modified local
  i32  rating
  u32  protected            != 0
  u32  dummy                != 0
  u32  shot mark count, then that many bytes
  u32  file count, then the files

file
  u16  file ID, 2 bytes padding
  u32  path length incl. NUL, then the path    "A:/DCIM/100MSDCF/DSC00001.JPG"
  u32  format                0x3801 JPEG, 0xB101 ARW, 0xB110 HEIF, 0xB982 MP4, 0xBA82 XML
  u64  size
  u8   UMID[32]              contains the camera MAC
  u32  has image params      if != 0: u32 width, u32 height
  u32  has video params      if != 0: 19 × u32 (not decoded)
  u32  has audio params      if != 0: 4 × u32 (not decoded)
```

A RAW+HEIF pair is one content with two files. A stable file identity is
`(path, created UTC)` or the UMID.

## Captured images to the host

In the remote mode the camera can hand each new shot to the host:

1. 0xD222 must include the host (0x01 or 0x11; the a6700 defaults to 0x11).
2. Poll 0xD215. Bit 15 set means an image is ready. No event announces it.
3. GetObjectInfo, then GetObject on the fixed handle **0xFFFFC001**. Each GetObject takes one image off the queue.

0xD268 picks the size: with 2 (the a6700 default) the file is 1616×1080, with 1 it is the original (6192×4128). The file type follows the camera's own file format setting.

## Information operations

Most replies start with u32 8 (header size) and u32 payload size.

| Op | Parameters | Reply |
|---|---|---|
| 0x9248 | none | u32 100, `YYYYMMDDThhmmss.s` with NUL, `±hhmm` with NUL, u8 DST |
| 0x922F | none | u32 n, n × (u16 code, u16 op): property codes whose changes are reported as operation results |
| 0x920E | none | header, u8 n, n PTP strings: FTP setting names ("FTP1"…"FTP9") |
| 0x9215 | list type (0 = all) | header, u32 lists; each list: u32 type, u16 datatype, u16 n, n × (value, u16 length, UTF-8 text with NUL) |
| 0x923A | 2 | UPnP XML device description. It contains the MAC address and serial number. |
| GetObjectInfo + GetObject on 0xFFFFC004 | | settings backup, about 160 KB, starting with "SONY1ILCE-6700". GetObject without GetObjectInfo first fails. |

- Time zone: in CEST the a6700 reports `+0100` with DST on. The offset is the standard offset; DST adds one hour.
- 0x9215 type 3 lists the base looks: S-Log3, s709, 709(800%), and `User1:(No Import)` … `User16:(No Import)` as values 0x101–0x110.

## Write operations

| Op | Parameters | Data out |
|---|---|---|
| 0x9249 set clock | none | u32 100, then three fields, each a u8 "present" flag and the value: `YYYYMMDDThhmmss.s` with NUL (18 bytes), `±hhmm` with NUL (6 bytes), u8 DST |
| 0x9250 delete | content ID, slot | none |
| 0x921A upload | 0x20001 | u32 100, u32 0x18, u32 name length incl. NUL, u32 data offset (0x18 + name length), u32 file size, u32 0, name with NUL, file |
| 0x921B upload control | 0x20000 | u32 100, u16 LUT slot 1–16; sent after 0x921A |
| SendObject 0x100D | 0xFFFFC004 | a settings backup |

- **Clock:** set to 12:00:00, read back 12:00:00.1 through 0x9248.
- **Delete:** the content disappears from 0x923C, and event 0xC240 follows.
- **LUT import:** a `.cube` file lands in the slot, and the 0x9215 string becomes `User16:<file name>`. The a6700 only imports with the mode dial on **movie**; in photo mode 0xC214 reports 4 (busy). There is no operation to clear a slot; use the camera menu.
- **Settings restore:** alpha2go pauses 1 s after the request, after StartData and after the Data packet, and sends an EndData with no payload. 0xC209 reports 1 (OK). Restoring without the pauses was not tried.

## Events

Sony events arrive on the PTP/IP event channel.

| Event | Parameters | Observed |
|---|---|---|
| 0xC203 | code | a property changed; read 0x9209(1) |
| 0xC209 | result | after a settings restore: 1 = OK |
| 0xC214 | result, kind | after 0x921B; kind 0x20000 = LUT; 1 = OK, 4 = busy |
| 0xC228 | 0, 0, 0 | the camera shows a caution message; seen after the restore |
| 0xC240 | slot, content ID, 1 | contents changed; seen after a delete |
| 0xC20F | 3, 0, 0 | during the LUT import; meaning unknown |
| 0xC221 | n, 0, x | on every connection; meaning unknown |
| 0xC234 | 1, 2, 0 | after a delete; meaning unknown |

## MTP object properties: 0x9801–0x9805

Standard MTP 1.1 operations.

- **0x9801 (format)** works in the remote mode. For JPEG: DC01 StorageID, DC02 ObjectFormat, DC03 ProtectionStatus, DC04 ObjectSize, DC07 ObjectFileName, DC08 DateCreated, DC87 Width, DC88 Height, DCD3 ImageBitDepth, plus vendor codes 0xD8AF, 0xD8B0 and 0xD8B1 of unknown meaning.
- **0x9805 (handle, 0, 0xFFFFFFFF, 0, 0)** needs the contents transfer mode (object handles only exist there). For a JPEG it returned 10 entries; 0xD8AF was u8 0, and 0xD8B0/0xD8B1 were missing.
- 0x9802 and 0x9803 are advertised but return OperationNotSupported.

## Rejected by the a6700

Advertised by the camera, but refused:

| Feature | Answer |
|---|---|
| 0x9223 lens information | OperationNotSupported |
| 0x921F FTP setting list | OperationNotSupported |
| 0x9231 zoom/focus preset | OperationNotSupported |
| 0x9222 setting file | OperationNotSupported |
| 0x9239 (1) restriction query | 0xA106 |
| 0x9243 movie playback start | 0xA106 in every camera state tried |
| 0x924D | not in the operation list |
| Grid line upload (0xE110) | rejected; 0xE10F offers only types 1–3 |
| Monitoring (0xE098, 0xE099, 0xE09D, 0xE09F) | InvalidParameter |

Operations and controls the camera lists but that have no known parameters are left alone (see [Safety](#safety)).

## Safety

Vendor operations with wrong parameters can hang or crash the camera.

- **0x923C with guessed parameters crashed the a6700.** It got no reply, then restarted, and its UI stayed locked until it was power-cycled.
- **0x9238 with the OSD mode off** returned about 6 MB of garbage and the connection timed out. The camera recovered by itself.
- Rules for experiments:
  - Nothing else connected to the camera.
  - One operation per run, with someone watching the camera display.
  - Set properties only to values from their own settable list or range. Note the old value and restore it.
  - Don't send unknown control codes.
- Recovery: switch the camera off and on, or pull the battery.
