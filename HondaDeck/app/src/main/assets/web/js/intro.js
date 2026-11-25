// Animação de abertura (~2,8 s) e animação do logo no topo ao tocar

import { el } from './util.js';

let currentBrand = 'HONDA';
let introDone = false;
let introStarted = false;
let introTimers = [];
let replayTimers = [];

function clearTimers(list) {
  while (list.length) clearTimeout(list.pop());
}

function populateLetters(container, brandText, initiallyShown = false) {
  container.replaceChildren();
  const chars = String(brandText || 'HONDA').split('');
  const spans = chars.map((ch) => {
    const sp = el('span', { className: initiallyShown ? 'brand-char shown' : 'brand-char' }, ch === ' ' ? '\u00A0' : ch);
    container.appendChild(sp);
    return sp;
  });
  return spans;
}

export function initIntro() {
  const topBtn = document.getElementById('top-logo-btn');
  const topEmblem = document.getElementById('top-emblem');
  const topLetters = document.getElementById('top-brand-letters');

  topEmblem.textContent = currentBrand.trim().charAt(0) || 'H';
  populateLetters(topLetters, currentBrand, true);

  topBtn.addEventListener('click', () => {
    replayTopLogo();
  });

  // Inicia a abertura após breve janela (90ms) para dar chance ao estado inicial informar introMode=="VIDEO"
  const bootTimer = setTimeout(() => {
    if (!introDone && !introStarted) {
      runFullIntro();
    }
  }, 90);
  introTimers.push(bootTimer);
}

export function skipIntroImmediately() {
  if (introDone) return;
  introDone = true;
  introStarted = true;
  clearTimers(introTimers);

  const overlay = document.getElementById('intro-overlay');
  const appShell = document.getElementById('app-shell');
  const topEmblem = document.getElementById('top-emblem');
  const topLetters = document.getElementById('top-brand-letters');

  overlay.classList.add('hidden');
  appShell.classList.remove('app-hidden');
  topEmblem.style.transform = 'translateX(0px) scale(1)';
  topEmblem.style.opacity = '1';
  populateLetters(topLetters, currentBrand, true);
}

export function updateBrandAndIntroMode(brand, introMode) {
  const nextBrand = String(brand || 'HONDA').trim() || 'HONDA';
  if (nextBrand !== currentBrand) {
    currentBrand = nextBrand;
    const initial = currentBrand.charAt(0);
    document.getElementById('top-emblem').textContent = initial;
    document.getElementById('intro-emblem').textContent = initial;
    populateLetters(document.getElementById('top-brand-letters'), currentBrand, true);
  }

  if (String(introMode || '').toUpperCase() === 'VIDEO' && !introDone) {
    skipIntroImmediately();
  }
}

export function runFullIntro() {
  const overlay = document.getElementById('intro-overlay');
  const stage = document.getElementById('intro-stage');
  const emblem = document.getElementById('intro-emblem');
  const lettersWrap = document.getElementById('intro-letters');
  const appShell = document.getElementById('app-shell');
  const topBtn = document.getElementById('top-logo-btn');

  clearTimers(introTimers);
  introStarted = true;
  introDone = false;

  overlay.classList.remove('hidden', 'intro-fade-bg');
  appShell.classList.add('app-hidden');

  emblem.textContent = currentBrand.charAt(0) || 'H';
  emblem.classList.remove('visible');
  lettersWrap.classList.remove('expanded');
  const letterSpans = populateLetters(lettersWrap, currentBrand, false);

  stage.style.transition = 'none';
  stage.style.transform = 'translate(0px, 0px) scale(1.75)';
  stage.style.opacity = '1';
  // Força reflow para aplicar estado inicial
  void stage.offsetWidth;
  stage.style.transition = 'transform 0.72s cubic-bezier(0.22, 0.85, 0.24, 1), opacity 0.35s ease';

  // 1) Emblema "H" surge e cresce no centro
  introTimers.push(setTimeout(() => {
    emblem.classList.add('visible');
  }, 80));

  // 2) Letras H O N D A aparecem uma a uma
  introTimers.push(setTimeout(() => {
    lettersWrap.classList.add('expanded');
  }, 640));

  letterSpans.forEach((sp, idx) => {
    introTimers.push(setTimeout(() => {
      sp.classList.add('shown');
    }, 720 + idx * 105));
  });

  // 3) Conjunto desliza e encolhe até a posição exata do logo no topo
  const slideAt = 720 + letterSpans.length * 105 + 380;
  introTimers.push(setTimeout(() => {
    const rect = topBtn.getBoundingClientRect();
    const cx = window.innerWidth / 2;
    const cy = window.innerHeight / 2;
    const tx = rect.width > 0 ? (rect.left + rect.width / 2 - cx) : 0;
    const ty = rect.height > 0 ? (rect.top + rect.height / 2 - cy) : -(cy - 44);
    stage.style.transform = `translate(${tx.toFixed(1)}px, ${ty.toFixed(1)}px) scale(1)`;
  }, slideAt));

  // 4) Painel surge com fade (~2,2 s -> 2,8 s)
  introTimers.push(setTimeout(() => {
    appShell.classList.remove('app-hidden');
    overlay.classList.add('intro-fade-bg');
  }, slideAt + 480));

  introTimers.push(setTimeout(() => {
    stage.style.opacity = '0';
  }, slideAt + 680));

  introTimers.push(setTimeout(() => {
    overlay.classList.add('hidden');
    introDone = true;
  }, 2800));
}

export function replayTopLogo() {
  clearTimers(replayTimers);
  const topEmblem = document.getElementById('top-emblem');
  const topLetters = document.getElementById('top-brand-letters');
  const spans = populateLetters(topLetters, currentBrand, false);
  const logoOff = Math.round((12 + currentBrand.length * 20) / 2);

  topEmblem.style.transition = 'none';
  topEmblem.style.opacity = '0';
  topEmblem.style.transform = `translateX(${logoOff}px) scale(0.5)`;
  void topEmblem.offsetWidth;
  topEmblem.style.transition = 'transform 0.75s cubic-bezier(0.2, 0.8, 0.2, 1), opacity 0.45s ease';

  replayTimers.push(setTimeout(() => {
    topEmblem.style.opacity = '1';
    topEmblem.style.transform = `translateX(${logoOff}px) scale(1.15)`;
  }, 80));

  replayTimers.push(setTimeout(() => {
    topEmblem.style.transform = 'translateX(0px) scale(1)';
  }, 820));

  spans.forEach((sp, i) => {
    replayTimers.push(setTimeout(() => {
      sp.classList.add('shown');
    }, 1020 + i * 90));
  });
}
