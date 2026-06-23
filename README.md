# NP 뷰어

NP 뷰어는 노벨피아를 더 가볍게 열어 보기 위한 비공식 Android WebView 앱입니다.
공식 앱처럼 노벨피아 웹사이트를 앱 안에서 보여 주지만, 꼭 필요한 기능만 남겨 전반적인 사용감을 단순하게
유지하는 데 초점을 둡니다.

> [!CAUTION]
> 이 앱은 노벨피아 공식 앱이 아닙니다. 노벨피아 및 노벨피아 운영사와 관련이 없는 개인 제작 앱입니다.

## 만든 이유

공식 노벨피아 앱도 WebView 기반 앱이지만, 개인적으로는 부가 기능이 많아 앱이 무겁고 느리게 느껴졌습니다.
NP 뷰어는 노벨피아를 보는 데 필요한 기능만 가볍게 구현하고, 사용 중 자주 답답했던 부분만 보완하려고 만든 앱입니다.

다음 같은 점이 불편했다면 써볼 수 있습니다.

* 공식 앱이 느리게 느껴져 답답했던 경우
* 페이지가 로딩 중인지, 멈춘 것인지 바로 알기 어려웠던 경우
* 네트워크가 불안정할 때 앱을 완전히 껐다 켜지 않고 새로고침으로 다시 시도하고 싶었던 경우
* 앱 안에서 캐시, 쿠키, 웹 스토리지, 광고 차단 필터를 직접 관리하고 싶었던 경우

## 주요 기능

### NP 뷰어에서 보완한 기능

* 화면 상단에 페이지 로딩 진행률을 표시합니다.
* 화면을 아래로 당겨 현재 페이지를 새로고침할 수 있습니다.
* 앱 시작 시 열 페이지를 홈페이지, 내 서재, 최근 본 작품 중에서 고를 수 있습니다.
* 당겨서 새로고침이 동작하는 화면 상단 영역을 설정할 수 있습니다.
* 구독형 필터 목록과 사용자 필터를 사용할 수 있습니다.
* 네트워크 요청 차단과 CSS 기반 요소 숨김 규칙을 적용합니다.
* 앱 실행 시 필터를 자동으로 갱신하거나, 설정에서 바로 갱신할 수 있습니다.
* 캐시, 쿠키, 웹 스토리지를 설정 화면에서 삭제할 수 있습니다.
* 앱 실행 시 새 버전을 자동으로 확인할 수 있습니다.
* 설정에서 수동으로 업데이트를 확인하고 APK를 다운로드할 수 있습니다.
* Android 13 이상에서는 업데이트 다운로드 완료 알림을 위해 알림 권한을 요청할 수 있습니다.

### 기본 사용감

공식 앱에서 익숙한 기본 동작도 함께 지원합니다.

* 노벨피아 링크를 앱으로 열도록 Android 시스템 설정을 연결합니다.
* 노벨피아 밖의 외부 링크는 기본 브라우저로 엽니다.
* 첫 화면에서 뒤로 가기를 두 번 누르면 앱을 종료합니다.
* 뷰어 페이지에서 볼륨 버튼으로 이전/다음 페이지를 넘길 수 있습니다.
* 볼륨 버튼 방향은 설정에서 바꿀 수 있습니다.

## 사용 방법

1. 앱을 설치하고 실행합니다.
2. 노벨피아 계정이 필요하다면 앱 안의 노벨피아 페이지에서 로그인합니다.
3. 화면을 길게 눌러 설정을 엽니다.
4. 시작 페이지, 링크 처리, 뷰어 볼륨 키, 광고 차단, 데이터 삭제, 업데이트 확인 방식을 원하는 대로 조정합니다.

노벨피아 링크가 항상 NP 뷰어에서 열리게 하려면 설정의 **링크 처리 활성화**를 누른 뒤,
Android의 **기본으로 열기** 화면에서 `novelpia.com` 링크를 이 앱으로 열도록 허용하세요.

## 광고 차단 필터 안내

NP 뷰어의 광고 차단 기능은 일반적인 AdGuard/uBlock 계열 필터 목록 일부를 지원합니다.
다만 모든 고급 문법을 완전히 구현한 광고 차단 앱은 아니며, 앱 안정성과 WebView 응답 속도를 우선합니다.

지원하는 대표 규칙은 다음과 같습니다.

* 일반 URL 차단 규칙
* `||example.com^` 형식의 도메인 기준 차단 규칙
* `@@`로 시작하는 허용 규칙
* `$script`, `$image`, `$stylesheet`, `$xmlhttprequest` 같은 일부 리소스 타입 옵션
* `##.ad-banner` 같은 CSS 선택자 기반 요소 숨김 규칙
* 특정 도메인에만 적용하거나 제외하는 cosmetic 규칙

정규식 규칙, redirect/removeparam/csp/cookie 같은 고급 옵션, scriptlet, procedural cosmetic 규칙 등은 지원하지 않습니다.
지원하지 않는 규칙은 앱을 중단시키지 않고 건너뜁니다.

## 개발자 참고

이 프로젝트는 Kotlin 기반 Android 앱입니다.

* 최소 지원 SDK: 31
* 컴파일 SDK: 37
* 타깃 SDK: 36
* JVM Toolchain: 17
* 주요 라이브러리: AndroidX AppCompat, Core KTX, Lifecycle Runtime KTX, Preference,
  SwipeRefreshLayout, WebKit, Material Components

대략적인 구조는 다음과 같습니다.

* `app/src/main/java/io/github/tetratheta/npviewer/activity`: 메인 화면과 설정 화면
* `app/src/main/java/io/github/tetratheta/npviewer/filter`: 광고 차단 필터 파싱, 컴파일, 런타임 적용
* `app/src/main/java/io/github/tetratheta/npviewer/update`: 앱 업데이트 확인, 다운로드, 설치 연결
* `app/src/main/assets`: WebView에 주입되는 스크롤 복원 및 요소 숨김 스크립트
* `app/src/main/res/xml/root_preferences.xml`: 설정 화면 구성

필터 엔진은 Kotlin에서 필터 목록을 파싱해 읽기 전용 규칙 객체로 만든 뒤, WebView 요청 처리 시 해당 객체를 조회합니다.
요소 숨김 규칙은 페이지 URL별 payload를 계산하고 WebView에 주입되는 스크립트가 적용합니다.

## 빌드

Android Studio에서 프로젝트를 열어 빌드하거나, 터미널에서 Gradle Wrapper를 사용할 수 있습니다.

배포용 APK는 release 서명 후 루트의 `apk` 디렉터리로 복사합니다.

```powershell
.\gradlew.bat :app:buildReleaseApk
```

release 빌드는 `KEYSTORE_PATH`, `KEY_ALIAS`, `KEYSTORE_PASSWORD` 값을 환경 변수 또는 `.env`에 설정해야 합니다.
`KEY_PASSWORD`는 생략하면 `KEYSTORE_PASSWORD`를 사용합니다.
로컬에서 빠르게 실행해 볼 때는 debug 빌드를 사용할 수 있습니다.

```powershell
.\gradlew.bat assembleDebug
```

prerelease 빌드는 release 설정을 기반으로 하되 디버그 서명을 사용합니다.

```powershell
.\gradlew.bat assemblePrerelease
```

릴리스 버전 정보는 `gradle.properties`의 `app.versionCode`, `app.versionName` 값을 사용합니다.
