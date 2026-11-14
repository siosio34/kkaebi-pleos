const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE||'playwright');
const url = pathToFileURL(path.resolve(__dirname, '../app/src/main/assets/web/demo/story.html')).href;
async function open(browser, {width = 603, height = 367, fontScale = 1, reduced = false} = {}) {
  const p = await browser.newPage({viewport: {width, height}, reducedMotion: reduced ? 'reduce' : 'no-preference'});
  p.errors = []; p.on('pageerror', error => p.errors.push(error.message));
  await p.goto(url);
  await p.evaluate(scale => {
    window.publishStoryHost = extra => {const n = Date.now(); kkaebiDemoHostState({hosted: true, interactiveAllowed: true, lifecycleActive: true, parked: true, restricted: false, observedAt: n, gearAt: n, speedAt: n, systemAnimationsEnabled: true, motionAllowed: true, fontScale: scale, ...extra});};
    publishStoryHost(); window.storyTestHeartbeat = setInterval(() => publishStoryHost(), 1000);
  }, fontScale);
  await p.evaluate(() => document.fonts.ready);
  await p.waitForFunction(() => document.querySelector('audio').readyState >= 2);
  return p;
}
async function seek(p, seconds) {
  await p.locator('#story-audio').evaluate((audio, t) => {audio.currentTime = t;}, seconds);
  // Let the local AAC decoder emit its queued waiting/playing pair after a seek.
  // This is playback settling, not a timeline or animation-time override.
  await p.waitForTimeout(120);
  await p.waitForFunction(t => !kkaebiStorySnapshot().buffering && Math.abs(kkaebiStorySnapshot().elapsed - t) < .5 && document.body.dataset.shot === kkaebiStorySnapshot().shot.id, seconds);
}
async function bounds(p, allHardware = false) {
  const result = await p.evaluate(() => {
    const box = el => el.getBoundingClientRect().toJSON();
    return {width: innerWidth, height: innerHeight, horizontal: document.documentElement.scrollWidth > innerWidth, vertical: document.documentElement.scrollHeight > innerHeight, buttons: [...document.querySelectorAll('button')].filter(el => el.offsetParent !== null).map(box), list: box(document.querySelector('#hardware-list')), rows: [...document.querySelectorAll('.hardware-row:not([hidden])')].map(el => ({...box(el), text: el.textContent, overflows: el.scrollWidth > el.clientWidth})), caption: box(document.querySelector('#story-caption')), dock: box(document.querySelector('.story-dock'))};
  });
  assert.equal(result.horizontal, false);
  if (result.width >= 500) assert.equal(result.vertical, false);
  for (const b of result.buttons) {assert.ok(b.height >= 48); assert.ok(b.x >= 0 && b.right <= result.width + 1); if (result.width >= 500) assert.ok(b.top >= 0 && b.bottom <= result.height + 1);}
  if (allHardware) for (const row of result.rows) {assert.ok(row.top >= result.list.top - 1 && row.bottom <= result.list.bottom + 1, JSON.stringify({row, list: result.list})); assert.equal(row.overflows, false, row.text);}
  assert.deepEqual(p.errors, []);
  return result;
}

test('unified media-clock story covers all chapters, 8 simultaneous hardware values, pause and replay', async () => {
  const browser = await chromium.launch({headless: true, channel: 'chrome'});
  try {
    const p = await open(browser);
    assert.equal(await p.evaluate(() => typeof KkaebiNative), 'undefined');
    await p.locator('#story-play').click();
    await p.waitForFunction(() => kkaebiStorySnapshot().running);
    for (const [seconds, id] of [[7, 'drowsy'], [17, 'route'], [30, 'checkin1'], [42, 'checkin2'], [58, 'arrive'], [67, 'prepare']]) {await seek(p, seconds); assert.equal((await p.evaluate(() => kkaebiStorySnapshot())).shot.id, id); await bounds(p, id === 'prepare');}
    let s = await p.evaluate(() => kkaebiStorySnapshot());
    assert.ok(s.cabin.activeKeys.length >= 4);
    await p.locator('#story-play').click();
    const paused = await p.evaluate(() => kkaebiStorySnapshot());
    await p.waitForTimeout(180);
    assert.deepEqual((await p.evaluate(() => kkaebiStorySnapshot())).cabin.values, paused.cabin.values);
    assert.equal(await p.locator('.hardware-row[data-active=true]').count(), 0);
    assert.equal(await p.locator('body').getAttribute('data-speaking'), 'false');
    assert.match(await p.locator('#cabin-state').textContent(), /일시정지/);
    await p.locator('#story-play').click();
    for (const [seconds, id] of [[82, 'rest'], [96, 'alarm'], [104, 'restore'], [118, 'recover'], [137, 'resume'], [147, 'finish']]) {await seek(p, seconds); assert.equal((await p.evaluate(() => kkaebiStorySnapshot())).shot.id, id); await bounds(p, id === 'restore');}
    await p.locator('#story-restart').click();
    s = await p.evaluate(() => kkaebiStorySnapshot());
    assert.equal(s.elapsed, 0); assert.equal(s.started, false); assert.equal(s.running, false);
    assert.equal(await p.locator('#story-play').textContent(), '통합 여정 시작');
    await p.locator('#story-play').click();
    await p.waitForFunction(() => kkaebiStorySnapshot().running);
    assert.deepEqual(p.errors, []);
  } finally {await browser.close();}
});

test('host interruption freezes exact model values and audio, latches reentry and clears active wording', async () => {
  const browser = await chromium.launch({headless: true, channel: 'chrome'});
  try {
    const p = await open(browser);
    await p.locator('#story-play').click(); await seek(p, 67);
    const result = await p.evaluate(() => {
      clearInterval(storyTestHeartbeat);
      const before = kkaebiStorySnapshot();
      publishStoryHost({interactiveAllowed: false, sessionInterrupted: true, parked: false, motionAllowed: false});
      return {before, after: kkaebiStorySnapshot()};
    });
    // Media time can advance a few milliseconds between snapshot and audio.pause().
    // A safety change must not switch to reduced-motion targets or advance thereafter.
    assert.ok(result.after.elapsed - result.before.elapsed < .05);
    for (const key of Object.keys(result.before.cabin.values)) assert.ok(Math.abs(result.after.cabin.values[key] - result.before.cabin.values[key]) < .04, key);
    assert.equal(result.after.running, false); assert.equal(result.after.latched, true);
    assert.equal(await p.locator('#story-play').isDisabled(), true);
    assert.equal(await p.locator('.hardware-row[data-active=true]').count(), 0);
    assert.equal(await p.locator('body').getAttribute('data-speaking'), 'false');
    assert.match(await p.locator('#cabin-state').textContent(), /중단/);
    await p.waitForTimeout(200); await p.evaluate(() => publishStoryHost());
    assert.deepEqual((await p.evaluate(() => kkaebiStorySnapshot())).cabin.values, result.after.cabin.values);
    assert.equal(await p.locator('#story-play').isDisabled(), true);
    assert.deepEqual(p.errors, []);
  } finally {await browser.close();}
});

test('responsive controls, enlarged text, reduced motion and media recovery retain the coherent story', async () => {
  const browser = await chromium.launch({headless: true, channel: 'chrome'});
  try {
    for (const config of [{width: 829, height: 504}, {width: 1440, height: 900}, {width: 390, height: 844}, {fontScale: 1.3}, {fontScale: 1.6}, {reduced: true}]) {
      const p = await open(browser, config); await p.locator('#story-play').click(); await seek(p, 67); await bounds(p, !config.fontScale && config.width !== 390);
      if (config.fontScale === 1.6) {
        const scene = await p.evaluate(() => {const box = selector => document.querySelector(selector).getBoundingClientRect().toJSON(); return {title: box('.cabin-state'), canvas: box('.story-canvas-wrap'), note: box('.cabin-note')};});
        assert.ok(scene.title.bottom <= scene.canvas.top + 1);
        assert.ok(scene.canvas.bottom <= scene.note.top + 1);
        assert.equal(await p.locator('.story-chapters').isVisible(), false);
      }
      if (config.reduced) {const s = await p.evaluate(() => kkaebiStorySnapshot()); assert.equal(s.motion, false); assert.equal(s.cabin.values.seat, .78); assert.equal(s.cabin.values.blind, 0);}
      await p.close();
    }
    const p = await open(browser); await p.locator('#story-audio').evaluate(audio => audio.dispatchEvent(new Event('error')));
    assert.equal((await p.evaluate(() => kkaebiStorySnapshot())).running, false);
    assert.match(await p.locator('#story-notice').textContent(), /음성 파일/);
    assert.equal(await p.locator('#story-play').textContent(), '다시 재생');
    await p.locator('#story-play').click(); await p.waitForFunction(() => kkaebiStorySnapshot().running);
    assert.equal((await p.evaluate(() => kkaebiStorySnapshot())).audioError, '');
    assert.deepEqual(p.errors, []);
  } finally {await browser.close();}
});
