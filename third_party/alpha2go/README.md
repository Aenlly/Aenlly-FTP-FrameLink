# alpha2go

Remote control for Sony Alpha cameras in pure Go, over Wi-Fi.

alpha2go speaks PTP/IP and Sony's vendor extensions directly. It needs no
vendor SDK, no cgo and no USB. Everything below was tested on a **Sony α6700
(ILCE-6700, firmware 2.00)**. Other bodies with the same protocol version may
work, but have not been tested.

```go
ssh := ptpip.NewSSHDialer(ptpip.SSHConfig{User: user, Password: pass, Fingerprint: "SHA256:…"})
defer ssh.Close()
c, err := ptpip.Dial(ctx, "192.168.1.50:15740", ptpip.Options{DialContext: ssh.DialContext})
if err != nil {
	return err
}
defer c.Close()
if err := c.OpenSession(ctx, 1); err != nil {
	return err
}
if _, err := c.SonyHandshake(ctx); err != nil {
	return err
}
return c.SonyCapture(ctx, true) // autofocus, then fire
```

Without Access Authentication, leave out the SSH dialer.

## What you can do

| Area | What | API |
|---|---|---|
| **Connect** | find the camera on the LAN (ARP, SSDP, port scan) | `discovery.Find` |
| | connect with Access Authentication (SSH tunnel, fingerprint check before the password) | `ptpip.NewSSHDialer` |
| | Wake-on-LAN magic packets | `wol.Send`, `wol.Loop` |
| **Settings** | read every camera setting with its allowed values | `SonyGetProps`, `SonyGetProp` |
| | change a setting (ISO, aperture, shutter, focus mode, …) | `SonySetProp` |
| | follow changes as they happen | `Events`, `SonyGetProps(ctx, true)` |
| | setting names as the camera displays them | `SonyGetDisplayStrings` |
| **Shooting** | take a picture, with or without autofocus | `SonyCapture` |
| | start / stop movie recording | `SonyToggleMovie` |
| | move the AF area, touch-to-track and cancel | `SonyControl` |
| | press any camera button, turn any dial | `SonyControl` |
| | receive each new shot on the host | `SonyCapturedPending`, `SonyGetCapturedImage` |
| **Live view** | JPEG stream (1024×680, ~30 fps) | `SonyOpenLiveView` |
| | level, focus frames, face frames | `ParseSonyLiveViewMeta` |
| | the camera's on-screen display as a transparent PNG | `SonySetOSDMode`, `SonyGetOSDImage` |
| **Card** | list all contents with dates, ratings and sizes | `SonyListAllContents` |
| | download files, resumable, ≥ 4 GiB ready | `SonyDownloadContentFile` |
| | thumbnails and screennails | `SonyGetContentsCompressed` |
| | delete contents | `SonyDeleteContent` |
| **Camera** | back up and restore all camera settings | `SonyDownloadSettings`, `SonyRestoreSettings` |
| | read and set the clock and time zone | `SonyGetTimeZone`, `SonySetTimeZone` |
| | import a LUT (`.cube`) into a user slot | `SonyImportLUT` |
| | device description, FTP setting names | `SonyGetDeviceDescription`, `SonyGetFTPServerNames` |
| **Raw** | any PTP operation | `Transaction` |

The a6700 rejects a few advertised features (lens info, movie playback, FTP
settings list). [docs/a6700.md](docs/a6700.md) has the full matrix.

The camera UI stays usable while alpha2go is connected. Only the optional
contents transfer mode (`SonySetContentsTransferMode`) locks it.

## ptpprobe

`cmd/ptpprobe` is a command-line tool for every feature above. It is the
quickest way to check a camera:

```sh
go install github.com/knallli/alpha2go/cmd/ptpprobe@latest

export PTPPROBE_SSH_PASSWORD='<password>'
P="ptpprobe -host 192.168.1.50 -ssh-user <user> -ssh-fingerprint SHA256:<fp> -list 0 -events 0"

$P -props                       # all settings
$P -set 0x500A=2                # change one
$P -capture af -fetch-captured  # shoot and download
$P -liveview 30 -out frames/    # live-view frames
$P -backup a6700.dat            # settings backup
```

Run `ptpprobe -h` for all flags.

## Docs

- [docs/camera-setup.md](docs/camera-setup.md): prepare the camera.
- [docs/a6700.md](docs/a6700.md): what works on the α6700.
- [docs/protocol.md](docs/protocol.md): the wire protocol in detail.

## How this was built

The PTP/IP and PTP layers follow the public CIPA DC-005 and ISO 15740
specifications. Every Sony vendor operation in this repository was tested on a
real α6700, and the docs describe only what the camera actually sent and
accepted. The repository contains no Sony code, headers or data tables.

## AI disclosure

This project was written with heavy use of an AI coding assistant (Anthropic's
Claude): code, tests and documentation. A human directed the work, ran every
camera test on real hardware, and reviewed the results. Treat the code like any
other young open-source project: read it before you trust it with your camera.

## Status

The API is not stable yet. Expect breaking changes before v1.

## License

Apache-2.0, see [LICENSE](LICENSE).

Sony and Alpha are trademarks of Sony Group Corporation. This project is not
affiliated with or endorsed by Sony.
