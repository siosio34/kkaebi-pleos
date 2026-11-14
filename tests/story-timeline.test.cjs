const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const Story = require('../app/src/main/assets/web/demo/story.js');
const Tour = require('../app/src/main/assets/web/demo/tour.js');
const context = {window: {}};
vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../app/src/main/assets/web/demo/story-data.js'), 'utf8'), context);
const data = JSON.parse(JSON.stringify(context.window.KkaebiStoryData));

test('one continuous 150-second story includes driving, arrival, sleep, wake and driver-led resumption', () => {
  assert.equal(data.shots[0].start, 0);
  assert.equal(data.shots.at(-1).end, data.duration);
  for (let i = 1; i < data.shots.length; i++) assert.equal(data.shots[i - 1].end, data.shots[i].start);
  assert.deepEqual(data.shots.map(item => item.id), ['intro', 'drowsy', 'route', 'checkin1', 'checkin2', 'arrive', 'prepare', 'rest', 'alarm', 'restore', 'recover', 'resume', 'finish']);
  for (const shot of data.shots) assert.equal(Story.sample(data, shot.start).shot.id, shot.id);
  assert.equal(Story.sample(data, 150).finished, true);
});

test('model preparation cannot precede scripted consent, and three independent groups progress together', () => {
  assert.ok(data.consentAt <= data.prepareAt);
  assert.deepEqual(Story.sample(data, data.prepareAt - .01).cabin.values, Tour.routineBaseline());
  const active = Story.sample(data, data.prepareAt + 1.5).cabin;
  assert.ok(active.groups.every(group => group.state === 'active'));
  assert.ok(active.values.seat > 0 && active.values.seat < .78);
  assert.ok(active.values.window > 0 && active.values.window < .45);
  assert.ok(active.values.light > .08 && active.values.light < .72);
  assert.equal(active.values.blind, 0);
  assert.equal(active.values.temperature, 24);
  assert.equal(Story.sample({...data, consentAt: undefined}, 80).cabin.started, false);
  assert.equal(Story.sample({...data, consentAt: 70}, 69).cabin.started, false);
});

test('sleep remains held through alarm until the depicted driver wake response', () => {
  const rest = Story.sample(data, 80).cabin;
  const alarm = Story.sample(data, data.restoreAt - .01).cabin;
  assert.equal(rest.awaitingWake, true);
  assert.deepEqual(alarm.values, rest.values);
  assert.equal(alarm.direction, 'prepare');
  assert.ok(data.wakeAckAt <= data.restoreAt);
  assert.equal(Story.sample({...data, wakeAckAt: undefined}, 120).cabin.awaitingWake, true);
  assert.equal(Story.sample({...data, wakeAckAt: 110}, 109).cabin.awaitingWake, true);
});

test('wake restoration uses reverse motor order and eventually returns every model value', () => {
  const early = Story.sample(data, data.restoreAt + 1.5).cabin;
  assert.equal(early.direction, 'restore');
  assert.ok(early.activeKeys.includes('blind'));
  assert.ok(early.activeKeys.includes('brightness'));
  assert.equal(early.values.seat, .78);
  assert.ok(early.values.blind < 1);
  const later = Story.sample(data, data.restoreAt + 5).cabin;
  assert.ok(later.activeKeys.includes('seatRecline'));
  assert.deepEqual(Story.sample(data, 115).cabin.values, Tour.routineBaseline());
});

test('seeking and reduced motion preserve phase boundaries and dependency confirmation', () => {
  const before = Story.sample(data, 68);
  Story.sample(data, 145);
  assert.deepEqual(Story.sample(data, 68), before);
  const reduced = Story.sample(data, data.prepareAt + 1, true).cabin;
  assert.equal(reduced.values.seat, .78);
  assert.equal(reduced.values.window, 0);
  assert.equal(reduced.values.blind, 0);
  assert.equal(reduced.values.temperature, 24);
  assert.deepEqual(Story.sample(data, -500).cabin.values, Tour.routineBaseline());
  assert.equal(Story.sample(data, Infinity).elapsed, 0);
});

test('measured narration cues never overlap and captions are active only within the audible cue', () => {
  for (let i = 0; i < data.cues.length; i++) {
    const cue = data.cues[i];
    assert.ok(cue.end > cue.start);
    assert.ok(cue.end <= data.duration);
    if (i) assert.ok(data.cues[i - 1].end <= cue.start);
    assert.equal(Story.sample(data, cue.start + .01).cue.text, cue.text);
    assert.notEqual(Story.sample(data, cue.end).cue?.text, cue.text);
  }
  assert.equal(Story.timeLabel(125), '2:05');
});
