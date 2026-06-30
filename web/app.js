const canvas = document.getElementById("canvas");
const ctx = canvas.getContext("2d");
const zoomText = document.getElementById("zoomText");
const fileText = document.getElementById("fileText");
const fileInput = document.getElementById("fileInput");
const colorInput = document.getElementById("colorInput");

const modes = ["pen", "eraser", "pan", "mind"];
let mode = "pen";
let scale = 1;
let offsetX = 0;
let offsetY = 0;
let rotationDegrees = 0;
let nextNodeId = 1;
let selectedNodeId = null;
let activeStroke = null;
let activeNode = null;
let lastPointer = null;
let fileHandle = null;
let currentFileName = "새 메모";

const doc = {
  strokes: [],
  nodes: [],
  arrows: []
};

function resize() {
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.floor(window.innerWidth * ratio);
  canvas.height = Math.floor(window.innerHeight * ratio);
  canvas.style.width = `${window.innerWidth}px`;
  canvas.style.height = `${window.innerHeight}px`;
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  draw();
}

function setMode(next) {
  mode = next;
  modes.forEach(name => document.getElementById(`${name}Btn`)?.classList.toggle("active", name === next));
}

function toWorld(x, y) {
  return {
    x: (x - window.innerWidth / 2) / scale - offsetX,
    y: (y - window.innerHeight / 2) / scale - offsetY
  };
}

function toScreen(point) {
  return {
    x: (point.x + offsetX) * scale + window.innerWidth / 2,
    y: (point.y + offsetY) * scale + window.innerHeight / 2
  };
}

function drawGrid() {
  const step = 42;
  const tl = toWorld(0, 0);
  const br = toWorld(window.innerWidth, window.innerHeight);
  ctx.strokeStyle = "rgba(148, 163, 184, 0.28)";
  ctx.lineWidth = 1;
  for (let x = Math.floor(tl.x / step) * step; x <= br.x; x += step) {
    const a = toScreen({ x, y: tl.y });
    const b = toScreen({ x, y: br.y });
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.lineTo(b.x, b.y);
    ctx.stroke();
  }
  for (let y = Math.floor(tl.y / step) * step; y <= br.y; y += step) {
    const a = toScreen({ x: tl.x, y });
    const b = toScreen({ x: br.x, y });
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.lineTo(b.x, b.y);
    ctx.stroke();
  }
}

function drawCenterGuide() {
  const origin = toScreen({ x: 0, y: 0 });
  ctx.save();
  ctx.setLineDash([14, 10]);
  ctx.strokeStyle = "rgba(15, 118, 110, 0.68)";
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(0, origin.y);
  ctx.lineTo(window.innerWidth, origin.y);
  ctx.moveTo(origin.x, 0);
  ctx.lineTo(origin.x, window.innerHeight);
  ctx.stroke();
  ctx.restore();
}

function drawStrokes() {
  for (const stroke of doc.strokes) {
    if (!stroke.points || stroke.points.length < 2) continue;
    ctx.strokeStyle = cssColor(stroke.color || "#132238");
    ctx.lineWidth = (stroke.size || 5) * scale;
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.beginPath();
    stroke.points.forEach((point, index) => {
      const s = toScreen(point);
      if (index === 0) ctx.moveTo(s.x, s.y);
      else ctx.lineTo(s.x, s.y);
    });
    ctx.stroke();
  }
}

function drawMindMap() {
  ctx.lineWidth = 2;
  ctx.strokeStyle = "rgba(15, 118, 110, 0.58)";
  for (const node of doc.nodes) {
    const parent = doc.nodes.find(item => item.id === node.parentId);
    if (!parent) continue;
    const a = toScreen(parent);
    const b = toScreen(node);
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.lineTo(b.x, b.y);
    ctx.stroke();
  }

  for (const arrow of doc.arrows) {
    const from = doc.nodes.find(item => item.id === arrow.fromId);
    const to = doc.nodes.find(item => item.id === arrow.toId);
    if (!from || !to) continue;
    const a = toScreen(from);
    const b = toScreen(to);
    ctx.save();
    ctx.setLineDash([12, 8]);
    ctx.strokeStyle = "rgba(19, 34, 56, 0.72)";
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.quadraticCurveTo((a.x + b.x) / 2, (a.y + b.y) / 2 - 50, b.x, b.y);
    ctx.stroke();
    ctx.restore();
  }

  for (const node of doc.nodes) {
    const s = toScreen(node);
    const w = 140 * (node.sizeScale || 1) * scale;
    const h = 50 * (node.sizeScale || 1) * scale;
    ctx.fillStyle = cssColor(node.color || "#ffffff");
    roundRect(s.x - w / 2, s.y - h / 2, w, h, 10, true, false);
    ctx.strokeStyle = node.id === selectedNodeId ? "#132238" : "#0f766e";
    ctx.lineWidth = node.id === selectedNodeId ? 4 : 2;
    roundRect(s.x - w / 2, s.y - h / 2, w, h, 10, false, true);
    ctx.fillStyle = isLight(node.color) ? "#132238" : "#ffffff";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.font = `700 ${Math.max(12, h * 0.36)}px system-ui`;
    ctx.fillText(node.text || "새 생각", s.x, s.y, w - 12);
  }
}

function drawMiniMap() {
  const box = { w: Math.min(window.innerWidth * 0.3, 260), h: 150 };
  box.x = window.innerWidth - box.w - 16;
  box.y = window.innerHeight - box.h - 16;
  ctx.fillStyle = "rgba(255,255,255,.68)";
  roundRect(box.x, box.y, box.w, box.h, 16, true, false);
  ctx.strokeStyle = "rgba(19,34,56,.24)";
  ctx.lineWidth = 2;
  roundRect(box.x, box.y, box.w, box.h, 16, false, true);
  ctx.fillStyle = "#132238";
  ctx.font = "700 16px system-ui";
  ctx.textAlign = "right";
  ctx.fillText(`${Math.round(scale * 100)}%`, box.x + box.w - 12, box.y + 24);
}

function draw() {
  ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
  ctx.fillStyle = "#fbfaf6";
  ctx.fillRect(0, 0, window.innerWidth, window.innerHeight);
  drawGrid();
  drawCenterGuide();
  drawStrokes();
  drawMindMap();
  drawMiniMap();
  zoomText.textContent = `${Math.round(scale * 100)}%`;
  fileText.textContent = currentFileName;
}

function hitNode(world) {
  return [...doc.nodes].reverse().find(node => {
    const w = 140 * (node.sizeScale || 1);
    const h = 50 * (node.sizeScale || 1);
    return Math.abs(node.x - world.x) <= w / 2 && Math.abs(node.y - world.y) <= h / 2;
  });
}

canvas.addEventListener("pointerdown", event => {
  canvas.setPointerCapture(event.pointerId);
  const world = toWorld(event.clientX, event.clientY);
  lastPointer = { x: event.clientX, y: event.clientY };
  if (mode === "pen") {
    activeStroke = { points: [world], color: colorInput.value, size: 5, kind: "BALLPOINT" };
    doc.strokes.push(activeStroke);
  } else if (mode === "mind") {
    const node = hitNode(world);
    if (node) {
      selectedNodeId = node.id;
      activeNode = node;
    } else {
      const node = { id: nextNodeId++, x: world.x, y: world.y, text: "새 생각", parentId: null, color: "#0f766e", sizeScale: 1 };
      doc.nodes.push(node);
      selectedNodeId = node.id;
    }
  } else if (mode === "eraser") {
    eraseAt(world);
  }
  draw();
});

canvas.addEventListener("pointermove", event => {
  if (!lastPointer) return;
  const world = toWorld(event.clientX, event.clientY);
  if (activeStroke) activeStroke.points.push(world);
  else if (activeNode) {
    activeNode.x = world.x;
    activeNode.y = world.y;
  } else if (mode === "pan") {
    offsetX += (event.clientX - lastPointer.x) / scale;
    offsetY += (event.clientY - lastPointer.y) / scale;
  } else if (mode === "eraser") eraseAt(world);
  lastPointer = { x: event.clientX, y: event.clientY };
  draw();
});

canvas.addEventListener("pointerup", () => {
  activeStroke = null;
  activeNode = null;
  lastPointer = null;
});

canvas.addEventListener("wheel", event => {
  event.preventDefault();
  const before = toWorld(event.clientX, event.clientY);
  scale = clamp(scale * (event.deltaY < 0 ? 1.08 : 0.92), 0.2, 4);
  const after = toWorld(event.clientX, event.clientY);
  offsetX += after.x - before.x;
  offsetY += after.y - before.y;
  draw();
}, { passive: false });

canvas.addEventListener("dblclick", event => {
  const node = hitNode(toWorld(event.clientX, event.clientY));
  if (!node) return;
  const text = prompt("박스 내용", node.text || "");
  if (text != null) {
    node.text = text.trim() || node.text;
    draw();
  }
});

function eraseAt(world) {
  const node = hitNode(world);
  if (node) {
    const ids = collectDescendants(node.id);
    ids.add(node.id);
    doc.nodes = doc.nodes.filter(item => !ids.has(item.id));
    doc.arrows = doc.arrows.filter(item => !ids.has(item.fromId) && !ids.has(item.toId));
    return;
  }
  doc.strokes = doc.strokes.filter(stroke => !stroke.points?.some(point => Math.hypot(point.x - world.x, point.y - world.y) < 20 / scale));
}

function collectDescendants(id) {
  const ids = new Set();
  for (const child of doc.nodes.filter(node => node.parentId === id)) {
    ids.add(child.id);
    for (const nested of collectDescendants(child.id)) ids.add(nested);
  }
  return ids;
}

function addChild() {
  const parent = doc.nodes.find(node => node.id === selectedNodeId);
  if (!parent) return;
  const children = doc.nodes.filter(node => node.parentId === parent.id);
  const node = {
    id: nextNodeId++,
    x: parent.x + 260,
    y: parent.y + (children.length - 0.5) * 84,
    text: "새 가지",
    parentId: parent.id,
    color: "#ffffff",
    sizeScale: parent.sizeScale || 1
  };
  doc.nodes.push(node);
  selectedNodeId = node.id;
  draw();
}

function addSibling() {
  const node = doc.nodes.find(item => item.id === selectedNodeId);
  if (!node || node.parentId == null) return;
  const next = { ...node, id: nextNodeId++, y: node.y + 90, text: "새 가지" };
  doc.nodes.push(next);
  selectedNodeId = next.id;
  draw();
}

function applyTheme() {
  const colors = ["#ff6b6b", "#ffe66d", "#95d5b2", "#7bdff2", "#b8b8ff", "#ff8bd1"];
  doc.nodes.forEach((node, index) => node.color = colors[index % colors.length]);
  draw();
}

function serialize() {
  return {
    nextNodeId,
    scale,
    offsetX,
    offsetY,
    rotationDegrees,
    gridOpacity: 72,
    strokes: doc.strokes,
    nodes: doc.nodes,
    arrows: doc.arrows
  };
}

function loadDocument(data) {
  nextNodeId = data.nextNodeId || 1;
  scale = data.scale || 1;
  offsetX = data.offsetX || 0;
  offsetY = data.offsetY || 0;
  rotationDegrees = data.rotationDegrees || 0;
  doc.strokes = data.strokes || [];
  doc.nodes = data.nodes || [];
  doc.arrows = data.arrows || [];
  selectedNodeId = null;
  draw();
}

async function openFile() {
  if ("showOpenFilePicker" in window) {
    const [handle] = await window.showOpenFilePicker({
      types: [{ description: "2M Memo", accept: { "application/json": [".2memo", ".json"] } }]
    });
    fileHandle = handle;
    currentFileName = handle.name;
    const file = await handle.getFile();
    loadDocument(JSON.parse(await file.text()));
  } else {
    fileInput.click();
  }
}

fileInput.addEventListener("change", async () => {
  const file = fileInput.files?.[0];
  if (!file) return;
  currentFileName = file.name;
  loadDocument(JSON.parse(await file.text()));
});

async function saveFile() {
  const text = JSON.stringify(serialize(), null, 2);
  if (fileHandle?.createWritable) {
    const writable = await fileHandle.createWritable();
    await writable.write(text);
    await writable.close();
    return;
  }
  const blob = new Blob([text], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = currentFileName === "새 메모" ? "2m-memo.2memo" : currentFileName;
  a.click();
  URL.revokeObjectURL(url);
}

function cssColor(value) {
  if (typeof value === "number") return `#${(value >>> 0).toString(16).slice(-6).padStart(6, "0")}`;
  return value || "#ffffff";
}

function isLight(value) {
  const color = cssColor(value).replace("#", "");
  const r = parseInt(color.slice(0, 2), 16);
  const g = parseInt(color.slice(2, 4), 16);
  const b = parseInt(color.slice(4, 6), 16);
  return (r * 299 + g * 587 + b * 114) / 1000 > 180;
}

function roundRect(x, y, w, h, r, fill, stroke) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  if (fill) ctx.fill();
  if (stroke) ctx.stroke();
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

document.getElementById("openBtn").onclick = openFile;
document.getElementById("saveBtn").onclick = saveFile;
document.getElementById("penBtn").onclick = () => setMode("pen");
document.getElementById("eraserBtn").onclick = () => setMode("eraser");
document.getElementById("panBtn").onclick = () => setMode("pan");
document.getElementById("mindBtn").onclick = () => setMode("mind");
document.getElementById("childBtn").onclick = addChild;
document.getElementById("siblingBtn").onclick = addSibling;
document.getElementById("themeBtn").onclick = applyTheme;
document.getElementById("centerBtn").onclick = () => {
  offsetX = 0;
  offsetY = 0;
  draw();
};

window.addEventListener("resize", resize);
resize();
