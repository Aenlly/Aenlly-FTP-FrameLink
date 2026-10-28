# Aenlly-FTP · FrameLink
<p align="center"><img src="design/brand/framelink-icon.png" alt="FrameLink" width="88"></p>
<p align="center"><a href="README.md">中文</a> · <strong>English</strong> · <a href="README.ja.md">日本語</a> · <a href="README.ko.md">한국어</a></p>

Connect a camera, review photos, and import the ones you want. FrameLink builds on Aenlly-FTP, keeping FTP transfers while adding wireless live view, local client selections, and optional cloud delivery.

**0.1.6 development preview** · Android 17 support (Android 8.0+) · iOS 17+ project · GPL-3.0

## Preview
| Camera | Gallery | Viewer |
|---|---|---|
| ![Camera](docs/images/home.png) | ![Gallery](docs/images/gallery.png) | ![Viewer](docs/images/viewer.png) |

Android emulator screenshots use licensed sample photos. [Credits and visual checks](docs/VISUAL_QA.md).

## Features
- **Camera**: saved connections, first-use fingerprint confirmation, card browsing, original downloads, live view, and remote controls where supported.
- **Gallery**: grouped RAW+JPEG, drag selection, one shared selection in grid and viewer, favorites, comparison, named batches, and image contact sheets.
- **Client review**: records named by client, phone number, or session; separate client choices and comments, renaming, deletion, completion, and reopening.
- **FTP**: receive camera uploads; browse, upload, download, and create folders with FTP or explicit FTPS.
- **Local files**: import from the phone or card reader; remove list entries or App-owned copies without deleting camera-card originals.
- **Delivery**: Android and the web interface can use a self-hosted cloud service for access-code selections and finished-photo downloads.

Local features need no account. Originals retain their bytes; previews and compressed copies are separate. The initial iOS app includes local photos, client records, Sony wireless, and FTP. Bluetooth and cloud integration are not yet available on iOS. [Platform scope and validation](docs/IMPLEMENTATION.md)

## Connect
**FTP reception is brand-independent**, provided the camera supports FTP uploads. Join the same Wi-Fi or phone hotspot, start “FTP 接收,” and enter the phone address, port, username, and password on the camera.

**Wireless browsing and control currently use the Sony adapter.** Enable the camera's remote-PC mode and required authentication, enter its network address, and confirm its fingerprint. Features vary by model and firmware. Use FTP or local import when this protocol is unavailable. Some A7C II hardware checks have been recorded; other models need testing. [Connection guide](android/README.md#连接相机)

Use plain FTP on a trusted local network; explicit FTPS validates server certificates. iOS reception requires the app to stay in the foreground. Failed transfers can be retried from the beginning.

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

Android output: `artifacts/FrameLink-0.1.6-debug.apk`. iPhone IPA files require re-signing; see the [installation guide](ios/INSTALL.md). The simulator archive is separate. The app interface is currently Chinese. [Android](android/README.md) · [iOS](ios/README.md) · [Server](cloud/README.md) · [Web](web/README.md)

## Contribute
Camera reports, reproducible bugs, and usability feedback are welcome. Include phone OS, camera model and firmware, connection method, and steps. Keep passwords and private photos out of reports.

[Issues](https://github.com/Aenlly/Aenlly-FTP-FrameLink/issues) · [Contributing](CONTRIBUTING.md) · [Development checks](docs/REVIEW.md) · [Hardware checklist](docs/HARDWARE_ACCEPTANCE.md)

## License
[GPL-3.0](LICENSE). Aenlly's original attribution and history are retained; the earlier source is in `third_party/aenlly-ftp/`. Thanks to alpha2go, α-Remote, SonnyLink, Apache, and Haze. [Versions, licenses, and usage](THIRD_PARTY_NOTICES.md).
