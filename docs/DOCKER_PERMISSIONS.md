# Web アプリの Docker 権限を限定する

Web アプリを管理用の `ec2-user` から専用の `rubinco-web` に分離する。
`rubinco-web` は Docker / wheel グループへ入れず、Docker ソケットを直接操作させない。
root 所有の固定ヘルパーを、引数まで限定した sudo 設定で呼び出す。

```text
ブラウザ → Spring の認証・認可・CSRF 検証
         → rubinco-web
         → sudo -n /usr/local/sbin/rubinco-game-control <許可された操作>
         → root が固定の Docker コマンドを実行
```

| 許可する操作 | 対象 |
| --- | --- |
| 状態取得 | 7dtd / Terraria / Core Keeper / Minecraft |
| 起動 | 7dtd / Core Keeper / Minecraft |
| 停止（`docker stop --time 120` 固定） | 7dtd / Core Keeper / Minecraft |
| ログ取得（直近5分から追尾） | 7dtd のみ。既存の Watchpoint ログ取り込み用 |

Terraria の停止・削除・コンテナ作成・`exec`・任意のオプション・PostgreSQL 操作は許可しない。
停止の猶予秒数はヘルパー内で固定し、呼び出し側から変更できない。
Terraria の自動起動方針と既存コンテナのデータは変更しない。

## 反映の順序

**先にヘルパー・専用ユーザー・読み取り権限と systemd 設定を準備し、新 JAR の配置後に再起動する。**
新 JAR はゲーム起動だけでなく 7dtd の Docker ログ取得もヘルパー経由になる。
未設定の場合は処理が失敗し、Docker 直接操作へはフォールバックしない。
「0. ファイル転送」の作成・転送コマンドは Mac、展開以降は EC2 の管理用 SSH セッション（`ec2-user`）で実行する。
既存の `ec2-user` の Docker / sudo 権限は管理作業に残す。Web アプリの実行ユーザーだけを分ける。

以下の `infra/...` ファイルは新 JAR と同じリビジョンから EC2 へ転送しておく。
GitHub Actions の既存デプロイは JAR だけを送るため、これらの設定は別途一度配置する。

## 0. ファイル転送（Mac → EC2）

まず Mac のターミナルで、リポジトリのソースから転送用アーカイブと貼り付け用ファイルを生成する。
`target` は Git 管理外であり、clone や `clean` の後は生成し直す。

```bash
cd /Users/agasa/Dev/Java/seven-days-stats
python3 infra/docker/prepare-permissions-files.py
```

### SCP が止まる場合：EC2 のターミナルへ直接貼り付ける

すでに EC2 のターミナルを開けている場合は、SCP の代わりに Mac 側の
`/Users/agasa/Dev/Java/seven-days-stats/target/rubinco-permissions-paste.sh` をエディタで開き、
**ファイルの全文**をコピーして **EC2 のターミナル**へ貼り付ける。実行中の SCP は Mac 側で
`Ctrl+C` で中断できる。Mac 側でこのスクリプトを実行しない。

このスクリプトは今回の3ファイルを埋め込んだ配置用ファイルであり、EC2 の
`/home/ec2-user/rubinco/apps/watchpoint/permissions/infra/` に展開する。
秘密鍵や `.env` は含まず、sudo 設定の有効化・サービス再起動も行わない。
配置完了メッセージと3ファイルの一覧が表示されたら、この節の SCP・アーカイブ展開を省略し、
手順1のバックアップ、続いて手順2のインストールへ進む。
アプリ側の3ファイルを変更した場合は、上記コマンドで再生成して使う。

### SCP を使う場合

Web アプリの実配置 `/home/ec2-user/rubinco/apps/watchpoint` に合わせ、次の構成にする。

```text
/home/ec2-user/rubinco/apps/watchpoint/
├── app/app.jar                       # 既存の JAR
├── .env                              # 切り替え前の設定・切り戻し用
├── rubinco-permissions.tar.gz         # 転送用アーカイブ
└── permissions/infra/                # ヘルパー・sudoers・systemd 設定の配置元
```

開発用の `infra` ディレクトリは EC2 に自動配置されない。まず **Mac のターミナル**で
転送用ファイルを作る。`permissions` はインストール元であり、権限を持つヘルパー本体は
手順2で root 所有の `/usr/local/sbin` に配置する。


`target/rubinco-permissions.tar.gz` を、普段の SSH と同じ接続先・秘密鍵を使い、
SCP / SFTP で EC2 の `/home/ec2-user/rubinco/apps/watchpoint/` へ転送する。
`.env` や秘密鍵はこのアーカイブに含めない。
今回の接続先では、**Mac のターミナル**で次を実行する。秘密鍵自体は転送しない。

```bash
chmod 400 /Users/agasa/Downloads/Rubinho_kagi.pem
scp -i /Users/agasa/Downloads/Rubinho_kagi.pem -o IdentitiesOnly=yes \
  /Users/agasa/Dev/Java/seven-days-stats/target/rubinco-permissions.tar.gz \
  ec2-user@13.158.251.186:/home/ec2-user/rubinco/apps/watchpoint/
```

`Operation timed out` は秘密鍵の認証より前に接続できていない状態。EC2 の現在のパブリック IP と
ネットワーク設定を確認する。SSH の受信ルールを追加する場合は TCP 22 を作業用 Mac の現在の
パブリック IP（単一 IPv4 なら `/32`）に限定し、全インターネットへ開放しない。

転送後は **EC2** で展開する。既存の `app` と `.env` は展開先に含まれない。

```bash
mkdir -p /home/ec2-user/rubinco/apps/watchpoint/permissions
tar -xzf /home/ec2-user/rubinco/apps/watchpoint/rubinco-permissions.tar.gz \
  -C /home/ec2-user/rubinco/apps/watchpoint/permissions
cd /home/ec2-user/rubinco/apps/watchpoint/permissions
ls -l infra/docker/rubinco-game-control infra/docker/rubinco-game-control.sudoers
```

`cannot stat 'infra/...'` は転送前か、実行ディレクトリが違う場合に発生する。
ファイルが確認できるまでインストールとサービス切り替えには進まない。
以降のインストールコマンドは配置元を絶対パスで指定するため、別のディレクトリからも実行できる。

## 1. 実設定の確認とバックアップ

```bash
systemctl show rubinco-watchpoint.service \
  --property=User,Group,ExecStart,ExecStartPre,WorkingDirectory,EnvironmentFiles,Requires,Wants \
  --no-pager
sudo sh -c 'umask 077; systemctl cat rubinco-watchpoint.service > /root/rubinco-watchpoint.before-restricted.txt'
```

バックアップには unit 内の環境設定が含まれる可能性があるため、共有しない。
現在確認できている JAR は `/home/ec2-user/rubinco/apps/watchpoint/app/app.jar`。
異なる場合は以下の drop-in と ACL 対象を実際の配置に合わせる。
複数サービスから同じ Web アプリを起動しない。

## 2. 専用ユーザーとヘルパーを配置

```bash
id rubinco-web || sudo useradd --system --user-group --no-create-home \
  --home-dir /var/lib/rubinco-watchpoint --shell /sbin/nologin rubinco-web
id rubinco-web

# 専用ユーザーには追加の管理用グループを持たせない。
sudo usermod -G '' rubinco-web
sudo install -d -o rubinco-web -g rubinco-web -m 0700 /var/lib/rubinco-watchpoint
sudo install -d -o root -g root -m 0755 /etc/rubinco
sudo install -o root -g root -m 0755 \
  /home/ec2-user/rubinco/apps/watchpoint/permissions/infra/docker/rubinco-game-control \
  /usr/local/sbin/rubinco-game-control

# まず候補ファイルを構文検証し、合格してから有効化する。
sudo install -o root -g root -m 0440 \
  /home/ec2-user/rubinco/apps/watchpoint/permissions/infra/docker/rubinco-game-control.sudoers \
  /etc/rubinco/game-control.sudoers.candidate
sudo visudo -c -f /etc/rubinco/game-control.sudoers.candidate
sudo install -o root -g root -m 0440 \
  /etc/rubinco/game-control.sudoers.candidate /etc/sudoers.d/rubinco-game-control
sudo visudo -c
```

`/usr/local/sbin` とヘルパーは root 所有で、Web ユーザーに書込権限を与えない。
sudoers はコマンドと引数を完全一致で列挙する。`sudo docker *` やシェルの許可へ変更しない。
ヘルパー自身も引数を再検証し、呼び出し元の環境を捨ててローカルの Docker ソケットへ接続する。

## 3. 設定ファイルと JAR・ゲームデータの読み取り権限

`EnvironmentFiles` で確認した**実際の `.env` のパス**を次の変数へ設定する。
内容をターミナルへ表示する必要はない。
場所が不明な場合は EC2 で次のコマンドを実行する。表示するのはパスだけで、秘密情報の内容ではない。

```bash
systemctl show rubinco-watchpoint.service --property=EnvironmentFiles --no-pager
sudo find /etc/rubinco /home/ec2-user/rubinco -maxdepth 6 -type f \
  \( -name '.env' -o -name '*.env' \) -print
```

確認済みの現行サービスは `/home/ec2-user/rubinco/apps/watchpoint/.env` を使用している。
`/home/ec2-user/rubinco/apps/.env` も存在するが、現行サービスのコピー元には使わない。
別の環境では `EnvironmentFiles` の実際のパスに合わせる。`RUBINCO_ENV_FILE` は
**コピー元の既存ファイル**。コピー先は drop-in の `EnvironmentFile` と一致する
`/etc/rubinco/watchpoint.env` にする。コピー先だけ変更してもコピー元のエラーは解消しない。

```bash
RUBINCO_ENV_FILE='/home/ec2-user/rubinco/apps/watchpoint/.env'
if sudo test -f "$RUBINCO_ENV_FILE"; then
  sudo install -d -o root -g root -m 0755 /etc/rubinco
  sudo install -o root -g root -m 0600 "$RUBINCO_ENV_FILE" /etc/rubinco/watchpoint.env
else
  printf '%s\n' 'コピー元が存在しません。RUBINCO_ENV_FILE に実在するファイルのパスを設定してください。'
fi
```

systemd が root としてこのファイルを読み、アプリへ環境変数を渡す。`rubinco-web` にファイルの
読み取り権限は不要。手順5で新 JAR を配置して再起動するまでは、稼働中のプロセスは元の設定を使う。
切り替え完了後の `.env` 更新先は `/etc/rubinco/watchpoint.env` に統一する。元のファイルは
切り戻し用に残し、移行中に設定変更した場合は新しいコピーにも反映する。

ACL コマンド `setfacl` がない場合は、Amazon Linux では `sudo dnf install acl` など、使用 OS の
パッケージ管理で `acl` を導入する。既存設定の所有者・権限を維持するため、まず ACL を保存する。

```bash
sudo sh -c 'umask 077; getfacl -R -p /home/ec2-user/7dtd > /root/rubinco-7dtd-acl.before'

sudo setfacl -m u:rubinco-web:--x \
  /home/ec2-user /home/ec2-user/rubinco /home/ec2-user/rubinco/apps \
  /home/ec2-user/rubinco/apps/watchpoint /home/ec2-user/rubinco/apps/watchpoint/app \
  /home/ec2-user/7dtd
sudo chmod 0644 /home/ec2-user/rubinco/apps/watchpoint/app/app.jar

# Watchpoint は既存の設定・ワールド・ゲーム定義を読む。書込権限は与えない。
sudo setfacl -R -m u:rubinco-web:r-X /home/ec2-user/7dtd/data /home/ec2-user/7dtd/game
sudo find /home/ec2-user/7dtd/data /home/ec2-user/7dtd/game -type d \
  -exec setfacl -m d:u:rubinco-web:r-x {} +
```

ディレクトリが別の位置にある場合は、設定した `SEVEN_DAYS_ROOT` / `SEVEN_DAYS_CONFIG_DIRECTORY`
に合わせる。ファイルログも使う環境では、そのログディレクトリにも必要な読み取り権限を設定する。
ゲームがファイルの権限を作成後に変更する場合は、変更後の読み取りも確認する。
アプリにゲームの compose.yml、起動スクリプト、`.ssh`、systemd unit の書込権限を与えない。

## 4. 操作権限が限定されているか確認

```bash
id rubinco-web
sudo -l -U rubinco-web
sudo -u rubinco-web test ! -w /usr/local/sbin/rubinco-game-control
sudo -u rubinco-web sudo -n /usr/local/sbin/rubinco-game-control statuses
```

`id` に `docker` / `wheel` が含まれず、`sudo -l` には許可した固定コマンドだけが表示されること。
次の**確認用コマンドは失敗するのが正解**。ゲームを変更するコマンドではない。

```bash
sudo -u rubinco-web /usr/bin/docker ps
sudo -u rubinco-web sudo -n /usr/bin/docker ps
```

どちらかが成功したら、Docker ソケットの ACL / モードや他の sudoers に広い許可が残っている。
切り替え前にその許可を取り除く。Docker ソケットを `666` にしない。

## 5. systemd 設定を準備し、新 JAR の配置後に切り替え

貼り付け用ファイル・転送用アーカイブに JAR は含まれない。ヘルパーを配置しただけでは
稼働中のアプリは更新されない。旧 JAR は Docker へ直接アクセスするため、新 JAR を
配置するまでサービスを再起動しない。

先に PostgreSQL が稼働中で、意図した restart policy になっていることを確認する。
この drop-in は旧 `ExecStartPre` の Docker Compose 呼び出しを解除するため、
PostgreSQL の次回起動は Docker の restart policy で維持する。

```bash
docker inspect --format '{{.State.Status}} restart={{.HostConfig.RestartPolicy.Name}}' seven-days-postgres
```

`running` と `unless-stopped` などの意図したポリシーを確認した後、drop-in を配置し、
設定だけを読み直す。この段階では再起動しない。7dtd の自動起動解除は
[GAME_SERVERS.md](GAME_SERVERS.md) の手順を維持する。

```bash
sudo install -d -o root -g root -m 0755 /etc/systemd/system/rubinco-watchpoint.service.d
sudo install -o root -g root -m 0644 \
  /home/ec2-user/rubinco/apps/watchpoint/permissions/infra/systemd/rubinco-watchpoint-restricted.conf.example \
  /etc/systemd/system/rubinco-watchpoint.service.d/50-restricted-user.conf
sudo systemctl daemon-reload
systemctl show rubinco-watchpoint.service --property=User,Group,MainPID --no-pager
```

`User=rubinco-web` は次回起動の設定を示す。この時点で稼働中の Java プロセスは旧ユーザーのまま。
既存 drop-in に別の `User` / `SupplementaryGroups` 設定がある場合は設定順も確認する。
`.env` に `GAME_SERVER_START_PROVIDER=mock` がある場合は、切り替え先の
`/etc/rubinco/watchpoint.env` で `docker` にするか削除する
（EnvironmentFile の値が Environment の値より優先される）。

その後、新 JAR をデプロイする。GitHub Actions は `main` への push で動き、
設定が `rubinco-web` であることとヘルパーが動くことを確認してから、JAR を配置して再起動する。
**この PR をマージする前に、ここまでの準備を済ませる。**
準備不足の場合は旧 JAR を置き換えずにデプロイを中止する。
デプロイ先は `/home/ec2-user/rubinco/apps/watchpoint/app/app.jar` を維持している。

手動で新 JAR を配置する場合は、先に旧 JAR のバックアップを取り、配置後に次を実行する。

```bash
sudo chmod 0644 /home/ec2-user/rubinco/apps/watchpoint/app/app.jar
sudo systemctl restart rubinco-watchpoint.service
```

デプロイ後はサービスとログを確認する。

```bash
systemctl show rubinco-watchpoint.service --property=User,Group,MainPID --no-pager
sudo journalctl -u rubinco-watchpoint.service -n 100 --no-pager
```

同じビルドを転送できたかは、Mac の `shasum -a 256 target/rubinco.jar` と EC2 の
`sha256sum /home/ec2-user/rubinco/apps/watchpoint/app/app.jar` を比較できる。
GitHub Actions がビルドした JAR は、ローカルビルドとチェックサムが異なる場合がある。

7dtd のログ取得は直近5分から追尾する固定仕様に変わる。`app.sevendays.docker.log-since` は
ヘルパーの引数として使わず、別のコンテナ名への変更も拒否する。

## 6. 動作確認と戻し方

メンバーによる停止中のゲームの起動と稼働中のゲームの停止、Terraria の自動起動表示、ゲストの操作拒否、
7dtd を稼働させた後の Watchpoint のログ取り込みを確認する。
`systemctl show` に表示された MainPID に対して `ps -p <PID> -o user,group,args` も確認する。

戻す場合はこの変更専用の drop-in を解除して再起動する。

```bash
sudo mv /etc/systemd/system/rubinco-watchpoint.service.d/50-restricted-user.conf \
  /root/rubinco-watchpoint.restricted-user.conf.disabled
sudo systemctl daemon-reload
sudo systemctl restart rubinco-watchpoint.service
```

旧ユーザーで新 JAR を使う場合は、そのユーザーにもヘルパーの sudo 許可が必要になる。
元の JAR と元のユーザーへ戻す場合は、保存した unit と JAR のバックアップを使う。
旧 JAR に戻すためにゲームコンテナや PostgreSQL ボリュームを削除する必要はない。

## 境界と残る権限

Web アプリが侵害されても Docker の任意操作を実行できないようにする構成だが、許可した3ゲームの
起動や7dtd のログ閲覧は引き続き可能。DB、Telnet、EC2 IAM ロールの権限は別の対策対象となる。
ヘルパーを sudo で実行するため、この方式では `NoNewPrivileges=yes` は使わない。
`NoNewPrivileges` も必須とする環境では、固定操作を受ける専用サービス等へ移行する。

## 参考

- [Docker グループは root 相当の権限](https://docs.docker.com/engine/install/linux-postinstall/)
- [systemd のユーザー・ファイルシステム・権限設定](https://www.freedesktop.org/software/systemd/man/latest/systemd.exec.html)
