#!/usr/bin/env python3
"""Build local synthetic Korean dialogue and a reproducible 150s demo soundtrack.

Uses macOS installed voices. This is authored scenario audio, not Gleo speech.
"""
import array
import json
import math
import subprocess
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "verification/journey-v11/audio"
WEB = ROOT / "app/src/main/assets/web/demo"
OUT.mkdir(parents=True, exist_ok=True)
RATE = 48000
DURATION = 150
SHOTS = [
    ("intro", 0, 5, "졸릴 땐, 깨비", "쉼터 찾기부터 다시 출발까지", "intro"),
    ("drowsy", 5, 14, "말 한마디로 시작", "운전 중에는 화면을 누르지 않아요", "drive"),
    ("route", 14, 27, "가장 가까운 졸음쉼터로", "찾아서 경유지로 넣어요", "drive"),
    ("checkin1", 27, 39, "가는 동안 말동무", "짧게 묻고, 짧게 답하기", "drive"),
    ("checkin2", 39, 53, "창문 열고 바람 쐬기", "도착할 때까지 깨어 있도록", "drive"),
    ("arrive", 53, 64, "쉼터 도착", "P단에 놓으면 준비를 시작해요", "arrive"),
    ("prepare", 64, 78, "20분 잘 준비", "시트, 블라인드, 조명, 공조를 한 번에", "prepare"),
    ("rest", 78, 91, "20분 휴식", "20분을 13초로 줄인 장면이에요", "rest"),
    ("alarm", 91, 102, "일어날 시간", "대답을 듣고 나서 시트를 세워요", "alarm"),
    ("restore", 102, 115, "원래대로", "바꾸기 전 설정으로 돌려요", "restore"),
    ("recover", 115, 132, "서두르지 않기", "출발은 운전자가 정해요", "recover"),
    ("resume", 132, 144, "다시 출발", "원래 가던 길로", "resume"),
    ("finish", 144, 150, "깨비", "졸리면 쉬었다 가요", "finish"),
]
LINES = [
    (5.4, "운전자", "깨비야, 나 좀 졸리다."),
    (8.4, "깨비", "많이 졸려요? 일 킬로 앞에 졸음쉼터 있어요. 들렀다 갈까요?"),
    (15.2, "운전자", "어, 그러자."),
    (17.4, "깨비", "경유지로 넣었어요. 금방 도착해요."),
    (27.5, "깨비", "가는 동안 말 좀 걸게요. 오늘 얼마나 운전했어요?"),
    (33.8, "운전자", "세 시간쯤. 부산에서 올라오는 길이야."),
    (39.4, "깨비", "오래 달렸네요. 창문 조금 열까요? 바람 좀 쐬게요."),
    (45.4, "운전자", "응, 열어 줘."),
    (49.0, "깨비", "저기 쉼터 입구 보이죠? 오른쪽이에요."),
    (54.5, "깨비", "도착했어요. 주차하고 피에 놓으면 알려 줘요."),
    (60.0, "운전자", "됐다. 나 이십 분만 잘게."),
    (64.6, "깨비", "네, 이십 분 뒤에 깨울게요. 시트 눕히고, 불도 줄일게요."),
    (78.6, "깨비", "잘 자요."),
    (93.0, "깨비", "이십 분 지났어요. 일어날 시간이에요."),
    (98.4, "운전자", "으음, 벌써? 일어났어."),
    (102.5, "깨비", "잘 잤어요? 시트 세우고, 아까대로 돌려놓을게요."),
    (115.6, "깨비", "물 한 모금 마시고, 기지개 한번 켜고 가요."),
    (122.0, "운전자", "그래. 조금만 더 있다 갈게."),
    (126.0, "깨비", "천천히 해요."),
    (130.6, "운전자", "이제 괜찮아. 가자."),
    (134.2, "깨비", "네, 원래 가던 길로 안내할게요."),
]
# 화면 자막은 숫자와 P단을 글자로 읽지 않게 따로 적는다.
CAPTIONS = {"많이 졸려요? 일 킬로 앞에 졸음쉼터 있어요. 들렀다 갈까요?": "많이 졸려요? 1km 앞에 졸음쉼터 있어요. 들렀다 갈까요?",
    "도착했어요. 주차하고 피에 놓으면 알려 줘요.": "도착했어요. 주차하고 P에 놓으면 알려 줘요.",
    "됐다. 나 이십 분만 잘게.": "됐다. 나 20분만 잘게.",
    "네, 이십 분 뒤에 깨울게요. 시트 눕히고, 불도 줄일게요.": "네, 20분 뒤에 깨울게요. 시트 눕히고, 불도 줄일게요.",
    "이십 분 지났어요. 일어날 시간이에요.": "20분 지났어요. 일어날 시간이에요."}

KEY_FILE = Path.home() / ".elevenlabs/api_key"
VOICE_FILE = Path.home() / ".elevenlabs/kkaebi-voices.json"

def synth(speaker, text, stem):
    """ElevenLabs 키 파일이 있으면 그 음성으로, 없으면 macOS 내장 음성으로 만든다."""
    if KEY_FILE.exists():
        import urllib.request
        voices = {"깨비": "21m00Tcm4TlvDq8ikWAM", "운전자": "pNInz6obpgDQGcFmaJgB"}
        if VOICE_FILE.exists():
            voices.update(json.loads(VOICE_FILE.read_text()))
        body = json.dumps({"text": text, "model_id": "eleven_multilingual_v2", "voice_settings": {"stability": 0.45, "similarity_boost": 0.8, "style": 0.25}}).encode()
        request = urllib.request.Request(f"https://api.elevenlabs.io/v1/text-to-speech/{voices[speaker]}?output_format=mp3_44100_128", data=body, headers={"xi-api-key": KEY_FILE.read_text().strip(), "Content-Type": "application/json"})
        target = stem.with_suffix(".mp3")
        target.write_bytes(urllib.request.urlopen(request, timeout=60).read())
        return "ElevenLabs " + voices[speaker], target
    voice = "Yuna (Premium)" if speaker == "깨비" else "Eddy (한국어(대한민국))"
    run(["/usr/bin/say", "-v", voice, "-r", "178", "-f", str(stem.with_suffix('.txt')), "-o", str(stem.with_suffix('.aiff'))])
    return voice, stem.with_suffix(".aiff")

def run(args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)

mix = [0.0] * (DURATION * RATE)
cues = []
for i, (start, speaker, text) in enumerate(LINES):
    stem = OUT / f"{i+1:02d}"
    stem.with_suffix(".txt").write_text(text)
    voice, source = synth(speaker, text, stem)
    run(["/opt/homebrew/bin/ffmpeg", "-y", "-v", "error", "-i", str(source), "-ac", "1", "-ar", str(RATE), "-af", "silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse,loudnorm=I=-19:TP=-2:LRA=7", str(stem.with_suffix('.wav'))])
    with wave.open(str(stem.with_suffix('.wav'))) as w:
        samples = array.array('h', w.readframes(w.getnframes()))
    duration = len(samples) / RATE
    end = start + duration
    next_start = LINES[i+1][0] if i+1 < len(LINES) else DURATION
    if end > next_start - .12:
        raise RuntimeError(f"Speech overlap: {i+1} {start:.2f}..{end:.2f}, next {next_start}")
    offset = round(start * RATE)
    for j, sample in enumerate(samples):
        mix[offset+j] += sample / 32768
    cues.append({"start": start, "end": round(end, 3), "speaker": speaker, "text": CAPTIONS.get(text, text), "voice": voice})
    print(f"{i+1:02d} {start:.1f}..{end:.2f} {speaker}", flush=True)

# Quiet, original sine-bell alarm. No stock music or third-party media.
for at, hz in [(91.15, 523.25), (91.7, 659.25), (92.25, 783.99)]:
    for j in range(int(.65*RATE)):
        t = j/RATE
        env = min(1, t/.025) * math.exp(-t*6)
        mix[int(at*RATE)+j] += .15*env*(math.sin(2*math.pi*hz*t)+.18*math.sin(2*math.pi*2*hz*t))
# A low, original ambient rest tone, with long fades and no voice masking.
for j in range(8*RATE):
    t = j/RATE
    env = min(1, t/2, (8-t)/3)
    mix[83*RATE+j] += .016*max(0,env)*(math.sin(2*math.pi*174.61*t)+.5*math.sin(2*math.pi*261.63*t))
pcm = array.array('h', (round(max(-.95, min(.95, x))*32767) for x in mix))
master = OUT / 'story-master.wav'
with wave.open(str(master), 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE); w.writeframes(pcm.tobytes())
run(["/opt/homebrew/bin/ffmpeg", "-y", "-v", "error", "-i", str(master), "-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", str(WEB/'journey-audio.m4a')])
data = {"duration": DURATION, "audio": "journey-audio.m4a", "prepareAt": 65, "restoreAt": 102, "consentAt": 64.4, "wakeAckAt": 102, "routeDistance": "1.0 km", "routeEta": "금방", "shots": [{"id": i, "start": s, "end": e, "title": title, "detail": detail, "phase": phase} for i,s,e,title,detail,phase in SHOTS], "cues": cues}
(OUT.parent/'storyboard.json').write_text(json.dumps(data, ensure_ascii=False, indent=2))
(WEB/'journey-data.js').write_text('/* Scripted, synthetic scenario; not live navigation or Gleo dialogue. */\nwindow.KkaebiStoryData = '+json.dumps(data, ensure_ascii=False, indent=2)+';\n')
def stamp(t):
    ms = round(t*1000); h,ms=divmod(ms,3600000); m,ms=divmod(ms,60000); s,ms=divmod(ms,1000)
    return f'{h:02}:{m:02}:{s:02},{ms:03}'
(OUT.parent/'kkaebi-journey-film.ko.srt').write_text('\n\n'.join(f'{i+1}\n{stamp(c["start"])} --> {stamp(c["end"])}\n{c["speaker"]}: {c["text"]}' for i,c in enumerate(cues))+'\n')
print(f'Built 150-second soundtrack, {len(cues)} cues, storyboard and Korean subtitles.')
