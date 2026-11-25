// Cartão grande de Clima detalhado: Agora + Próximas 24h + 7 Dias

import { el, createWeatherSvg, fmtNum, jsonSig } from './util.js';

let modalEl = null;
let cardEl = null;
let latestWeather = null;
let isOpen = false;
let lastRenderedSig = '';

function metricBox(label, value, sub = '') {
  return el('div', { className: 'wx-metric-box' },
    el('span', { className: 'wx-metric-label' }, label),
    el('span', { className: 'wx-metric-val' }, value),
    sub ? el('span', { className: 'wx-metric-sub' }, sub) : null
  );
}

function renderNowSection(w) {
  const available = Boolean(w && w.available);
  const temp = available ? `${Math.round(Number(w.tempC))}°C` : '--°C';
  const feels = available ? `Sensação ${Math.round(Number(w.feelsLikeC ?? w.tempC))}°C` : 'Sensação --°C';
  const range = available ? `Máx ${Math.round(Number(w.maxC))}° · Mín ${Math.round(Number(w.minC))}°` : 'Máx --° · Mín --°';
  const icon = createWeatherSvg(available ? w.kind : 'CLOUD');
  icon.setAttribute('width', '58');
  icon.setAttribute('height', '58');

  const windVal = available ? `${Math.round(Number(w.windKmh || 0))} km/h` : '--';
  const windSub = available && w.windDirLabel ? `Direção ${w.windDirLabel}` : '';
  const rainVal = available ? `${Math.round(Number(w.rainChancePct || 0))}%` : '--';
  const rainSub = available ? `${fmtNum(w.precipMm || 0, 1)} mm` : '';
  const uvVal = available ? fmtNum(w.uvIndex || 0, 1) : '--';
  const humVal = available ? `${Math.round(Number(w.humidityPct || 0))}%` : '--';
  const sunVal = available && w.sunrise ? w.sunrise : '--:--';
  const sunsetSub = available && w.sunset ? `Pôr ${w.sunset}` : '';

  return el('div', { className: 'wx-now-section' },
    el('div', { className: 'wx-now-hero' },
      el('div', { className: 'wx-now-icon' }, icon),
      el('div', { className: 'wx-now-main' },
        el('div', { className: 'wx-now-temp' }, temp),
        el('div', { className: 'wx-now-cond' }, available ? (w.summary || 'Clima local') : 'Aguardando GPS / internet'),
        el('div', { className: 'wx-now-sub' }, `${feels} · ${range}`)
      )
    ),
    el('div', { className: 'wx-metrics-grid' },
      metricBox('UMIDADE', humVal),
      metricBox('VENTO', windVal, windSub),
      metricBox('CHUVA', rainVal, rainSub),
      metricBox('ÍNDICE UV', uvVal),
      metricBox('SOL', sunVal, sunsetSub)
    )
  );
}

function renderHourlySection(w) {
  const hours = Array.isArray(w?.hourly) ? w.hourly.slice(0, 24) : [];
  if (hours.length === 0) {
    return el('div', { className: 'wx-section' },
      el('div', { className: 'wx-section-title' }, 'PRÓXIMAS 24 HORAS'),
      el('div', { className: 'wx-empty' }, 'Previsão horária indisponível.')
    );
  }

  const cards = hours.map((h) => {
    const ic = createWeatherSvg(h.kind || 'PARTLY_CLOUDY');
    ic.setAttribute('width', '26');
    ic.setAttribute('height', '26');
    const rain = Math.round(Number(h.rainChancePct || 0));
    return el('div', { className: 'wx-hour-item' },
      el('span', { className: 'wx-hour-time' }, h.hour || '--:--'),
      el('span', { className: 'wx-hour-icon' }, ic),
      el('span', { className: 'wx-hour-temp' }, `${Math.round(Number(h.tempC))}°`),
      el('span', { className: `wx-hour-rain${rain >= 30 ? ' wet' : ''}` }, `${rain}%`)
    );
  });

  return el('div', { className: 'wx-section' },
    el('div', { className: 'wx-section-title' }, 'PRÓXIMAS 24 HORAS'),
    el('div', { className: 'wx-hourly-strip' }, cards)
  );
}

function renderDailySection(w) {
  const days = Array.isArray(w?.daily) ? w.daily.slice(0, 7) : [];
  if (days.length === 0) {
    return el('div', { className: 'wx-section' },
      el('div', { className: 'wx-section-title' }, 'PRÓXIMOS 7 DIAS'),
      el('div', { className: 'wx-empty' }, 'Previsão semanal indisponível.')
    );
  }

  const globalMin = Math.min(...days.map((d) => Number(d.minC)));
  const globalMax = Math.max(...days.map((d) => Number(d.maxC)));
  const span = Math.max(1, globalMax - globalMin);

  const rows = days.map((d) => {
    const minC = Math.round(Number(d.minC));
    const maxC = Math.round(Number(d.maxC));
    const leftPct = Math.max(0, Math.min(100, ((minC - globalMin) / span) * 100));
    const widthPct = Math.max(8, Math.min(100 - leftPct, ((maxC - minC) / span) * 100));
    const fill = el('div', { className: 'wx-day-bar-fill' });
    fill.style.left = `${leftPct.toFixed(1)}%`;
    fill.style.width = `${widthPct.toFixed(1)}%`;

    const ic = createWeatherSvg(d.kind || 'PARTLY_CLOUDY');
    ic.setAttribute('width', '24');
    ic.setAttribute('height', '24');
    const rain = Math.round(Number(d.rainChancePct || 0));

    return el('div', { className: 'wx-day-row' },
      el('span', { className: 'wx-day-name' }, d.day || '--'),
      el('span', { className: 'wx-day-icon' }, ic),
      el('span', { className: `wx-day-rain${rain >= 30 ? ' wet' : ''}` }, `${rain}%`),
      el('span', { className: 'wx-day-min' }, `${minC}°`),
      el('div', { className: 'wx-day-bar-track' }, fill),
      el('span', { className: 'wx-day-max' }, `${maxC}°`)
    );
  });

  return el('div', { className: 'wx-section' },
    el('div', { className: 'wx-section-title' }, 'PRÓXIMOS 7 DIAS'),
    el('div', { className: 'wx-daily-list' }, rows)
  );
}

function render(force = false) {
  if (!cardEl || !isOpen) return;
  const w = latestWeather || {};
  const sig = jsonSig(w);
  if (!force && sig === lastRenderedSig && cardEl.childElementCount > 0) {
    return;
  }
  lastRenderedSig = sig;

  // Preserva posição de rolagem caso o clima atualize enquanto o motorista lê o cartão
  const prevBody = cardEl.querySelector('.wx-modal-body');
  const prevScrollTop = prevBody ? prevBody.scrollTop : 0;
  const prevStrip = cardEl.querySelector('.wx-hourly-strip');
  const prevScrollLeft = prevStrip ? prevStrip.scrollLeft : 0;

  const city = w.city || 'Anápolis';

  const header = el('div', { className: 'modal-card-header' },
    el('div', { className: 'modal-card-title-group' },
      el('span', { className: 'modal-card-eyebrow' }, 'PREVISÃO DO TEMPO'),
      el('h2', { className: 'modal-card-title' }, city)
    ),
    el('button', {
      type: 'button',
      className: 'close-hero-btn',
      dataset: { test: 'weather-close' },
      'aria-label': 'Fechar clima',
      onClick: closeWeatherCard
    }, '✕')
  );

  const body = el('div', { className: 'wx-modal-body', dataset: { test: 'weather-scroll' } },
    renderNowSection(w),
    renderHourlySection(w),
    renderDailySection(w)
  );

  cardEl.replaceChildren(header, body);
  if (!force && prevScrollTop > 0) {
    body.scrollTop = prevScrollTop;
  }
  if (!force && prevScrollLeft > 0) {
    const nextStrip = body.querySelector('.wx-hourly-strip');
    if (nextStrip) nextStrip.scrollLeft = prevScrollLeft;
  }
}

export function initWeatherCard() {
  modalEl = document.getElementById('weather-modal');
  cardEl = document.getElementById('weather-card');
  if (!modalEl || !cardEl) return;

  modalEl.addEventListener('click', (e) => {
    if (e.target === modalEl) closeWeatherCard();
  });
}

export function openWeatherCard() {
  if (!modalEl) return;
  isOpen = true;
  lastRenderedSig = '';
  modalEl.classList.remove('hidden');
  modalEl.classList.add('open');
  render(true);
}

export function closeWeatherCard() {
  if (!modalEl) return;
  isOpen = false;
  lastRenderedSig = '';
  modalEl.classList.remove('open');
  modalEl.classList.add('hidden');
}

export function updateWeatherCard(weather) {
  latestWeather = weather || null;
  if (isOpen) render(false);
}
