# Luminite（Terraria 連携）

Terraria サーバーのワールドファイル（`.wld`）を読み取り、PostgreSQL に保存して
`luminite.rubinco.link` に統計として表示する。Octarine（Core Keeper）と同じ取り込み基盤
（`worldprogress`）・同じ DB・同じ認証で動く。

```text
Terraria がワールドを保存（.wld を丸ごと書き直す）
  → アプリ内の定期ジョブ（5分ごと、Web リクエストとは別スレッド）
  → 読み取り専用でメモリへコピー、前後の更新情報を比較（StableSourceReader、Octarine と共通）
  → Java パーサー（TerrariaWorldParser。v326 の全セクション境界を検証）
  → 内容ハッシュが前回と同じなら「変更なし」（時刻・天気は比較から除く）
  → 1トランザクションでスナップショット・進捗・チェスト・合計・図鑑・看板を登録
  → /luminite 以下の各ページ、/luminite/maintenance
```

Terraria は自動保存のたびにファイルを書き直すため、ファイルのハッシュではなく
「時刻・天気を除いた内容」のハッシュで同一判定している。

## ページ

| URL | 内容 |
| --- | --- |
| `/luminite` | ワールド概要（サイズ・難易度・勢力）、ハイライト、今の時刻・月・イベント、ハロウ/悪の割合、ボス・イベント・住人・ワールドの進捗、最近の動き |
| `/luminite/items` | 保管中のアイテム一覧（チェスト・タンス・アイテムフレーム・武器棚・マネキン・帽子掛け・プレート・アイテムフラスコの合計）。名前検索（ひらがな/カタカナ区別なし・英語名可）、並び替え |
| `/luminite/items/{id}` | アイテムごとのページ。**一度でも保管が確認されたアイテムだけ**（それ以外は 404）。置き場所・個数の変化 |
| `/luminite/chests` | チェスト検索。入っているアイテム名・チェスト名・種類で絞り込み。場所は方位磁石/深度計と同じ表記 |
| `/luminite/chests/{id}` | 入れ物の中身（枠番号・接頭辞つき） |
| `/luminite/bestiary` | 図鑑の討伐数ランキング、目撃・会話 |
| `/luminite/world` | 残っている鉱石・宝石・ブロック、ライフクリスタル等の設置物、住人と家、パイロン、建築・配線の統計 |
| `/luminite/deaths` | 墓石の碑文（死因と日付）と看板の文字 |
| `/luminite/history` | 進捗の履歴と、アイテム・討伐・墓石の動き |
| `/luminite/maintenance` | 管理者のみ。取り込み履歴・手動再取得・名前辞書の状態・世代・進捗項目・攻略記事 |

## 実装済み・未対応・未検証

| 項目 | 状態 |
| --- | --- |
| v326（1.4.4 系の現行版）の全セクションの読み取りと境界検証 | **検証済み**。実ワールド1件（6400×1800、チェスト355、図鑑467種）で全セクションが記録位置で終わることをテスト（ファイルは公開リポジトリに含めないため、ある場合だけ実行） |
| 他バージョンの拒否、切れたファイル、フッター改ざん | **検証済み**（合成ワールドのテスト） |
| ボス・イベント・NPC・ワールドのフラグ 65項目 | 実装済み。実ワールドの値は独立に書いた試作抽出器と一致。**討伐直後の false→true の変化は未検証** |
| チェスト・タンスの中身、種類（たからばこの種類） | 実装済み・実データ確認済み |
| アイテムフレーム・武器棚・マネキン・帽子掛け・プレート・アイテムフラスコ | 実装済み。アイテムフレームは実データで確認、**ほかは実データに無く未検証** |
| 図鑑の討伐数・目撃・会話 | 実装済み・実データ確認済み |
| ハロウ/悪の割合 | ゲームの `WorldGen.CountTiles` と同じ計算。**ゲーム内のドライアドの表示との突き合わせは未実施** |
| 資源・設置物の数 | タイル数から計算。設置物（ライフクリスタル等）は左上タイルだけ数える |
| プレイヤーの持ち物・ピギーバンク・金庫・討伐した人・プレイ時間 | **対象外**（`.wld` に含まれない。プレイヤーファイル側の情報） |
| 墓石の置かれた日時 | 碑文の日付（ゲームが書く文字）だけ。初回取り込み時からあった墓石は確認時刻なし |
| マップ画像 | 今回は作らない |
| TShock 等のサーバー改造版 | `.wld` の形式は同じはずだが**未検証** |

## 名前辞書（サーバーに置く）

アイテム・NPC・接頭辞・入れ物の名前はゲームの文字データなので、公開リポジトリには入れない。
ローカルの Steam 版から生成し、サーバーの `TERRARIA_NAMES_DIR`（本番の既定値
`/etc/rubinco/terraria-names`）に置く。無い場合も動き、名前の代わりに番号を表示する。
ファイルの更新は1分以内に自動で読み直す（再起動不要）。

生成手順（macOS の例。作業フォルダはリポジトリの外にする）:

```bash
EXE="$HOME/Library/Application Support/Steam/steamapps/common/Terraria/Terraria.app/Contents/Resources/Terraria.exe"
W=~/terraria-names-work; mkdir -p $W/decomp $W/res $W/tools
dotnet tool install ilspycmd --tool-path $W/tools
for t in ItemID NPCID PrefixID; do $W/tools/ilspycmd -t Terraria.ID.$t "$EXE" > $W/decomp/$t.cs; done
(cd $W/res && monodis --mresources "$EXE")
python3 scripts/terraria/build-name-tables.py $W/decomp $W/res $W/tables
```

`items.tsv` `npcs.tsv` `prefixes.tsv` `containers.tsv` `mapobjects.tsv` の5つができる（約430KB）。
ゲームを更新してアイテムが増えたら作り直す。サーバーへの配置:

```bash
# 手元からコピーしたあと、EC2 で:
sudo install -d -m 755 /etc/rubinco/terraria-names
sudo install -m 644 /tmp/terraria-names/*.tsv /etc/rubinco/terraria-names/
ls -l /etc/rubinco/terraria-names
```

管理画面の「名前辞書」に読み込んだアイテム数が出る。

## EC2 / AWS で行う作業

DNS・証明書・Nginx・Cognito の Luminite 分は設定済み（[OCTARINE.md](OCTARINE.md) の手順2で
`luminite.rubinco.link` も追加済み）。残りは次のとおり。

### 1. ワールドファイルの場所を確認して設定する

ワールドファイルの場所は推測で決めない。サーバーの compose 設定とファイルを確認する:

```bash
sudo grep -nE "volumes|wld|world" /home/ec2-user/rubinco/servers/terraria/compose.yml
sudo find /home/ec2-user/rubinco/servers/terraria -name "*.wld" -printf "%p %s %TY-%Tm-%Td %TH:%TM\n"
```

見つかったパスを `/etc/rubinco/watchpoint.env` に追加する（1行ずつ実行）:

```bash
F=/etc/rubinco/watchpoint.env
echo "TERRARIA_WORLD_FILE=<.wld のフルパス>" | sudo tee -a $F
echo "TERRARIA_IMPORT_ENABLED=true" | sudo tee -a $F
```

### 2. 読み取り権限

Octarine と同じく、`rubinco-web` に読み取り専用の ACL を付ける。`<world dir>` は手順1の
`.wld` があるフォルダ。途中のフォルダには通過権（`--x`）だけを付ける。

```bash
sudo setfacl -m u:rubinco-web:--x /home/ec2-user/rubinco/servers/terraria
# <world dir> までの途中のフォルダにも同じ --x を付ける。
sudo setfacl -R -m u:rubinco-web:r-X <world dir>
sudo setfacl -m d:u:rubinco-web:r-X <world dir>
sudo -u rubinco-web head -c 4 <.wld のフルパス> | od -An -tx1
sudo -u rubinco-web test -w <world dir> && echo "書き込めてしまう" || echo "OK: 書き込み不可"
```

保存でファイルが作り直されても既定 ACL で読めることを、保存後の取り込み結果で確認する。

### 3. デプロイ後の確認

1. 起動時に Flyway V44 が Terraria 用のテーブルと進捗項目を作る。
2. `sudo systemctl restart rubinco-watchpoint.service`（環境変数を読み直す）。
3. 管理者で `/luminite/maintenance` を開き「今すぐ再取得」。15秒ほどで `SUCCEEDED` になる。
   `SOURCE_NOT_CONFIGURED` は手順1、`SOURCE_UNREADABLE` は手順2を見直す。
4. 初回は「初回観測」で、アイテムや討伐の増減は記録しない。2回目以降の保存から「最近の動き」に出る。

## ワールドを差し替えるとき

管理画面の「新しいワールドへ差し替える」で新しい世代を作る。ワールドの GUID が変わったのに
世代を切り替えていない場合は `WORLD_MISMATCH` で取り込みを止め、旧ワールドの表示を維持する。

## 上限と負荷

| 設定 | 既定値 |
| --- | --- |
| `TERRARIA_MAX_FILE_SIZE` | 64MB |
| `TERRARIA_IMPORT_INTERVAL` | 5分（最短1分） |
| `app.terraria.parse-timeout` | 60秒 |
| `TERRARIA_IMPORT_RUN_RETENTION` | 90日 |

中ワールド（6.9MB）の解析は手元で約0.2秒。ファイル全体を一時的に Java ヒープへ読み込む。
内容が変わらない保存はスナップショットを作らない。

## 権限

- 閲覧は Octarine と同じくログインしたメンバー。未ログインはログインへ誘導。
- `/luminite/maintenance/**` は管理者だけ（ルート・コントローラの両方で判定）。変更操作は CSRF トークンが必要。
- チェスト名・看板・墓石・NPC 名はプレイヤーが書ける文字なので、すべてエスケープして表示する。
- Web アプリはワールドファイルを読むだけで、書き込み権限も Docker ソケットも持たない。
