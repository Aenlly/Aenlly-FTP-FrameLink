# Aenlly-FTP · FrameLink
<p align="center"><img src="design/brand/framelink-icon.png" alt="FrameLink" width="88"></p>
<p align="center"><a href="README.md">中文</a> · <strong>English</strong> · <a href="README.ja.md">日本語</a> · <a href="README.ko.md">한국어</a></p>

Connect a camera, review photos, and import the ones you want. FrameLink builds on Aenlly-FTP, keeping FTP transfers while adding wireless live view, local client selections, and optional cloud delivery.

**Development preview: Android 0.1.24 / iOS 0.1.6** · Android 17 support (Android 8.0+) · iOS 17+ project · GPL-3.0

## Preview
| Camera | Gallery | Viewer |
|---|---|---|
| ![Camera](docs/images/home.png) | ![Gallery](docs/images/gallery-0.1.22.png) | ![Viewer](docs/images/viewer.png) |

Android emulator screenshots use licensed sample photos. [Credits and visual checks](docs/VISUAL_QA.md).

![Landscape viewfinder (simulated camera stream)](docs/images/camera-v6/landscape-photo.png)

## Features
- **Camera**: remembered connections, live view, remote shooting, and Creative Look where supported. Android phone controls turn in place as you rotate the device. Tap to focus; automatic import after capture is optional and off by default.
- **Gallery**: grouped RAW+JPEG, full counts for the current date range, background list loading, and thumbnails on demand. Choose small, medium, or large grids or a list; drag to select, with one shared selection in grid and viewer.
- **Review**: swipe, zoom, favorite, compare, and save named selections or contact sheets. “查看原片” temporarily caches JPEG detail without marking it as imported. Import reuses a matching cache. The cache is capped at about 2GB and can be cleared without deleting saved photos or selections.
- **Photo info (Android)**: read EXIF ISO, shutter speed, aperture, focal length, lens, camera, capture time, and recorded GPS. Missing values stay explicit; current camera settings and phone location are not substituted.
- **Client review**: records named by client, phone number, or session; separate client choices and comments, renaming, deletion, completion, and reopening.
- **FTP**: receive camera uploads; browse, upload, download, and create folders with FTP or explicit FTPS.
- **Local files**: import from the phone or card reader; remove list entries or App-owned copies without deleting camera-card originals.
- **Delivery**: Android and the web interface can use a self-hosted cloud service for access-code selections and finished-photo downloads.

Local features need no account. Originals retain their bytes; previews and compressed copies are separate. The initial iOS app includes local photos, client records, Sony wireless, and FTP. Bluetooth and cloud integration are not yet available on iOS. [Platform scope and validation](docs/IMPLEMENTATION.md)

## Connect
**FTP reception is brand-independent**, provided the camera supports FTP uploads. Join the same Wi-Fi or phone hotspot, start “FTP 接收,” and enter the phone address, port, username, and password on the camera.

- **Sony network**: enable PC Remote and required authentication, then enter the address and confirm the fingerprint. Capabilities depend on the body, firmware, and negotiation. A7C II hardware checks are recorded; other targets still need hardware testing.
- **Canon CCAPI, experimental**: enable CCAPI and enter the HTTP address, port, and optional credentials. EOS 850D is a hardware-test target; current evidence comes from an independent service and Android integration tests.
- **USB reader, experimental**: use phone OTG to browse and import from PTP/MTP devices. This is read-only media access, not remote shooting. X-T30 II hardware testing remains pending; Fujifilm wireless control is not implemented. Use local import for system-mounted storage.

The new CCAPI and USB entries are Android-only. [Connection guide](android/README.md#连接相机) · [Transport and validation matrix](docs/CAMERA_BACKENDS.md)

Use plain FTP on a trusted local network; explicit FTPS validates certificates. iOS reception requires the app to stay in the foreground. Retry and resume behavior depends on the transport; see the connection guide.

## Build
This preview is not published to Releases yet. Existing releases belong to the earlier FTP app.
```sh
git clone --branch feature/framelink-v1 https://github.com/Aenlly/Aenlly-FTP-FrameLink.git
cd Aenlly-FTP-FrameLink
# macOS Apple Silicon; install JDK17 first
python3 scripts/bootstrap.py
sh scripts/build-android.sh
# Requires full Xcode and the iOS SDK
sh scripts/build-ios.sh
```

Android output: `artifacts/FrameLink-0.1.24-debug.apk`. iPhone IPA files require re-signing; see the [installation guide](ios/INSTALL.md). The simulator archive is separate. The app interface is currently Chinese. [Android](android/README.md) · [iOS](ios/README.md) · [Server](cloud/README.md) · [Web](web/README.md)

## Contribute
Camera reports, reproducible bugs, and usability feedback are welcome. Include phone OS, camera model and firmware, connection method, and steps. Keep passwords and private photos out of reports.

[Issues](https://github.com/Aenlly/Aenlly-FTP-FrameLink/issues) · [Contributing](CONTRIBUTING.md) · [Development checks](docs/REVIEW.md) · [Hardware checklist](docs/HARDWARE_ACCEPTANCE.md)

## License
[GPL-3.0](LICENSE). Aenlly's original attribution and history are retained; the earlier source is in `third_party/aenlly-ftp/`. Thanks to alpha2go, α-Remote, SonnyLink, Apache, and Haze. [Versions, licenses, and usage](THIRD_PARTY_NOTICES.md).
