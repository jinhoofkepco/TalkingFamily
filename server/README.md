# 가족 메신저 중계 서버

Node.js 22.13 이상과 SQLite로 동작하는 두 사람용 서버입니다. 안드로이드 앱에서 받은 메시지·GPS 좌표·층간 이동 이벤트·스티커 이벤트를 **발신 봇 → Telegram → 수신 봇**으로 전달합니다. 수신 봇의 검증된 `getUpdates` 응답을 받은 뒤 상대방 앱에 공개합니다. Telegram이 설정되지 않은 상태에서는 전송 API가 503을 반환하며, 로컬 전송으로 대체하지 않습니다.

## 실행

```sh
cd server
npm ci
npm run setup
```

설정 명령은 서로 다른 기기 토큰 두 개를 `.env`에 생성하며 화면에 출력하지 않습니다. 기존 `.env`가 있으면 덮어쓰지 않습니다. 파일을 직접 열어 봇 설정을 마치고 각 휴대폰에는 해당 기기의 토큰만 설정합니다. Telegram 봇 토큰과 Firebase 관리자 키는 서버에만 보관합니다.

1. Telegram [@BotFather](https://t.me/BotFather)에서 서로 다른 봇 두 개를 만듭니다.
2. 두 봇 모두 **Bot-to-Bot Communication Mode**를 켭니다. 공식 [봇 간 개인 대화 설명](https://core.telegram.org/bots/features#bot-to-bot-communication)에 따라 수신 봇의 `@username`으로 메시지를 보냅니다.
3. `.env`에 `CHILD_BOT_TOKEN`, `GUARDIAN_BOT_TOKEN`을 설정합니다.
4. 다른 프로그램에서 이 봇들의 `getUpdates`를 실행하지 않습니다. 기존 webhook이 있다면 소유자가 제거한 다음 실행해야 합니다. 서버가 임의로 webhook을 삭제하지는 않습니다.
5. `npm start`로 실행합니다. `GET /health`의 `telegramReady`가 `true`인지 확인합니다. 이 값은 봇 신원 확인·webhook 검사 완료 여부이며, 실제 왕복 전송 성공은 앱의 `relayed` 상태로 확인합니다.

두 봇을 모두 비워 두어도 서버를 시작해 인증된 상태 화면을 확인할 수 있습니다. 이 경우 전송은 할 수 없습니다. Firebase 없이도 앱을 열어 상태를 갱신하면 데이터를 받지만, 화면이 꺼진 동안 도착 알림을 받으려면 다음 설정이 필요합니다.

## 잠금화면 알림

앱과 같은 Firebase 프로젝트의 관리자 서비스 계정 JSON 파일 경로를 `FIREBASE_SERVICE_ACCOUNT`에 설정합니다. `firebase-admin`은 선택 의존성입니다. 패키지를 설치하지 않았거나 키가 잘못되면 설정된 푸시 기능이 동작하는 것처럼 표시하지 않고 서버 시작을 중단합니다.

앱이 `POST /v1/push-token`으로 자신의 FCM 토큰을 등록합니다. Telegram 전달이 확인된 뒤 수신 기기에 일반 알림 문구와 이벤트 ID·종류만 전송합니다. 좌표, 메시지 본문, 이름은 푸시에 담지 않습니다. 자동 위치·층간 이동·상태 이벤트는 반복 소음 알림을 만들지 않습니다. 수동 위치 공유와 대화·스티커 변경은 알림 대상입니다. FCM 수신은 상대 기기에서 내용을 읽었다는 의미가 아닙니다.

## 휴대폰에서 연결

휴대폰 두 대가 인터넷에서 접근할 수 있는 **HTTPS 주소**를 준비하고 이 서버 앞에 HTTPS 역방향 프록시를 둡니다. 기본 서버는 `127.0.0.1:8787`에만 열립니다. 휴대폰에서 서버의 `localhost`를 사용할 수는 없습니다. 운영 시 외부로 8787 포트를 직접 열지 말고 HTTPS 포트만 공개합니다. 앱의 기기 토큰을 URL 매개변수에 넣지 않습니다.

Docker로 사용할 경우:

```sh
docker build -t family-server .
docker run --rm --name family-server --env-file .env \
  -e HOST=0.0.0.0 -p 127.0.0.1:8787:8787 \
  -v family-server-data:/app/data family-server
```

Firebase 사용 시 관리자 JSON을 읽기 전용으로 별도 마운트하고 `FIREBASE_SERVICE_ACCOUNT`에 컨테이너 내부 경로를 설정합니다. 데이터 볼륨은 `node` 사용자에게 쓰기 권한이 있어야 합니다. SQLite 하나당 서버 인스턴스는 하나로 운영합니다. DB lease는 같은 데이터베이스의 중복 worker를 차단하지만 서로 다른 데이터베이스로 같은 봇을 실행하는 설정은 방지할 수 없습니다.

## API

`/health`를 제외한 요청은 `Authorization: Bearer <device-token>`을 사용합니다. POST 요청에는 `Content-Type: application/json`이 필요합니다. 기기당 분당 180회 요청 제한이 있습니다. 브라우저 CORS는 기본 허용하지 않으며 `ALLOWED_ORIGINS`에 정확한 Origin만 추가할 수 있습니다.

| 요청 | 동작 |
|---|---|
| `GET /health` | 서버, Telegram 준비 여부, 푸시 설정 상태 |
| `GET /v1/state` | 역할, 최근 1,000개 이벤트, 스티커 잔액·사용 요청·약속 목록, 공유 상태, 최신 위치·하트비트 |
| `POST /v1/events` | `{id: UUID, kind, payload}`를 저장해 Telegram 전송 대기열에 추가 |
| `POST /v1/push-token` | `{token: "FCM registration token"}`으로 현재 기기 토큰 등록·갱신 |

새 이벤트는 HTTP 202와 `{event}`를 반환합니다. 동일 ID·동일 데이터 재시도는 HTTP 200을 반환합니다. ID를 다른 데이터나 다른 역할에서 재사용하면 409입니다. 검증 오류는 400, 권한 오류는 403, 스티커 승인 충돌은 409입니다. 오류 형태는 `{error:{code,message}}`입니다.

이벤트는 `{id, kind, payload, sender: "child"|"guardian", createdAt, delivery: "pending"|"relayed"}`입니다. `createdAt`은 서버 접수 시각이며 실제 측정 시각은 payload의 `capturedAt`, `measuredAt`, `recordedAt`입니다. 모든 시간은 UTC ISO 8601로 정규화합니다. `relayed`는 수신 Telegram 봇이 받았다는 뜻입니다. **상대 휴대폰 도착·읽음 확인은 아닙니다.** 앱에서 별도로 읽음 표시를 만들어서는 안 됩니다.

| kind | 보낼 수 있는 역할 | payload |
|---|---|---|
| `chat` | 둘 다 | `{text}` (1~1,500자) |
| `location` | 자녀 | `{latitude,longitude,accuracy,capturedAt,source:"manual"|"automatic"}` |
| `vertical` | 자녀 | `{phase,relativeMeters,measuredAt,confidence:"estimated",latitude?,longitude?}` |
| `sticker_award` | 보호자 | `{count:1,reason}` |
| `reward_upsert` | 보호자 | `{rewardId:UUID,name,cost}` — 약속 추가·수정, 이름 공백 제거 후 1~60자, 개수 정수 1~999 |
| `reward_delete` | 보호자 | `{rewardId:UUID}` — 약속 삭제 |
| `sticker_redeem_request` | 자녀 | `{rewardId:UUID,cost,reward}` — 등록된 약속 ID·이름·개수와 일치해야 함 |
| `sticker_redeem_approve` | 보호자 | `{requestId,accepted}` |
| `sharing_status` | 자녀 | `{enabled}` |
| `heartbeat` | 자녀 | `{batteryPercent?,recordedAt}` (배터리 값이 없으면 알 수 없음) |

층간 이동 `phase`는 `ascent_started`, `ascent_finished`, `descent_started`, `descent_finished`입니다. 고도 변화 값은 미터 단위이고 추정값입니다. 측정·5분 간격·움직임 감지는 자녀 휴대폰이 수행합니다. 서버는 기존 측정 시각을 보존하므로 통신 복구 뒤 지난 사건을 받아도 언제 이동했는지 표시할 수 있습니다. 오래된 위치가 늦게 수신돼도 최신 측정 위치를 덮어쓰지 않습니다.

`GET /v1/state`는 `{role,events,stickerBalance,rewards,redemptions,sharingEnabled,latestLocation,latestHeartbeat,transport,pushConfigured}`를 반환합니다. 전송 대기 이벤트는 발신 역할에만 보입니다. 상대는 Telegram 수신이 확인된 이벤트만 받습니다. 잔액·사용 요청 상태는 수신 확인 이후 반영됩니다. 수락 결정을 접수할 때 금액을 예약하여 다른 요청의 중복 지출을 막고, 전송 수신·차감·요청 상태·수신 offset을 한 SQLite 트랜잭션으로 반영합니다.

### 우리의 약속

`rewards`는 `[{id,name,cost}]` 형태의 실제 전달된 약속 목록이며 처음에는 비어 있습니다. 보호자가 새 약속에 UUID를 하나 정해 `reward_upsert`를 전송하고, 수정할 때 같은 `rewardId`를 사용합니다. 각 전송 이벤트의 `id`는 별도의 UUID입니다. 약속 변경도 Telegram 수신 확인 후 두 기기의 목록에 반영되며, 보호자의 전송 대기 변경은 `events`에서 확인할 수 있습니다. 같은 방향의 전송 순서를 유지하므로 오프라인 중 추가·수정·삭제한 순서대로 적용됩니다. 이미 없는 약속을 삭제해도 안전하게 완료됩니다.

자녀의 사용 요청을 처음 접수할 때 전달 완료된 목록의 ID·이름·개수를 대조합니다. 없는 약속은 HTTP 409 `reward_unavailable`, 바뀐 이름·개수는 409 `reward_changed`이므로 최신 목록을 받아 다시 선택해야 합니다. 아직 전달되지 않은 보호자 변경은 요청 가능 목록을 바꾸지 않습니다. 접수된 요청에는 당시 이름과 개수가 고정되며, 이후 약속을 수정하거나 삭제해도 보호자는 기존 요청을 당시 조건으로 승인·거절할 수 있습니다. 같은 이벤트 ID·내용의 재시도는 약속 변경 후에도 중복 적용하지 않습니다. 기존 DB는 시작할 때 약속 테이블을 추가하며 이전 스티커 잔액·사용 요청은 유지합니다. 새 사용 요청에는 `rewardId`가 필수이므로 서버와 두 휴대폰 앱을 함께 업데이트합니다.

## 실패·재시작·개인정보 보관

- Telegram 통신이 끊기면 이벤트를 SQLite에 남기고 지수형 지연으로 재시도합니다. 양방향 순서를 각각 유지합니다. 송신 API 응답만으로 완료 처리하지 않습니다.
- 수신 봇은 상대 봇의 숫자 ID, 개인 대화 ID, 전달된 원문 전체와 이미 서버에 저장된 이벤트를 대조합니다. 임의 Telegram 메시지가 스티커나 위치 기록을 만들 수 없습니다.
- 수신 offset·전송 시도·약속 목록·스티커 장부·승인 예약·푸시 대기열은 재시작 후 유지됩니다. Firebase 실패가 메시지 전송을 되돌리지는 않습니다.
- Telegram은 [업데이트를 최대 24시간 보관](https://core.telegram.org/bots/api#getting-updates)합니다. 아직 수신 처리되지 않은 이벤트는 재전송되므로 중복 도착할 수 있지만 잔액은 중복 반영하지 않습니다.
- 서버 데이터베이스에는 메시지, 좌표, 시간, 스티커 장부, FCM 토큰이 저장됩니다. 현재 버전에는 자동 보관 만료·선택 삭제 UI가 없습니다. 설치 전 안내에 이 보관 방식을 표시하고, 사용을 끝내면 서버를 중단하고 `data/family.sqlite`, `-wal`, `-shm` 파일 또는 Docker 데이터 볼륨을 삭제합니다. 삭제하면 스티커 장부도 초기화됩니다. 백업도 별도로 삭제해야 합니다.
- SQLite 저장 파일 자체는 암호화하지 않습니다. 호스트의 디스크 암호화와 접근 제어를 사용합니다. Telegram 봇 대화는 종단간 암호화 비밀대화가 아니며 좌표와 메시지 payload가 Telegram을 통과합니다. 이 구조가 설치 안내에 포함되어야 합니다.

## 검증

```sh
npm test
```

실제 로컬 HTTP 서버와 가짜 Telegram Bot API 서버를 사용해 인증·역할 제한·위치 검증·대기 이벤트 비공개·재전송·위조 수신 차단·원자적 스티커 차감·서버 재시작·수신 offset·알림 대기열을 확인합니다. 약속 추가·수정·삭제의 순서·재시작·중복 처리, 오래되거나 조작된 사용 요청 거절, 삭제 후 기존 요청 승인도 확인합니다. 외부 봇이나 가족의 실제 위치에 접속하지 않습니다. 실제 Telegram 계정 왕복, Firebase 푸시, 모바일망 및 절전 상태는 소유자의 키 설정 후 두 기기에서 별도로 확인해야 합니다.
