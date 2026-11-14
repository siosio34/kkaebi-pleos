'use strict';
/* 주 화면의 차량 장면. 통합 여정 시연과 같은 three.js 모형을 쓰고, 실제 차량에서 읽은 값이나 미리보기 값으로 움직인다.
   정차 중에는 쉼터에 선 차의 실내를, 주행 중에는 밤길을 달리는 차를 보여 준다. */
(function (root) {
  const defaults = () => ({seat: 0, slide: 0.3, light: 0.7, window: 0, blind: 0, fan: 0.3, temperature: 22, vent: 0});
  const lerp = (a, b, k) => a + (b - a) * k;
  const PARKED = [-6.6, 4.9, 5.6, 0, 0.35, 0.1], DRIVING = [7.6, 5.0, 10.6, 0.2, 0, -2.4], RESTING = [-4.6, 9.6, 6.2, 0, 0.2, 0];
  function create(canvas) {
    const scene = root.KkaebiJourneyScene.create(canvas);
    let current = defaults(), from = defaults(), target = defaults(), started = 0, duration = 0, frame = 0, options = {}, look = {resting: 0, warm: 0, drive: 0, route: 0}, lookTarget = Object.assign({}, look), last = 0;
    function context() { return root.kkaebiSceneContext || {}; }
    function paint(now) {
      const ctx = context(), driving = !!ctx.driving;
      const values = Object.assign({}, current, {hvacPower: current.fan > 0.02 && !driving ? 1 : 0, seat: current.seat * 0.8, window: current.window * 0.45});
      // 주행 장면은 시연 시간표의 순항 구간을 16초 주기로 돈다. 16초는 차선과 표지 간격의 공배수라 이음매가 보이지 않는다.
      const t = driving ? 22 + (options.motion ? (now / 1000) % 16 : 0) : 70;
      const k = look.drive;
      const cam = PARKED.map((v, i) => lerp(lerp(v, RESTING[i], look.resting), DRIVING[i], k));
      scene.update(t, values, {still: !options.motion, studio: {camera: cam, reveal: driving ? 0 : 1 - look.drive, resting: look.resting, warm: look.warm, route: look.route, shift: innerWidth > 520 ? (document.body.dataset.routine === 'true' ? 0.24 : 0.15) : 0}});
      if (root.kkaebiLayoutStage) root.kkaebiLayoutStage(scene.project);
    }
    function tick(now) {
      frame = 0;
      const k = duration > 0 ? Math.min(1, (now - started) / duration) : 1, e = 1 - Math.pow(1 - k, 3);
      for (const key of Object.keys(target)) current[key] = lerp(from[key], target[key], e);
      const step = Math.min(1, (now - last) / 600); last = now;
      let settling = k < 1;
      for (const key of Object.keys(look)) { const d = lookTarget[key] - look[key]; if (Math.abs(d) > 0.004) { look[key] += d * (options.motion ? step : 1); settling = true; } else look[key] = lookTarget[key]; }
      paint(now);
      if (options.motion && (settling || context().driving)) frame = requestAnimationFrame(tick);
    }
    function setState(next, opts) {
      options = opts || {};
      const ctx = context();
      from = Object.assign({}, current); target = Object.assign(defaults(), next || {});
      started = performance.now(); last = started; duration = options.motion ? (options.duration == null ? 900 : options.duration) : 0;
      const phase = options.phase || 'awake';
      lookTarget = {resting: phase === 'rest' ? 1 : 0, warm: phase === 'rest' ? 1 : phase === 'wake' ? 0.35 : target.light < 0.3 ? 0.8 : 0.15, drive: ctx.driving ? 1 : 0, route: ctx.guiding ? 1 : 0};
      if (!options.motion) { current = Object.assign({}, target); look = Object.assign({}, lookTarget); }
      if (!frame) frame = requestAnimationFrame(tick);
    }
    function stop() { if (frame) cancelAnimationFrame(frame); frame = 0; }
    addEventListener('resize', () => { if (!frame) frame = requestAnimationFrame(tick); });
    return {setState, stop, project: name => scene.project(name), getDebug: () => Object.assign({values: Object.assign({}, current), look: Object.assign({}, look)}, scene.getDebug())};
  }
  root.CabinScene = {create, defaults};
})(window);
