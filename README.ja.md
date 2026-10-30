# Aenlly-FTP · FrameLink
<p align="center"><img src="design/brand/framelink-icon.png" alt="FrameLink" width="88"></p>
<p align="center"><a href="README.md">中文</a> · <a href="README.en.md">English</a> · <strong>日本語</strong> · <a href="README.ko.md">한국어</a></p>

カメラにつなぎ、写真を見て、必要なものだけ取り込む。FrameLinkはAenlly-FTPをもとに、FTP転送に加えてライブビュー、現場での写真選定、任意のクラウド納品を開発しているオープンソースアプリです。

**Android 0.1.25／iOS 0.1.11ソース候補（実機用パッケージ：0.1.10）** · Android 17対応（8.0以降） · iOS 17以降のプロジェクト · GPL-3.0

## 画面
| カメラ | ギャラリー | 写真表示 |
|---|---|---|
| ![カメラ](docs/images/home.png) | ![ギャラリー](docs/images/gallery-0.1.22.png) | ![写真表示](docs/images/viewer.png) |

Androidエミュレーターの画面です。許諾済みの作例を使用しています。[素材と画面確認](docs/VISUAL_QA.md)

![横向きの撮影画面（カメラ通信シミュレーター）](docs/images/camera-v6/landscape-photo.png)

## 主な機能
- **カメラ**：接続情報を保存し、機種が対応するライブビュー、遠隔撮影、クリエイティブルックを利用できます。Androidスマートフォンでは操作位置を保ったままアイコンが回転し、タップでAFを実行。撮影後の自動取り込みは選択式で、初期状態はオフです。
- **ギャラリー**：RAW＋JPEGをまとめ、日付範囲の総数を表示。一覧はバックグラウンドで補い、サムネイルは必要に応じて取得します。小・中・大のグリッドと一覧表示、ドラッグ選択、一覧と大きな写真の選択共有に対応します。
- **写真の確認**：ページ送り、拡大、お気に入り、比較、名前付き記録とコンタクトシート。「查看原片」はJPEGを一時キャッシュし、取り込み済みにはしません。正式な取り込みでは一致するキャッシュを再利用します。上限は約2GB。保存済み写真や選定記録を残して手動で消去できます。
- **写真情報**：EXIFのISO、シャッター速度、絞り、焦点距離、レンズ、機種、撮影日時、記録済みGPSを読み取ります。不明な項目を現在のカメラ設定やスマートフォンの位置で補いません。
- **現場での選定**：顧客名・電話番号・撮影名を付けた記録、顧客独自の選択とコメント、名前変更、削除、確定と再編集。
- **FTP**：カメラからの受信、FTP／明示的FTPSでの閲覧、アップロード、ダウンロード、フォルダー作成。
- **接続と履歴**：複数機器の認証情報を任意で暗号化保存し、切り替えや削除ができます。完了・キャンセル済みの転送履歴だけを消去し、ファイルと未処理のタスクは残します。
- **端末内の管理**：スマートフォンやカードリーダーから取り込み、一覧だけの削除、またはアプリ内コピーの削除。カメラ内の原本は削除しません。
- **納品**：AndroidとWebから自分で設置したクラウドに接続し、アクセスコードによる写真選定と仕上がり写真のダウンロード。

ローカル機能にアカウントは不要です。原本のバイト列を保持し、プレビューや圧縮画像は別に作成します。iOS初版は端末内の管理、顧客記録、ソニー無線接続、FTPに対応する実装です。BluetoothとクラウドはiOS未対応です。[機能範囲と検証](docs/IMPLEMENTATION.md)

## 接続
**FTP受信はメーカーを限定しません。** カメラ側にFTP送信機能が必要です。同じWi-Fiまたはスマートフォンのホットスポットに接続して「FTP 接收」を開始し、端末のIPアドレス、ポート、ユーザー名、パスワードをカメラに設定します。

- **Sonyネットワーク**：PCリモートと必要な認証を有効にし、アドレスと指紋を確認します。機能は機種・ファームウェア・通信時の応答に依存します。A7C IIの実機記録があり、ほかの対象機種は実機確認待ちです。
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

Androidをソースからビルドした出力：`android/app/build/outputs/apk/debug/app-debug.apk`。iPhone用IPAは再署名が必要です。[インストール手順](ios/INSTALL.md)。シミュレーター用ファイルは別です。アプリの表示言語は現在中国語です。[Android](android/README.md) · [iOS](ios/README.md) · [サーバー](cloud/README.md) · [Web](web/README.md)

## 開発に参加
対応機種の報告、不具合の再現手順、使い勝手への意見を歓迎します。スマートフォンのOS、カメラの型番とファームウェア、接続方法を記載し、パスワードや個人の原本写真は送らないでください。

[Issues](https://github.com/Aenlly/Aenlly-FTP-FrameLink/issues) · [貢献ガイド](CONTRIBUTING.md) · [開発と確認](docs/REVIEW.md) · [実機チェック](docs/HARDWARE_ACCEPTANCE.md)

## ライセンス
[GPL-3.0](LICENSE)。Aenllyの原著作者表記と履歴を保持し、旧版ソースは`third_party/aenlly-ftp/`にあります。alpha2go、α-Remote、SonnyLink、Apache、Hazeに感謝します。[バージョン・ライセンス・利用方法](THIRD_PARTY_NOTICES.md)
