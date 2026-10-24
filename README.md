# dongne · 오늘날씨

**광고 없는 한국형 날씨 위젯.** 기상청 동네예보(5km 격자·1시간 간격)를 우선 쓰고, 아침 7시에 "오늘 뭐 입고 나가지"까지 결론부터 알려 준다.

- 광고·추적기·분석 SDK 없음. 서버 없음 — 폰이 데이터 출처에 직접 묻는다
- 외부 라이브러리 0개 (Android SDK 만 사용), APK 약 2MB
- 계정·API 키 불필요

## 기능

| | |
|---|---|
| 🏠 **크기 적응형 위젯** | 1×1 부터 전체 화면까지, 런처가 고른 크기에 맞는 레이아웃 |
| 🌡️ **상태바 아이콘** | 상단 상태바에 지금 상황 하나(비·눈·진눈깨비·뇌우·폭염·한파·강풍·미세먼지·맑음 등)를 아이콘으로 |
| ⏰ **아침 7시 안내** | 비·눈 시간대 + 겉옷·상의·하의·챙길 것. 인사말 없이 결론만 |
| ☔ **다음 변화** | 앞으로 12시간 안에 비가 언제 시작·그치는지 한 줄로 |
| 👕 **옷차림** | 출퇴근 체감(7~9시·18~21시)과 한낮 기온, 계절 적응 보정 |
| 🚨 **기상 특보** | 지금 위치에 발효 중인 기상청 특보(폭염·한파·호우·대설·강풍·태풍 등)를 행동 요령과 함께 하루 한 번 알림. 특보가 없을 땐 예보값이 특보 기준을 넘으면 "예보 기준"으로 알림 |
| 📅 **10일 예보** | 단기(0~3일)는 시간별, 4~10일은 기상청 중기예보 |
| 📋 **생활 정보** | 출퇴근 우산·자외선·미세먼지·일교차·빨래 — 항목마다 근거 표시 |

## 데이터 출처

| 데이터 | 출처 | 비고 |
|---|---|---|
| 국내 단기(0~3일)·중기(4~10일) 예보 | [기상청 날씨누리](https://www.weather.go.kr) 동네예보 | 국내 좌표에서 우선 사용 |
| 국내 현재 기온·습도·바람·강수, 기상 특보 | 기상청 날씨누리 현재 날씨(관측소 실측) | 현재값은 예보보다 관측을 우선 |
| 국내 미세먼지(PM10·PM2.5) | [에어코리아](https://www.airkorea.or.kr) 측정소 실측(날씨누리에 실린 값) | 가장 가까운 측정소 |
| 해외 전부·기상청 실패 시 | [Open-Meteo](https://open-meteo.com) 예보, Air Quality([Copernicus CAMS](https://atmosphere.copernicus.eu)) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), 대기질은 모델 추정치 |

> Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0).
> Contains modified Copernicus Atmosphere Monitoring Service information.

## 근거로 쓴 공식·기준

- 겨울 체감온도: 기상청 JAG/TI 식 — Osczevski & Bluestein (2005), *BAMS* 86(10)
- 여름 체감온도: 기상청 2022~ 산식 — Stull (2011), *J. Appl. Meteor. Climatol.* 50
- 폭염·한파: 기상청 기상특보 발표기준
- 미세먼지 등급: 환경부 예보등급 (PM10 30/80/150, PM2.5 15/35/75 ㎍/㎥)
- 자외선: WHO *Global Solar UV Index: A Practical Guide* (2002)
- 계절 적응 보정: de Dear & Brager (1998), 적응형 열쾌적 모델

경험칙으로 정한 구간(옷차림 기온대, 빨래 단계 등)은 코드 주석과 앱 화면에 경험칙이라고 밝힌다.

## 설치

[Releases](../../releases) 에서 `dongne-<버전>.apk` 를 받아 설치한다(Android 12 이상). 서명 인증서 SHA-256:

```
AE:BA:E4:B3:ED:C5:08:4A:9D:26:CC:40:96:81:4A:1A:9F:6C:A4:6B:53:9B:86:E4:BF:C9:52:BB:77:F9:FE:D4
```

`apksigner verify --print-certs dongne-<버전>.apk` 로 같은지 확인할 수 있다. 처음 열면 위치·알림 권한을 묻고, 홈 화면에 위젯을 추가하면 된다.

## 빌드

요구사항: JDK 17, Android SDK 35, 기기 Android 12(API 31) 이상.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 캐시·기상청 파서(실제 페이지 조각)·병합·옷차림·갱신 주기 테스트
LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveTest'   # 실제 날씨누리를 불러 페이지 형식 변경 감지 (CI 는 매주)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# debug 빌드 전용: 아침 안내·갱신을 바로 돌려 보기
adb shell am broadcast -n dev.dongne.weather/.DebugReceiver -a dev.dongne.weather.MORNING
adb shell am broadcast -n dev.dongne.weather/.DebugReceiver -a dev.dongne.weather.REFRESH
```

release 서명 키는 저장소 밖에 두고 루트의 `keystore.properties`(커밋 금지, `.gitignore` 됨)로 가리킨다. 파일이 없으면 debug 키로 서명된다.

```properties
storeFile=/absolute/path/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## 구조

```
Sources.kt   출처 인터페이스(LocalSource·AirSource)와 목록, Open-Meteo
Kma.kt       기상청 동네예보·중기·현재 관측·특보 (LocalSource), 에어코리아 측정소 (KmaAir)
Net.kt       HTTP GET + 응답 원문 파일 캐시 (출처별 TTL, 실패 시 지난 응답)
Weather.kt   Repo.fetch → 순수 함수 Repo.build 로 병합 → Snapshot
Next/Outfit/Life/Alerts  판단 규칙과 알림, GlanceWidget 위젯
```

전 세계 기본 예보(Open-Meteo) 위에, 좌표를 덮는 지역 출처가 시간별·일별 값을 덮어쓴다. 체감온도·하강폭처럼 서로 비교하는 값은 한 출처 안에서만 계산한다.

### 출처 추가하기

1. `LocalSource`(또는 `AirSource`)를 구현한다 — `covers(p)` 로 담당 영역을, `fetch(net, p, force)` 에서 `net.get(url, ttl = …)` 로 받아 파싱한다. 못 읽으면 `null`
2. `Sources.local`(또는 `Sources.air`) 목록에 넣는다. 앞에서부터 covers 하는 출처를 시도하고 실패하면 다음으로 넘어간다
3. 실제 응답 조각을 `app/src/test/resources/` 에 두고 파서 테스트를 붙인다

### 캐시

| 요청 | 신선도(TTL) | 실패 시 지난 응답 |
|---|---|---|
| 기상청 동 코드 | 30일 | 1년 |
| 기상청 동네예보 | 20분 | 6시간 |
| 기상청 현재 날씨·특보·측정소 | 10분 | 2시간 |
| Open-Meteo 예보 | 30분 | 6시간 |
| 대기질 | 1시간 | 6시간 |

같은 URL 을 동시에 부르면 한 번만 받고, 7일 넘은 캐시 파일은 하루 한 번 지운다. 좌표는 소수 둘째 자리(~1km)로 반올림해 요청한다.

갱신 주기는 상황에 맞춘다: 비·눈이 2시간 안이면 15분, 밤(0~5시)·절전 모드면 2시간, 그 외 30분. 화면이 꺼져 있으면 깨우지 않고 켜진 뒤에 갱신한다. 새로고침 버튼만 신선도를 무시하고 다시 받는다. 화면의 '업데이트' 시각은 실제로 받은 시각이다.

## 권한

| 권한 | 이유 |
|---|---|
| 위치(대략·정확) | 현재 동네의 예보를 고르려고. 좌표는 기기 밖으로는 날씨 API 요청에만 쓰인다 |
| 백그라운드 위치 | 앱을 열지 않아도 위젯·아침 안내가 지금 위치 기준이도록 (선택, 거부하면 마지막 위치 사용) |
| 알림 | 상태바 아이콘, 아침 안내, 비 시작 전 안내, 기상 특보 |
| 정확한 알람(`SCHEDULE_EXACT_ALARM`) | 아침 7:00 정각 안내. 허용하지 않으면 몇 분 늦을 수 있다 (앱 화면의 버튼으로 허용) |
| 부팅 완료 | 재부팅 뒤 위젯 갱신·아침 안내 예약 복구 |

## 프라이버시

앱이 보내는 네트워크 요청은 아래 세 곳뿐이며, 요청에는 ~1km 로 반올림한 위도·경도만 담긴다.

- `api.open-meteo.com`, `air-quality-api.open-meteo.com`
- `www.weather.go.kr` (국내 좌표일 때)

위치·기록은 기기의 SharedPreferences 와 캐시 폴더에만 저장되며, 백업(`allowBackup`)에서 빠진다. 다른 앱이 위젯 갱신·알림을 임의로 부를 수 없도록 내부 리시버는 export 하지 않는다.

## 알려진 한계

- 기상청 데이터는 공개 API 가 아니라 날씨누리 웹 페이지를 읽는다. 페이지 형식이 바뀌면 자동으로 Open-Meteo 로 넘어간다
- 해외 미세먼지는 모델 추정치라 실측과 차이가 날 수 있다
- 특보가 없을 때의 폭염·한파 알림은 예보값을 특보 기준에 대 본 **자체 판정**이며 알림 제목에 "예보 기준"이라고 붙는다

## License

[GPL-3.0](LICENSE) — 고쳐서 배포해도 소스를 공개해야 하므로, 광고를 붙인 비공개 포크가 나오지 않는다.
