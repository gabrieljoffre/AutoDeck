// Fundo <canvas> estrada em perspectiva leve:
// - Asfalto escuro em perspectiva com bordas/acostamento sutis em --accent
// - Faixa central tracejada, marcações laterais e refletores discretos vindo na direção do motorista
// - Velocidade proporcional a telemetry.speedKmh (parado = imóvel)
// - Horizonte com brilho suave e brilho radial atrás do velocímetro
// - Máximo de 30 fps, sem sombras/filtros caros, pausa com document.hidden

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

// Posição longitudinal normalizada (0..1) de um ciclo de marcações na estrada
let roadOffset = 0;
let rafId = null;
let lastFrameMs = 0;
const FRAME_INTERVAL_MS = 1000 / 30;

// Parâmetros de projeção em perspectiva (1/z)
const Z_NEAR = 0.85;
const Z_FAR = 11.0;
const SEGMENT_COUNT = 12;
const SEGMENT_SPAN = (Z_FAR - Z_NEAR) / SEGMENT_COUNT;

function resize() {
  if (!canvas) return;
  dpr = Math.min(2, window.devicePixelRatio || 1);
  width = window.innerWidth || 1333;
  height = window.innerHeight || 800;
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
}

/** Projeta profundidade z (Z_NEAR..Z_FAR) para fator de perspectiva (0 no horizonte, 1 na base). */
function projectPersp(z) {
  const pNear = 1 / Z_NEAR;
  const pFar = 1 / Z_FAR;
  return clamp((1 / z - pFar) / (pNear - pFar), 0, 1);
}

function drawFrame(dtSec) {
  if (!ctx) return;

  // Suaviza a transição de velocidade; parado (0 km/h) trava completamente imóvel
  smoothSpeed += (targetSpeed - smoothSpeed) * Math.min(1, dtSec * 4.5);
  if (targetSpeed <= 0.1 && smoothSpeed < 0.15) {
    smoothSpeed = 0;
  }

  if (smoothSpeed > 0) {
    // 1 ciclo ~ 12 m: a 60 km/h (~16,7 m/s) => ~1,39 ciclos/s; a 120 km/h => ~2,78 ciclos/s
    const speedMps = clamp(smoothSpeed, 0, 220) / 3.6;
    const advanceRate = speedMps / 12;
    roadOffset = (roadOffset + dtSec * advanceRate) % 1;
  }

  ctx.clearRect(0, 0, width, height);

  const { r, g, b } = accentRgb;
  const cx = width * 0.5;
  const horizonY = height * 0.46;
  const bottomY = height;
  const roadHeight = bottomY - horizonY;

  // Largura da pista (meia-largura no horizonte e na base, enquadrada entre os cartões)
  const topHalfW = width * 0.028;
  const botHalfW = width * 0.30;
  const topShoulderW = width * 0.038;
  const botShoulderW = width * 0.36;

  // Opacidade geral ligeiramente menor nos modos 2/3 para não competir com os painéis
  const modeScale = currentMode === 'home' ? 1 : 0.72;

  // 1) Brilho radial atrás do velocímetro
  const glowAlpha = (currentMode === 'home' ? 0.13 : 0.055);
  const radial = ctx.createRadialGradient(cx, height * 0.45, 22, cx, height * 0.45, 340);
  radial.addColorStop(0, `rgba(${r}, ${g}, ${b}, ${glowAlpha.toFixed(3)})`);
  radial.addColorStop(0.55, `rgba(${r}, ${g}, ${b}, ${(glowAlpha * 0.36).toFixed(3)})`);
  radial.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`);
  ctx.fillStyle = radial;
  ctx.fillRect(0, 0, width, height);

  // 2) Brilho suave no horizonte
  const horizonGlow = ctx.createLinearGradient(0, horizonY - 28, 0, horizonY + 44);
  horizonGlow.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0)`);
  horizonGlow.addColorStop(0.42, `rgba(${r}, ${g}, ${b}, ${(0.08 * modeScale).toFixed(3)})`);
  horizonGlow.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`);
  ctx.fillStyle = horizonGlow;
  ctx.fillRect(0, horizonY - 28, width, 72);

  // Linha fina do horizonte
  const hLineGrad = ctx.createLinearGradient(width * 0.08, 0, width * 0.92, 0);
  hLineGrad.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0)`);
  hLineGrad.addColorStop(0.5, `rgba(${r}, ${g}, ${b}, ${(0.13 * modeScale).toFixed(3)})`);
  hLineGrad.addColorStop(1, `rgba(${r}, ${g}, ${b}, 0)`);
  ctx.strokeStyle = hLineGrad;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(width * 0.08, horizonY);
  ctx.lineTo(width * 0.92, horizonY);
  ctx.stroke();

  // 3) Acostamento sutil em perspectiva
  const shoulderGrad = ctx.createLinearGradient(0, horizonY, 0, bottomY);
  shoulderGrad.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0.006)`);
  shoulderGrad.addColorStop(1, `rgba(${r}, ${g}, ${b}, ${(0.05 * modeScale).toFixed(3)})`);
  ctx.fillStyle = shoulderGrad;
  ctx.beginPath();
  ctx.moveTo(cx - topShoulderW, horizonY);
  ctx.lineTo(cx + topShoulderW, horizonY);
  ctx.lineTo(cx + botShoulderW, bottomY);
  ctx.lineTo(cx - botShoulderW, bottomY);
  ctx.closePath();
  ctx.fill();

  // 4) Asfalto escuro em perspectiva
  const asphaltGrad = ctx.createLinearGradient(0, horizonY, 0, bottomY);
  asphaltGrad.addColorStop(0, 'rgba(10, 16, 26, 0.30)');
  asphaltGrad.addColorStop(0.45, 'rgba(13, 20, 32, 0.62)');
  asphaltGrad.addColorStop(1, 'rgba(16, 24, 38, 0.82)');
  ctx.fillStyle = asphaltGrad;
  ctx.beginPath();
  ctx.moveTo(cx - topHalfW, horizonY);
  ctx.lineTo(cx + topHalfW, horizonY);
  ctx.lineTo(cx + botHalfW, bottomY);
  ctx.lineTo(cx - botHalfW, bottomY);
  ctx.closePath();
  ctx.fill();

  // 5) Bordas da pista e linhas externas de acostamento em --accent
  const edgeGrad = ctx.createLinearGradient(0, horizonY, 0, bottomY);
  edgeGrad.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0.02)`);
  edgeGrad.addColorStop(0.35, `rgba(${r}, ${g}, ${b}, ${(0.14 * modeScale).toFixed(3)})`);
  edgeGrad.addColorStop(1, `rgba(${r}, ${g}, ${b}, ${(0.24 * modeScale).toFixed(3)})`);

  ctx.strokeStyle = edgeGrad;
  ctx.lineWidth = 1.8;
  ctx.beginPath();
  ctx.moveTo(cx - topHalfW, horizonY);
  ctx.lineTo(cx - botHalfW, bottomY);
  ctx.moveTo(cx + topHalfW, horizonY);
  ctx.lineTo(cx + botHalfW, bottomY);
  ctx.stroke();

  const outerGrad = ctx.createLinearGradient(0, horizonY, 0, bottomY);
  outerGrad.addColorStop(0, `rgba(${r}, ${g}, ${b}, 0.008)`);
  outerGrad.addColorStop(1, `rgba(${r}, ${g}, ${b}, ${(0.09 * modeScale).toFixed(3)})`);
  ctx.strokeStyle = outerGrad;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(cx - topShoulderW, horizonY);
  ctx.lineTo(cx - botShoulderW, bottomY);
  ctx.moveTo(cx + topShoulderW, horizonY);
  ctx.lineTo(cx + botShoulderW, bottomY);
  ctx.stroke();

  // 6) Faixa central tracejada, marcações laterais e refletores discretos em movimento
  const dashLenZ = SEGMENT_SPAN * 0.44;
  const tickLenZ = SEGMENT_SPAN * 0.18;

  for (let i = 0; i < SEGMENT_COUNT; i++) {
    // z diminui conforme roadOffset cresce => elementos vêm na direção do motorista
    let zHead = Z_FAR - ((i + roadOffset) / SEGMENT_COUNT) * (Z_FAR - Z_NEAR);
    if (zHead < Z_NEAR) zHead += (Z_FAR - Z_NEAR);
    const zTail = Math.min(Z_FAR, zHead + dashLenZ);

    const pHead = projectPersp(zHead);
    const pTail = projectPersp(zTail);
    if (pHead <= 0.005) continue;

    const yHead = horizonY + pHead * roadHeight;
    const yTail = horizonY + pTail * roadHeight;
    const alpha = Math.min(0.25, (0.025 + pHead * 0.21) * modeScale);

    // 6a) Faixa central tracejada (trapézio fino em perspectiva)
    const halfWHead = 1.0 + pHead * 2.8;
    const halfWTail = 0.8 + pTail * 2.2;
    ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${alpha.toFixed(3)})`;
    ctx.beginPath();
    ctx.moveTo(cx - halfWTail, yTail);
    ctx.lineTo(cx + halfWTail, yTail);
    ctx.lineTo(cx + halfWHead, yHead);
    ctx.lineTo(cx - halfWHead, yHead);
    ctx.closePath();
    ctx.fill();

    // 6b) Marcações laterais no acostamento (esquerda e direita)
    const zTickTail = Math.min(Z_FAR, zHead + tickLenZ);
    const pTickTail = projectPersp(zTickTail);
    const yTickTail = horizonY + pTickTail * roadHeight;

    const edgeInHead = topHalfW + pHead * (botHalfW - topHalfW);
    const edgeOutHead = topShoulderW + pHead * (botShoulderW - topShoulderW);
    const edgeInTail = topHalfW + pTickTail * (botHalfW - topHalfW);
    const edgeOutTail = topShoulderW + pTickTail * (botShoulderW - topShoulderW);

    const tickAlpha = Math.min(0.18, (0.015 + pHead * 0.15) * modeScale);
    ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${tickAlpha.toFixed(3)})`;

    // Esquerda
    ctx.beginPath();
    ctx.moveTo(cx - edgeOutTail, yTickTail);
    ctx.lineTo(cx - edgeInTail, yTickTail);
    ctx.lineTo(cx - edgeInHead, yHead);
    ctx.lineTo(cx - edgeOutHead, yHead);
    ctx.closePath();
    ctx.fill();

    // Direita
    ctx.beginPath();
    ctx.moveTo(cx + edgeInTail, yTickTail);
    ctx.lineTo(cx + edgeOutTail, yTickTail);
    ctx.lineTo(cx + edgeOutHead, yHead);
    ctx.lineTo(cx + edgeInHead, yHead);
    ctx.closePath();
    ctx.fill();

    // 6c) Postes/refletores discretos a cada 2 segmentos, fora do acostamento
    if (i % 2 === 0 && pHead > 0.04) {
      const postOffset = edgeOutHead + 5 + pHead * 18;
      const postH = 6 + pHead * 24;
      const postAlpha = Math.min(0.18, (0.02 + pHead * 0.15) * modeScale);
      const dotR = 1.2 + pHead * 1.9;

      ctx.strokeStyle = `rgba(180, 200, 224, ${(postAlpha * 0.6).toFixed(3)})`;
      ctx.lineWidth = Math.max(1, 0.8 + pHead * 1.1);
      ctx.beginPath();
      ctx.moveTo(cx - postOffset, yHead);
      ctx.lineTo(cx - postOffset, yHead - postH);
      ctx.moveTo(cx + postOffset, yHead);
      ctx.lineTo(cx + postOffset, yHead - postH);
      ctx.stroke();

      // Refletor discreto no topo do poste (sem shadowBlur)
      ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${(postAlpha * 0.38).toFixed(3)})`;
      ctx.beginPath();
      ctx.arc(cx - postOffset, yHead - postH, dotR * 2.1, 0, Math.PI * 2);
      ctx.arc(cx + postOffset, yHead - postH, dotR * 2.1, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${Math.min(0.28, postAlpha * 1.4).toFixed(3)})`;
      ctx.beginPath();
      ctx.arc(cx - postOffset, yHead - postH, dotR, 0, Math.PI * 2);
      ctx.arc(cx + postOffset, yHead - postH, dotR, 0, Math.PI * 2);
      ctx.fill();
    }
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
