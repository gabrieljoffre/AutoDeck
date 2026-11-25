// Fundo atmosférico <canvas> leve:
// Em Home/Mapa o protagonista do fundo é o mapa em perspectiva de drone;
// o canvas mantém brilho radial discreto atrás do mostrador esquerdo e cumpre o contrato de fundo.

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

let rafId = null;
let lastFrameMs = 0;
const FRAME_INTERVAL_MS = 1000 / 20;

function resize() {
  if (!canvas) return;
  dpr = Math.min(1.5, window.devicePixelRatio || 1);
  width = window.innerWidth || 1333;
  height = window.innerHeight || 800;
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}

function drawFrame(dtSec) {
  if (!ctx) return;

  smoothSpeed += (targetSpeed - smoothSpeed) * Math.min(1, dtSec * 4.5);
  if (targetSpeed <= 0.1 && smoothSpeed < 0.15) smoothSpeed = 0;

  ctx.clearRect(0, 0, width, height);

  const { r, g, b } = accentRgb;
  const speedBoost = clamp(smoothSpeed / 140, 0, 1) * 0.05;
  const baseAlpha = currentMode === 'home' ? 0.12 + speedBoost : currentMode === 'music' ? 0.08 : 0.04;

  // Brilho radial sutil atrás do velocímetro na esquerda
  const leftCx = width * 0.17;
  const leftCy = height * 0.48;
  const radialLeft = ctx.createRadialGradient(leftCx, leftCy, 24, leftCx, leftCy, 340);
  radialLeft.addColorStop(0, `rgba(${r}, ${g}, ${b}, ${baseAlpha.toFixed(3)})`);
  radialLeft.addColorStop(0.6, `rgba(${r}, ${g}, ${b}, ${(baseAlpha * 0.35).toFixed(3)})`);
  radialLeft.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`);
  ctx.fillStyle = radialLeft;
  ctx.fillRect(0, 0, width, height);
}

function loop(nowMs) {
  if (document.hidden) {
    rafId = null;
    return;
  }
  rafId = requestAnimationFrame(loop);
  if (!lastFrameMs) {
    lastFrameMs = nowMs;
    drawFrame(1 / 20);
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
  drawFrame(0);
}

export function setBackgroundSpeed(speedKmh) {
  targetSpeed = clamp(speedKmh, 0, 220);
}

export function setBackgroundMode(mode) {
  currentMode = mode || 'home';
  drawFrame(0);
}
