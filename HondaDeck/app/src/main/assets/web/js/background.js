// Fundo <canvas> synthwave discreto em perspectiva:
// - Velocidade proporcional a telemetry.speedKmh (parado ~imóvel)
// - Cor --accent com opacidade <= 0.18
// - Brilho radial atrás do velocímetro
// - Limite de 30 fps e pausa automática com document.hidden

import { hexToRgb, clamp } from './util.js';

let canvas = null;
let ctx = null;
let width = 1333;
let height = 800;
let dpr = 1;

let accentRgb = { r: 34, g: 211, b: 238 };
let targetSpeed = 0;
let smoothSpeed = 0;
let currentMode = 'home';

let offsetZ = 0;
let rafId = null;
let lastFrameMs = 0;
const FRAME_INTERVAL_MS = 1000 / 30;

function resize() {
  if (!canvas) return;
  dpr = Math.min(2, window.devicePixelRatio || 1);
  width = window.innerWidth || 1333;
  height = window.innerHeight || 800;
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}

function drawFrame(dtSec) {
  if (!ctx) return;

  smoothSpeed += (targetSpeed - smoothSpeed) * Math.min(1, dtSec * 4.5);
  // Parado (~0 km/h) => quase imóvel (0.012 u/s); a 120 km/h => avanço fluido (2.8 u/s)
  const advanceRate = smoothSpeed < 0.8 ? 0.012 : 0.06 + (clamp(smoothSpeed, 0, 180) / 120) * 2.75;
  offsetZ = (offsetZ + dtSec * advanceRate) % 1;

  ctx.clearRect(0, 0, width, height);

  const { r, g, b } = accentRgb;
  const cx = width * 0.5;
  const horizonY = height * 0.47;
  const bottomY = height;
  const roadHeight = bottomY - horizonY;

  // 1) Brilho radial atrás do velocímetro (mais intenso no modo Início, sutil nos demais)
  const glowAlpha = currentMode === 'home' ? 0.14 : 0.06;
  const radial = ctx.createRadialGradient(cx, height * 0.46, 24, cx, height * 0.46, 330);
  radial.addColorStop(0, `rgba(${r}, ${g}, ${b}, ${glowAlpha})`);
  radial.addColorStop(0.55, `rgba(${r}, ${g}, ${b}, ${(glowAlpha * 0.38).toFixed(3)})`);
  radial.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`);
  ctx.fillStyle = radial;
  ctx.fillRect(0, 0, width, height);

  // Brilho suave na linha do horizonte
  const horizonGlow = ctx.createLinearGradient(0, horizonY - 24, 0, horizonY + 48);
  horizonGlow.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0)`);
  horizonGlow.addColorStop(0.45, `rgba(${r}, ${g}, ${b}, 0.07)`);
  horizonGlow.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`);
  ctx.fillStyle = horizonGlow;
  ctx.fillRect(0, horizonY - 24, width, 72);

  // 2) Linhas longitudinais em perspectiva irradiando do ponto de fuga
  ctx.lineWidth = 1;
  const numCols = 16;
  const spreadBottom = width * 1.35;
  for (let i = -numCols / 2; i <= numCols / 2; i++) {
    const norm = i / (numCols / 2);
    const xBottom = cx + norm * spreadBottom;
    const xTop = cx + norm * (width * 0.08);
    const edgeFade = 1 - Math.abs(norm) * 0.45;
    const alpha = Math.min(0.16, 0.14 * edgeFade);

    const grad = ctx.createLinearGradient(xTop, horizonY, xBottom, bottomY);
    grad.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0.01)`);
    grad.addColorStop(0.35, `rgba(${r}, ${g}, ${b}, ${(alpha * 0.65).toFixed(3)})`);
    grad.addColorStop(1, `rgba(${r}, ${g}, ${b}, ${alpha.toFixed(3)})`);

    ctx.strokeStyle = grad;
    ctx.beginPath();
    ctx.moveTo(xTop, horizonY);
    ctx.lineTo(xBottom, bottomY);
    ctx.stroke();
  }

  // 3) Linhas horizontais vindo na direção do motorista (projeção 1/z, opacidade <= 0.18)
  const numRows = 14;
  const zNear = 0.85;
  const zFar = 9.5;
  for (let i = 0; i < numRows; i++) {
    // z diminui conforme offsetZ cresce => linhas aproximam-se do motorista (y desce)
    let z = zFar - ((i + offsetZ) / numRows) * (zFar - zNear);
    if (z <= zNear) z += (zFar - zNear);
    const persp = zNear / z; // 0..1 (1 = borda inferior)
    const y = horizonY + persp * roadHeight;
    if (y < horizonY || y > bottomY) continue;

    // Opacidade estritamente <= 0.18
    const alpha = Math.min(0.18, 0.025 + persp * 0.15);
    ctx.strokeStyle = `rgba(${r}, ${g}, ${b}, ${alpha.toFixed(3)})`;
    ctx.beginPath();
    ctx.moveTo(0, y);
    ctx.lineTo(width, y);
    ctx.stroke();
  }
}

function loop(nowMs) {
  if (document.hidden) {
    rafId = null;
    return;
  }
  rafId = requestAnimationFrame(loop);
  if (!lastFrameMs) {
    lastFrameMs = nowMs;
    drawFrame(1 / 30);
    return;
  }
  const elapsed = nowMs - lastFrameMs;
  if (elapsed < FRAME_INTERVAL_MS) return;
  const dtSec = Math.min(0.1, elapsed / 1000);
  lastFrameMs = nowMs - (elapsed % FRAME_INTERVAL_MS);
  drawFrame(dtSec);
}

function startLoop() {
  if (rafId !== null || document.hidden) return;
  lastFrameMs = 0;
  rafId = requestAnimationFrame(loop);
}

function stopLoop() {
  if (rafId !== null) {
    cancelAnimationFrame(rafId);
    rafId = null;
  }
}

export function initBackground() {
  canvas = document.getElementById('bg-canvas');
  if (!canvas) return;
  ctx = canvas.getContext('2d');
  resize();
  window.addEventListener('resize', () => {
    resize();
    drawFrame(0);
  });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) stopLoop();
    else startLoop();
  });
  startLoop();
}

export function setBackgroundAccent(hex) {
  accentRgb = hexToRgb(hex);
}

export function setBackgroundSpeed(speedKmh) {
  targetSpeed = clamp(speedKmh, 0, 220);
}

export function setBackgroundMode(mode) {
  currentMode = mode || 'home';
}
