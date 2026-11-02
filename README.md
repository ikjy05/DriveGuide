# DriveGuide (Pleos Connect 프로토타입)

Pleos 내비에서 현재 위치를 받아, 지나가는 지역의 관광지와 여행(드라이브) 코스를 추천하고 경유지로 추가하는 앱입니다.

## 구조

| 파일 | 역할 |
|---|---|
| `navi/NaviBridge.kt` | NaviHelper SDK 3.0.5 래퍼. 10초마다 `getCurrentLocationInfo()`를 호출하고 `onCurrentLocationInfo` 결과를 StateFlow로 노출. `addWaypoint()` 처리 |
| `tour/TourApiClient.kt` | 한국관광공사 TourAPI(KorService2) `locationBasedList2` 호출. 관광지(12), 여행코스(25) |
| `RecommendationEngine.kt` | 주소에서 시/군/구가 바뀌거나 5km 이상 이동하면 재조회 |
| `MainActivity.kt` | 관광지/코스 탭, 경유지 추가 버튼, 지역 진입 시 TTS 안내 |

## 설정

1. [공공데이터포털](https://www.data.go.kr/data/15101578/openapi.do)에서 "한국관광공사_국문 관광정보 서비스_GW" 활용신청을 합니다.
2. 발급받은 **Decoding** 인증키를 `local.properties`에 넣습니다.
   ```
   TOUR_API_KEY=발급받은_디코딩_키
   ```
3. Pleos Connect 에뮬레이터를 실행한 뒤 빌드하고 설치합니다.
   ```bash
   ./gradlew installDebug
   ```
4. Virtual Console에서 주행을 시뮬레이션하면 위치가 바뀌면서 추천 목록이 갱신됩니다.

## 분할 화면과 알림

- **카드 목록**: 추천 장소는 16:9 사진 카드로 보여주고, 제목·거리·주소·"+ 경유지" 버튼을 사진 아래쪽 그라데이션 위에 얹습니다(`layout/item_place_card.xml`, `ui/AspectRatioFrameLayout.kt`). 이미지는 TourAPI `firstimage`(약 940px)를 HTTPS로 받아 카드 폭에 맞게 줄여 디코딩합니다(`ui/ImageLoader.kt`, 메모리 + 50MB 디스크 캐시). 이미지가 없는 장소는 위치 아이콘을 표시합니다. 여행코스는 대표 이미지가 약 4%에만 있어서, 화면에 나오는 코스에 한해 `detailInfo2`로 포함 장소의 첫 이미지를 조회해 쓰고 코스 캐시에 저장합니다.
- **분할 화면**: Pleos Connect는 내비와 앱 하나를 나란히 띄웁니다(Connect-S 1:1, Connect-W 2:1·1:2). 창 폭이 1200dp 이상이면 `layout-w1200dp`/`values-w1200dp`(카드 2열), 그보다 좁으면 `layout`/`values`(카드 1열, 짧은 탭 이름)를 씁니다. 분할 전환 시 재시작하지 않고 `onConfigurationChanged`에서 레이아웃만 바꾸므로 추천 목록이 유지됩니다.
- **헤드업 알림**(`notify/`): 지역에 진입하거나 가는 길 추천이 새로 생기면 내비 위에 알림을 띄웁니다. "경유지 추가" 버튼은 `AddWaypointReceiver`가 NaviHelper에 따로 연결해 처리하므로, 앱 화면이 없어도 동작합니다. 같은 장소는 한 번만, 가는 길 알림은 3분 간격으로 띄웁니다.

## 테마

- 시스템(Pleos) 라이트/다크 설정을 따릅니다. 앱 테마는 `Theme.DeviceDefault.DayNight` 기반 `AppTheme`이고, 색은 `values/colors.xml`(라이트)과 `values-night/colors.xml`(다크)에 같은 이름으로 정의합니다.
- 버튼은 Pleos 기본 앱 규칙을 따릅니다. 주요 버튼(경유지, 선택된 탭)은 라이트·다크 모두 검정이고, 보조 버튼(목적지로, 전화, 읽어주기)은 라이트에서 흰색, 다크에서 검정입니다(`btn_*` 색).
- 사진 위에 얹는 글씨, 그라데이션, 파란 배지는 테마와 무관하게 고정입니다.
- 테마 전환 시 재시작하지 않고(`configChanges`에 `uiMode`) 새 색으로 다시 그리므로 추천 목록과 열린 상세 화면이 유지됩니다.

## 상세 화면

- 카드를 누르면 상세 화면(`ui/PlaceDetailScreen.kt`)이 열립니다. 사진이 창 전체를 채우고 그 위에 정보를 얹습니다. 전체 화면은 왼쪽 아래 이름·칩·버튼·썸네일, 오른쪽 아래 반투명 패널에 소개·코스 구성(`layout-w1200dp/view_place_detail.xml`). 분할 화면은 위쪽 약 1/3을 사진만 보여 주고 아래 반투명 시트에 모두 담아 스크롤합니다(`layout/view_place_detail.xml`). 공통 부분은 `detail_header.xml`, `detail_body.xml`입니다.
- 상세 화면은 사진 위에 그리므로 테마와 무관하게 흰 글씨, 흰 주요 버튼, 반투명 보조 버튼·칩을 씁니다(`PillButton.OnPhoto*` 스타일). 분할 전환 시에도 열린 상태를 유지합니다.
- 관광지: `detailCommon2`(소개·전화), `detailIntro2`(이용 시간·휴무·주차·문의처), `detailImage2`(사진 갤러리)를 조회합니다. 여행코스: `detailIntro2`(총 거리·소요 시간), `detailInfo2`(포함 장소)를 조회하고, 포함 장소의 경유지 버튼을 누르면 `detailCommon2`로 좌표를 받아 경유지로 넣습니다.
- 좌우 스와이프(또는 오른쪽 위 ‹ › 버튼)로 같은 목록의 이전·다음 장소로 넘깁니다(`ui/SwipeFrameLayout.kt`). 썸네일·칩 줄 위의 가로 드래그는 제외합니다. 정차 중에는 사진이 5초마다 바뀌고 주행 중에는 멈춥니다.
- 버튼: 경유지 추가, 목적지로(NaviHelper `requestRoute`), 전화(문의처에서 번호 추출, 다이얼러 열기), 읽어주기(TTS).
- 주행 중에는 `CarUxRestrictionsManager`로 화면 제한 상태를 받아 소개글을 숨기고 읽어주기만 남깁니다(`ui/DrivingRestriction.kt`).

## 알려진 제약 / TODO

- TourAPI의 `contentid`는 내비 POI ID가 아니어서, 경유지는 `poiId=""`와 좌표로 요청합니다. Pleos 에뮬레이터 내비에서 정상적으로 경로에 반영되는 것을 확인했습니다.
- 여행코스(25)는 TourAPI 위치기반 조회로는 결과가 거의 나오지 않습니다. 그래서 `CourseRepository`가 전체 목록(좌표 있는 약 1,000건)을 받아 `filesDir/courses.json`에 7일간 캐시하고, 앱에서 반경 60km 안의 가까운 순 5개를 계산합니다. 거리는 코스 출발 지점 기준입니다.
- **가는 길 추천**: NaviHelper는 경로 선형(좌표 목록)을 주지 않습니다. 그래서 현재 위치 → 남은 경유지 → 목적지를 직선으로 잇고, 내비의 남은 도로 거리 비율로 보정해 경로 앞 지점(최대 60km, 3~15km 간격)을 추정합니다(`route/RouteAhead.kt`). 각 지점 반경 5km 관광지와 10km 코스를 보여줍니다. 직선 근사라서 산길처럼 크게 굽는 경로에서는 실제 도로와 어긋날 수 있습니다.
- Pleos 에뮬레이터 내비는 `onWayPointInfo`의 경유지 좌표를 (0, 0)으로 돌려줍니다. 그래서 앱에서 추가한 경유지 좌표를 이름으로 기억해 보완합니다. 사용자가 내비에서 직접 넣은 경유지는 좌표를 알 수 없어 계산에서 빠집니다.
- Pleos 에뮬레이터는 헤드업 알림 창을 화면에 표시하지 않고(셸 테스트 알림도 동일), 알림 센터 UI도 열리지 않습니다. 알림 등록과 "경유지 추가" 브로드캐스트 처리까지만 확인했습니다. 실제 표시는 실차·실기기에서 확인이 필요합니다.
- 추천과 알림은 Activity에서 동작합니다. Pleos는 화면에서 내려간 앱을 종료하지 않지만, 시스템이 앱을 정리하면 알림도 멈춥니다. 확실하게 하려면 포그라운드 서비스로 옮겨야 합니다.
- 주행 중 소개글 숨김은 에뮬레이터에서 확인하지 못했습니다. 셸에서 차량 속도·기어 값을 주입하는 명령(`inject-vhal-event`)이 거부됩니다. Pleos Virtual Console로 주행 상태를 바꿔 확인해야 합니다.
- 내비가 주는 주소는 시·도 없이 오기도 합니다(예: "수정구 시흥동"). 그래서 지역 판단은 시·군·구 토큰만 사용합니다.
- 주행 중 UX 제한: `distractionOptimized`로 선언했지만, Pleos 스토어 심사 기준에 맞게 글자 크기와 항목 수를 조정해야 할 수 있습니다.