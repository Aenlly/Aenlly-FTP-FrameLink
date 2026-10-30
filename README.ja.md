# Aenlly-FTP · FrameLink
<p align="center"><img src="design/brand/framelink-icon.png" alt="FrameLink" width="88"></p>
<p align="center"><a href="README.md">中文</a> · <a href="README.en.md">English</a> · <strong>日本語</strong> · <a href="README.ko.md">한국어</a></p>

カメラにつなぎ、写真を見て、必要なものだけ取り込む。FrameLinkはAenlly-FTPをもとに、FTP転送に加えてライブビュー、現場での写真選定、任意のクラウド納品を開発しているオープンソースアプリです。

**開発プレビュー：Android 0.1.17／iOS 0.1.6** · Android 17対応（8.0以降） · iOS 17以降のプロジェクト · GPL-3.0

## 画面
| カメラ | ギャラリー | 写真表示 |
|---|---|---|
| ![カメラ](docs/images/home.png) | ![ギャラリー](docs/images/gallery.png) | ![写真表示](docs/images/viewer.png) |

Androidエミュレーターの画面です。許諾済みの作例を使用しています。[素材と画面確認](docs/VISUAL_QA.md)

![横向きの撮影画面と設定バー（カメラ通信シミュレーター）](docs/images/live-landscape.png)

## 主な機能
- **カメラ**：接続情報の保存、通信方式に応じた機器の識別、カード内の閲覧、原本の取り込み、対応するライブビューと遠隔操作。AndroidではタップでAFを実行し、撮影後の自動取り込みは初期状態でオフです。
- **ギャラリー**：RAW＋JPEGのまとめ表示、ドラッグ選択、一覧と大きな写真の選択共有、お気に入り、比較、名前付き記録とコンタクトシート。撮影日時とファイル日時を区別して並べ、スクロールで続きの写真を読み込みます。
- **現場での選定**：顧客名・電話番号・撮影名を付けた記録、顧客独自の選択とコメント、名前変更、削除、確定と再編集。
- **FTP**：カメラからの受信、FTP／明示的FTPSでの閲覧、アップロード、ダウンロード、フォルダー作成。
- **端末内の管理**：スマートフォンやカードリーダーから取り込み、一覧だけの削除、またはアプリ内コピーの削除。カメラ内の原本は削除しません。
- **納品**：AndroidとWebから自分で設置したクラウドに接続し、アクセスコードによる写真選定と仕上がり写真のダウンロード。

ローカル機能にアカウントは不要です。原本のバイト列を保持し、プレビューや圧縮画像は別に作成します。iOS初版は端末内の管理、顧客記録、ソニー無線接続、FTPに対応する実装です。BluetoothとクラウドはiOS未対応です。[機能範囲と検証](docs/IMPLEMENTATION.md)

## 接続
**FTP受信はメーカーを限定しません。** カメラ側にFTP送信機能が必要です。同じWi-Fiまたはスマートフォンのホットスポットに接続して「FTP 接收」を開始し、端末のIPアドレス、ポート、ユーザー名、パスワードをカメラに設定します。

- **Sonyネットワーク**：PCリモートと必要な認証を有効にし、アドレスと指紋を確認します。機能は機種・ファームウェア・通信時の応答に依存します。A7C IIの一部過去記録があり、ほかの対象機種は実機確認待ちです。
- **Canon CCAPI・実験段階**：機体でCCAPIを有効にし、HTTPアドレス・ポート・必要な認証情報を入力します。EOS850Dは実機検証対象です。現在の証拠は独立した通信サービスとAndroidの模擬テストで、機体の結果ではありません。
- **USB読取り・実験段階**：スマートフォンのOTGでPTP/MTP機器の原本を閲覧・取り込みます。遠隔撮影は提供しません。X-T30 IIは実機確認待ちで、富士フイルムの無線遠隔操作は未実装です。システムにマウントされた記録媒体は端末内取り込みを使います。

新しいCCAPIとUSBの入口はAndroidのみです。[接続手順](android/README.md#连接相机) · [通信方式と検証状況](docs/CAMERA_BACKENDS.md)

通常のFTPは信頼できるLANで使用してください。明示的FTPSは証明書を検証します。iOSでは受信中にアプリを前面に保つ必要があります。再試行・再開の動作は通信方式によって異なります。接続手順を参照してください。

## ビルド
開発プレビューはまだReleasesに公開していません。既存のリリースは旧FTP版です。
```sh
git clone --branch feature/framelink-v1 https://github.com/Aenlly/Aenlly-FTP-FrameLink.git
cd Aenlly-FTP-FrameLink
# macOS Apple Silicon。先にJDK17をインストール
python3 scripts/bootstrap.py
sh scripts/build-android.sh
# 完全版XcodeとiOS SDKが必要
sh scripts/build-ios.sh
```

Androidの出力：`artifacts/FrameLink-0.1.17-debug.apk`。iPhone用IPAは再署名が必要です。[インストール手順](ios/INSTALL.md)。シミュレーター用ファイルは別です。アプリの表示言語は現在中国語です。[Android](android/README.md) · [iOS](ios/README.md) · [サーバー](cloud/README.md) · [Web](web/README.md)

## 開発に参加
対応機種の報告、不具合の再現手順、使い勝手への意見を歓迎します。スマートフォンのOS、カメラの型番とファームウェア、接続方法を記載し、パスワードや個人の原本写真は送らないでください。

[Issues](https://github.com/Aenlly/Aenlly-FTP-FrameLink/issues) · [貢献ガイド](CONTRIBUTING.md) · [開発と確認](docs/REVIEW.md) · [実機チェック](docs/HARDWARE_ACCEPTANCE.md)

## ライセンス
[GPL-3.0](LICENSE)。Aenllyの原著作者表記と履歴を保持し、旧版ソースは`third_party/aenlly-ftp/`にあります。alpha2go、α-Remote、SonnyLink、Apache、Hazeに感謝します。[バージョン・ライセンス・利用方法](THIRD_PARTY_NOTICES.md)
