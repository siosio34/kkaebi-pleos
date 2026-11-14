'use strict';
(function (root) {
  const Tour = typeof module !== 'undefined' && module.exports ? require('./tour.js') : root.CabinTour;
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const timeLabel = seconds => Math.floor(seconds / 60) + ':' + String(Math.floor(seconds % 60)).padStart(2, '0');

  // The narration clock is the only time source. Seeking rebuilds the isolated model;
  // neither the fictional arrival nor the voice track dispatches a vehicle command.
  function sample(data, seconds, reduced = false) {
    const elapsed = clamp(Number.isFinite(seconds) ? seconds : 0, 0, data.duration);
    const shot = data.shots.find(item => elapsed >= item.start && elapsed < item.end) || data.shots[data.shots.length - 1];
    const cue = data.cues.find(item => elapsed >= item.start && elapsed < item.end) || null;
    const engine = new Tour.Engine({mode: 'sleep'});
    const prepareAt = Number.isFinite(data.consentAt) ? Math.max(data.prepareAt, data.consentAt) : Infinity;
    const restoreAt = Number.isFinite(data.wakeAckAt) ? Math.max(data.restoreAt, data.wakeAckAt) : Infinity;
    if (elapsed >= prepareAt) {
      engine.start();
      engine.tick((elapsed - prepareAt) * 1000, reduced);
      if (elapsed >= restoreAt && engine.awaitingWake) {
        engine.wake();
        const wakeScreen = Tour.parallelSleepSteps.find(item => item.id === 'alarm').duration;
        engine.tick(wakeScreen + (elapsed - restoreAt) * 1000, reduced);
      }
    }
    return {elapsed, shot, cue, cabin: engine.snapshot(), finished: elapsed >= data.duration};
  }
  const api = {sample, timeLabel};
  if (typeof module !== 'undefined' && module.exports) { module.exports = api; return; }
  root.KkaebiStory = api;

  const $ = selector => document.querySelector(selector);
  const data = root.KkaebiStoryData;
  const audio = $('#story-audio');
  const scene = CabinScene.create($('#cabin-canvas'));
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const hosted = location.protocol === 'file:';
  const rows = new Map();
  const labels = {waiting: '대기', adjusting: '조절 중', confirming: '확인 중', complete: '완료'};
  const chapters = {intro: 0, drowsy: 0, route: 1, checkin1: 1, checkin2: 1, arrive: 2, prepare: 3, rest: 3, alarm: 4, restore: 4, recover: 4, resume: 5, finish: 5};
  let host = null, latched = false, started = false, pending = false, audioError = '', lastShot = '', lastPaint = 0, frame = 0, playRequest = 0, buffering = false;

  function safe() {
    if (document.hidden) return false;
    if (!hosted) return true;
    if (!host || latched || host.sessionInterrupted || host.interactiveAllowed !== true) return false;
    const now = Date.now();
    return host.lifecycleActive === true && host.parked === true && host.restricted === false && [host.observedAt, host.gearAt, host.speedAt].every(value => Number.isFinite(value) && now >= value && now - value <= 5000);
  }
  function moving() { return safe() && !reduced.matches && (!hosted || host.systemAnimationsEnabled === true && host.motionAllowed === true); }
  function simplified() { return reduced.matches || hosted && host?.systemAnimationsEnabled === false; }
  function running() { return started && !audio.paused && !audio.ended && safe() && !audioError; }
  function text(selector, value) { const node = typeof selector === 'string' ? $(selector) : selector; if (node.textContent !== value) node.textContent = value; }
  function log(event) {const current = sample(data, audio.currentTime); console.info('[KkaebiStory] ' + JSON.stringify({event, elapsed: current.elapsed, mediaTime: audio.currentTime, shot: current.shot.id, running: running(), hosted}));}
  function pause() { const wasPlaying = !audio.paused || pending; ++playRequest; pending = false; audio.pause(); scene.stop(); if (wasPlaying) log('pause'); }
  function stopForHost() { if (hosted && started && !audio.paused) latched = true; pause(); }

  root.kkaebiDemoHostState = state => {
    if (!state || state.hosted !== true) return;
    host = state;
    if (state.sessionInterrupted || state.interactiveAllowed === false) latched = true;
    if (!safe()) stopForHost();
    document.documentElement.style.setProperty('--font-scale', Math.max(1, Math.min(1.6, Number(state.fontScale) || 1)));
    document.body.dataset.largeType = String(Number(state.fontScale) > 1.15);
    render();
  };
  function setFacts(items) {
    const content = items.join('\n');
    if ($('#story-facts').dataset.content === content) return;
    $('#story-facts').dataset.content = content;
    $('#story-facts').replaceChildren(...items.map(value => {const p = document.createElement('p'); p.textContent = value; return p;}));
  }
  function createHardware() {
    const shortNames = {seatRecline: '시트', window: '창문', blind: '블라인드', hvacPower: '전원', temperature: '온도', fan: '풍량', seatVentilation: '통풍', brightness: '조명'};
    for (const component of Tour.components) {
      const row = document.createElement('li');
      row.className = 'hardware-row'; row.dataset.key = component.key;
      row.innerHTML = '<div class="hardware-row-head"><strong></strong><span class="hardware-value"></span><span class="hardware-state"></span></div><div class="hardware-meter" role="progressbar" aria-valuemin="0" aria-valuemax="100"><span></span></div>';
      text(row.querySelector('strong'), shortNames[component.key] || component.label);
      row.hidden = !Object.hasOwn(shortNames, component.key);
      row.querySelector('.hardware-meter').setAttribute('aria-label', component.label + ' 모형 진행률');
      $('#hardware-list').append(row); rows.set(component.key, row);
    }
  }
  function updateHardware(s, playing) {
    const active = s.cabin.rows.filter(row => row.active);
    const hardware = active.filter(row => row.groupId !== 'support');
    const completed = s.cabin.rows.filter(row => row.groupId !== 'support' && row.state === 'complete').length;
    const paused = !playing;
    const pauseLabel = latched ? '중단' : '일시정지';
    text('#hardware-summary', hardware.length ? hardware.length + '개 ' + (paused ? pauseLabel : '함께 조절') + ' · ' + completed + '/8 완료' : completed === 8 ? '8개 항목 완료 · 모두 모형값' : '알람 확인 후 함께 시작 · 모형값');
    for (const item of s.cabin.rows) {
      const row = rows.get(item.key), activeNow = item.active && playing;
      row.dataset.active = String(activeNow);
      row.dataset.state = item.active && paused ? 'paused' : item.state;
      row.setAttribute('aria-current', activeNow ? 'step' : 'false');
      text(row.querySelector('.hardware-value'), Tour.format(item.key, item.current));
      text(row.querySelector('.hardware-state'), item.active && paused ? pauseLabel : labels[item.state]);
      const meter = row.querySelector('.hardware-meter');
      const percent = Math.round(item.progress * 100);
      meter.setAttribute('aria-valuenow', String(percent));
      meter.setAttribute('aria-valuetext', '모형 ' + percent + '% · ' + (item.active && paused ? pauseLabel : labels[item.state]));
      meter.firstElementChild.style.width = percent + '%';
    }
  }
  function information(s) {
    const {id, phase, detail} = s.shot;
    if (id === 'intro') {
      text('#story-message', '졸음 신고부터 안전한 휴식, 다시 출발할 때까지 함께해요.');
      setFacts(['운전자와 깨비의 한국어 대화', '쉼터 안내 → 수면 환경 → 기상·복원']);
    } else if (id === 'drowsy') {
      text('#story-message', '“깨비, 나 조금 졸려.”'); setFacts(['화면 조작 없이 음성으로 호출하는 예시', '목표는 안전한 장소에서 쉬는 것']);
    } else if (id === 'route') {
      text('#story-message', '전방 졸음쉼터로 안내할게요.'); setFacts([data.routeDistance + ' · ' + data.routeEta + ' · 경로 예시', '현재 진행 방향에서 진입 가능한 장소']);
    } else if (id === 'checkin1') {
      text('#story-message', '“지금 눈꺼풀이 무겁나요?”'); setFacts(['짧은 상태 확인 · 답변 재촉 없음', '시선은 도로에, 필요한 안내는 음성으로']);
    } else if (id === 'checkin2') {
      text('#story-message', '안전한 정차가 가장 먼저예요.'); setFacts(['대화는 졸음을 없애거나 휴식을 대신하지 않아요.', '졸음이 심해지면 가까운 안전한 곳에 정차']);
    } else if (phase === 'arrive') {
      text('#story-message', s.elapsed < 60 ? '주차 위치와 주변을 확인해요.' : '“깨비, 휴식 시작해줘.”');
      setFacts(['쉼터 도착 · P단 · 0 km/h 확인 예시', s.elapsed < data.consentAt ? '환경 적용은 운전자 동의 뒤에 시작' : '주변 확인 · 저장한 환경 적용 동의']);
    } else if (phase === 'alarm') {
      text('#story-message', s.elapsed < 99 ? '“눈을 뜨셨나요?”' : '“응, 일어났어.”');
      setFacts(['기상 알람과 안내 음성', '운전자 응답을 확인한 뒤 설정 복원']);
    } else if (phase === 'recover') {
      text('#story-message', '눈을 떴다고 바로 출발하지 않아요.');
      setFacts(['천천히 움직이며 몸 상태 확인', '아직 졸리거나 멍하면 더 쉬기']);
    } else if (phase === 'resume') {
      text('#story-message', '충분히 더 쉰 뒤, 다시 출발해요.');
      setFacts(['기상 후 시간을 더 보낸 상황의 예시', '운전자가 상태와 주변을 확인하고 출발']);
    } else if (phase === 'finish') {
      text('#story-message', '쉼터에서 쉬고, 다시 출발하는 여정.');
      setFacts(['호출부터 쉼터, 수면과 기상까지', '안전한 다음 출발을 돕는 깨비']);
    } else { text('#story-message', detail); setFacts([]); }
  }
  function render() {
    if (!data) return;
    const s = sample(data, audio.currentTime, simplified());
    const playing = running(), allowed = safe(), phase = s.shot.phase;
    const hardware = phase === 'prepare' || phase === 'restore';
    const cabinVisible = ['arrive', 'prepare', 'rest', 'alarm', 'restore', 'recover'].includes(phase);
    const isRest = phase === 'rest';
    document.body.dataset.shot = s.shot.id;
    document.body.dataset.storyPhase = phase;
    document.body.dataset.storyRunning = String(playing);
    document.body.dataset.storyTime = s.elapsed.toFixed(3);
    document.body.dataset.speaking = String(playing && !!s.cue);
    document.body.classList.toggle('motion-ok', moving() && playing);
    $('#route-scene').hidden = cabinVisible;
    $('#cabin-scene').hidden = !cabinVisible;
    $('#story-information').hidden = hardware || isRest;
    $('#story-hardware').hidden = !hardware;
    $('#story-rest').hidden = !isRest;
    text('#story-title', s.shot.title);
    text('#story-detail', s.shot.detail);
    information(s); updateHardware(s, playing);
    const activeHardware = s.cabin.rows.filter(row => row.active && row.groupId !== 'support');
    const largeType = document.body.dataset.largeType === 'true';
    const stateLabel = !playing && activeHardware.length ? (latched ? '시연 중단' : '시연 일시정지') + (largeType ? ' · 모형값' : ' · 마지막 모형값') : phase === 'prepare' ? largeType ? '수면 설정 준비 중' : '저장한 수면 환경을 준비해요' : phase === 'restore' ? largeType ? '이전 설정 복원 중' : '이전 환경으로 되돌려요' : phase === 'rest' ? '20분 휴식 · 시간 축약' : phase === 'alarm' ? '기상 응답을 기다려요' : phase === 'recover' ? largeType ? '복원 완료 · 상태 확인' : '설정 복원 완료 · 몸 상태 확인' : largeType ? '정차 확인 · 설정 동의' : '안전한 정차 · 환경 적용 동의';
    text('#cabin-state', stateLabel);
    text('.cabin-note', largeType ? '창문은 시연 전용' : '모형값 · 창문 자동 동작은 시연 전용');
    const pose = isRest ? 'resting' : ['alarm', 'restore', 'recover'].includes(phase) ? 'recovery' : 'attentive';
    const companionPath = '../../character/kkaebi-' + pose + '.webp';
    if ($('#companion').getAttribute('src') !== companionPath) $('#companion').src = companionPath;
    scene.setState(s.cabin.values, {motion: moving() && playing, selectedKeys: playing ? activeHardware.map(item => item.key) : [], phase: isRest ? 'rest' : ['alarm', 'restore', 'recover'].includes(phase) ? 'wake' : 'awake', duration: 0});
    const travel = clamp((s.elapsed - 14) / 39, 0, 1);
    const pathTravel = moving() ? travel : travel < .45 ? 0 : travel < .8 ? .6 : 1;
    let x, y;
    if (pathTravel < .62) {x = 49 + 106 * pathTravel / .62; y = 204 - 99 * pathTravel / .62;}
    else if (pathTravel < .83) {x = 155; y = 105 - 57 * (pathTravel - .62) / .21;}
    else {x = 155 + 80 * (pathTravel - .83) / .17; y = 48;}
    $('#route-driver').setAttribute('transform', 'translate(' + x.toFixed(1) + ' ' + y.toFixed(1) + ')');
    text('#route-distance', phase === 'resume' || phase === 'finish' ? '다시 이어지는 여정' : data.routeDistance + ' · ' + data.routeEta);
    if (isRest) {const remaining = 1200 * (1 - (s.elapsed - s.shot.start) / (s.shot.end - s.shot.start)); text('#rest-clock', timeLabel(Math.max(0, remaining)));}
    const chapter = chapters[s.shot.id] ?? 0;
    $('.story-chapters').querySelectorAll('span').forEach((node, index) => {node.dataset.current = String(index === chapter); node.dataset.done = String(index < chapter); if (index === chapter) node.setAttribute('aria-current', 'step'); else node.removeAttribute('aria-current');});
    const recentCue = s.cue || [...data.cues].reverse().find(cue => s.elapsed >= cue.end && s.elapsed - cue.end <= 2.5);
    text('#caption-speaker', recentCue?.speaker || '깨비');
    text('#caption-text', recentCue?.text || (isRest ? '편히 쉬세요. 알람에 맞춰 다시 부를게요.' : s.shot.detail));
    text('#story-time', timeLabel(s.elapsed) + ' / ' + timeLabel(data.duration));
    $('#story-time-fill').style.width = s.elapsed / data.duration * 100 + '%';
    text('#story-play', pending ? '음성 준비 중' : audioError ? '다시 재생' : s.finished || audio.ended ? '다시 보기' : playing ? '일시정지' : !started ? '통합 여정 시작' : '이어 보기');
    $('#story-play').disabled = !allowed || pending;
    $('#story-restart').disabled = !allowed || pending;
    const message = latched ? '정차·전경 상태가 바뀌어 시연을 멈췄어요. 앱으로 돌아갔다 다시 열어 주세요.' : !allowed ? hosted && !host ? '차량의 정차 상태를 확인하고 있어요.' : '정차·전경 상태를 확인할 때까지 시연을 멈춰요.' : audioError || (buffering && playing ? '음성을 불러오고 있어요. 장면도 같은 위치에서 기다려요.' : '');
    $('#story-notice').hidden = !message; text('#story-notice', message);
    text('#story-host', hosted ? 'P단 정차 시연 · 합성 대화 · 차량 제어 없음' : '브라우저 시연 · 합성 대화 · 차량 제어 없음');
    if (lastShot !== s.shot.id) {lastShot = s.shot.id; text('#story-live', s.shot.title + '. ' + s.shot.detail); log('shot');}
  }

  async function play() {
    if (!safe() || pending) return;
    if (running()) {pause(); render(); return;}
    if (audio.ended || audio.currentTime >= data.duration) audio.currentTime = 0;
    if (audioError) {audioError = ''; audio.load();}
    pending = true; started = true;
    const request = ++playRequest;
    render();
    try {
      await audio.play();
      if (request !== playRequest || !safe()) {audio.pause(); return;}
      pending = false;
      log('play');
    } catch (error) {
      if (request !== playRequest) return;
      pending = false;
      audioError = '음성을 재생하지 못했어요. ‘다시 재생’을 눌러 주세요.';
      audio.pause();
    }
    render();
  }
  $('#story-play').onclick = play;
  $('#story-restart').onclick = () => {if (!safe()) return; pause(); audio.currentTime = 0; started = false; audioError = ''; buffering = false; $('#hardware-list').scrollTop = 0; render();};
  $('#exit').addEventListener('click', pause);
  $('.story-details').addEventListener('click', pause);
  if (!hosted) $('#exit').href = '../index.html';
  audio.addEventListener('waiting', () => {buffering = true; render();});
  audio.addEventListener('playing', () => {buffering = false; render();});
  audio.addEventListener('pause', render);
  audio.addEventListener('ended', () => {pending = false; buffering = false; render();});
  audio.addEventListener('seeked', render);
  audio.addEventListener('error', () => {pause(); audioError = '음성 파일을 불러오지 못했어요. ‘다시 재생’을 눌러 주세요.'; render();});
  document.addEventListener('visibilitychange', () => {if (document.hidden) {if (hosted && started) latched = true; pause();} render();});
  window.addEventListener('pagehide', () => {pause(); cancelAnimationFrame(frame);});
  reduced.addEventListener('change', () => {scene.stop(); render();});
  function tick(now) {
    if (!safe() && !audio.paused) stopForHost();
    if (now - lastPaint >= 32) {if (running() || !safe()) render(); lastPaint = now;}
    frame = requestAnimationFrame(tick);
  }
  root.kkaebiStorySnapshot = () => ({...sample(data, audio.currentTime, simplified()), running: running(), hosted, safe: safe(), latched, started, pending, buffering, audioError, motion: moving() && running(), mediaTime: audio.currentTime, scene: scene.getDebug()});
  createHardware();
  audio.src = data.audio;
  render();
  frame = requestAnimationFrame(tick);
})(typeof window !== 'undefined' ? window : globalThis);
