'use strict';
/* 차량 부품에 닻을 내린 라벨. app.js가 kkaebiStageLabels에 목록을 넣으면 장면이 그려질 때마다 위치를 맞춘다.
   설정 화면에서는 라벨이 곧 선택 버튼이다. */
(function (root) {
  const nodes = new Map(), SVG = 'http://www.w3.org/2000/svg';
  function make(key, layer, svg) {
    const node = document.createElement('button');
    node.type = 'button'; node.className = 'stage-label'; node.innerHTML = '<b></b><strong></strong><em></em>';
    const line = document.createElementNS(SVG, 'line'), dot = document.createElementNS(SVG, 'circle');
    dot.setAttribute('r', '3'); svg.append(line, dot); layer.append(node);
    const entry = {node, line, dot}; nodes.set(key, entry); return entry;
  }
  function text(el, value) { if (el.textContent !== value) el.textContent = value; }
  root.kkaebiLayoutStage = function (project) {
    const layer = document.getElementById('stage-labels'); if (!layer || !project) return;
    const svg = layer.querySelector('svg'), list = root.kkaebiStageLabels || [];
    const w = innerWidth, h = innerHeight, rem = parseFloat(getComputedStyle(document.documentElement).fontSize);
    const seen = new Set(), placed = [];
    for (const item of list) {
      const entry = nodes.get(item.key) || make(item.key, layer, svg), p = project(item.anchor), on = !!(p && p.visible);
      seen.add(item.key);
      text(entry.node.children[0], item.name); text(entry.node.children[1], item.value); text(entry.node.children[2], item.status || '');
      entry.node.dataset.state = item.state || 'idle'; entry.node.dataset.on = String(on);
      entry.node.dataset.selected = String(!!item.selected);
      if (item.option) { entry.node.dataset.option = item.option; entry.node.disabled = false; entry.node.tabIndex = 0; }
      else { delete entry.node.dataset.option; entry.node.disabled = true; entry.node.tabIndex = -1; }
      entry.line.style.display = entry.dot.style.display = on ? '' : 'none';
      if (on) placed.push({entry, item, x: p.x * w, y: p.y * h});
    }
    for (const [key, entry] of nodes) if (!seen.has(key)) { entry.node.dataset.on = 'false'; entry.line.style.display = entry.dot.style.display = 'none'; }
    document.body.dataset.stageLabels = String(placed.length > 0);
    if (!placed.length) return;
    const panel = document.querySelector('.control-panel'), edge = panel ? panel.getBoundingClientRect().left : w * 0.55;
    const wide = w > 520, right = wide ? Math.min(w * 0.535, edge - 0.8 * rem) : w - 14, pad = wide ? Math.max(14, w * 0.022) : 14;
    const width = Math.min(8.2 * rem, right * 0.3), top = h * 0.29, bottom = h * 0.6;
    placed.sort((a, b) => a.x - b.x);
    const half = Math.ceil(placed.length / 2);
    for (const side of ['left', 'right']) {
      const group = (side === 'left' ? placed.slice(0, half) : placed.slice(half)).sort((a, b) => a.y - b.y);
      group.forEach((p, i) => {
        const y = group.length === 1 ? (top + bottom) / 2 : top + (bottom - top) * i / (group.length - 1);
        const x = side === 'left' ? pad : right - width;
        p.entry.node.dataset.side = side; p.entry.node.style.width = width + 'px';
        p.entry.node.style.transform = 'translate(' + Math.round(x) + 'px,' + Math.round(y - 1.9 * rem) + 'px)';
        const lx = side === 'left' ? x + width * 0.82 : x + width * 0.18, active = p.item.state === 'working' || p.item.selected;
        p.entry.line.setAttribute('x1', lx); p.entry.line.setAttribute('y1', y); p.entry.line.setAttribute('x2', p.x); p.entry.line.setAttribute('y2', p.y);
        p.entry.dot.setAttribute('cx', p.x); p.entry.dot.setAttribute('cy', p.y);
        p.entry.line.dataset.active = p.entry.dot.dataset.active = String(!!active);
      });
    }
  };
})(window);
