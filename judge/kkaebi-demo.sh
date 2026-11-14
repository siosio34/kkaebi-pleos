#!/bin/sh
# 깨비 심사용 시연 도우미 (macOS / Linux)
# Pleos Connect Emulator v2.1.0을 Cold boot로 켠 뒤 저장소 루트에서 실행한다.
#   sh judge/kkaebi-demo.sh setup    설치, 가상 콘솔 연결, 출발 위치와 원래 목적지 준비
#   sh judge/kkaebi-demo.sh drive    D단 90km/h 주행
#   sh judge/kkaebi-demo.sh arrive   하남 졸음쉼터 도착, 0km/h, P단
#   sh judge/kkaebi-demo.sh resume   다시 D단 60km/h로 출발
#   sh judge/kkaebi-demo.sh park     그 자리에서 0km/h, P단
#   sh judge/kkaebi-demo.sh status   현재 기어, 속도, 운전석 창문
# 에뮬레이터가 여러 대면 ANDROID_SERIAL=emulator-5554 처럼 지정한다.
set -e

PKG=ai.kkaebi.app.contest
APK="apk/조영제_깨비_release.apk"
CONSOLE=http://127.0.0.1:8088
# 중부선 하남 졸음쉼터 약 1.8km 앞
START_LNG=127.221
START_LAT=37.466
# 하남 졸음쉼터
REST_LNG=127.2331
REST_LAT=37.4791
# 졸음쉼터에 들르기 전 원래 가던 목적지
DESTINATION="37.8846,127.7166,춘천역"

adb_() { adb ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }
gear() { curl -fsS -X PUT "$CONSOLE/vehicle/gear?gear=$1" >/dev/null; }
speed() { curl -fsS -X PUT "$CONSOLE/vehicle/controls?speed=$1&accelerator=$2&brake=$3" >/dev/null; }
console() { adb_ forward tcp:8088 tcp:8088 >/dev/null; }

case "$1" in
  setup)
    adb_ install -r "$APK"
    console
    speed 0 0 100; gear Park
    adb_ emu geo fix "$START_LNG" "$START_LAT"
    adb_ shell am start -n "$PKG/ai.kkaebi.app.MainActivity" >/dev/null
    echo "깨비를 열었습니다. 처음 실행이면 권한 창 4개에서 모두 '허용'을 눌러 주세요."
    echo "권한 창이 모두 닫히고 깨비 화면이 보이면 원래 목적지를 넣습니다..."
    sleep 3; n=0
    until adb_ shell dumpsys window | grep mCurrentFocus | grep -q "$PKG/ai.kkaebi.app.MainActivity"; do
      sleep 1; n=$((n+1)); [ $n -ge 120 ] && { echo "깨비 화면을 찾지 못했습니다. 권한을 허용한 뒤 다시 실행해 주세요."; exit 1; }
    done
    sleep 4
    adb_ shell "am start -n $PKG/ai.kkaebi.app.MainActivity --es kkaebi_destination '$DESTINATION'" >/dev/null
    # 목적지를 넣으면 내비가 앞으로 나온다. 경로가 잡힐 시간을 준 뒤 깨비를 다시 연다.
    sleep 8
    adb_ shell am start -n "$PKG/ai.kkaebi.app.MainActivity" >/dev/null
    echo "준비 완료: P단 정차, 하남 졸음쉼터 1.8km 앞, 내비 목적지 춘천역."
    echo "가상 콘솔: $CONSOLE (브라우저에서 열면 창문·조명·공조 값을 함께 볼 수 있습니다)"
    ;;
  drive)
    console; gear Drive; speed 90 20 0
    echo "D단 90km/h로 주행 중입니다. 깨비 졸음모드에서 '졸려요 · 쉼터 찾기'를 누르거나 '깨비야, 졸려'라고 말해 보세요."
    ;;
  arrive)
    console
    adb_ emu geo fix "$REST_LNG" "$REST_LAT"
    speed 0 0 100; gear Park
    echo "하남 졸음쉼터에 도착해 P단에 놓았습니다. 깨비 화면이 '도착했어요'로 바뀝니다."
    ;;
  resume)
    console; gear Drive; speed 60 15 0
    echo "다시 출발했습니다. 내비가 원래 목적지(춘천역)로 안내합니다."
    ;;
  park)
    console; speed 0 0 100; gear Park
    echo "P단 정차."
    ;;
  status)
    console
    STATE=$(curl -fsS "$CONSOLE/vehicle/state")
    GEAR=$(printf '%s' "$STATE" | grep -o '"actual":{"value":"[A-Za-z]*"' | head -1 | sed 's/.*"value":"//; s/"$//')
    SPEED=$(printf '%s' "$STATE" | grep -o '"speed_kph":[0-9.]*' | head -1 | cut -d: -f2)
    WINDOW=$(printf '%s' "$STATE" | grep -o '"windows":{"front_left":[0-9.]*' | head -1 | sed 's/.*://')
    echo "기어 $GEAR | 속도 $SPEED km/h | 운전석 창문 $WINDOW %"
    ;;
  *)
    sed -n '2,11p' "$0"; exit 1 ;;
esac
