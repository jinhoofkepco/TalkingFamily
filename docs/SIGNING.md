# 서명과 업데이트 관리

가족이 설치하는 APK는 패키지 `kr.family.homeway`와 기존 서명을 계속 사용합니다. 앱을 지우지 않고 새 APK를 설치하면 업데이트되도록 관리합니다. Android는 설치된 앱과 업데이트의 서명을 확인하며, 이전보다 낮은 `versionCode` 설치를 제한합니다. 새 배포마다 `versionCode`를 반드시 올립니다. [Android 서명 안내](https://developer.android.com/studio/publish/app-signing), [버전 관리 안내](https://developer.android.com/studio/publish/versioning)

| 항목 | 고정값 또는 규칙 |
| --- | --- |
| 패키지 이름 | `kr.family.homeway` |
| 0.3.1 | `versionCode = 4`, `versionName = "0.3.1"` |
| 0.4.0 | `versionCode = 5`, `versionName = "0.4.0"` |
| 인증서 SHA-256 | `db2c91c3bd56513a79878f2d25d59778f61804ff650c866de7c71445c34d12ce` |
| 이후 배포 | 같은 인증서로 서명하고 `versionCode` 증가 |

현재 인증서는 이전 0.1.0·0.2.0·0.3.0 설치본에 사용했던 개발용 인증서에서 이어집니다. 기존 설치와의 호환성을 위해 동일한 개인키를 암호로 보호한 PKCS12 파일에 보존합니다. 0.3.1부터는 디버깅이 꺼진 `release` APK로 만들지만 인증서는 바꾸지 않습니다. 개인용 APK 직접 설치를 위한 구성이며 Play Store 배포용 서명 구성은 아닙니다.

## 휴대폰에서 업데이트

1. [최신 릴리스](https://github.com/jinhoofkepco/TalkingFamily/releases/latest)의 `TalkingFamily-버전.apk`를 내려받습니다.
2. 기존 앱을 삭제하지 않고 APK를 열어 **업데이트**를 선택합니다.
3. 같은 버전 APK를 다시 설치할 때도 기존 앱 위에 설치합니다. 앞으로 배포되는 새 버전은 버전 번호가 더 높아집니다.

앱을 먼저 삭제하면 휴대폰 안에 저장된 연결 설정과 로컬 기록도 삭제됩니다. APK 업데이트와 앱 삭제 후 재설치는 다릅니다. 예전 버전으로 되돌려 설치하는 기능은 제공하지 않습니다.

## 로컬 서명 파일

현재 서명 파일은 저장소 바깥에 둡니다.

- 개인키: `~/.config/talkingfamily/signing.p12`
- 설정: `~/.config/talkingfamily/signing.properties`

설정 파일에는 다음 네 항목을 사용합니다. 실제 암호와 키 파일은 Git에 커밋하거나 릴리스에 첨부하지 않습니다.

```properties
storeFile=/절대/경로/signing.p12
storePassword=로컬에서만_입력
keyAlias=보존한_키의_별칭
keyPassword=로컬에서만_입력
```

자동화 환경에서는 `TALKINGFAMILY_KEYSTORE_FILE`, `TALKINGFAMILY_STORE_PASSWORD`, `TALKINGFAMILY_KEY_ALIAS`, `TALKINGFAMILY_KEY_PASSWORD` 환경 변수로 같은 정보를 제공합니다. 키가 없거나 인증서가 위 지문과 다르면 릴리스 빌드가 실패합니다. 새 키를 자동 생성하거나 임시 디버그 키로 대체하지 않습니다.

로컬 디버그 빌드는 고정 서명 설정이 있으면 같은 키를 사용합니다. 설정이 없는 다른 컴퓨터에서 만든 일반 디버그 APK는 개발용이며 가족 휴대폰의 기존 설치본에 덮어쓰는 용도로 사용하지 않습니다.

## 릴리스 만들기

JDK 17, Android SDK 35, Android Build Tools 35.0.1, Python 3, GitHub CLI(`gh`)를 준비합니다. 로컬에서는 먼저 `gh auth login`으로 저장소에 접근할 계정에 로그인합니다. 패키징 스크립트는 GitHub의 기존 릴리스 메타데이터를 읽어 새 `versionCode`가 이전 배포보다 높은지 확인합니다. 저장소 루트에서 실행합니다.

```sh
python3 scripts/package_release.py --tag v0.3.1
```

결과는 `dist/TalkingFamily-0.3.1.apk`, `dist/TalkingFamily-0.3.1.apk.sha256`, `dist/release-notes.md`, `dist/release-manifest.json`입니다. APK 자체의 SHA-256은 파일 무결성 확인용이며, 위의 인증서 SHA-256과는 별개입니다. 인증서는 동일하게 유지되어도 APK 내용과 파일 해시는 버전마다 달라집니다.

위 명령은 0.3.1을 처음 배포할 때의 예시입니다. 이미 게시한 버전은 새 릴리스로 다시 패키징하지 못하도록 차단합니다. 같은 버전을 휴대폰에 재설치하려면 게시된 APK를 다시 사용하고, 변경한 앱을 배포하려면 버전 번호를 올립니다.

다음 버전은 `android/app/build.gradle.kts`의 `versionCode`와 `versionName`을 올리고 `docs/releases/버전.md`를 작성합니다. 변경을 커밋한 뒤 `v버전` 태그를 GitHub에 푸시하면 릴리스 워크플로가 서명 APK를 빌드해 GitHub Releases에 게시합니다.

## GitHub Actions와 백업

저장소 `jinhoofkepco/TalkingFamily`의 Actions Secrets에는 다음 값을 사용합니다.

| Secret | 내용 |
| --- | --- |
| `TALKINGFAMILY_KEYSTORE_BASE64` | 동일한 `signing.p12`의 Base64 값 |
| `TALKINGFAMILY_STORE_PASSWORD` | 키 저장소 암호 |
| `TALKINGFAMILY_KEY_ALIAS` | 키 별칭 |
| `TALKINGFAMILY_KEY_PASSWORD` | 개인키 암호 |

워크플로는 키를 실행 중에만 복원하여 서명하고, 키 파일이나 암호를 릴리스에 포함하지 않습니다. GitHub Secrets는 등록 후 원문을 다시 내려받는 백업 수단이 아니므로, **암호화된 개인키의 별도 백업과 암호 보관**이 필요합니다. 컴퓨터를 바꿀 때는 보존한 키를 옮기며 새 키를 만들지 않습니다. 직접 배포 앱의 서명 개인키를 잃으면 기존 설치본을 같은 서명으로 업데이트할 수 없습니다. [Android 서명 키 관리](https://developer.android.com/studio/publish/app-signing)
