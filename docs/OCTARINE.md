# Octarine（Core Keeper 連携）

Core Keeper 専用サーバーの保存データからワールド進捗を読み取り、PostgreSQL に保存して
`octarine.rubinco.link` に表示する。Watchpoint（7DTD）・Luminite（Terraria）と同じ Spring Boot
アプリ・同じ DB・同じ認証で動く。

```text
Core Keeper が 0.world.gzip を保存
  → アプリ内の定期ジョブ（5分ごと、Web リクエストとは別スレッド）
  → 読み取り専用でメモリへコピー、前後の更新情報を比較
  → Java パーサー（Brotli 展開 → DOTSBIN v77 の構造検証）
  → 1トランザクションでスナップショット・状態・差分を登録し、最後に最新参照を切り替え
  → /octarine（概要・進捗・次の目標・変化）、/octarine/history、/octarine/maintenance
```

パーサーは提供された試作 `extract-progress.cjs` を Java へ移植したもの（`CoreKeeperWorldParser`）。
EC2 に Node.js は不要。ゲーム DLL は実行時に使わず、リポジトリにも含めない。

## 実装済み・未対応・未検証

| 項目 | 状態 |
| --- | --- |
| 提供ワールド（新規ワールド1件）の再解析 | **検証済み**。試作と同じ結果（大壁未解放、対応ボス記録なし、記録4件）をテストで再現 |
| 切れたファイル・未知の形式バージョン・壊れたノード表・サイズ上限超過の拒否 | **検証済み**（テスト） |
| 大壁の解放状態（`TheGreatWallStatusSerializedCD.HasBeenLowered`） | 実装済み。**解放後のセーブでの true は未検証** |
| ボス10種の討伐状態（ゲームが記録一覧から討伐フラグを立てる ID） | 実装済み。**討伐後のセーブでの false→true は未検証** |
| 記録一覧が外部バッファに移る大きなワールド | 読み取り処理は実装済み。**実データでは未検証** |
| Glurch・Ghorm・Hive Mother・Malugaz | 「取得対象外」。ゲームがこの一覧から討伐判定していないため、不明として扱う |
| ワールド生成 JSON・シード | 取得して保存。シードが変わったら取り込みを止める（ワールド差し替えの検知） |
| 難易度・「生成設定が全部中」 | **未対応**。内部値との対応が未確認のため表示しない。管理画面の「設定メモ」は手入力の設定情報として区別して表示 |
| 討伐日時・参加者・回数 | 対象外。履歴の日時は「サイトが初めて確認した時刻」 |
| 全体クリア率 | 表示しない。「取得対象のうち何項目達成したか」だけを表示 |
| チェスト・装備・スキル・マップ・プレイ時間 | 初期対象外 |

**本番運用の前に**、ボスを1体倒して保存したセーブと、大壁を解放した後のセーブで、該当項目が
「達成」に変わることを確認する。確認できるまでは結果を参考値として扱う。

ボスの表示名は仮称を含む（ゲームの日本語名を確認できていないため）。管理画面で変更できる。
内部キーと判定元（ObjectID）は表示名と別に保存しており、表示名を変えても判定は変わらない。

## 判定と履歴のルール

- 状態は「達成」「未達」「不明」の3つ。解析に失敗したときはスナップショットを作らず、最新の正常データを表示し続ける。失敗で未達に戻ることはない。
- 記録一覧を最後まで読めた場合に限り、対象 ID が無いことを「未達」とする。取得対象外の項目は常に「不明（取得対象外）」。
- 初回取り込みは「初回観測」として記録し、タイムラインには流さない。
- 以後の変化は、変化を確認 / 状態が後退（バックアップ復元などでも起こる。理由は断定しない）/
  解析方式の更新による変更 / 新たに取得対象化、に分けて記録する。
- タイムラインへ投稿するのは「変化を確認」で達成になった項目だけ。
- 同じ内容（SHA-256）を同じパーサーバージョンで再登録しない。パーサーを更新すると
  `PARSER_VERSION` が変わり、同じセーブでも自動で再解析される。
- 未到達のネタバレ項目は名前を「？？？」で表示する。ページの「未到達の名前を表示」で切り替えられる。
- 攻略記事は、すべての「表示」条件を満たし、どの「非表示」条件にも当たらないときに表示する。
  不明な項目は条件を満たさない。取得対象外の項目は条件に使えない。記事は初期状態では0件。

## 保存中ファイルへの対処

- ファイルは読み取りのみ。ゲームサーバーを止めたり、ファイルをロックしたりしない。
- 読む前後でサイズと更新時刻を比べ、変わっていたら3秒おいて最大3回まで読み直す。
  直近5秒以内に更新されたファイルも保存中とみなして待つ。それでも安定しなければ
  `SOURCE_CHANGING` として見送る。
- コピー後に構造をすべて検証する。読めたことは整合性の保証にはならないため、検証に通らなければ登録しない。
- 定期実行では、サイズと更新時刻が前回成功時と同じならファイルを読まずに「変更なし」とする。

## 上限と負荷

| 設定 | 既定値 |
| --- | --- |
| `COREKEEPER_MAX_COMPRESSED_SIZE` | 64MB |
| `COREKEEPER_MAX_DECOMPRESSED_SIZE` | 256MB |
| `app.corekeeper.parse-timeout` | 60秒 |
| `COREKEEPER_IMPORT_RUN_RETENTION` | 90日（取り込み履歴。進捗の履歴とスナップショットは削除しない） |

提供ワールドは圧縮 0.67MB・展開 9.8MB。展開後のデータは一時的に Web アプリの Java ヒープに載る。
管理画面の取り込み履歴に処理時間と展開サイズが残るので、ワールドが大きくなったら
`free -h` / `docker stats` と合わせて確認する。負荷が問題になる場合は、上限を下げるか、
解析を別環境に分ける。解析用ファイルはディスクに残さない。

## EC2 / AWS で行う作業

以下はこのリポジトリの変更だけでは反映されない。順番どおりに行う。

### 1. 保存ファイルの読み取り権限

Web アプリは `rubinco-web` ユーザーで動く（[DOCKER_PERMISSIONS.md](DOCKER_PERMISSIONS.md)）。
保存フォルダへ読み取り専用の ACL を付ける。書き込み権限は与えない。

```bash
sudo sh -c 'umask 077; getfacl -R -p /home/ec2-user/rubinco/servers/corekeeper > /root/rubinco-corekeeper-acl.before'

sudo setfacl -m u:rubinco-web:--x \
  /home/ec2-user /home/ec2-user/rubinco /home/ec2-user/rubinco/servers \
  /home/ec2-user/rubinco/servers/corekeeper /home/ec2-user/rubinco/servers/corekeeper/server-data
# 既存ファイルと、ゲームが今後作り直すファイルの両方を読めるようにする。
sudo setfacl -R -m u:rubinco-web:r-X /home/ec2-user/rubinco/servers/corekeeper/server-data/worlds
sudo setfacl -m d:u:rubinco-web:r-X /home/ec2-user/rubinco/servers/corekeeper/server-data/worlds

# 確認（読めること、書けないこと）
sudo -u rubinco-web head -c 4 /home/ec2-user/rubinco/servers/corekeeper/server-data/worlds/0.world.gzip | xxd
sudo -u rubinco-web test -w /home/ec2-user/rubinco/servers/corekeeper/server-data/worlds && echo "書き込めてしまう" || echo "OK: 書き込み不可"
```

保存時にゲームがファイルを作り直しても、既定 ACL で読めることを、保存後の取り込み結果で確認する。

### 2. DNS・Nginx・証明書

- `octarine.rubinco.link` を既存 EC2 へ向ける（他のサイトと同じレコード種別）。
- Nginx の `server_name` に `octarine.rubinco.link` を追加する（[設定例](../infra/nginx/game-portal.conf.example)）。
- 証明書に `octarine.rubinco.link` を SAN として追加する（現行の Let's Encrypt 証明書の更新手順に合わせる）。

### 3. Cognito アプリクライアント

[infra/cognito/template.yaml](../infra/cognito/template.yaml) に `OctarineClient` を追加した。
スタックを更新し、出力 `OctarineClientId` を `/etc/rubinco/watchpoint.env` に保存する。

```ini
COGNITO_OCTARINE_CLIENT_ID=<OctarineClientId の値>
```

**未設定でもアプリは起動する**（Octarine だけポータルのクライアント ID で代用する）。その間は
Cognito にコールバック URL が無いため、Octarine へのログインが Cognito で失敗する。
他のサイトには影響しない。設定後に `rubinco-watchpoint.service` を再起動する。

### 4. デプロイ後の確認

1. 新しい JAR の起動で Flyway V43 が `M_GAME_WORLD` などのテーブルと初期データを作る。
2. 管理者で `https://octarine.rubinco.link/octarine/maintenance` を開き、「今すぐ再取得」を押す。
3. 15秒ほどで取り込み履歴が `SUCCEEDED` になることを確認する。`SOURCE_UNREADABLE` なら手順1を見直す。
4. メンバーで `/octarine` を開き、大壁・ボスの進捗と「初回観測」の履歴が出ることを確認する。
5. ポータルのサイドバーに Octarine（◆）が出て、リンクから移動できることを確認する。

## ワールドを差し替えるとき

管理画面の「新しいワールドへ差し替える」で新しい世代を作る。以後の取り込みは新しい世代の
履歴になり、旧世代の履歴には継ぎ足さない。シードが変わったのに世代を切り替えていない場合は
`WORLD_MISMATCH` で取り込みを止め、旧ワールドの表示を維持する。

## 権限

- 閲覧（`/octarine`、`/octarine/history`）はログインしたメンバー。未ログインはログインへ誘導。
- 管理（`/octarine/maintenance/**`）は管理者だけ。ゲスト権限が混ざったアカウントは管理者として扱わない。
  ルート・コントローラ・サービスの3か所で同じ判定をする。変更操作はすべて CSRF トークンが必要。
- 手動再取得はジョブとして登録し、実行待ち・実行中があるあいだは追加しない。
- 記事の参考リンクは http(s) の URL だけ受け付ける。ゲーム由来・管理者入力の文字列はエスケープして表示する。
- セーブが更新されないことだけでサーバー停止とは判定しない。稼働状況は Docker の状態から表示する。
