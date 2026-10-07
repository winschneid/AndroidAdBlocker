# AndroidAdBlocker

Android の Chrome を含む **すべてのアプリ** で動く、DNS フィルタ型の広告ブロッカーです。root 不要、ブラウザの切り替えも不要です。

Android 版 Chrome は拡張機能に対応していないため、ページ内のスクリプトで広告を消す方式は使えません。
このアプリは代わりに **ローカル VPN（`VpnService`）として端末の DNS 問い合わせだけを横取り** し、広告・トラッカーのドメインの名前解決を失敗させることで広告を読み込ませません。

## 仕組み

```
Chrome / 各アプリ ──DNS 問い合わせ──▶ 10.111.222.2 (偽の DNS サーバー)
                                        │  VPN の経路に乗っているのはこのアドレス 1 つだけ
                                        ▼
                               AdBlockVpnService (TUN デバイス)
                                        │ IPv4/IPv6 + UDP + DNS を解釈
                                        ├─ ブロックリストに一致 → NXDOMAIN (または 0.0.0.0) を即答
                                        └─ それ以外 → 上流 DNS (システム / 1.1.1.1 など) に転送して応答を返す
```

- VPN に経路設定するのは偽の DNS サーバーアドレスだけなので、Web ページや動画などの通信そのものは VPN を通りません。バッテリーや速度への影響は最小限です。
- このアプリ自身の通信（上流 DNS への転送、リストの取得）は VPN の対象外です。上流 DNS を「システムの DNS」にしている場合は、その時点の既定ネットワーク（Wi-Fi / モバイル）の DNS サーバーに追従します。
- 返答は `NXDOMAIN`（既定）か `0.0.0.0 / ::`（hosts ファイル方式）を選べます。アドレスを返さないブロック応答には SOA レコードを付け、端末側で 10 秒間キャッシュされるようにしています。
- 偽 DNS サーバーへの TCP 接続（DNS-over-TLS の自動検出や TCP フォールバック）には RST を返し、待ち時間を発生させません。
- ブロック判定はドメインとその親ドメインに対して行うため、`doubleclick.net` を登録すれば `ad.doubleclick.net` も止まります。

## 機能

- **ブロックリスト**: [StevenBlack/hosts](https://github.com/StevenBlack/hosts)（MIT）と [AdAway](https://github.com/AdAway/adaway.github.io)（CC BY 3.0）を既定で有効化、[Peter Lowe's list](https://pgl.yoyo.org/adservers/) は任意。URL を追加して任意のリストも使えます。hosts 形式 / ドメイン一覧 / AdBlock 形式のドメインルール（`||example.com^`）に対応。1 日ごとに自動更新。
- **内蔵の基本リスト**: リスト取得前やオフラインでも主要な広告ネットワーク（Google 広告、主要 SSP/DSP、日本のアドネットワークなど）を遮断。
- **手動ルール**: 常に許可 / 追加でブロックするドメイン。許可リストが最優先。
- **クエリログ**: 直近 500 件の問い合わせを表示し、そこから許可・ブロックに追加。
- **上流 DNS の選択**: システム DNS / Cloudflare / Google / Quad9 / AdGuard DNS / カスタム。
- **クイック設定タイル**、通知からの停止、端末起動時の自動開始、常時 ON VPN への対応。
- **プライベート DNS の検出**: 広告ブロックを素通りさせる設定になっている場合にホーム画面で警告。
- **別名（CNAME）で隠されたドメインのブロック（任意、既定はオフ）**: 問い合わせた名前がリストに無くても、応答の CNAME が指す先がブロック対象なら遮断します。ログには「ブロック（CNAME）」と転送先が表示されます。副作用があるため設定でオンにした場合だけ働きます（下の制限事項を参照）。
- 日本語 / 英語 UI。

## 制限事項（DNS 方式の限界）

| 状況 | 結果 |
| --- | --- |
| Android の「プライベート DNS」をホスト名指定（例: `dns.google`）にしている | DNS が暗号化されて直接送られるためブロックされません。「オフ」または「自動」にしてください。 |
| Chrome の「セキュア DNS」でカスタムプロバイダを指定している | 同上。「現在のサービス プロバイダを使用する」のままにしてください（既定値なら問題ありません）。 |
| YouTube アプリなど、広告が本体と同じドメインから配信されるサービス | DNS では区別できないため止められません。 |
| サイト自身のドメインから配信されるファーストパーティ広告 | 同上。 |
| 広告ブロックを検知して広告を差し込み直すサイト（オリコン、スポーツ紙の一部など。`html-load.com` を読み込むもの） | 読み込み元がブロックされると、リストに無い別名ドメイン（`html-load.com` への CNAME）経由で広告を出し直します。既定ではこの別名は止めないため、記事は読めますが広告が残ります。設定の「別名（CNAME）で隠された広告ドメインもブロック」をオンにすると広告は消えますが、サイト側が記事の代わりに「広告表示の許可をお願いします」という画面を出します。DNS 方式ではこのどちらかにしかできません。 |
| アプリが独自に DNS サーバー IP を埋め込んでいる場合 | その問い合わせは VPN を通らず素通りします。 |
| 「VPN なしの接続をブロック」（常時 ON VPN の lockdown） | このアプリは DNS 以外を転送しないため、有効にすると他の通信がすべて止まります。無効のまま使ってください。 |
| ほかの VPN アプリとの併用 | Android では同時に 1 つの VPN しか動かせません。 |
| Xiaomi（MIUI / HyperOS）など、アプリの自動起動を制限する端末 | 端末起動時やアプリ更新後の自動開始が OS にブロックされます。端末の設定でこのアプリの「自動起動」を許可してください。 |
| 大きな DNS 応答による TCP での再問い合わせ | 現状は未対応（RST を返す）。EDNS が使われる通常の環境では稀です。 |

ブロックされた広告の枠は空白やエラー表示として残ることがあります（DNS 方式では要素を消すことはできません）。

## ビルド

必要なもの: JDK 17 以上、Android SDK（compileSdk 36）、Android Studio Ladybug 以降（推奨）。

```bash
# コアモジュールの単体テスト（Android SDK 不要）
./gradlew :core:test

# デバッグ APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 端末にインストール
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions（`.github/workflows/android.yml`）が push ごとにテストとデバッグ APK のビルドを行い、Artifacts に `app-debug` としてアップロードします。

リリースビルド（`assembleRelease`）は署名設定を追加してください（`*.jks` は `.gitignore` 済み）。

## 使い方

1. APK をインストールして起動し、「開始」を押します。
2. Android の **VPN 接続リクエスト** を許可します（初回のみ）。Android 13 以降では通知の許可も求められます。動作中の通知はスワイプで消せます。消した通知は、次に VPN を開始するまで再表示されません。
3. 初回起動時にブロックリストが自動でダウンロードされます（数 MB、Wi-Fi 推奨）。「リスト」タブでも手動更新できます。
4. Chrome で広告の多いサイトを開き、「ログ」タブでブロック状況を確認します。
5. 特定のサイトが動かない場合は、ログから該当ドメインを「許可リストに追加」してください。許可は 10 秒ほどで反映されます。逆に許可を取り消した場合やブロックを追加した場合は、端末に残っている DNS キャッシュが切れるまで（数分かかることがあります）以前の結果が使われます。

## プロジェクト構成

```
core/   純 Kotlin（Android 非依存）。JVM 上で単体テスト可能
  dns/     DnsCodec        DNS メッセージの解析とブロック応答の生成
  net/     IpPacketCodec   IPv4/IPv6 + UDP の解析・生成、チェックサム、TCP RST
  filter/  HostsParser     hosts / ドメイン一覧 / ABP ドメインルールの解析
           DomainMatcher   許可 > 手動ブロック > リスト の優先順位で親ドメインまで照合
           ListDownloader  リストの取得（リダイレクト追従、サイズ上限、原子的な置き換え）
           BuiltinBlocklist 内蔵の基本リスト
  engine/  DnsProxyEngine  TUN から読んだパケット 1 つを「ブロック応答 / 転送 / RST / 破棄」に判定
           PendingQueryTable 転送中クエリの ID 書き換えと期限管理

app/    Android アプリ（Kotlin + Jetpack Compose / Material 3）
  vpn/      AdBlockVpnService  VpnService 本体（経路設定、上流 DNS の決定、通知）
            VpnSession         TUN 読み取りスレッドと上流応答スレッド
            VpnStateHolder     状態・統計・クエリログ（StateFlow）
  data/     AppSettings, BlocklistRepository（リスト・ルールの永続化と DomainMatcher の再構築）
  ui/       MainActivity, MainViewModel, 画面（ホーム / リスト / ログ / 設定）
  tile/     クイック設定タイル
  receiver/ 起動時・更新後の自動開始
```

## 使用しているブロックリストのライセンス

- StevenBlack/hosts — MIT License
- AdAway hosts — CC BY 3.0
- Peter Lowe's Ad and tracking server list — 配布元 (pgl.yoyo.org) の条件に従ってください

各リストの内容と条件は配布元を確認してください。

## 今後の候補

- DNS-over-HTTPS / DNS-over-TLS の上流対応
- アプリごとの除外（`addDisallowedApplication`）
- TCP での DNS 問い合わせの中継
