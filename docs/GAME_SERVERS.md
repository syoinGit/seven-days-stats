# EC2 のゲームサーバー起動

Terraria は EC2/Docker 起動時に自動起動し、Core Keeper・Minecraft・7 Days to Die は
ログインしたメンバーまたは管理者がポータルから起動する。
この機能は既存コンテナを `docker start` する。EC2 自体を起動する機能ではない。
Web アプリ、Docker、PostgreSQL は稼働している必要がある。

| ゲーム ID | コンテナ | 起動方法 | Docker restart policy |
| --- | --- | --- | --- |
| `terraria` | `terraria` | 自動 | `always` |
| `corekeeper` | `rubinco-corekeeper` | ポータル | `no` |
| `minecraft` | `rubinco-minecraft` | ポータル | `no` |
| `7dtd` | `7dtd` | ポータル | `no` |

## EC2 で一度だけ行う設定

以下は EC2 に SSH 接続して実行する。本リポジトリの変更だけでは、既存 EC2 の起動設定は
変更されない。現在の実行ユーザーは Web アプリと同じ `ec2-user`。

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

現時点でサイトは起動操作のみ。停止は各ゲームの保存・終了方法を確認したうえで運用する。
アプリのローカル開発と自動テストは `mock` のままで、実ゲームや EC2 は操作しない。

## 操作権限

- 未ログイン、ゲスト（`ROLE_VIEWER`）、認識していない権限のユーザーは起動できない。
- ゲストに `ADMIN` / `MEMBER` / `PLAYER` が混在しても、ゲスト権限を優先して拒否する。
- 起動を許可するのは認証済みの管理者・メンバー（`ADMIN` / `MEMBER` / `PLAYER`）だけ。
- 操作ボタンの表示、HTTP の起動 API、起動サービスのメソッドに同じ認可ルールを適用する。
  操作者名の文字列を渡すだけでは権限を得られない。
- セッションを使うポータル操作とローカル API は、有効な CSRF トークンも必要。
  Cognito の `/api/**` は JWT 認証で、セッション Cookie だけでは操作できない。
- 認可を拒否された起動要求は Docker コマンドを呼び出さず、状態行も変更しない。

ゲストの実ログインセッション、直接 API 呼び出し、権限混在、CSRF 不足・不正、
サービスの直接呼び出しを自動テストで検証する。

## 公式仕様

- [Docker の自動起動と restart policy](https://docs.docker.com/engine/containers/start-containers-automatically/)
- [既存コンテナの restart policy 変更](https://docs.docker.com/reference/cli/docker/container/update/)
- [Spring Security のメソッド認可](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
