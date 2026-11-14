# 깨비 심사용 시연 도우미 (Windows PowerShell)
# Pleos Connect Emulator v2.1.0을 Cold boot로 켠 뒤 저장소 루트에서 실행한다.
#   .\judge\kkaebi-demo.ps1 setup    설치, 가상 콘솔 연결, 출발 위치와 원래 목적지 준비
#   .\judge\kkaebi-demo.ps1 drive    D단 90km/h 주행
#   .\judge\kkaebi-demo.ps1 arrive   하남 졸음쉼터 도착, 0km/h, P단
#   .\judge\kkaebi-demo.ps1 resume   다시 D단 60km/h로 출발
#   .\judge\kkaebi-demo.ps1 park     그 자리에서 0km/h, P단
#   .\judge\kkaebi-demo.ps1 status   현재 기어, 속도, 운전석 창문
# 실행 정책 때문에 막히면: powershell -ExecutionPolicy Bypass -File .\judge\kkaebi-demo.ps1 setup
# 에뮬레이터가 여러 대면 $env:ANDROID_SERIAL = "emulator-5554" 처럼 지정한다.
param([string]$Step = "")

$ErrorActionPreference = "Stop"
$Pkg = "ai.kkaebi.app.contest"
$Apk = "apk\조영제_깨비_release.apk"
$Console = "http://127.0.0.1:8088"
$StartLng = "127.221"; $StartLat = "37.466"      # 중부선 하남 졸음쉼터 약 1.8km 앞
$RestLng = "127.2331"; $RestLat = "37.4791"     # 하남 졸음쉼터
$Destination = "37.8846,127.7166,춘천역"         # 졸음쉼터에 들르기 전 원래 가던 목적지

function Adb { if ($env:ANDROID_SERIAL) { & adb -s $env:ANDROID_SERIAL @args } else { & adb @args } }
function Gear($g) { Invoke-RestMethod -Method Put -Uri "$Console/vehicle/gear?gear=$g" | Out-Null }
function Speed($s, $a, $b) { Invoke-RestMethod -Method Put -Uri "$Console/vehicle/controls?speed=$s&accelerator=$a&brake=$b" | Out-Null }
function Connect-Console { Adb forward tcp:8088 tcp:8088 | Out-Null }

switch ($Step) {
  "setup" {
    Adb install -r $Apk
    Connect-Console
    Speed 0 0 100; Gear "Park"
    Adb emu geo fix $StartLng $StartLat
    Adb shell am start -n "$Pkg/ai.kkaebi.app.MainActivity" | Out-Null
    Write-Host "깨비를 열었습니다. 처음 실행이면 권한 창 4개에서 모두 '허용'을 눌러 주세요."
    Write-Host "권한 창이 모두 닫히고 깨비 화면이 보이면 원래 목적지를 넣습니다..."
    Start-Sleep -Seconds 3
    $n = 0
    while (-not ((Adb shell dumpsys window | Select-String "mCurrentFocus") -match "$Pkg/ai.kkaebi.app.MainActivity")) {
      Start-Sleep -Seconds 1; $n++
      if ($n -ge 120) { Write-Host "깨비 화면을 찾지 못했습니다. 권한을 허용한 뒤 다시 실행해 주세요."; exit 1 }
    }
    Start-Sleep -Seconds 4
    Adb shell "am start -n $Pkg/ai.kkaebi.app.MainActivity --es kkaebi_destination '$Destination'" | Out-Null
    # 목적지를 넣으면 내비가 앞으로 나온다. 경로가 잡힐 시간을 준 뒤 깨비를 다시 연다.
    Start-Sleep -Seconds 8
    Adb shell am start -n "$Pkg/ai.kkaebi.app.MainActivity" | Out-Null
    Write-Host "준비 완료: P단 정차, 하남 졸음쉼터 1.8km 앞, 내비 목적지 춘천역."
    Write-Host "가상 콘솔: $Console (브라우저에서 열면 창문·조명·공조 값을 함께 볼 수 있습니다)"
  }
  "drive" {
    Connect-Console; Gear "Drive"; Speed 90 20 0
    Write-Host "D단 90km/h로 주행 중입니다. 깨비 졸음모드에서 '졸려요 · 쉼터 찾기'를 누르거나 '깨비야, 졸려'라고 말해 보세요."
  }
  "arrive" {
    Connect-Console
    Adb emu geo fix $RestLng $RestLat
    Speed 0 0 100; Gear "Park"
    Write-Host "하남 졸음쉼터에 도착해 P단에 놓았습니다. 깨비 화면이 '도착했어요'로 바뀝니다."
  }
  "resume" {
    Connect-Console; Gear "Drive"; Speed 60 15 0
    Write-Host "다시 출발했습니다. 내비가 원래 목적지(춘천역)로 안내합니다."
  }
  "park" {
    Connect-Console; Speed 0 0 100; Gear "Park"
    Write-Host "P단 정차."
  }
  "status" {
    Connect-Console
    $s = Invoke-RestMethod -Uri "$Console/vehicle/state"
    Write-Host ("기어 {0} | 속도 {1} km/h | 운전석 창문 {2} %" -f $s.gear.actual.value, $s.controls.speed_kph, $s.body.windows.front_left)
  }
  default { Get-Content $PSCommandPath -TotalCount 10 | Select-Object -Skip 1; exit 1 }
}
