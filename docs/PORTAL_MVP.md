# Game Portal MVP

## 決定事項

このMVPでは、Portal、Watchpoint、Luminite、API、PostgreSQLを既存のゲーム用EC2へ
集約します。EC2停止中に通常サイトを閲覧できる必要はありません。認証局にはAmazon
Cognito User Poolを使いますが、通常サイトのセッション処理とアクセス制御はSpring
Securityが担当します。

AWSへのデプロイ、課金リソース作成、実EC2の起動は、このリポジトリの実装では一切
実行していません。

```text
Internet
  |
  +-- portal.rubinco.link -----+
  +-- watchpoint.rubinco.link -+--> Nginx --> Spring Boot :8082
  +-- luminite.rubinco.link ---+                  |
  +-- api.rubinco.link --------+                  +--> existing PostgreSQL
  |
  +-- auth.rubinco.link --> Cognito hosted UI / OAuth 2.0
```

3つの通常サイトは現時点では1つのSpring Boot成果物に含めています。Watchpointを別
ディレクトリへ移動して壊すより安全であり、ホスト名、Controller、Service、認証App
Clientの境界は分けてあるため、将来は各サイトを個別成果物へ切り出せます。

## 現行Watchpointの調査結果

- Java 21、Spring Boot 4、Thymeleaf、Spring Security、PostgreSQL、Flyway、Maven Wrapper。
- `https://watchpoint.rubinco.link/` は公開ランディング、`/dashboard`以下は認証必須。
- 既存認証は `M_WEB_ACCOUNT` のBCryptパスワードで、`PLAYER`、`ADMIN`、`VIEWER`。
  公開登録機能はない。
- タイムラインの読取モデルは `T_TIMELINE_POST`。V23で旧投稿・AIコメントをこの表へ
  移行済みで、ゲームログそのものは別テーブルに保持している。
- 既存URL、ログ取込、投稿、リアクション、AI観測、管理画面のControllerは移動して
  いない。

## 実装した画面

| ホスト | ルート | 内容 |
| --- | --- | --- |
| `portal.rubinco.link` | `/` → `/portal` | 公開トップページ、ログインモーダル、全体タイムライン |
| `watchpoint.rubinco.link` | 既存のまま | 7 Days to Dieの既存機能、ヘッダーからPortalへ戻る導線 |
| `luminite.rubinco.link` | `/` → `/luminite` | Terrariaの進行度、状態、専用タイムラインのシェル |
| `api.rubinco.link` | `/api/games/**` | JWTで保護した共有API |

Portalの左サイドバーには各ゲームのアイコンと「7DTD」「Terraria」のラベルを表示します。
未ログインでもトップを閲覧でき、ログインモーダルはヘッダーのボタンから開きます。
メンバー・ゲストともにログイン後は全体タイムライン（20件ずつ）の画面へ進みます。
ゲストにも表示名をそのまま表示し、外部プラットフォームIDのマスクと閲覧制限は維持します。ユーザー用トップは一旦タイムラインだけとし、
メイン欄はタイムラインに絞り、サイドバーにDB保存済みの稼働状況とサイトリンクを表示します。
7DTDとTerrariaのヘッダーは共通部品を利用します。起動ボタンは表示せず、起動APIの既存の権限制御は維持しています。

Watchpointへ未ログインで直接アクセスした場合は、Portalのログインへ移動します。
Cognito利用時はPortalからのサイトリンクで各サイトのOAuthフローを開始し、
Cognitoの共通セッションを利用して各ホストのセッションを確立します。
ローカルでは同じホストの `/portal`・`/dashboard`・`/luminite` を利用します。
今回の変更は自動テストで確認済みです。以前の画面の視覚確認記録は
[VISUAL_CHECK.md](VISUAL_CHECK.md) を参照してください。

## 通常サイトのSpring Security認証

本番は `production,cognito` profileを使います。

- Cognito User Poolは `AllowAdminCreateUserOnly: true`。公開サインアップを無効化。
- `member` と `admin` のCognito GroupをSpringのRoleへ変換。
- Portal、Watchpoint、Luminiteごとに、secretを持たない別App Clientを使用。
- 各ClientはAuthorization Code Flowのみを許可。Spring Securityがpublic client用の
  PKCE verifier/challengeを要求ごとに生成し、トークンをサーバー側セッションへ保持。
- Cognito APIによる直接のSRP/password/custom認証はApp Clientで無効化し、明示的な認証
  flowはrefresh tokenだけに限定。
- `RUBINCO_SESSION`はSecure、HttpOnly、SameSite=Laxのhost-only Cookie。別サイトへ
  初めて移動したときはホスト別App Clientの認可フローへ自動遷移し、Cognito側の既存
  セッションにより通常は資格情報を再入力せず戻る。Spring Session自体はサイト間で共有しない。
- 初回SSO時にCognito `sub`を `M_WEB_ACCOUNT.cognito_sub`へ保存。既存Watchpointの
  `PLAYER`互換性を保ちながら、Cognito `member`を `ROLE_MEMBER` と `ROLE_PLAYER`へ対応。
- ログアウト時はローカルセッションを破棄し、ホスト別Client IDを指定してCognitoの
  `/logout`へ遷移。
- APIはセッションCookieを使わない。JWTの署名、issuer、期限、`token_use=access`、
  許可した3 Client ID、`member/admin` Groupをサーバー側で検証。
- Nginx設定例は`api.rubinco.link`で`/api/**`だけを公開し、共有セッションCookieを
  upstreamへ渡さない。
- Cognito有効時は3 Client IDの空値・重複を起動時に拒否し、JWT decoderへ独自claim検証を
  装着できない場合もfail closedで起動を止める。
- トークン、Client secret、AWS長期キーをJavaScriptやソースへ置かない。

`local` profileだけは既存フォームログインを残しており、Cognitoを作る前でもローカル
確認できます。本番のログイン画面ではローカルログインとゲストログインを表示しません。

## 共有タイムラインと状態

ゲーム別テーブルは作らず、既存 `T_TIMELINE_POST` 1表を拡張しました。

| 列 | 用途 |
| --- | --- |
| `game_id` | `7dtd` または `terraria` |
| `event_id` | 共有APIのイベントID。既存行は `source_hash` を継承 |
| `post_type` | イベント種別 |
| `title`, `message` | タイトル、本文 |
| `published_at` | 発生日時 |
| `progress`, `stage_name` | 任意の進行度とステージ |
| `creator_sub` | 作成者のCognito `sub` |
| `metadata_json` | 任意メタデータのJSON |
| `created_at`, `updated_at` | 作成・更新日時 |

現在状態は同じPostgreSQLの `M_GAME_PORTAL_STATE` 1表で管理し、Portalサマリーはこれと
各ゲームの最新タイムラインを結合して返します。イベント追加・更新後の進行度とステージは
`published_at`が最新の値から再計算するため、過去イベントの移行投入で現在値が巻き戻りません。

| Method | Path | 権限 |
| --- | --- | --- |
| GET | `/api/games` | member/admin |
| GET | `/api/games/{gameId}` | member/admin |
| GET | `/api/games/{gameId}/timeline?page=0&size=20` | member/admin |
| GET | `/api/games/{gameId}/timeline/latest` | member/admin |
| GET | `/api/games/{gameId}/progress` | member/admin |
| POST | `/api/games/{gameId}/events` | admin |
| PUT | `/api/games/{gameId}/events/{eventId}` | admin |
| POST | `/api/games/{gameId}/start` | member/admin、モックのみ |

本文、タイトル、種別、進行度、ステージ、metadataサイズはAPI境界で検証します。

## 既存データを壊さない移行

V37は `game_id` をnullableで追加し、既存行をすべて `7dtd` にした後でdefaultとNOT NULL
を設定します。V38は共有イベント列を追加・バックフィルし、状態表を作ります。既存表を
削除・renameせず、既存の `source_hash` と主キーも維持します。

`event_id`は移行期間だけDB上nullableのままです。旧Watchpointバイナリへ戻すと旧版は
この列を設定しないためです。新コードはすべての書込でIDを設定し、旧版が移行期間中に
作った行だけは一意な `source_hash`をAPIのIDとして返します。安定稼働後に別migrationで
NOT NULLへ収縮できます。

本番適用前に、最低限次を確認します。

```sql
SELECT COUNT(*) AS timeline_rows,
       COUNT(DISTINCT source_hash) AS unique_source_hashes
FROM T_TIMELINE_POST;

SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 10;
```

1. EC2とゲームを停止する前に、PostgreSQLのスナップショットまたは`pg_dump`を取得。
2. 件数と `source_hash` の一意性を記録。
3. 新jar起動時にFlyway V37/V38を適用。
4. 先にWatchpointの読取・投稿・リアクションを確認し、その後Terrariaイベントを有効化。
5. 問題時は旧jarへ戻しても追加列は残す。適用済みFlywayファイルを編集・削除しない。旧jarは
   `game_id`を読まないため、Terrariaイベントの本番書込を開始した後は旧jarへ長期ロールバック
   せず、修正版をロールフォワードする。自動rollbackは新画面公開前のhealth check失敗用とする。

このMVPは同じ表を直接共有するため、デュアルライトは不要です。将来サービスを物理分離
する場合は、まず共有APIへのデュアルリード、件数照合、APIへの書込切替の順に進めます。

## EC2起動APIのモック

`GameServerStartGateway`が将来のAWS実装との境界です。`app.games.start-provider=mock`で
明示的に選ばれる`MockGameServerStartGateway`はAWS SDKも `StartInstances`も呼びません。
未レビューのprovider名に変えると実装Beanがないため起動に失敗し、暗黙にAWS操作へ切り替わる
ことはありません。受理時はDB状態を
`starting`へ変えます。起動要求を許可するのは`offline`だけで、`starting`、`online`、
`maintenance`、`error`では状態別の理由を返します。1プロセス内ではゲーム単位の10秒
cooldownをatomicに確保するため、別ユーザーからの同時要求も重複実行しません。Portalは
CSRF付きの同一オリジンBFF endpointを呼び、15秒で応答がなければ現在表示を維持したまま
通信エラーを案内します。

実装をLambdaへ差し替える場合は、対象インスタンスARNだけの `ec2:StartInstances`、
状態確認用の最小限の `ec2:DescribeInstances`、起動可能時間、WAF/API Gateway throttle、
監査ログ、冪等キーを追加します。ブラウザへAWS認証情報は渡しません。

## EC2上の自動起動

`infra/systemd/rubinco.service.example`は、EC2起動時に既存PostgreSQL Composeを
先に起動し、その後Spring Bootを `production,cognito`で起動します。Nginx設定例は4つの
通常ホストを同じupstreamへ渡し、元のHostとHTTPS情報をSpringへ伝えます。

```bash
sudo cp infra/systemd/rubinco.service.example \
  /etc/systemd/system/rubinco.service
sudo systemctl daemon-reload
sudo systemctl enable rubinco.service
```

既存EC2のservice名、jarのport、Composeファイル、PostgreSQL volumeを先に比較し、例を
そのまま上書きしないでください。

## EC2停止前のゲームデータ保存

23:30の既存自動停止処理は維持します。ただしWebアプリからEC2停止は行いません。
停止ジョブは次の順序にします。

1. 7DTD/Terrariaへ保存コマンドを送り、正常応答を待つ。
2. ゲームコンテナを正常終了し、timeout時だけ失敗として停止を中断。
3. PostgreSQL backupとゲームsave directoryの世代バックアップを作成・検証。
4. 対象EC2のinstance ID/tagを再確認してから停止。

ゲームごとの実コンテナ名・保存コマンドが現行インフラから確定していないため、この
停止スクリプトは今回あえて追加していません。推測したコマンドで本番saveを壊さないため
です。

## EC2外の起動ページ

今回の要件変更により、EC2停止中はPortalも停止して構いません。そのため
`wake.rubinco.link`、API Gateway、Lambdaは作成していません。つまり、今回のPortal内
モックボタンから停止中EC2を実際に起動することはできません。

将来、友人が停止中EC2をWebから起動する要件を有効化する場合だけ、EC2外に静的wake
ページとLambdaを置きます。認証案は次の順で評価します。

1. **wake専用Cognito App Client**: 既存招待ユーザーを再利用でき、権限・監査・失効の
   バランスが最良。推奨。
2. 外部OAuth: 安全だが、別アカウントとの紐付けとallowlist管理が増える。
3. 強い共有起動コード: 最小実装だが、共有・漏洩・個人監査に弱い。
4. 通常サイトと同じSpring Session: EC2停止中に検証できないため不適。
5. 友人へAWS権限付与: 権限境界とアカウント運用が重すぎるため採用しない。

## ローカル起動

```bash
cp .env.local.example .env
# ローカルDBのpasswordとbootstrap passwordを設定
cp compose.example.yml compose.yml
docker compose up -d postgres
set -a
source .env
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

ローカルURLは `/portal`、既存 `/dashboard`、`/luminite`です。フォームログインで確認でき、
起動ボタンは常にモックです。

本番の `scripts/run-app.sh`とsystemd例は `production,cognito`を既定で選択するため、
bootstrap passwordは不要です。ポート・EC2パス・URLの既定値は `application*.yml`に集約し、
URLは `DOMAIN`（既定 `rubinco.link`）から派生します。個別overrideや従来フォーム認証の
起動方法は [RUNNING.md](../RUNNING.md#configuration-overrides)を参照してください。

## AWS設定とデプロイ手順（未実行）

0. AWS CLI v2と、短期セッションを使うデプロイ用IAM roleを準備。長期access keyは保存しない。
1. `auth.rubinco.link`用の非exportable ACM public certificateを **us-east-1** で発行・検証。
2. `infra/cognito/template.yaml`を対象regionへレビュー付きでデプロイ。
3. 新規環境では `.env.example`を `.env`へコピーし、DB/Telnet passwordとStack outputのissuer・3 Client IDを設定。既存環境の `.env`と配置先は維持。
4. `scripts/invite-cognito-user.sh USER_POOL_ID friend@example.com member`で招待。既存の
   Watchpointアカウントを引き継ぐ場合は第4引数へ既存login IDを指定。
5. `portal`、`luminite`、`api`のDNSを既存EC2へ向け、Nginx証明書へSANを追加。
6. PostgreSQL backupと上記preflightを実行後、`scripts/build-app.sh`でjarを作成。
7. Nginx・systemd例を既存設定とmergeし、Watchpoint→Portal→Luminite→APIの順でsmoke test。

`main`へのpushは既存の`.github/workflows/deploy-rubinco.yml`を起動します。workflowは
同時デプロイを直列化し、JARを一時ファイルへ取得してから置換し、Spring Bootのローカル
health check成功まで待ちます。再起動またはhealth checkに失敗した場合は直前のJARを戻して
再起動し、workflow自体は失敗として記録します。AWS設定、DBバックアップ、DNS/Nginxを
確認する前に`main`へpushしないでください。

Cognitoテンプレートには削除保護とRetain、Lite tier、招待専用、emailの大文字小文字非区別、
14文字password、任意TOTP、TLS 1.2 (2021 policy)以上、3つのpublic App Clientが含まれます。
AWS変更は明示的な許可を得てから実行してください。

## 月額追加費用の目安

- 既存EC2と既存PostgreSQLに同居するPortal/Luminite/API: 追加computeは **0 USD**。
- Cognito Lite: Cognitoへ直接sign-inするユーザーは月10,000 MAUまで継続無料枠のため、
  少人数利用のMAU料金は通常 **0 USD**。
- Cognito用のACM非exportable public certificate: AWS統合サービス用途は **0 USD**。
- Route 53: 既存 `rubinco.link` hosted zoneを再利用するなら新しいzone料金はなく、alias record
  によるCognitoへのDNS queryは無料です。Portal等をElastic IPへ向ける通常のA recordには
  標準query料金がかかりますが、少人数では僅少です。zoneを新設する場合は最初の25 zone
  まで **0.50 USD/月/zone**。
- 招待・復旧emailやSMSはSES/SNSの別料金になり得ます。MFAはSMSではなくTOTPを採用。

これは2026-09時点の公開料金に基づく少人数前提の概算です。実デプロイ直前にAWS Pricing
Calculatorと請求アラームで再確認します。wake構成は今回ないため、Lambda/API Gateway/WAF
の費用も発生しません。

## セキュリティ上の妥協点

- 単一EC2障害で全通常サイトとDBが停止する。今回の要件として受容。
- Cognito自体はAWS上に残るが、EC2停止中は通常サイトへcallbackできない。
- Spring Sessionはhost-onlyのため、1サイトでログアウトしても既に確立済みの別サイトの
  ローカルセッションまでは即時破棄されない。Cognitoセッションはログアウトし、各ローカル
  セッションは30分で失効する。全サイト同時失効は将来Spring Session JDBC等で追加する。
- Portal状態はゲームサーバーの実測ではなく管理状態。モック起動後の`starting`も自動で
  `online`にはならない。
- Cognito Liteの高度な脅威検知は有効化していない。少人数・費用優先の判断であり、
  14文字password、TOTP、招待制、group必須で補う。
- 物理的な独立デプロイは将来段階。MVPでは既存Watchpointを壊さないことを優先した。

## 参照

- [Cognito authorization code / PKCE](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-settings-client-apps.html)
- [管理者だけがユーザーを作成する設定](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-properties-cognito-userpool-admincreateuserconfig.html)
- [Cognito custom domainの証明書要件](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-add-custom-domain.html)
- [Cognito料金](https://aws.amazon.com/jp/cognito/pricing/)
- [ACM料金](https://aws.amazon.com/certificate-manager/pricing/)
- [Route 53料金](https://aws.amazon.com/jp/route53/pricing/)

### 閲覧画面の共通ルール

PortalとTerrariaのタイムラインは20件単位で過去の記録へ移動できます。
存在しないページでは、記録全体が空であるかのように案内せず、前のページへ戻れます。
Terrariaの稼働状況はPortalと同じ日本語ラベルを利用します。共通エラー画面は
Rubinco名義でPortalへ案内します。キーボードのフォーカス表示と、端末の
「動きを減らす」設定に対応しています。
