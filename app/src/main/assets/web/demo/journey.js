'use strict';
(function (root) {
  const Tour = root.CabinTour, data = root.KkaebiStoryData;
  const $ = selector => document.querySelector(selector);
  const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
  const timeLabel = seconds => Math.floor(seconds / 60) + ':' + String(Math.floor(seconds % 60)).padStart(2, '0');

  // 음성 시계가 유일한 시간 기준이다. 어느 지점으로 옮겨도 모형을 처음부터 다시 계산한다.
  // 가상의 도착과 음성은 차량 명령을 보내지 않는다.
  function sample(seconds, reduced) {
    const elapsed = clamp(Number.isFinite(seconds) ? seconds : 0, 0, data.duration);
    const shot = data.shots.find(item => elapsed >= item.start && elapsed < item.end) || data.shots[data.shots.length - 1];
    const cue = data.cues.find(item => elapsed >= item.start && elapsed < item.end) || null;
    const engine = new Tour.Engine({mode: 'sleep'});
    const prepareAt = Math.max(data.prepareAt, data.consentAt), restoreAt = Math.max(data.restoreAt, data.wakeAckAt);
    if (elapsed >= prepareAt) {
      engine.start();
      engine.tick((elapsed - prepareAt) * 1000, reduced);
      if (elapsed >= restoreAt && engine.awaitingWake) {
        engine.wake();
        engine.tick(Tour.parallelSleepSteps.find(item => item.id === 'alarm').duration + (elapsed - restoreAt) * 1000, reduced);
      }
    }
    return {elapsed, shot, cue, cabin: engine.snapshot(), finished: elapsed >= data.duration};
  }

  const audio = $('#audio');
  const scene = root.KkaebiJourneyScene.create($('#stage'));
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  const hosted = location.protocol === 'file:' && !new URLSearchParams(location.search).has('browser');
  const query = new URLSearchParams(location.search);
  let preview = !hosted && query.has('t') ? Number(query.get('t')) : null;
  // 브라우저에서 프레임 단위로 영상을 뽑을 때만 쓰는 모드. 재생 버튼을 숨기고 모든 움직임을 재생 시각에서 계산한다.
  const film = !hosted && query.has('film');
  if (film) document.body.dataset.film = 'true';
  const stateText = {waiting: '대기', adjusting: '조절 중', confirming: '확인 중', complete: '완료'};
  const chapterList = [['졸음', 0], ['쉼터 안내', 14], ['도착', 53], ['수면', 64], ['기상', 91], ['다시 출발', 132]];
  let host = null, latched = false, started = false, pending = false, audioError = '', lastShot = '', frame = 0, playRequest = 0, buffering = false, dirty = true, slow = 0, lastFrame = 0;

  function safe() {
    if (document.hidden) return false;
    if (!hosted) return true;
    if (!host || latched || host.sessionInterrupted || host.interactiveAllowed !== true) return false;
    const now = Date.now();
    return host.lifecycleActive === true && host.parked === true && host.restricted === false && [host.observedAt, host.gearAt, host.speedAt].every(value => Number.isFinite(value) && now >= value && now - value <= 5000);
  }
  function moving() { return safe() && !reduced.matches && (!hosted || host.systemAnimationsEnabled === true && host.motionAllowed === true); }
  function simplified() { return reduced.matches || hosted && host && host.systemAnimationsEnabled === false; }
  function running() { return started && !audio.paused && !audio.ended && safe() && !audioError; }
  function now() { return preview != null ? preview : audio.currentTime; }
  function text(node, value) { if (typeof node === 'string') node = $(node); if (node.textContent !== value) node.textContent = value; }
  function log(event) { console.info('[KkaebiStory] ' + JSON.stringify({event, elapsed: now(), mediaTime: audio.currentTime, running: running(), hosted})); }
  function pause() { const was = !audio.paused || pending; ++playRequest; pending = false; audio.pause(); dirty = true; if (was) log('pause'); }
  function stopForHost() { if (hosted && started && !audio.paused) latched = true; pause(); }

  root.kkaebiDemoHostState = state => {
    if (!state || state.hosted !== true) return;
    host = state;
    if (state.sessionInterrupted || state.interactiveAllowed === false) latched = true;
    if (!safe()) stopForHost();
    document.documentElement.style.setProperty('--font-scale', Math.max(1, Math.min(1.6, Number(state.fontScale) || 1)));
    document.body.dataset.large = String(Number(state.fontScale) > 1.15);
    dirty = true;
  };

  // 3D 부품에 닻을 내린 라벨
  const labelDefs = [['seatRecline', '시트 등받이'], ['blind', '블라인드'], ['window', '창문'], ['brightness', '실내 조명'], ['climate', '공조 온도 · 풍량'], ['seatVentilation', '시트 통풍']];
  const labels = new Map(), leaders = $('#leaders'), SVG = 'http://www.w3.org/2000/svg';
  for (const [key, name] of labelDefs.concat([['marker', '']])) {
    const node = document.createElement('div');
    node.className = 'label' + (key === 'marker' ? ' marker' : '');
    node.innerHTML = '<b></b><strong></strong><em></em>';
    text(node.firstChild, name);
    $('#labels').append(node);
    const line = document.createElementNS(SVG, 'line'), dot = document.createElementNS(SVG, 'circle');
    dot.setAttribute('r', '3');
    if (key !== 'marker') leaders.append(line, dot);
    labels.set(key, {node, line, dot});
  }
  const hardwareRows = new Map();
  for (const [key, name] of labelDefs) { const li = document.createElement('li'); $('#hardware').append(li); hardwareRows.set(key, {li, name}); }

  for (const [name, start] of chapterList) {
    const li = document.createElement('li');
    li.textContent = name; li.style.left = start / data.duration * 100 + '%';
    $('#chapters').append(li);
  }

  function hardwareView(s) {
    const row = key => s.cabin.rows.find(item => item.key === key);
    const one = key => { const r = row(key); return {value: Tour.format(key, r.current), state: r.state, active: r.active}; };
    const power = row('hvacPower'), temp = row('temperature'), fan = row('fan');
    const climateRows = [power, temp, fan];
    const climateState = climateRows.some(r => r.state === 'adjusting') ? 'adjusting' : climateRows.some(r => r.state === 'confirming') ? 'confirming' : climateRows.every(r => r.state === 'complete') ? 'complete' : 'waiting';
    return {
      seatRecline: one('seatRecline'), blind: one('blind'), window: one('window'), brightness: one('brightness'), seatVentilation: one('seatVentilation'),
      climate: {value: power.current >= 0.5 ? Tour.format('temperature', temp.current) + ' · ' + Tour.format('fan', fan.current) : '꺼짐', state: climateState, active: climateRows.some(r => r.active)}
    };
  }

  function layoutLabels(s, playing, show) {
    const w = innerWidth, h = innerHeight, rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
    const view = hardwareView(s);
    const placed = [];
    for (const [key] of labelDefs) {
      const item = labels.get(key), info = view[key], p = scene.project(key);
      const on = show && p && p.visible;
      item.node.dataset.on = String(!!on);
      const state = info.active && !playing ? 'waiting' : info.state;
      item.node.dataset.state = state;
      text(item.node.children[1], info.value);
      text(item.node.children[2], info.active && !playing ? (latched ? '중단' : '일시정지') : stateText[info.state]);
      text(hardwareRows.get(key).li, hardwareRows.get(key).name + ' ' + info.value + ' ' + stateText[info.state]);
      item.line.style.display = item.dot.style.display = on ? '' : 'none';
      if (on) placed.push({key, item, x: p.x * w, y: p.y * h, active: info.active && playing});
    }
    // 화면 좌우 가장자리의 고정 칸에 배치하고, 부품까지 가는 선으로 잇는다.
    const pad = Math.max(20, Math.min(48, w * 0.026)), top = h * 0.36, gap = Math.max((h < 440 ? 4.2 : 4.5) * rem, h * 0.115);
    placed.sort((a, b) => a.x - b.x);
    const half = Math.ceil(placed.length / 2);
    for (const sideName of ['left', 'right']) {
      const group = (sideName === 'left' ? placed.slice(0, half) : placed.slice(half)).sort((a, b) => a.y - b.y);
      group.forEach((p, i) => {
        const y = top + gap * i;
        const node = p.item.node, width = 9.5 * rem;
        const x = sideName === 'left' ? pad : w - pad - width;
        node.dataset.side = sideName;
        node.style.transform = 'translate(' + Math.round(x) + 'px,' + Math.round(y - 2.2 * rem) + 'px)';
        node.style.width = width + 'px';
        const lx = sideName === 'left' ? x + Math.min(width, 7.2 * rem) : x + width - Math.min(width, 7.2 * rem);
        p.item.line.setAttribute('x1', lx); p.item.line.setAttribute('y1', y); p.item.line.setAttribute('x2', p.x); p.item.line.setAttribute('y2', p.y);
        p.item.dot.setAttribute('cx', p.x); p.item.dot.setAttribute('cy', p.y);
        p.item.line.dataset.active = p.item.dot.dataset.active = String(p.active);
      });
    }
    const marker = labels.get('marker');
    const km = scene.routeDistance(s.elapsed) * 1.2;
    const markerOn = s.elapsed >= 15 && s.elapsed < 53;
    marker.node.dataset.on = String(markerOn);
    if (markerOn) {
      text(marker.node.children[0], '전방 졸음쉼터 · 가상 경로');
      text(marker.node.children[1], km >= 0.1 ? km.toFixed(1) + ' km' : Math.max(0, Math.round(km * 100) * 10) + ' m');
      text(marker.node.children[2], '경유지로 안내 중');
    }
  }

  function statusLine(s, done, speed) {
    const phase = s.shot.phase;
    if (phase === 'prepare') return '수면 환경 준비 · ' + done + '/8 완료 · 모형값';
    if (phase === 'restore') return '이전 설정 복원 · ' + done + '/8 완료 · 모형값';
    if (phase === 'rest') return '휴식 중 · 기상 알람 예약됨';
    if (phase === 'alarm') return s.elapsed < 99 ? '기상 알람 · 응답을 기다려요' : '기상 응답 확인';
    if (phase === 'recover') return '복원 완료 · 몸 상태 확인';
    if (speed > 0.5) return '주행 중 · 조작은 음성으로';
    return phase === 'arrive' ? (s.elapsed < data.consentAt ? 'P단 · 0 km/h · 주변 확인' : '정차 확인 · 설정 적용 동의') : 'P단 · 0 km/h';
  }

  function render() {
    const s = sample(now(), simplified());
    const playing = running(), allowed = safe(), phase = s.shot.phase, isRest = phase === 'rest';
    const motion = moving() && (playing || preview != null);
    document.body.dataset.shot = s.shot.id;
    document.body.dataset.phase = phase;
    document.body.dataset.storyRunning = String(playing);
    document.body.dataset.storyTime = s.elapsed.toFixed(3);
    document.body.dataset.speaking = String((playing || film) && !!s.cue);
    document.body.dataset.motion = String(motion);

    const info = scene.update(s.elapsed, s.cabin.values, {still: !moving() && preview == null, phase});
    const done = s.cabin.rows.filter(row => row.groupId !== 'support' && row.state === 'complete').length;
    text('#status', statusLine(s, done, info.speed));
    layoutLabels(s, playing || preview != null, phase === 'prepare' || phase === 'restore');

    if (lastShot !== s.shot.id) {
      lastShot = s.shot.id;
      text('#title', s.shot.title); text('#detail', s.shot.detail);
      const heading = $('.heading'); heading.classList.remove('swap'); void heading.offsetWidth; if (motion && !film) heading.classList.add('swap');
      text('#live', s.shot.title + '. ' + s.shot.detail); log('shot');
    }
    if (film) {
      const k = clamp((s.elapsed - s.shot.start) / 0.7, 0, 1), e = 1 - Math.pow(1 - k, 4);
      $('.heading').style.opacity = String(e * (isRest ? 0.55 : 1)); $('.heading').style.transform = 'translateY(' + ((1 - e) * 0.5).toFixed(3) + 'rem)';
      document.querySelectorAll('.voice i').forEach((bar, i) => { bar.style.height = (s.cue ? 0.35 + 0.95 * Math.abs(Math.sin(s.elapsed * 7 + i * 1.3)) : 0.35).toFixed(2) + 'rem'; });
    }
    $('#rest').hidden = !isRest;
    if (isRest) text('#rest-clock', timeLabel(Math.max(0, 1200 * (1 - (s.elapsed - s.shot.start) / (s.shot.end - s.shot.start)))));

    const recent = s.cue || [...data.cues].reverse().find(cue => s.elapsed >= cue.end && s.elapsed - cue.end <= 2.5);
    const speaker = recent ? recent.speaker : '깨비';
    $('#caption').dataset.speaker = speaker === '운전자' ? 'driver' : 'kkaebi';
    text('#caption-speaker', speaker);
    text('#caption-text', recent ? recent.text : isRest ? '편히 쉬세요. 알람에 맞춰 다시 부를게요.' : '');
    $('#caption').dataset.quiet = String(!recent && !isRest);
    const pose = isRest ? 'resting' : ['alarm', 'restore', 'recover'].includes(phase) ? 'recovery' : 'attentive';
    const path = '../../character/kkaebi-' + pose + '.webp';
    if ($('#companion').getAttribute('src') !== path) $('#companion').src = path;

    let chapter = 0; chapterList.forEach((item, index) => { if (s.elapsed >= item[1]) chapter = index; });
    $('#chapters').querySelectorAll('li').forEach((node, index) => { node.dataset.current = String(index === chapter); node.dataset.done = String(index < chapter); });
    text('#time', timeLabel(s.elapsed) + ' / ' + timeLabel(data.duration));
    $('#fill').style.width = s.elapsed / data.duration * 100 + '%';
    $('#timeline').setAttribute('aria-valuenow', String(Math.round(s.elapsed)));
    $('#timeline').setAttribute('aria-valuetext', timeLabel(s.elapsed) + ' · ' + chapterList[chapter][0]);
    text('#play', pending ? '음성 준비 중' : audioError ? '다시 재생' : s.finished || audio.ended ? '다시 보기' : playing ? '일시정지' : !started ? '통합 여정 시작' : '이어 보기');
    $('#play').disabled = !allowed || pending;
    $('#restart').disabled = !allowed || pending;
    const message = latched ? '정차·전경 상태가 바뀌어 시연을 멈췄어요. 앱으로 돌아갔다 다시 열어 주세요.' : !allowed ? hosted && !host ? '차량의 정차 상태를 확인하고 있어요.' : '정차·전경 상태를 확인할 때까지 시연을 멈춰요.' : audioError || (buffering && playing ? '음성을 불러오고 있어요. 장면도 같은 위치에서 기다려요.' : '');
    $('#notice').hidden = !message; text('#notice', message);
    text('#host', hosted ? 'P단 정차 시연 · 합성 대화' : '브라우저 시연 · 합성 대화');
  }

  async function play() {
    if (!safe() || pending) return;
    if (running()) { pause(); return; }
    if (preview != null) { audio.currentTime = clamp(preview, 0, data.duration - 0.1); preview = null; }
    if (audio.ended || audio.currentTime >= data.duration) audio.currentTime = 0;
    if (audioError) { audioError = ''; audio.load(); }
    pending = true; started = true;
    const request = ++playRequest; dirty = true;
    try {
      await audio.play();
      if (request !== playRequest || !safe()) { audio.pause(); return; }
      pending = false; log('play');
    } catch (error) {
      if (request !== playRequest) return;
      pending = false; audioError = '음성을 재생하지 못했어요. ‘다시 재생’을 눌러 주세요.'; audio.pause();
    }
    dirty = true;
  }
  function seek(seconds) {
    if (!safe() || pending) return;
    const target = clamp(seconds, 0, data.duration - 0.05);
    if (started) audio.currentTime = target; else preview = target;
    dirty = true;
  }
  $('#play').onclick = play;
  $('#restart').onclick = () => { if (!safe()) return; pause(); preview = null; audio.currentTime = 0; started = false; audioError = ''; buffering = false; dirty = true; };
  const timeline = $('#timeline');
  const seekFromPointer = event => { const box = timeline.getBoundingClientRect(); seek((event.clientX - box.left) / box.width * data.duration); };
  timeline.addEventListener('pointerdown', event => { timeline.setPointerCapture(event.pointerId); seekFromPointer(event); });
  timeline.addEventListener('pointermove', event => { if (timeline.hasPointerCapture(event.pointerId)) seekFromPointer(event); });
  timeline.addEventListener('keydown', event => { if (event.key === 'ArrowRight') seek(now() + 5); else if (event.key === 'ArrowLeft') seek(now() - 5); });
  $('#exit').addEventListener('click', pause);
  $('.details').addEventListener('click', pause);
  if (!hosted) $('#exit').href = '../index.html';
  audio.addEventListener('waiting', () => { buffering = true; dirty = true; });
  audio.addEventListener('playing', () => { buffering = false; dirty = true; });
  for (const name of ['pause', 'seeked']) audio.addEventListener(name, () => { dirty = true; });
  audio.addEventListener('ended', () => { pending = false; buffering = false; dirty = true; });
  audio.addEventListener('error', () => { pause(); audioError = '음성 파일을 불러오지 못했어요. ‘다시 재생’을 눌러 주세요.'; dirty = true; });
  document.addEventListener('visibilitychange', () => { if (document.hidden) { if (hosted && started) latched = true; pause(); } dirty = true; });
  addEventListener('pagehide', () => { pause(); cancelAnimationFrame(frame); });
  addEventListener('resize', () => { dirty = true; });
  reduced.addEventListener('change', () => { dirty = true; });
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { dirty = true; });

  function tick(time) {
    if (!safe() && !audio.paused) stopForHost();
    const live = running();
    if (live || dirty || !safe()) {
      if (live || dirty) {
        dirty = false; render();
        // 프레임이 계속 느리면 그림자와 해상도를 낮춘다.
        if (live && lastFrame) { slow = time - lastFrame > 45 ? slow + 1 : Math.max(0, slow - 1); if (slow > 40) { scene.setQuality(0); slow = -1e9; } }
      }
    }
    lastFrame = live ? time : 0;
    frame = requestAnimationFrame(tick);
  }
  root.kkaebiStorySnapshot = () => ({...sample(now(), simplified()), running: running(), hosted, safe: safe(), latched, started, pending, buffering, audioError, motion: moving() && running(), mediaTime: audio.currentTime, scene: scene.getDebug()});
  root.kkaebiPreview = seconds => { preview = seconds; dirty = true; };
  root.kkaebiRenderAt = seconds => { preview = seconds; dirty = false; render(); };
  audio.src = data.audio;
  frame = requestAnimationFrame(tick);
})(window);
