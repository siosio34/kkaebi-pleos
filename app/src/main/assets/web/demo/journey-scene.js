'use strict';
/* 통합 여정의 three.js 장면. 모든 상태는 재생 시각 t의 순수 함수라서 어느 지점으로 옮겨도 같은 그림이 나온다.
   차량과 쉼터는 손으로 만든 모형이며 실제 차종이나 장소를 측정한 것이 아니다. */
(function (root) {
  const T = root.THREE;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const lerp = (a, b, k) => a + (b - a) * k;
  const smooth = (a, b, v) => { const k = clamp((v - a) / (b - a), 0, 1); return k * k * (3 - 2 * k); };

  // 속도(m/s)와 누적 이동 거리. 57초에 멈추고 134초에 다시 움직인다.
  const STOP_AT = 57, GO_AT = 134, DURATION = 150, STEP = 0.05;
  const speedAt = t => 27 * (1 - smooth(46, STOP_AT, t)) + 24 * smooth(GO_AT, 143, t);
  const travelled = [0];
  for (let i = 1; i <= DURATION / STEP; i++) travelled.push(travelled[i - 1] + speedAt(i * STEP) * STEP);
  const distanceAt = t => { const f = clamp(t, 0, DURATION) / STEP, i = Math.floor(f); return lerp(travelled[i], travelled[Math.min(i + 1, travelled.length - 1)], f - i); };
  const STOP_DISTANCE = distanceAt(STOP_AT), ROUTE_FROM = 14;

  const CAMERA = [
    [0, 9.5, 7.6, 13.5, 0, 0.6, -2.5],
    [5, 6.4, 4.6, 8.6, 0, 0.7, -1],
    [14, -5.2, 2.6, 5.6, -0.1, 0.8, -0.6],
    [20, 3.2, 5.6, 8.8, 0.2, -1.6, -5.5],
    [27, -6.4, 2.2, 1.6, 0, 0.8, -0.1],
    [39, -5.4, 3.0, -4.6, 0, 0.7, 0.1],
    [50, 6.4, 5.2, 8.4, 0.4, 0.5, -2.2],
    [58, 5.6, 4.2, 6.8, 0, 0.6, -0.2],
    [64, -5.2, 3.8, 4.2, 0, 0.7, 0.1],
    [72, -4.6, 3.5, 3.5, 0, 0.75, 0.2],
    [78, -4.2, 4.4, 3.8, 0, 0.6, 0.25],
    [85, 0.4, 9.6, 3.4, 0, 0.4, -1.5],
    [91, -1.6, 7.6, 4.6, 0, 0.5, -0.8],
    [99, -4.8, 3.9, 3.8, 0, 0.7, 0.2],
    [110, -4.5, 3.5, 3.4, 0, 0.75, 0.2],
    [116, -6.0, 2.8, -3.2, 0, 0.7, 0.1],
    [128, 4.8, 3.4, -6.4, 0, 0.7, 0],
    [136, 6.4, 4.2, 8.4, 0, 0.6, -1],
    [144, 9.5, 7.6, 13.5, 0, 0.6, -2.5],
    [150, 10.5, 8.4, 15, 0, 0.6, -3]
  ];

  function cameraAt(t, still) {
    let i = 0;
    while (i < CAMERA.length - 2 && t >= CAMERA[i + 1][0]) i++;
    const a = CAMERA[i], b = CAMERA[i + 1];
    const k = still ? 0 : smooth(a[0], b[0], t);
    const c = a.map((v, n) => lerp(v, b[n], k));
    c[5] -= 0.45; // 아래쪽 자막 영역만큼 차를 화면 위로 올린다.
    return c;
  }

  function create(canvas) {
    const renderer = new T.WebGLRenderer({canvas, antialias: true, powerPreference: 'high-performance'});
    renderer.outputEncoding = T.sRGBEncoding;
    renderer.toneMapping = T.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.15;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = T.PCFSoftShadowMap;
    renderer.localClippingEnabled = true;
    const NIGHT = new T.Color(0x060a13);
    const scene = new T.Scene();
    scene.background = NIGHT.clone();
    scene.fog = new T.FogExp2(NIGHT.getHex(), 0.021);
    const camera = new T.PerspectiveCamera(34, 16 / 9, 0.1, 400);
    (function () {
      // 반사에 쓸 밤하늘과 부드러운 조명판. 화면에는 직접 보이지 않는다.
      const sky = new T.Scene();
      const dome = new T.Mesh(new T.SphereGeometry(50, 24, 16), new T.MeshBasicMaterial({color: 0x0a111e, side: T.BackSide})); sky.add(dome);
      const card = (color, w, h, x, y, z) => { const m = new T.Mesh(new T.PlaneGeometry(w, h), new T.MeshBasicMaterial({color, side: T.DoubleSide})); m.position.set(x, y, z); m.lookAt(0, 0, 0); sky.add(m); };
      card(0x8fa9cf, 30, 6, 0, 20, -30); card(0x3d5273, 40, 10, -30, 12, 10); card(0xf1c58e, 8, 3, 22, 14, 14); card(0x27364d, 60, 8, 0, 2, 40);
      const pmrem = new T.PMREMGenerator(renderer);
      scene.environment = pmrem.fromScene(sky, 0.04).texture; pmrem.dispose();
    })();

    const mat = (color, o = {}) => new T.MeshStandardMaterial(Object.assign({color, roughness: 0.8, metalness: 0}, o));
    const paint = new T.MeshPhysicalMaterial({color: 0x38475a, roughness: 0.26, metalness: 0.8, clearcoat: 1, clearcoatRoughness: 0.12, side: T.DoubleSide});
    const trim = mat(0x1a1f28, {roughness: 0.7});
    const carpet = mat(0x12151b, {roughness: 1});
    const fabric = mat(0x77716a, {roughness: 0.95});
    const fabricDark = mat(0x4c4844, {roughness: 0.95});
    const rubber = mat(0x08090b, {roughness: 0.9});
    const rim = mat(0x9aa1ab, {roughness: 0.35, metalness: 0.9});
    const glass = new T.MeshPhysicalMaterial({color: 0x9cc3e6, roughness: 0.05, metalness: 0, transparent: true, opacity: 0.13, side: T.DoubleSide, depthWrite: false});
    const blindMat = mat(0x4a443e, {roughness: 1, side: T.DoubleSide, transparent: true, opacity: 0.9});

    function rbox(w, h, d, r, material) {
      const b = Math.min(r, 0.035, d / 2 - 0.001), iw = w - 2 * b, ih = h - 2 * b, cr = Math.max(0.001, Math.min(r - b, iw / 2, ih / 2));
      const s = new T.Shape();
      s.moveTo(-iw / 2 + cr, -ih / 2); s.lineTo(iw / 2 - cr, -ih / 2); s.quadraticCurveTo(iw / 2, -ih / 2, iw / 2, -ih / 2 + cr);
      s.lineTo(iw / 2, ih / 2 - cr); s.quadraticCurveTo(iw / 2, ih / 2, iw / 2 - cr, ih / 2);
      s.lineTo(-iw / 2 + cr, ih / 2); s.quadraticCurveTo(-iw / 2, ih / 2, -iw / 2, ih / 2 - cr);
      s.lineTo(-iw / 2, -ih / 2 + cr); s.quadraticCurveTo(-iw / 2, -ih / 2, -iw / 2 + cr, -ih / 2);
      const g = new T.ExtrudeGeometry(s, {depth: d - 2 * b, bevelEnabled: true, bevelThickness: b, bevelSize: b, bevelSegments: 3, curveSegments: 5});
      g.center();
      const m = new T.Mesh(g, material); m.castShadow = true; m.receiveShadow = true; return m;
    }
    function box(w, h, d, material) { const m = new T.Mesh(new T.BoxGeometry(w, h, d), material); m.castShadow = true; m.receiveShadow = true; return m; }
    function rod(a, b, r, material) {
      const A = new T.Vector3(...a), B = new T.Vector3(...b), d = B.clone().sub(A);
      const m = new T.Mesh(new T.CylinderGeometry(r, r, d.length(), 10), material);
      m.position.copy(A).add(B).multiplyScalar(0.5);
      m.quaternion.setFromUnitVectors(new T.Vector3(0, 1, 0), d.normalize());
      m.castShadow = true; return m;
    }
    // (z, y) 옆모습을 차폭 방향으로 밀어낸 판.
    function profile(points, thickness, x, material) {
      const s = points instanceof T.Shape ? points : new T.Shape(points.map(p => new T.Vector2(p[0], p[1])));
      const g = thickness ? new T.ExtrudeGeometry(s, {depth: thickness, bevelEnabled: false, curveSegments: 20}) : new T.ShapeGeometry(s);
      const m = new T.Mesh(g, material);
      m.rotation.y = -Math.PI / 2; m.position.x = x + thickness / 2; m.castShadow = !!thickness; m.receiveShadow = !!thickness; return m;
    }

    // ── 환경: 고속도로와 쉼터 ─────────────────────────────────────────
    const env = new T.Group(); scene.add(env);
    const asphalt = new T.Mesh(new T.PlaneGeometry(60, 260), mat(0x0c1017, {roughness: 0.88}));
    asphalt.rotation.x = -Math.PI / 2; asphalt.position.set(6, 0, -70); asphalt.receiveShadow = true; env.add(asphalt);
    const lineMat = new T.MeshBasicMaterial({color: 0xc9d2de});
    const yellowMat = new T.MeshBasicMaterial({color: 0xb9973c});
    const moving = new T.Group(); env.add(moving);
    const DASH = 12, POST = 16;
    const dashes = new T.Group(), posts = new T.Group(); moving.add(dashes, posts);
    for (let i = -16; i < 5; i++) { const d = new T.Mesh(new T.BoxGeometry(0.14, 0.012, 3.2), lineMat); d.position.set(-1.8, 0.006, i * DASH); dashes.add(d); }
    const reflector = new T.MeshBasicMaterial({color: 0xffc46b});
    for (let i = -12; i < 4; i++) {
      const p = new T.Mesh(new T.CylinderGeometry(0.035, 0.035, 0.9, 8), mat(0x3a4250)); p.position.set(3.9, 0.45, i * POST); posts.add(p);
      const r = new T.Mesh(new T.BoxGeometry(0.07, 0.12, 0.02), reflector); r.position.set(3.9, 0.82, i * POST + 0.04); posts.add(r);
    }
    for (const [x, m] of [[1.8, lineMat], [-5.4, yellowMat]]) { const l = new T.Mesh(new T.BoxGeometry(0.14, 0.012, 260), m); l.position.set(x, 0.006, -70); env.add(l); }
    const median = box(0.5, 0.8, 260, mat(0x222a35, {roughness: 0.95})); median.position.set(-6.2, 0.4, -70); env.add(median);

    const rest = new T.Group(); rest.position.x = 5.4; env.add(rest);
    for (const x of [-4.5, -1.5, 1.5, 4.5]) { const l = new T.Mesh(new T.BoxGeometry(0.11, 0.012, 5.4), lineMat); l.position.set(x, 0.007, 0.1); rest.add(l); }
    const curb = box(13, 0.16, 0.34, mat(0x39414d, {roughness: 0.95})); curb.position.set(0, 0.08, -3.3); rest.add(curb);
    const hedge = rbox(13, 1.0, 0.9, 0.3, mat(0x0d1a16, {roughness: 1})); hedge.position.set(0, 0.6, -4.1); rest.add(hedge);
    const poleMat = mat(0x39424f, {roughness: 0.5, metalness: 0.6});
    rest.add(rod([3.1, 0, -3.5], [3.1, 4.4, -3.5], 0.05, poleMat), rod([3.1, 4.4, -3.5], [2.2, 4.5, -3.0], 0.035, poleMat));
    const lampHead = new T.Mesh(new T.BoxGeometry(0.5, 0.06, 0.22), new T.MeshBasicMaterial({color: 0xffd9a0})); lampHead.position.set(2.1, 4.47, -2.95); lampHead.rotation.y = -0.5; rest.add(lampHead);
    const lamp = new T.SpotLight(0xffd3a1, 0, 22, 0.85, 0.85, 1.3);
    lamp.position.set(2.1, 4.4, -2.95); lamp.target.position.set(0.2, 0, 0.2); lamp.castShadow = true;
    lamp.shadow.mapSize.set(1024, 1024); lamp.shadow.bias = -0.0015; lamp.shadow.radius = 5; rest.add(lamp, lamp.target);
    const signCanvas = document.createElement('canvas'); signCanvas.width = 512; signCanvas.height = 192;
    const signTexture = new T.CanvasTexture(signCanvas); signTexture.encoding = T.sRGBEncoding;
    function drawSign() {
      const c = signCanvas.getContext('2d');
      c.fillStyle = '#17467f'; c.fillRect(0, 0, 512, 192);
      c.strokeStyle = '#e8eef6'; c.lineWidth = 6; c.strokeRect(9, 9, 494, 174);
      c.fillStyle = '#f2f5f9'; c.textBaseline = 'middle'; c.textAlign = 'center';
      c.font = '600 84px Pretendard, "Apple SD Gothic Neo", sans-serif'; c.fillText('졸음쉼터', 256, 100);
      signTexture.needsUpdate = true;
    }
    drawSign(); if (document.fonts && document.fonts.load) document.fonts.load('600 84px Pretendard').then(drawSign, () => {});
    const sign = new T.Mesh(new T.PlaneGeometry(1.9, 0.71), new T.MeshBasicMaterial({map: signTexture})); sign.position.set(-3.3, 2.5, -3.55); rest.add(sign);
    rest.add(rod([-3.3, 0, -3.6], [-3.3, 2.2, -3.6], 0.045, poleMat));
    const mint = 0x7fe3c8;
    const marker = new T.Group(); marker.position.set(-2.4, 0, 0.4); rest.add(marker);
    const markerMat = new T.MeshBasicMaterial({color: mint, transparent: true, opacity: 0.9});
    marker.add(rod([0, 0, 0], [0, 3.4, 0], 0.025, markerMat));
    const ring = new T.Mesh(new T.TorusGeometry(0.34, 0.03, 8, 40), markerMat); ring.position.y = 3.75; marker.add(ring);
    const markerTop = new T.Object3D(); markerTop.position.y = 4.3; marker.add(markerTop);
    const ribbonMat = new T.MeshBasicMaterial({color: 0x35d9ae, transparent: true, opacity: 0, depthWrite: false, toneMapped: false, fog: false});
    const ribbon = new T.Mesh(new T.PlaneGeometry(0.3, 1), ribbonMat); ribbon.rotation.x = -Math.PI / 2; ribbon.position.y = 0.03; scene.add(ribbon);
    const routeEnd = new T.Object3D(); scene.add(routeEnd);

    // ── 차량 ────────────────────────────────────────────────────────
    const car = new T.Group(); scene.add(car);
    // 길이 방향으로 단면(둥근 사각형)을 이어 붙여 매끈한 차체를 만든다.
    function loft(stations, exponent, material) {
      const M = 40, pos = [], idx = [];
      for (const [z, w, cy, h] of stations) for (let k = 0; k < M; k++) {
        const a = k / M * Math.PI * 2, c = Math.cos(a), sn = Math.sin(a), e = 2 / exponent;
        pos.push(Math.sign(c) * Math.pow(Math.abs(c), e) * w, cy + Math.sign(sn) * Math.pow(Math.abs(sn), e) * h, z);
      }
      const R = stations.length;
      for (let r = 0; r < R - 1; r++) for (let k = 0; k < M; k++) {
        const a = r * M + k, b = r * M + (k + 1) % M, c = a + M, d = b + M;
        idx.push(a, b, c, b, d, c);
      }
      for (const [r, flip] of [[0, false], [R - 1, true]]) {
        const z = stations[r][0], centre = pos.length / 3; pos.push(0, stations[r][2], z);
        for (let k = 0; k < M; k++) { const a = r * M + k, b = r * M + (k + 1) % M; if (flip) idx.push(centre, a, b); else idx.push(centre, b, a); }
      }
      const g = new T.BufferGeometry();
      g.setAttribute('position', new T.Float32BufferAttribute(pos, 3)); g.setIndex(idx); g.computeVertexNormals();
      const m = new T.Mesh(g, material); m.castShadow = true; m.renderOrder = 2; return m;
    }
    // [z, 반폭, 중심 높이, 반높이]. 긴 보닛과 반듯한 어깨선, 수직에 가까운 앞면을 가진 대형 세단 비례.
    const body = loft([[-2.52, 0.8, 0.5, 0.2], [-2.47, 0.9, 0.52, 0.29], [-2.2, 0.935, 0.54, 0.33], [-1.5, 0.945, 0.57, 0.37], [-1.0, 0.95, 0.59, 0.39], [0, 0.95, 0.6, 0.4], [1.3, 0.95, 0.6, 0.4], [2.0, 0.93, 0.6, 0.39], [2.4, 0.88, 0.59, 0.35], [2.5, 0.76, 0.58, 0.27]], 4.8, paint);
    car.add(body);
    const canopyMat = new T.MeshPhysicalMaterial({color: 0x121a25, roughness: 0.05, metalness: 0.2, clearcoat: 1, clearcoatRoughness: 0.04, transparent: true, opacity: 0.88, depthWrite: false, side: T.DoubleSide});
    const canopy = loft([[-1.2, 0.6, 0.92, 0.03], [-0.95, 0.76, 0.92, 0.2], [-0.35, 0.8, 0.92, 0.47], [0.3, 0.8, 0.92, 0.54], [1.1, 0.79, 0.92, 0.52], [1.6, 0.76, 0.92, 0.36], [1.95, 0.7, 0.92, 0.1]], 3.2, canopyMat);
    canopy.castShadow = false; canopy.renderOrder = 3; car.add(canopy);
    // 지붕은 유리 덮개와 같은 곡면을 위쪽만 잘라 차체 색으로 덮는다.
    const roofMat = paint.clone(); roofMat.clippingPlanes = [new T.Plane(new T.Vector3(0, 1, 0), -1.3)]; roofMat.side = T.FrontSide;
    const roof = new T.Mesh(canopy.geometry, roofMat); roof.scale.set(1.006, 1.004, 1.004); roof.position.y = -0.003; roof.castShadow = true; roof.renderOrder = 4; car.add(roof);
    const chrome = mat(0xc9d0d8, {roughness: 0.18, metalness: 1});
    for (const x of [-0.955, 0.955]) { const strip = box(0.012, 0.014, 2.5, chrome); strip.position.set(x, 0.985, 0.35); strip.castShadow = false; car.add(strip); }
    for (const x of [-1.0, 1.0]) { const mirror = rbox(0.1, 0.09, 0.17, 0.04, paint); mirror.position.set(x, 1.02, -0.78); car.add(mirror); }
    const grilleCanvas = document.createElement('canvas'); grilleCanvas.width = 512; grilleCanvas.height = 128;
    (function () { const c = grilleCanvas.getContext('2d'); c.fillStyle = '#07090c'; c.fillRect(0, 0, 512, 128); c.fillStyle = '#39424d'; for (let y = 8; y < 128; y += 12) for (let x = (y / 12 % 2) * 9; x < 512; x += 18) c.fillRect(x, y, 9, 4); })();
    const grilleTexture = new T.CanvasTexture(grilleCanvas); grilleTexture.encoding = T.sRGBEncoding;
    const grille = new T.Mesh(new T.PlaneGeometry(1.34, 0.3), new T.MeshStandardMaterial({map: grilleTexture, roughness: 0.4, metalness: 0.6})); grille.position.set(0, 0.47, -2.524); grille.rotation.y = Math.PI; car.add(grille);
    const floor = box(1.66, 0.06, 2.6, carpet); floor.position.set(0, 0.23, 0.27); car.add(floor);
    const headMat = new T.MeshBasicMaterial({color: 0xf4f7ff}), tailMat = new T.MeshBasicMaterial({color: 0x7a1010});
    const headBar = new T.Mesh(new T.BoxGeometry(1.56, 0.022, 0.03), headMat); headBar.position.set(0, 0.675, -2.52); car.add(headBar);
    for (const x of [-0.62, 0.62]) { const lamp2 = new T.Mesh(new T.BoxGeometry(0.2, 0.09, 0.02), headMat); lamp2.position.set(x, 0.5, -2.527); car.add(lamp2); }
    const tailBar = new T.Mesh(new T.BoxGeometry(1.46, 0.028, 0.03), tailMat); tailBar.position.set(0, 0.79, 2.505); car.add(tailBar);
    const wheels = [];
    const archMat = new T.MeshBasicMaterial({color: 0x04060a});
    for (const x of [-0.85, 0.85]) for (const z of [-1.55, 1.36]) {
      const w = new T.Group(); w.position.set(x, 0.345, z);
      const tire = new T.Mesh(new T.CylinderGeometry(0.345, 0.345, 0.26, 40), rubber); tire.rotation.z = Math.PI / 2; tire.castShadow = true;
      const disc = new T.Mesh(new T.CylinderGeometry(0.245, 0.245, 0.27, 32), rim); disc.rotation.z = Math.PI / 2;
      const hub = new T.Mesh(new T.CylinderGeometry(0.06, 0.06, 0.28, 12), rubber); hub.rotation.z = Math.PI / 2;
      for (let k = 0; k < 5; k++) { const a = k / 5 * Math.PI * 2, slot = new T.Mesh(new T.CylinderGeometry(0.045, 0.045, 0.275, 12), rubber); slot.rotation.z = Math.PI / 2; slot.position.set(0, Math.cos(a) * 0.15, Math.sin(a) * 0.15); w.add(slot); }
      w.add(tire, disc, hub); car.add(w); wheels.push(w);
      const arch = new T.Mesh(new T.CylinderGeometry(0.405, 0.405, 0.2, 40), archMat); arch.rotation.z = Math.PI / 2; arch.position.set(x * 0.985, 0.345, z); car.add(arch);
    }
    const BELT = 0.97;
    const frontPane = [[-0.86, 0], [0.16, 0.015], [0.16, 0.4], [-0.34, 0.4]], rearPane = [[0.24, 0.015], [1.3, 0.03], [1.02, 0.4], [0.24, 0.4]];
    const blinds = [];
    const paneMat = new T.MeshBasicMaterial({color: 0x9cc3e6, transparent: true, opacity: 0.1, side: T.DoubleSide, depthWrite: false});
    const driverWindow = profile(frontPane, 0, -0.76, paneMat); driverWindow.position.y = BELT; car.add(driverWindow);
    for (const x of [-0.74, 0.74]) for (const pane of [frontPane, rearPane]) { const b = profile(pane, 0, x, blindMat); b.position.y = BELT; b.visible = false; car.add(b); blinds.push(b); }
    const rearBlind = new T.Mesh(new T.PlaneGeometry(1.36, 0.5), blindMat); rearBlind.geometry.translate(0, 0.25, 0); rearBlind.position.set(0, 0.98, 1.52); rearBlind.rotation.x = -0.75; rearBlind.visible = false; car.add(rearBlind); blinds.push(rearBlind);

    // 실내
    const dash = rbox(1.62, 0.34, 0.44, 0.1, trim); dash.position.set(0, 0.76, -0.84); car.add(dash);
    const screenCanvas = document.createElement('canvas'); screenCanvas.width = 512; screenCanvas.height = 160;
    (function () {
      const c = screenCanvas.getContext('2d');
      c.fillStyle = '#0b1622'; c.fillRect(0, 0, 512, 160);
      c.strokeStyle = '#7fe3c8'; c.lineWidth = 7; c.lineCap = 'round'; c.lineJoin = 'round';
      c.beginPath(); c.moveTo(60, 128); c.lineTo(180, 80); c.lineTo(180, 44); c.lineTo(300, 44); c.stroke();
      c.fillStyle = '#7fe3c8'; c.beginPath(); c.arc(318, 44, 13, 0, 7); c.fill();
      c.fillStyle = '#33465c'; c.fillRect(360, 40, 110, 10); c.fillRect(360, 66, 78, 10); c.fillRect(360, 110, 96, 10);
    })();
    const screenTexture = new T.CanvasTexture(screenCanvas); screenTexture.encoding = T.sRGBEncoding;
    const screenMat = new T.MeshBasicMaterial({map: screenTexture});
    const display = new T.Mesh(new T.PlaneGeometry(0.44, 0.26), screenMat); display.position.set(0.02, 1.0, -0.655); display.rotation.x = -0.2; car.add(display);
    const cluster = new T.Mesh(new T.PlaneGeometry(0.3, 0.085), screenMat); cluster.position.set(-0.4, 0.985, -0.7); cluster.rotation.x = -0.2; car.add(cluster);
    const displayBack = rbox(0.47, 0.29, 0.025, 0.02, trim); displayBack.position.set(0.02, 1.0, -0.67); displayBack.rotation.x = -0.2; car.add(displayBack);
    const wheel = new T.Mesh(new T.TorusGeometry(0.175, 0.018, 10, 40), mat(0x0f1217, {roughness: 0.6})); wheel.position.set(-0.4, 0.94, -0.52); wheel.rotation.x = -0.42; car.add(wheel);
    car.add(rod([-0.4, 0.94, -0.52], [-0.4, 0.82, -0.78], 0.03, trim));
    const consoleBox = rbox(0.26, 0.26, 1.2, 0.06, trim); consoleBox.position.set(0, 0.39, 0.05); car.add(consoleBox);
    const stripMat = new T.MeshBasicMaterial({color: 0xffc98a});
    const strips = [];
    const dashStrip = new T.Mesh(new T.BoxGeometry(1.5, 0.012, 0.012), stripMat); dashStrip.position.set(0, 0.69, -0.615); car.add(dashStrip); strips.push(dashStrip);
    for (const x of [-0.8, 0.8]) { const s = new T.Mesh(new T.BoxGeometry(0.012, 0.012, 2.1), stripMat); s.position.set(x, 0.9, 0.2); car.add(s); strips.push(s); }
    function seat(x, z) {
      const g = new T.Group(); g.position.set(x, 0, z);
      const base = rbox(0.5, 0.16, 0.54, 0.07, fabricDark); base.position.set(0, 0.36, 0); g.add(base);
      const cushion = rbox(0.48, 0.1, 0.5, 0.05, fabric); cushion.position.set(0, 0.47, -0.01); cushion.rotation.x = -0.06; g.add(cushion);
      const pivot = new T.Group(); pivot.position.set(0, 0.5, 0.25); g.add(pivot);
      const back = rbox(0.48, 0.64, 0.13, 0.06, fabric); back.position.set(0, 0.33, 0); pivot.add(back);
      for (const sx of [-0.2, 0.2]) { const bolster = rbox(0.08, 0.52, 0.16, 0.04, fabricDark); bolster.position.set(sx, 0.3, -0.02); pivot.add(bolster); }
      const head = rbox(0.26, 0.2, 0.11, 0.05, fabricDark); head.position.set(0, 0.8, -0.01); pivot.add(head);
      pivot.add(rod([-0.06, 0.64, 0], [-0.06, 0.72, 0], 0.008, rim), rod([0.06, 0.64, 0], [0.06, 0.72, 0], 0.008, rim));
      const top = new T.Object3D(); top.position.set(0, 0.92, 0); pivot.add(top);
      const seatTop = new T.Object3D(); seatTop.position.set(0, 0.56, -0.05); g.add(seatTop);
      g.userData = {pivot, top, seatTop}; pivot.rotation.x = 0.3; car.add(g); return g;
    }
    const driver = seat(-0.4, 0.12); seat(0.4, 0.12);
    const bench = rbox(1.36, 0.18, 0.5, 0.07, fabric); bench.position.set(0, 0.42, 1.08); car.add(bench);
    const benchBack = rbox(1.36, 0.58, 0.13, 0.06, fabric); benchBack.position.set(0, 0.78, 1.38); benchBack.rotation.x = 0.3; car.add(benchBack);

    // 공조 바람과 시트 통풍
    const airMat = new T.MeshBasicMaterial({color: 0xbff4e6, transparent: true, opacity: 0, blending: T.AdditiveBlending, depthWrite: false});
    const air = [];
    for (let i = 0; i < 30; i++) { const m = new T.Mesh(new T.BoxGeometry(0.006, 0.006, 0.2), airMat.clone()); m.userData = {phase: (i * 0.618) % 1, lane: ((i * 7) % 5 - 2) * 0.07, lift: ((i * 3) % 4) * 0.04}; car.add(m); air.push(m); }
    const vent = [];
    for (let i = 0; i < 12; i++) { const m = new T.Mesh(new T.SphereGeometry(0.012, 6, 6), airMat.clone()); m.userData = {phase: (i * 0.381) % 1, x: ((i * 5) % 7 - 3) * 0.055, z: ((i * 3) % 5 - 2) * 0.08}; car.add(m); vent.push(m); }

    // 닻: 라벨이 가리키는 부품 위치
    const point = (x, y, z, parent) => { const o = new T.Object3D(); o.position.set(x, y, z); (parent || car).add(o); return o; };
    const anchors = {
      seatRecline: driver.userData.top,
      seatVentilation: driver.userData.seatTop,
      window: point(-0.76, 1.28, -0.3),
      blind: point(-0.74, 1.2, 0.75),
      brightness: point(0.5, 0.7, -0.6),
      climate: point(-0.1, 0.9, -0.64),
      marker: routeEnd
    };

    // 조명
    const hemi = new T.HemisphereLight(0x8fa8d0, 0x0a0d12, 0.5); scene.add(hemi);
    const moon = new T.DirectionalLight(0x9db7e6, 0.55); moon.position.set(-6, 10, 5); scene.add(moon);
    const cabin = new T.PointLight(0xffc98a, 1, 5.5, 1.6); cabin.position.set(0, 1.3, 0.15); car.add(cabin);
    const screenGlow = new T.PointLight(mint, 0.5, 1.8, 2); screenGlow.position.set(0.02, 1.02, -0.5); car.add(screenGlow);
    const beams = [];
    for (const x of [-0.62, 0.62]) {
      const s = new T.SpotLight(0xdfe9ff, 0, 60, 0.42, 0.7, 1.1); s.position.set(x, 0.6, -2.5); s.target.position.set(x * 1.4, 0, -22); car.add(s, s.target); beams.push(s);
    }
    const cool = new T.Color(0xdfe7f5), warm = new T.Color(0xffb866), tmpColor = new T.Color();

    let quality = 1, width = 0, height = 0;
    function resize() {
      const w = canvas.clientWidth, h = canvas.clientHeight;
      if (!w || !h || (w === width && h === height)) return;
      width = w; height = h;
      renderer.setPixelRatio(Math.min(root.devicePixelRatio || 1, quality > 0 ? 2 : 1));
      renderer.setSize(w, h, false);
      camera.aspect = w / h;
      // 세로가 좁은 차량 화면에서는 화각을 넓혀 차가 잘리지 않게 한다.
      camera.fov = clamp(34 * (16 / 9) / Math.max(camera.aspect, 1.2) * 0.92 + 3, 30, 46);
      camera.updateProjectionMatrix();
    }
    function setQuality(level) { quality = level; renderer.shadowMap.enabled = level > 0; lamp.castShadow = level > 0; width = 0; resize(); }

    const projected = new T.Vector3();
    function project(name) {
      const o = anchors[name]; if (!o) return null;
      o.getWorldPosition(projected); projected.project(camera);
      return {x: (projected.x + 1) / 2, y: (1 - projected.y) / 2, visible: projected.z < 1 && projected.z > -1};
    }

    function update(t, values, opts) {
      opts = opts || {};
      resize();
      const still = !!opts.still, v = values || {};
      const moved = distanceAt(t), park = smooth(47, 56.5, t) - smooth(GO_AT, 142, t);
      const ahead = STOP_DISTANCE - moved;
      env.position.x = -park * 5.4;
      dashes.position.z = moved % DASH; posts.position.z = moved % POST;
      rest.position.z = -ahead;
      for (const w of wheels) w.rotation.x = -moved / 0.345;

      const phase = opts.phase || 'drive';
      const resting = smooth(76, 82, t) - smooth(97, 104, t);
      const driving = clamp(speedAt(t) / 20, 0, 1);
      const light = v.light == null ? 0.72 : v.light;
      const sleepy = smooth(64, 72, t) - smooth(102, 110, t);
      tmpColor.copy(cool).lerp(warm, sleepy);
      cabin.color.copy(tmpColor); cabin.intensity = 0.22 + light * 1.5;
      for (const s of strips) s.material.color.copy(tmpColor).multiplyScalar(0.25 + light * 0.95);
      hemi.intensity = lerp(0.5, 0.16, resting);
      moon.intensity = lerp(0.55, 0.2, resting);
      lamp.intensity = lerp(0, 1.9, park) * lerp(1, 0.4, resting);
      lampHead.material.color.setHex(0xffd9a0).multiplyScalar(lerp(1, 0.55, resting));
      for (const b of beams) b.intensity = 5.5 * Math.max(driving, 0.25 * (1 - smooth(57, 62, t) + smooth(130, 134, t)));
      headMat.color.setHex(0xf4f7ff).multiplyScalar(lerp(0.12, 1, Math.max(driving, 1 - smooth(57, 62, t) + smooth(130, 134, t))));
      const braking = smooth(46, 49, t) - smooth(57, 60, t);
      tailMat.color.setRGB(lerp(lerp(0.12, 0.5, Math.max(driving, 1 - smooth(57, 62, t) + smooth(130, 134, t))), 1, braking), 0.04, 0.04);
      screenMat.color.setScalar(lerp(1, 0.18, resting)); screenGlow.intensity = lerp(0.5, 0.06, resting);
      renderer.toneMappingExposure = lerp(1.15, 0.92, resting);

      const reveal = smooth(60, 66, t) - smooth(128, 134, t);
      paint.transparent = reveal > 0.001; paint.opacity = lerp(1, 0.2, reveal); paint.depthWrite = reveal < 0.5; body.castShadow = reveal < 0.5;
      canopyMat.opacity = lerp(0.88, 0.09, reveal);
      roofMat.transparent = reveal > 0.001; roofMat.opacity = 1 - smooth(0, 0.6, reveal); roofMat.depthWrite = reveal < 0.3; roofMat.clearcoat = 1 - reveal; roofMat.envMapIntensity = 1 - reveal; roof.visible = reveal < 0.6; roof.castShadow = reveal < 0.3;
      tailMat.color.multiplyScalar(1 - reveal * 0.75);

      const seatValue = v.seat || 0;
      driver.userData.pivot.rotation.x = 0.3 + seatValue * 1.02;
      driver.position.z = 0.12 + seatValue * 0.1;
      const blind = v.blind || 0;
      for (const b of blinds) { b.visible = blind > 0.01; b.scale.y = Math.max(blind, 0.01); }
      driverWindow.position.y = BELT - (v.window == null ? 0.45 : v.window) * 0.44;

      const flow = (v.hvacPower || 0) * (v.fan == null ? 0.5 : v.fan);
      const clock = still ? 0 : t;
      for (const m of air) {
        const u = (clock * 0.55 + m.userData.phase) % 1;
        m.position.set(-0.4 + m.userData.lane, 0.93 + Math.sin(u * Math.PI) * (0.16 + m.userData.lift), -0.62 + u * 1.25);
        m.material.opacity = flow * 0.9 * Math.sin(u * Math.PI) * (still ? 0.6 : 1); m.visible = flow > 0.01;
      }
      const ventValue = v.vent || 0;
      for (const m of vent) {
        const u = (clock * 0.4 + m.userData.phase) % 1;
        m.position.set(-0.4 + m.userData.x, 0.55 + u * 0.26, 0.1 + driver.position.z - 0.12 + m.userData.z);
        m.material.opacity = ventValue * 2.2 * (1 - u) * (still ? 0.6 : 1); m.visible = ventValue > 0.01;
      }

      const route = smooth(ROUTE_FROM, ROUTE_FROM + 2.5, t) * (1 - smooth(46, 50, t));
      ribbonMat.opacity = route * 0.8;
      const reach = clamp(ahead - 6, 1, 130);
      ribbon.scale.y = reach; ribbon.position.z = -2.8 - reach / 2; ribbon.visible = route > 0.01;
      const labelReach = Math.min(reach, 26); routeEnd.position.set(ahead < 60 ? env.position.x + 5.4 - 2.4 : 0, ahead < 60 ? 4.4 : 1.6, -2.6 - labelReach);
      markerMat.opacity = 0.9 * smooth(ROUTE_FROM, ROUTE_FROM + 2, t) * (1 - smooth(50, 54, t));
      marker.visible = markerMat.opacity > 0.01;

      const c = cameraAt(t, still);
      camera.position.set(c[1], c[2], c[3]); camera.lookAt(c[4], c[5], c[6]);
      renderer.render(scene, camera);
      return {phase, park, ahead, speed: speedAt(t)};
    }

    return {update, project, resize, setQuality, routeDistance: t => clamp((STOP_DISTANCE - distanceAt(t)) / (STOP_DISTANCE - distanceAt(ROUTE_FROM)), 0, 1), speedAt, getDebug: () => ({quality, calls: renderer.info.render.calls, triangles: renderer.info.render.triangles})};
  }

  const api = {create, speedAt, STOP_AT, GO_AT};
  root.KkaebiJourneyScene = api;
})(window);
