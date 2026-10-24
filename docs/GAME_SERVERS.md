# EC2 のゲームサーバー起動

Terraria は EC2/Docker 起動時に自動起動し、Core Keeper・Minecraft・7 Days to Die は
ログインしたメンバーまたは管理者がポータルから起動する。
この機能は既存コンテナを `docker start` / `docker stop` する。EC2 自体を起動・停止する機能ではない。
Web アプリ、Docker、PostgreSQL は稼働している必要がある。

| ゲーム ID | コンテナ | 起動方法 | Docker restart policy |
| --- | --- | --- | --- |
| `terraria` | `terraria` | 自動 | `always` |
| `corekeeper` | `rubinco-corekeeper` | ポータル | `no` |
| `minecraft` | `rubinco-minecraft` | ポータル | `no` |
| `7dtd` | `7dtd` | ポータル | `no` |

## EC2 で一度だけ行う設定

### 起動操作でサイトが停止した場合の切り分け

まず EC2 のターミナルで次を実行する。CloudShell ではなく、実際に Docker が動く
EC2 ホストで確認する。これらのコマンドはコンテナやサービスを起動・停止しない。

```bash
# Docker 起動時に Java が OOM kill されていないか。空なら範囲を広げて確認する。
sudo journalctl -k --since '2 hours ago' --no-pager | grep -Ei 'oom|out of memory|killed process'
sudo journalctl -u rubinco-watchpoint.service --since '2 hours ago' -n 150 --no-pager
free -h
sudo docker stats --no-stream

# Docker 自動起動・実状態・コンテナ自身の OOM を比較する。
sudo docker inspect --format '{{.Name}} restart={{.HostConfig.RestartPolicy.Name}} status={{.State.Status}} started={{.State.StartedAt}} oom={{.State.OOMKilled}} exit={{.State.ExitCode}} memoryLimit={{.HostConfig.Memory}}' \
  terraria rubinco-corekeeper rubinco-minecraft 7dtd

# Web unit の依存や事前処理からゲームを起動していないか。
systemctl show rubinco-watchpoint.service --property=Requires,Wants,ExecStartPre --no-pager
systemctl is-enabled rubinco-7dtd-server.service
systemctl list-unit-files --type=service | grep -Ei '7dtd|seven.?days|core.?keeper|minecraft|terraria'
```

`Killed process ... (java)` や Web unit の `oom-kill` は Web 停止の証拠になる。
ゲーム側の `OOMKilled=false` だけでは Java が OOM kill されていないとは判断できない。
`Memory` 制限の `0` は Docker によるメモリ上限がないことを表す。
メモリ不足が確認できた場合は、EC2 の容量とゲームごとの実使用量に合わせて
ゲームコンテナのメモリ上限・同時稼働数を調整する。起動ボタンの例外処理だけでは
OS による Java プロセスの終了を防げない。

Docker の `restart=no` でも systemd や Compose の `up` からは起動できる。
Docker と systemd の両方を確認してから、以下の設定を適用する。

### 自動起動方針を反映する

以下は EC2 に SSH 接続して実行する。本リポジトリの変更だけでは、既存 EC2 の起動設定は
変更されない。管理作業は `ec2-user`、Web アプリの実行ユーザーは `rubinco-web`。

```bash
# 次回 EC2 起動時に、systemd から 7dtd を起動しない。
# --now を付けないので、この操作でゲームコンテナは停止しない。
sudo systemctl disable rubinco-7dtd-server.service

# 既存コンテナの次回以降の起動方針を変更。現在の Core Keeper は停止しない。
docker update --restart=always terraria
docker update --restart=no rubinco-corekeeper rubinco-minecraft 7dtd

# Terraria が停止中なら起動する。既存コンテナとデータをそのまま使用する。
docker start terraria
```

`disable` は依存関係からの起動を禁止する操作ではない。以下を確認し、Web アプリの unit に
`rubinco-7dtd-server.service` への `Requires` / `Wants` や `ExecStartPre` によるゲーム起動が
ある場合は、設定元からその依存・起動処理を外す。PostgreSQL の起動処理は残す。

```bash
systemctl show rubinco-watchpoint.service --property=Requires,Wants,ExecStartPre --no-pager
```

Compose からコンテナを再作成しても設定が戻らないよう、**既存** Compose ファイル内の
該当サービスにも同じ restart policy を保存する。`no` は YAML で文字列として引用する。

```yaml
# Terraria のサービス
restart: always

# Core Keeper / Minecraft / 7dtd の各サービス
restart: "no"
```

確認済みの Compose ファイル:

- Terraria: `/home/ec2-user/rubinco/servers/terraria/compose.yml`
- Core Keeper: `/home/ec2-user/rubinco/servers/corekeeper/compose.yml`
- Minecraft / 7dtd: 使用している起動定義に保存する。ファイルの場所は未確認。

既存ファイルのイメージ、ポート、マウント、環境変数は維持する。
この設定変更を反映するために `compose down` やコンテナの再作成を行う必要はない。
`docker update` で既存コンテナへの反映は完了している。

## サイトのメモリ枠による起動制限

ゲームごとの設定値を予約枠として扱い、稼働中・起動中・メンテナンス中の枠と
起動対象の枠の合計がゲーム用上限を超える場合、起動を拒否する。
Terraria のような自動起動ゲームも合算する。状態不明・取得エラーのゲームが
ある場合は空き容量を推測せず、新しい起動を拒否する。
サイトには各ゲームの必要枠、予約済みの合計、上限と拒否理由を表示する。

| 環境変数 | 既定値（MiB） | 表示 |
| --- | ---: | --- |
| `GAME_MEMORY_BUDGET_MIB` | 2560 | ゲーム全体 2.5 GiB |
| `SEVEN_DAYS_MEMORY_MIB` | 2048 | 7DTD 2.0 GiB |
| `MINECRAFT_MEMORY_MIB` | 1024 | Minecraft 1.0 GiB |
| `COREKEEPER_MEMORY_MIB` | 1024 | Core Keeper 1.0 GiB |
| `TERRARIA_MEMORY_MIB` | 512 | Terraria 0.5 GiB |

既定値では、7DTD は自動起動の Terraria とだけ同時に稼働できる（2.0 + 0.5 = 2.5 GiB）。
7DTD の稼働中は Minecraft・Core Keeper を起動できず、逆に Minecraft か Core Keeper が
稼働中は 7DTD を起動できない。Minecraft・Core Keeper・Terraria の3つは同時に稼働できる。
これらは運用上の予約枠であり、各ゲームの動作保証値ではない。
実測のピーク使用量、ワールドやMOD、同時接続数に合わせて枠を設定し、Web・DB・OS用の
余裕をゲーム予算とは別に確保する。特にMinecraftのJavaヒープ以外の使用量も考慮する。

本番の `/etc/rubinco/watchpoint.env` に必要な上書きを保存し、JAR反映後に
`rubinco-watchpoint.service` を再起動すると適用される。
Spring設定名は `app.games.memory-budget-mib` と各カタログ項目の `memory-mib`。
値は1〜1048576の整数で、0や負数で制限を無効化することはできない。

起動APIでも同じ判定を行うため、JavaScript無効化やAPIの直接呼び出しでは制限を回避できない。
すべてのゲーム状態行を同じ順序でロックし、Docker状態の表示キャッシュを使わずに確認してから
起動する。別のゲームや別のアプリインスタンスへの同時要求も、同じDBを使う限り直列化される。
起動に成功したゲームは、Dockerで稼働中（またはDBで起動中）の時点で枠を消費する。

この制限はサイト経由の起動判断であり、Dockerの実メモリ上限を設定するものではない。
`docker start` やsystemdによる外部起動、自動起動は止められないため、前節の起動方針も反映する。
すでに予算を超えて稼働している場合は追加起動だけを拒否し、稼働中のゲームを自動で停止しない。
実際の使用量が予約枠を超えればホストのメモリ不足は起こり得るので、Docker側の上限設定も別途行う。

## アプリで Docker 起動を有効にする

**先に [Docker 権限の分離手順](DOCKER_PERMISSIONS.md) を実施する。**
アプリの実行ユーザーを `rubinco-web` に分け、固定操作だけを許可した root 所有ヘルパーを配置する。
ゲームの起動と7dtd のログ取得の両方がこのヘルパーを使う。

既定値は `mock`。Docker を操作するのは明示的に `docker` を選んだ場合だけ。
本番の実行パスは `/home/ec2-user/rubinco/apps/watchpoint/app/app.jar`、サービス名は
`rubinco-watchpoint.service`。既存のデプロイ手順で変更後の JAR を配置する。
新 JAR の起動で Flyway V42 が Core Keeper / Minecraft の状態行を追加する。

本番サービスへ環境変数を渡すには次を実行する。

```bash
sudo systemctl edit rubinco-watchpoint.service
```

次の内容を保存する。

```ini
[Service]
Environment=GAME_SERVER_START_PROVIDER=docker
```

```bash
sudo systemctl daemon-reload
sudo systemctl restart rubinco-watchpoint.service
sudo journalctl -u rubinco-watchpoint.service -n 100 --no-pager
```

すでに unit の `EnvironmentFile` で `.env` を読み込んでいる場合は、代わりにそのファイルへ
`GAME_SERVER_START_PROVIDER=docker` を追加して再起動してもよい。Spring Boot 自体は
`.env` を自動では読み込まない。Cognito の issuer/client ID の変更は不要。

アプリは `/usr/bin/sudo -n` 経由で `/usr/local/sbin/rubinco-game-control` を呼ぶ。
`rubinco-web` に Docker ソケットのアクセス権限や汎用的な sudo 権限を与えない。
コンテナ名と Docker 引数は root 所有のヘルパー内で固定し、利用者の入力をシェルコマンドとして
実行しない。ヘルパー未配置・権限不備の場合は起動を拒否し、直接 Docker へ切り替えない。

## 動作確認

1. メンバーまたは管理者で `/portal` にログインする。
2. サーバー起動欄に4ゲームが表示され、Terraria は「自動起動」でボタンがないことを確認する。
3. 停止中の Core Keeper・Minecraft・7dtd だけ「起動する」が押せることを確認する。
4. 起動するとボタンが無効になり、状態が15秒間隔で更新される。
5. `docker ps -a` とサイトの表示が一致することを確認する。

```bash
docker inspect --format '{{.Name}} restart={{.HostConfig.RestartPolicy.Name}} status={{.State.Status}}' \
  terraria rubinco-corekeeper rubinco-minecraft 7dtd
systemctl is-enabled rubinco-7dtd-server.service
```

`is-enabled` の期待値は `disabled`（終了コードが非ゼロでも正常）。
7dtd の unit が `active` でも、過去に `docker stop 7dtd` した場合はコンテナは停止している。
サイトは systemd の `active` ではなく Docker の状態を参照する。
Docker 状態を取得できない場合はエラー表示にして起動を拒否する。
Docker に healthcheck がある場合はその `starting` / `unhealthy` も反映する。
healthcheck のないゲームは「稼働中」になっても、ゲームへの接続準備に数分かかる場合がある。

アプリのローカル開発と自動テストは `mock` のままで、実ゲームや EC2 は操作しない。

## サイトからの停止

起動できるユーザーは、稼働中（起動中を含む）の 7dtd・Core Keeper・Minecraft を停止できる。
Terraria は自動起動のため停止ボタンを表示せず、API でも拒否する。
停止前に確認ダイアログを表示する。接続中のプレイヤーは切断される。

停止はヘルパー経由の `docker stop --time 120` で行う。コンテナに SIGTERM を送り、
120秒以内に終了しなければ Docker が SIGKILL で強制終了する。ワールドの保存は
各イメージが SIGTERM を受けて保存・終了する実装に依存する。導入済みイメージの挙動を
一度確認し、保存に120秒以上かかるゲームがある場合はヘルパーの値を見直す。

```bash
# 停止後のログで保存・正常終了していることを確認する（SIGKILL なら ExitCode=137）。
docker inspect --format '{{.Name}} exit={{.State.ExitCode}} finished={{.State.FinishedAt}}' 7dtd
docker logs --tail 50 7dtd
```

停止処理は Web リクエストとは別スレッドで実行し、サイトには完了まで「停止処理中」と表示する。
停止処理中のゲームは起動できず、メモリ枠も停止完了まで予約したままにする。
アプリ側の待ち時間は `app.games.docker.stop-timeout`（既定150秒、125〜600秒）。
停止処理中に Web アプリを再起動すると Docker クライアントも終了するため、
完了しなかった場合はサイトの表示を確認して再度停止する。

**停止を使うには、EC2 のヘルパーと sudoers を更新版に入れ替える必要がある。**
[Docker 権限の分離手順](DOCKER_PERMISSIONS.md) の配置手順を再実行する。
旧ヘルパーのままでは sudo が停止を拒否し、ログに `Docker stop failed` を出して稼働中の表示に戻る。

## 操作権限

- 未ログイン、ゲスト（`ROLE_VIEWER`）、認識していない権限のユーザーは起動・停止できない。
- ゲストに `ADMIN` / `MEMBER` / `PLAYER` が混在しても、ゲスト権限を優先して拒否する。
- 起動・停止を許可するのは認証済みの管理者・メンバー（`ADMIN` / `MEMBER` / `PLAYER`）だけ。
- 操作ボタンの表示、HTTP の起動・停止 API、サービスのメソッドに同じ認可ルールを適用する。
  操作者名の文字列を渡すだけでは権限を得られない。
- セッションを使うポータル操作とローカル API は、有効な CSRF トークンも必要。
  Cognito の `/api/**` は JWT 認証で、セッション Cookie だけでは操作できない。
- 認可を拒否された起動・停止要求は Docker コマンドを呼び出さず、状態行も変更しない。

ゲストの実ログインセッション、直接 API 呼び出し、権限混在、CSRF 不足・不正、
サービスの直接呼び出しを自動テストで検証する。

## 公式仕様

- [Docker の自動起動と restart policy](https://docs.docker.com/engine/containers/start-containers-automatically/)
- [既存コンテナの restart policy 変更](https://docs.docker.com/reference/cli/docker/container/update/)
- [Spring Security のメソッド認可](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
