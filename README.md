# 모시다 (Mosida)

Pleos Connect 차량용 AI 비서 프로토타입 (현대자동차그룹 차량용 앱 개발 공모전 출품작)

카카오톡 속 약속을 PC의 AI 비서가 먼저 읽고, 실시간 교통·날씨·주차·평소 습관을 따져 "이렇게 갈게요"라고 이유와 함께 설명한 뒤, 운전자의 "응, 그렇게 해" 한마디로 경유지가 포함된 경로를 Pleos 내비에 바로 설정합니다.

## 흐름

1. 카톡 수신(시뮬레이션) → PC 에이전트(OpenMausBot Bot API)가 약속(상대·시간·장소·좌표) 추출
2. TMAP 실시간 교통 경로, 기상 예보(met.no), 서울시 공영주차장 실시간 빈자리를 모아 계획 작성
3. 비서가 TextToSpeech로 계획과 이유를 설명 → SpeechToText로 "응, 그렇게 해 / 다르게 해줘" 인식 (버튼으로도 가능)
4. 동의하면 NaviHelper `requestRouteWithWaypoints`로 목적지와 경유지를 한 번에 설정 → 안내 화면
5. "다르게 해줘"는 경유지를 뺀 대안을 다시 제안, 늦을 것 같으면 LLM이 답장 초안 작성 (전송은 하지 않음)

## 사용한 Pleos SDK

NaviHelper, TextToSpeech, SpeechToText, FusedLocation

## APK

[`apk/project_mosida_release.apk`](apk/project_mosida_release.apk) (release 빌드)

공개 APK는 API 키 없이 빌드되어 실시간 교통(TMAP)·주차·PC 비서 없이 동작합니다. 예시 약속과 직선 거리 기반 추정 경로로 전체 흐름(제안 → 동의 → Pleos 내비 경로 설정 → 안내 화면)을 확인할 수 있습니다.

```bash
adb install apk/project_mosida_release.apk
adb shell appops set --user 10 com.hmgchallenge.mosida SYSTEM_ALERT_WINDOW allow   # 내비 안내 시작 후 앱으로 복귀
```

## 테스트 환경

- Pleos Connect Emulator **v2.1.0 (API 34)**, 화면 **2560×1440** (가로)
- Android Studio, JDK 17, Kotlin + Jetpack Compose, minSdk 28 / targetSdk 34
- 권한: `pleos.car.permission.NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `TTS_SERVICE`, `STT_SERVICE`, `FUSED_LOCATION`(실행 시 허용), `SYSTEM_ALERT_WINDOW`(위 appops 명령)
- 네트워크: 인터넷 필요 (지도 타일 OpenStreetMap, 기상 예보 met.no)

## 실행 (소스 빌드)

`local.properties`에 아래 키를 넣으면 실제 데이터로 동작합니다. 비워 두면 위 공개 APK와 같은 예시 동작입니다.

```properties
omb.token=   # OpenMausBot Bot API 키 (PC에서 OpenMausBot 실행, 에뮬레이터에서 10.0.2.2:8800)
tmap.key=    # SK open API TMAP (Free)
seoul.key=   # 서울 열린데이터광장 인증키
llm.url=     # OpenAI 호환 API 주소
llm.key=
llm.model=
```

```bash
./gradlew :app:installDebug
# 안내 시작 후 앱으로 돌아오려면 (에뮬레이터)
adb shell appops set --user 10 com.hmgchallenge.mosida SYSTEM_ALERT_WINDOW allow
```

## 프로토타입 한계

- 카톡 수신과 평소 습관(자주 가는 카페, 플레이리스트)은 예시 데이터
- 음악은 화면 문구만 표시, 실제 재생 연동은 2차
- 서울시 실시간 주차 데이터는 시영 주차장 122곳뿐이라 가장 가까운 곳이 멀 수 있음
- 데모 영상용으로 앱 안 시계를 18:52부터 흐르게 둠 (`clockNow()`)
