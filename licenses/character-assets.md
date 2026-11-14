# 깨비 캐릭터 자산

`app/src/main/assets/web/art/kkaebi-{attentive,resting,recovery}.webp`는 2026-11-03 이 프로젝트를 위해 이미지 생성 도구로 새로 제작한 일러스트다. 기존 캐릭터·제3자 사진을 입력하지 않았다. 기본 포즈를 기준으로 휴식·회복 포즈를 제작했다.

직접 제작한 프롬프트는 PNG 메타데이터와 WebP의 동명 `.webp.json` 출처 파일에 보존했다. 원본 PNG는 개발용 `.impeccable/assets/source/`에 보관하고 Android에는 512px WebP를 번들한다. 외부 이미지 요청 없이 사용한다.

캐릭터의 표정은 앱 상태를 설명하는 장식이다. 운전자 상태를 측정하거나 수면·회복 효과를 입증한 자료가 아니다. 주행 중에는 정지 이미지만 표시하며, 졸음 점수나 출발 허가를 나타내지 않는다.

## v0.5 네이티브 봇 · 2026-11-04

`app/src/main/assets/character/kkaebi-{attentive,resting,recovery}.webp`는 built-in `image_gen`으로 새로 만든 3D 플러시 봇이다. 기본 포즈를 만든 뒤 같은 이미지를 참조해 휴식·기지개 포즈를 생성했다. Meta Muse/Jolly는 부드러운 둥근 형태의 시각적 참고로만 열람했으며 생성 입력이나 배포 자산으로 사용하지 않았다.

핵심 생성 프롬프트: “original cute animated-film 3D plush Korean dokkaebi rest companion; ivory fur, navy face panel and asymmetric horns, large mint eyes, sage scarf, full-body seated pose, true transparent background.” 전체 프롬프트와 도구·출처 파일명은 각 `.webp.json`에 보관한다. 원본은 `.impeccable/assets/source/bot-v5/`에 보존하고, 앱에는 640×640 WebP(q88, alpha 유지)로 변환해 포함했다. 기존 v0.4 웹 일러스트와 혼동하지 않는다.

## v0.6 차량 공간 미리보기 · 2026-11-04

현재 앱에서 제거하고 `verification/cabin-v6/retired-art/cabin/`에 과거 증거로 보존한 `{awake,rest,wake}.webp`는 built-in `image_gen`으로 새로 제작한 고품질 차량 실내 렌더다. 특정 차종의 CAD·실차 사진이 아닌 앱의 공간 미리보기다. 기본 운전석 모듈을 생성하고 동일 이미지를 참조해 취침 자세와 새벽 조명 상태를 제작했다. 외부 제작물이나 상표를 입력하지 않았다.

프롬프트 요약: “premium automotive cutaway driver's cabin, sculptural warm-grey leather seat, dashboard and window module, physically rendered materials, true transparent background.” 각 WebP의 `.json`에 정확한 프롬프트를 보존하고, 원본 PNG와 편집 이력은 개발용 `.impeccable/assets/source/cabin-v6/`에 남긴다. 앱 자산은 960×960 WebP(q90, alpha 유지)다. 입체감은 WebGL 원근·조명 효과와 조합하며, 차량의 실제 위치·지원·성공 여부는 별도 SDK 결과로만 판단한다.

## v0.7 독립 부품 모델 · 2026-11-07

현재 실내는 `app/src/main/assets/web/scene.js`의 직접 작성한 매개변수 3D 메시다. 외부 CAD·차종 자산·실차 사진을 사용하지 않았다. Canvas2D로 원근 투영·면 음영을 그리며 등받이 힌지, 창유리, 블라인드, 조명과 공기 흐름의 상태가 각각 바뀐다. WebGL이 없는 Pleos WebView에서도 같은 기하를 사용한다.

이 모형은 실제 차종의 측정 모델이 아니다. 시연의 각도·퍼센트는 설명용 모형 값이며 실제 SDK의 원시 위치값과 구분한다. 캐릭터는 위 v0.5 원본과 출처 정보를 유지한다.
