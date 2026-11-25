// Executor de auditoria automática do HondaDeck 3.0 no tablet (CDP + ADB).
// Lê scenarios.json e gera D:\car\multimidia\HondaDeck\results\v3\auditoria-<data>.json e .md.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  APP_PACKAGE,
  DEFAULT_ADB_PATH,
  checkScreenLock,
  clampTimeout,
  clearLogcat,
  installApk,
  keepScreenOn,
  listDevices,
  logcatErrors,
  pidof,
  screencap,
  startApp,
} from './adb.mjs';
import { DEFAULT_CDP_PORT, connectWebView, isLikelyJsExpression } from './cdp.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const DEFAULT_SCENARIOS_FILE = resolve(HERE, 'scenarios.json');
const DEFAULT_RESULTS_DIR = 'D:\\car\\multimidia\\HondaDeck\\results\\v3';

// Lista canônica de comandos JS -> Kotlin definidos em .agy/contrato-ponte.md.
export const VALID_BRIDGE_CMDS = new Set([
  'ui.ready',
  'ui.setAccent',
  'ui.mode',
  'audio.setVolume',
  'audio.toggleMute',
  'music.toggle',
  'music.next',
  'music.prev',
  'music.shuffle',
  'music.repeat',
  'music.like',
  'music.seek',
  'music.playUri',
  'music.search',
  'music.authorize',
  'music.openApp',
  'map.search',
  'map.route',
  'map.stopRoute',
  'map.openExternal',
  'fav.save',
  'fav.delete',
  'fav.useCurrentLocation',
  'copilot.listen',
  'copilot.cancel',
  'copilot.ask',
  'copilot.live.start',
  'copilot.live.stop',
  'copilot.ask.voice',
  'copilot.clearMemory',
  'notif.read',
  'notif.dismiss',
  'obd.connect',
  'obd.disconnect',
  'obd.readDtc',
  'obd.clearDtc',
  'obd.setSimulator',
  'obd.selectDevice',
  'fuel.log',
  'fuel.delete',
  'fuel.tank',
  'settings.set',
  'system.downloadOfflineMap',
  'camera.toggle',
  'calendar.refresh',
  'calendar.requestPermission',
]);

const VALID_ACTIONS = new Set(['click', 'type', 'eval', 'wait', 'screenshot', 'send']);
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function formatTimestampForFile(date = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}_` +
    `${pad(date.getHours())}-${pad(date.getMinutes())}-${pad(date.getSeconds())}`
  );
}

function slugify(text) {
  return String(text || 'item')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 48) || 'item';
}

/**
 * Compila e valida sintaticamente uma expressão/bloco JS e devolve o código IIFE assíncrono para o CDP.
 */
export function compilePageExpression(rawExpr, saveAs = null) {
  const clean = String(rawExpr ?? '').replace(/^js:/, '').trim();
  if (!clean) {
    throw new Error('Expressão JS vazia.');
  }

  let bodyCode;
  try {
    new AsyncFunction('state', 'vars', `return (\n${clean}\n);`);
    bodyCode = `return (\n${clean}\n);`;
  } catch {
    // Tenta como bloco de instruções com `return` explícito.
    try {
      new AsyncFunction('state', 'vars', clean);
      bodyCode = clean;
    } catch (syntaxErr) {
      throw new Error(`Erro de sintaxe na expressão JS "${clean}": ${syntaxErr.message}`);
    }
  }

  const saveSnippet = saveAs
    ? `window.__auditVars[${JSON.stringify(String(saveAs))}] = __val;`
    : '';

  return `(async () => {
    window.__auditVars = window.__auditVars || {};
    const state = (window.HD && window.HD.lastState !== undefined) ? window.HD.lastState : null;
    const vars = window.__auditVars;
    const __fn = async (state, vars) => { ${bodyCode} };
    const __val = await __fn(state, vars);
    ${saveSnippet}
    return __val;
  })()`;
}

/**
 * Valida integralmente a estrutura de `scenarios.json` contra o contrato e sintaxe JS.
 */
export function validateScenarios(scenarios) {
  const errors = [];
  if (!Array.isArray(scenarios) || scenarios.length === 0) {
    errors.push('O arquivo de cenários deve conter um array JSON não vazio.');
    return errors;
  }

  scenarios.forEach((sc, idx) => {
    const label = `Cenário #${idx + 1} (${sc?.id || sc?.name || 'sem-nome'})`;
    if (!sc || typeof sc !== 'object') {
      errors.push(`${label}: deve ser um objeto.`);
      return;
    }
    if (!sc.name || typeof sc.name !== 'string') {
      errors.push(`${label}: campo obrigatório "name" ausente ou inválido.`);
    }
    if (!Array.isArray(sc.steps)) {
      errors.push(`${label}: campo obrigatório "steps" deve ser um array.`);
    } else {
      sc.steps.forEach((st, sIdx) => {
        const sLabel = `${label} -> passo #${sIdx + 1}`;
        if (!st || typeof st !== 'object' || !VALID_ACTIONS.has(st.do)) {
          errors.push(
            `${sLabel}: ação "do" inválida (${JSON.stringify(st?.do)}). Esperado: ${[...VALID_ACTIONS].join('|')}`
          );
          return;
        }
        if (st.do === 'click' && (!st.selector || typeof st.selector !== 'string')) {
          errors.push(`${sLabel} (click): requer "selector" string.`);
        }
        if (st.do === 'type') {
          if (!st.selector || typeof st.selector !== 'string') {
            errors.push(`${sLabel} (type): requer "selector" string.`);
          }
          if (st.text === undefined || st.text === null) {
            errors.push(`${sLabel} (type): requer "text".`);
          }
        }
        if (st.do === 'eval') {
          if (!st.expr || typeof st.expr !== 'string') {
            errors.push(`${sLabel} (eval): requer "expr" string.`);
          } else {
            try {
              compilePageExpression(st.expr, st.saveAs);
            } catch (e) {
              errors.push(`${sLabel} (eval): ${e.message}`);
            }
          }
        }
        if (st.do === 'wait') {
          const hasMs = typeof st.ms === 'number' && st.ms > 0;
          const target = st.for || st.selector || st.expr;
          if (!hasMs && (!target || typeof target !== 'string')) {
            errors.push(`${sLabel} (wait): requer "ms" (> 0) ou "for"/"selector"/"expr".`);
          }
          if (target && typeof target === 'string' && isLikelyJsExpression(target)) {
            try {
              compilePageExpression(target);
            } catch (e) {
              errors.push(`${sLabel} (wait): ${e.message}`);
            }
          }
        }
        if (st.do === 'screenshot' && st.mode && !['cdp', 'adb', 'both'].includes(st.mode)) {
          errors.push(`${sLabel} (screenshot): "mode" inválido (${st.mode}).`);
        }
        if (st.do === 'send') {
          if (!st.cmd || !VALID_BRIDGE_CMDS.has(st.cmd)) {
            errors.push(
              `${sLabel} (send): comando "${st.cmd}" não consta no contrato (.agy/contrato-ponte.md).`
            );
          }
        }
      });
    }

    if (!Array.isArray(sc.expect)) {
      errors.push(`${label}: campo obrigatório "expect" deve ser um array.`);
    } else {
      sc.expect.forEach((ex, eIdx) => {
        const eLabel = `${label} -> expect #${eIdx + 1}`;
        if (typeof ex === 'string') {
          if (!ex.trim()) errors.push(`${eLabel}: string vazia.`);
          else if (isLikelyJsExpression(ex)) {
            try {
              compilePageExpression(ex);
            } catch (e) {
              errors.push(`${eLabel}: ${e.message}`);
            }
          }
          return;
        }
        if (!ex || typeof ex !== 'object') {
          errors.push(`${eLabel}: deve ser string ou objeto.`);
          return;
        }
        if (!ex.selector && !ex.expr) {
          errors.push(`${eLabel}: informe ao menos "selector" ou "expr".`);
        }
        if (ex.expr) {
          try {
            compilePageExpression(ex.expr);
          } catch (e) {
            errors.push(`${eLabel} (expr): ${e.message}`);
          }
        }
        if (ex.whenExpr) {
          try {
            compilePageExpression(ex.whenExpr);
          } catch (e) {
            errors.push(`${eLabel} (whenExpr): ${e.message}`);
          }
        }
      });
    }
  });

  return errors;
}

export function parseArgs(argv) {
  const opts = {
    dryRun: false,
    scenariosPath: DEFAULT_SCENARIOS_FILE,
    outDir: DEFAULT_RESULTS_DIR,
    only: '',
    installApkPath: null,
    doInstall: false,
    noStart: false,
    skipAdb: false,
    urlFilter: 'appassets.androidplatform.net',
    port: DEFAULT_CDP_PORT,
    serial: process.env.ANDROID_SERIAL || '',
    stepTimeoutMs: 12_000,
    writeReportOnDryRun: false,
  };

  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dry-run') opts.dryRun = true;
    else if (a === '--write-report') opts.writeReportOnDryRun = true;
    else if (a === '--no-start') opts.noStart = true;
    else if (a === '--skip-adb') opts.skipAdb = true;
    else if (a === '--scenarios' && argv[i + 1]) opts.scenariosPath = resolve(argv[++i]);
    else if (a === '--out-dir' && argv[i + 1]) opts.outDir = resolve(argv[++i]);
    else if (a === '--only' && argv[i + 1]) opts.only = argv[++i];
    else if (a === '--url-filter' && argv[i + 1]) opts.urlFilter = argv[++i];
    else if (a === '--port' && argv[i + 1]) opts.port = Number(argv[++i]) || DEFAULT_CDP_PORT;
    else if (a === '--serial' && argv[i + 1]) opts.serial = argv[++i];
    else if (a === '--timeout' && argv[i + 1]) opts.stepTimeoutMs = clampTimeout(argv[++i]);
    else if (a === '--install') {
      opts.doInstall = true;
      if (argv[i + 1] && !argv[i + 1].startsWith('--')) {
        opts.installApkPath = argv[++i];
      }
    } else if (a === '--help' || a === '-h') {
      opts.help = true;
    }
  }
  return opts;
}

function printHelp() {
  console.log(`Uso: node tools/audit/run-audit.mjs [opções]

Opções:
  --dry-run              Valida scenarios.json, contrato da ponte e sintaxe JS sem conectar ao tablet
  --scenarios <arquivo>  Caminho do scenarios.json (padrão: tools/audit/scenarios.json)
  --only <filtro>        Executa apenas cenários cujo id ou nome contenha o texto informado
  --out-dir <pasta>      Pasta de saída dos relatórios (padrão: ${DEFAULT_RESULTS_DIR})
  --install [apk]        Instala o APK debug antes de iniciar a auditoria (mantendo dados)
  --no-start             Não executa 'am start' antes de conectar à WebView
  --skip-adb             Conecta direto na porta CDP local (útil para testar mock no PC)
  --url-filter <trecho>  Filtro de URL do alvo CDP (padrão: appassets.androidplatform.net)
  --port <porta>         Porta TCP local do CDP (padrão: 9222)
  --serial <id>          Serial ADB específico quando há mais de um dispositivo
  --timeout <ms>         Timeout padrão por passo em ms (máx. 20000)`);
}

async function captureEvidence(cdp, adbOpts, fileBase, mode = 'cdp') {
  const results = [];
  if (mode === 'adb' || mode === 'both') {
    const adbFile = mode === 'both' ? `${fileBase}-adb.png` : `${fileBase}.png`;
    const r = await screencap(adbFile, adbOpts);
    results.push(r.file);
  }
  if (mode === 'cdp' || mode === 'both') {
    const cdpFile = mode === 'both' ? `${fileBase}-cdp.png` : `${fileBase}.png`;
    try {
      const r = await cdp.screenshot(cdpFile);
      results.push(r.file);
    } catch (err) {
      if (mode === 'cdp' && !adbOpts.skipAdb) {
        // Fallback automático para screencap ADB se a captura CDP falhar.
        const fallback = await screencap(cdpFile, adbOpts);
        results.push(fallback.file);
      } else {
        throw err;
      }
    }
  }
  return results;
}

async function evaluateExpectation(cdp, rawExpect, defaultWaitMs = 3_500) {
  const exp =
    typeof rawExpect === 'string'
      ? isLikelyJsExpression(rawExpect)
        ? { desc: rawExpect, expr: rawExpect }
        : { desc: `Seletor presente: ${rawExpect}`, selector: rawExpect }
      : rawExpect;

  const desc = exp.desc || exp.selector || exp.expr || 'Verificação';

  if (exp.whenExpr) {
    const condIife = compilePageExpression(exp.whenExpr);
    const condOk = Boolean(await cdp.evaluate(condIife, 6_000));
    if (!condOk) {
      return {
        status: 'PENDENTE',
        desc,
        detail: exp.pendingReason || `Pré-condição externa não atendida (${exp.whenExpr})`,
      };
    }
  }

  const waitMs = clampTimeout(exp.waitMs ?? defaultWaitMs, 3_500);
  const deadline = Date.now() + waitMs;
  let lastDetail = '';

  while (true) {
    try {
      let ok = true;
      const details = [];

      if (exp.selector) {
        const shouldExist = exp.exists !== false;
        const requireVisible = Boolean(exp.visible);
        const textNeedle = exp.textContains ? String(exp.textContains).toLowerCase() : null;
        const domCheck = await cdp.evaluate(
          `(() => {
            const sel = ${JSON.stringify(exp.selector)};
            const nodes = Array.from(document.querySelectorAll(sel));
            const visNodes = nodes.filter((el) => {
              const st = window.getComputedStyle(el);
              if (st.display === 'none' || st.visibility === 'hidden') return false;
              const r = el.getBoundingClientRect();
              return r.width > 0 && r.height > 0;
            });
            const activeNodes = ${JSON.stringify(requireVisible)} ? visNodes : nodes;
            const texts = activeNodes.map((el) => (el.textContent || el.value || '').trim());
            return { total: nodes.length, visible: visNodes.length, texts };
          })()`,
          5_000
        );

        const count = requireVisible ? domCheck.visible : domCheck.total;
        if (shouldExist && count === 0) {
          ok = false;
          details.push(
            `seletor ${exp.selector} não encontrado${requireVisible ? ' visível' : ''}`
          );
        } else if (!shouldExist && domCheck.visible > 0) {
          ok = false;
          details.push(`seletor ${exp.selector} ainda está visível (${domCheck.visible})`);
        } else if (shouldExist && textNeedle) {
          const hit = (domCheck.texts || []).some((t) => t.toLowerCase().includes(textNeedle));
          if (!hit) {
            ok = false;
            details.push(
              `texto "${exp.textContains}" não encontrado em ${exp.selector} (atual: ${JSON.stringify(
                (domCheck.texts || []).slice(0, 2)
              )})`
            );
          }
        }
      }

      if (ok && exp.expr) {
        const iife = compilePageExpression(exp.expr);
        const val = await cdp.evaluate(iife, 6_000);
        if (!val) {
          ok = false;
          details.push(`expressão retornou ${JSON.stringify(val)}: ${exp.expr}`);
        }
      }

      if (ok) {
        return { status: 'OK', desc, detail: 'Verificado com sucesso' };
      }
      lastDetail = details.join('; ');
    } catch (err) {
      lastDetail = err.message;
    }

    if (Date.now() + 200 > deadline) break;
    await sleep(200);
  }

  return {
    status: 'FALHA',
    desc,
    detail: lastDetail || 'Condição não satisfeita dentro do limite de espera',
  };
}

function renderMarkdownReport(report, outDir) {
  const relPath = (p) => (p ? relative(outDir, p).replace(/\\/g, '/') : '—');
  const lines = [];
  lines.push(`# Relatório de Auditoria Automática — HondaDeck 3.0`);
  lines.push('');
  lines.push(`- **Data/Hora:** ${report.startedAt}`);
  lines.push(`- **Duração:** ${(report.durationMs / 1000).toFixed(1)} s`);
  lines.push(
    `- **Dispositivo:** ${
      report.device
        ? `${report.device.serial} (${report.device.model || report.device.state})`
        : 'N/D'
    }`
  );
  lines.push(`- **PID / WebView:** ${report.pid ?? 'N/D'} · \`${report.targetUrl || 'N/D'}\``);
  lines.push(
    `- **Resumo:** **${report.summary.ok} OK** · **${report.summary.failed} FALHA** · **${report.summary.pending} PENDENTE** (Total: ${report.summary.total})`
  );
  lines.push('');

  if (report.blockedReason) {
    lines.push(`> **BLOQUEIO DETECTADO:** ${report.blockedReason}`);
    lines.push('');
  }

  lines.push('## Tabela de Resultados');
  lines.push('');
  lines.push('| # | Cenário | Resultado | Evidência | Observação |');
  lines.push('|---:|---|:---:|---|---|');

  report.scenarios.forEach((sc, i) => {
    const evLinks =
      sc.screenshots && sc.screenshots.length > 0
        ? sc.screenshots
            .map((f, idx) => `[captura-${idx + 1}](${relPath(f)})`)
            .join(', ')
        : '—';
    const obs = (sc.observation || '—').replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
    lines.push(`| ${i + 1} | ${sc.name} | **${sc.status}** | ${evLinks} | ${obs} |`);
  });

  lines.push('');
  lines.push('## Detalhes de Falhas e Pendências');
  lines.push('');

  const nonOk = report.scenarios.filter((s) => s.status !== 'OK');
  if (nonOk.length === 0) {
    lines.push('Nenhuma falha ou pendência registrada.');
  } else {
    for (const sc of nonOk) {
      lines.push(`### ${sc.name} (${sc.status})`);
      if (sc.error) {
        lines.push(`- **Erro de passo:** \`${sc.error}\``);
      }
      for (const ex of sc.expectations || []) {
        if (ex.status !== 'OK') {
          lines.push(`- **[${ex.status}]** ${ex.desc} — ${ex.detail}`);
        }
      }
      if (sc.consoleErrors?.length) {
        lines.push(`- **Erros no console durante o cenário (${sc.consoleErrors.length}):**`);
        for (const ce of sc.consoleErrors.slice(0, 5)) {
          lines.push(`  - \`[${ce.type}] ${ce.text}\``);
        }
      }
      lines.push('');
    }
  }

  if (report.health) {
    lines.push('## Saúde Global (Console WebView & Logcat Android)');
    lines.push('');
    lines.push(`- **Erros totais no console da WebView:** ${report.health.consoleErrorCount}`);
    lines.push(
      `- **Ocorrências fatais do app no logcat (FATAL/ANR/Uncaught):** ${report.health.logcatAppFatalCount}`
    );
    if (report.health.logcatAppMatches?.length) {
      lines.push('');
      lines.push('```text');
      lines.push(...report.health.logcatAppMatches.slice(0, 20));
      lines.push('```');
    }
  }

  lines.push('');
  return lines.join('\n');
}

function saveReportFiles(report, outDir, fileStamp) {
  mkdirSync(outDir, { recursive: true });
  const jsonFile = resolve(outDir, `auditoria-${fileStamp}.json`);
  const mdFile = resolve(outDir, `auditoria-${fileStamp}.md`);
  writeFileSync(jsonFile, JSON.stringify(report, null, 2), 'utf8');
  writeFileSync(mdFile, renderMarkdownReport(report, outDir), 'utf8');
  return { jsonFile, mdFile };
}

async function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (opts.help) {
    printHelp();
    return;
  }

  if (!existsSync(opts.scenariosPath)) {
    throw new Error(`Arquivo de cenários não encontrado: ${opts.scenariosPath}`);
  }

  const allScenarios = JSON.parse(readFileSync(opts.scenariosPath, 'utf8'));
  const validationErrors = validateScenarios(allScenarios);
  if (validationErrors.length > 0) {
    console.error('[run-audit] Erros de validação em scenarios.json:');
    for (const err of validationErrors) {
      console.error(`  - ${err}`);
    }
    process.exit(1);
  }

  const selectedScenarios = opts.only
    ? allScenarios.filter((sc) => {
        const needle = opts.only.toLowerCase();
        return (
          String(sc.id || '').toLowerCase().includes(needle) ||
          String(sc.name || '').toLowerCase().includes(needle)
        );
      })
    : allScenarios;

  if (selectedScenarios.length === 0) {
    throw new Error(`Nenhum cenário corresponde ao filtro --only "${opts.only}".`);
  }

  if (opts.dryRun) {
    const totalSteps = selectedScenarios.reduce((acc, s) => acc + s.steps.length, 0);
    const totalExpects = selectedScenarios.reduce((acc, s) => acc + s.expect.length, 0);
    const usedCmds = new Set();
    const usedSelectors = new Set();

    for (const sc of selectedScenarios) {
      for (const st of sc.steps) {
        if (st.do === 'send') usedCmds.add(st.cmd);
        if (st.selector) usedSelectors.add(st.selector);
        if (st.for && !isLikelyJsExpression(st.for)) usedSelectors.add(st.for);
      }
      for (const ex of sc.expect) {
        const sel = typeof ex === 'string' && !isLikelyJsExpression(ex) ? ex : ex?.selector;
        if (sel) usedSelectors.add(sel);
      }
    }

    const drySummary = {
      mode: 'dry-run',
      scenariosFile: opts.scenariosPath,
      adbBinaryExists: existsSync(DEFAULT_ADB_PATH),
      adbPath: DEFAULT_ADB_PATH,
      scenariosValidated: selectedScenarios.length,
      totalSteps,
      totalExpectations: totalExpects,
      bridgeCommandsUsed: [...usedCmds].sort(),
      uniqueSelectorsCount: usedSelectors.size,
      scenarios: selectedScenarios.map((sc, idx) => ({
        index: idx + 1,
        id: sc.id || slugify(sc.name),
        name: sc.name,
        steps: sc.steps.length,
        expectations: sc.expect.length,
      })),
    };

    console.log(JSON.stringify(drySummary, null, 2));
    console.log(
      `[run-audit] DRY-RUN OK: ${selectedScenarios.length} cenários, ${totalSteps} passos e ${totalExpects} verificações validados sem erros.`
    );
    return;
  }

  const startedDate = new Date();
  const fileStamp = formatTimestampForFile(startedDate);
  const shotsDir = resolve(opts.outDir, 'capturas', fileStamp);
  const adbOpts = { serial: opts.serial, skipAdb: opts.skipAdb };

  const report = {
    startedAt: startedDate.toISOString(),
    durationMs: 0,
    device: null,
    pid: null,
    targetUrl: null,
    blockedReason: null,
    summary: { total: 0, ok: 0, failed: 0, pending: 0 },
    scenarios: [],
    health: null,
  };

  let cdp = null;
  try {
    if (!opts.skipAdb) {
      const devices = await listDevices(adbOpts);
      const online = devices.filter((d) => d.state === 'device');
      if (online.length === 0) {
        report.blockedReason =
          devices.length === 0
            ? 'Nenhum dispositivo Android conectado via USB/ADB.'
            : `Dispositivo ADB não autorizado ou offline (${devices.map((d) => `${d.serial}:${d.state}`).join(', ')}).`;
        report.durationMs = Date.now() - startedDate.getTime();
        const files = saveReportFiles(report, opts.outDir, fileStamp);
        console.error(`[run-audit] BLOQUEADO: ${report.blockedReason}`);
        console.error(`[run-audit] Relatório salvo em: ${files.mdFile}`);
        process.exit(2);
      }

      report.device = opts.serial
        ? online.find((d) => d.serial === opts.serial) || online[0]
        : online[0];
      adbOpts.serial = report.device.serial;

      await keepScreenOn(true, adbOpts);
      const lockState = await checkScreenLock(adbOpts);
      if (lockState.locked) {
        // Regra estrita do plano mestre: NUNCA digitar senha/PIN; parar imediatamente e relatar.
        report.blockedReason =
          `Tela do tablet está bloqueada (isKeyguardShowing=${lockState.isKeyguardShowing}, ` +
          `mDreamingLockscreen=${lockState.mDreamingLockscreen}). Desbloqueie manualmente o tablet; ` +
          `nenhuma senha/PIN foi digitada conforme regra do plano mestre.`;
        report.durationMs = Date.now() - startedDate.getTime();
        const files = saveReportFiles(report, opts.outDir, fileStamp);
        console.error(`[run-audit] PARADO (TELA BLOQUEADA): ${report.blockedReason}`);
        console.error(`[run-audit] Relatório salvo em: ${files.mdFile}`);
        process.exit(3);
      }

      if (opts.doInstall) {
        console.log('[run-audit] Instalando APK no tablet...');
        await installApk(opts.installApkPath, adbOpts);
      }

      await clearLogcat(adbOpts);

      if (!opts.noStart) {
        console.log('[run-audit] Abrindo HondaDeck no tablet...');
        await startApp(adbOpts);
        await sleep(1_200);
      }

      report.pid = await pidof(APP_PACKAGE, adbOpts);
    }

    console.log('[run-audit] Conectando ao DevTools da WebView (CDP)...');
    cdp = await connectWebView({
      port: opts.port,
      pid: report.pid,
      skipAdbForward: opts.skipAdb,
      urlFilter: opts.urlFilter,
      adbOpts,
    });
    report.pid = cdp.meta.pid ?? report.pid;
    report.targetUrl = cdp.target?.url || '';

    // Garante que a ponte enviou ui.ready caso ainda não haja window.HD.lastState.
    await cdp
      .evaluate(
        `(() => {
          window.__auditVars = {};
          if (window.HD && !window.HD.lastState && window.HondaBridge && typeof window.HondaBridge.send === 'function') {
            window.HondaBridge.send(JSON.stringify({ cmd: 'ui.ready', args: {} }));
          }
          return Boolean(window.HD);
        })()`,
        6_000
      )
      .catch(() => {});

    for (let i = 0; i < selectedScenarios.length; i++) {
      const sc = selectedScenarios[i];
      const scSlug = `${String(i + 1).padStart(2, '0')}-${slugify(sc.id || sc.name)}`;
      const scStart = Date.now();
      const errorsBefore = cdp.consoleErrors().length;
      const screenshots = [];
      const stepLogs = [];
      let stepError = null;

      console.log(`[run-audit] (${i + 1}/${selectedScenarios.length}) ${sc.name}...`);

      for (let sIdx = 0; sIdx < sc.steps.length; sIdx++) {
        const st = sc.steps[sIdx];
        const timeoutMs = clampTimeout(st.timeoutMs ?? opts.stepTimeoutMs, opts.stepTimeoutMs);
        try {
          if (st.do === 'click') {
            const info = await cdp.click(st.selector, timeoutMs);
            stepLogs.push({ step: sIdx + 1, do: 'click', selector: st.selector, ok: true, info });
          } else if (st.do === 'type') {
            const info = await cdp.type(st.selector, st.text, {
              timeoutMs,
              clear: st.clear !== false,
              submit: Boolean(st.submit),
            });
            stepLogs.push({ step: sIdx + 1, do: 'type', selector: st.selector, ok: true, info });
          } else if (st.do === 'eval') {
            const iife = compilePageExpression(st.expr, st.saveAs);
            const value = await cdp.evaluate(iife, timeoutMs);
            stepLogs.push({ step: sIdx + 1, do: 'eval', saveAs: st.saveAs || null, ok: true, value });
          } else if (st.do === 'wait') {
            const target = st.for || st.selector || st.expr;
            if (target) {
              const condTarget =
                isLikelyJsExpression(target) && !String(target).startsWith('js:')
                  ? `js:${compilePageExpression(target)}`
                  : target;
              await cdp.waitFor(condTarget, timeoutMs, { visible: st.visible !== false });
            }
            if (typeof st.ms === 'number' && st.ms > 0) {
              await sleep(clampTimeout(st.ms, st.ms));
            }
            stepLogs.push({ step: sIdx + 1, do: 'wait', ok: true });
          } else if (st.do === 'send') {
            const info = await cdp.sendBridge(st.cmd, st.args ?? {}, timeoutMs);
            stepLogs.push({ step: sIdx + 1, do: 'send', cmd: st.cmd, ok: true, info });
          } else if (st.do === 'screenshot') {
            const shotSlug = slugify(st.name || `passo-${sIdx + 1}`);
            const base = resolve(shotsDir, `${scSlug}-${shotSlug}`);
            const saved = await captureEvidence(cdp, adbOpts, base, st.mode || 'cdp');
            screenshots.push(...saved);
            stepLogs.push({ step: sIdx + 1, do: 'screenshot', ok: true, files: saved });
          }
        } catch (err) {
          if (st.optional) {
            stepLogs.push({
              step: sIdx + 1,
              do: st.do,
              optional: true,
              skipped: true,
              reason: err.message,
            });
          } else {
            stepError = `Passo #${sIdx + 1} (${st.do}): ${err.message}`;
            stepLogs.push({ step: sIdx + 1, do: st.do, ok: false, error: err.message });
            break;
          }
        }
      }

      const expectations = [];
      if (!stepError) {
        for (const ex of sc.expect) {
          const res = await evaluateExpectation(cdp, ex);
          expectations.push(res);
        }
      }

      // Garante ao menos uma captura de evidência por cenário.
      if (screenshots.length === 0) {
        const autoBase = resolve(shotsDir, `${scSlug}-${stepError ? 'erro' : 'final'}`);
        const autoSaved = await captureEvidence(cdp, adbOpts, autoBase, 'cdp').catch(() => []);
        screenshots.push(...autoSaved);
      }

      const allErrorsNow = cdp.consoleErrors();
      const newConsoleErrors = allErrorsNow.slice(errorsBefore);

      let status = 'OK';
      const notes = [];
      if (stepError) {
        status = 'FALHA';
        notes.push(stepError);
      } else if (expectations.some((e) => e.status === 'FALHA')) {
        status = 'FALHA';
        notes.push(
          ...expectations.filter((e) => e.status === 'FALHA').map((e) => `${e.desc}: ${e.detail}`)
        );
      } else if (expectations.some((e) => e.status === 'PENDENTE')) {
        status = 'PENDENTE';
        notes.push(
          ...expectations.filter((e) => e.status === 'PENDENTE').map((e) => `${e.desc}: ${e.detail}`)
        );
      } else {
        notes.push(`${expectations.length} verificação(ões) aprovada(s)`);
      }

      if (newConsoleErrors.length > 0) {
        notes.push(`${newConsoleErrors.length} erro(s) no console da WebView`);
      }

      report.scenarios.push({
        id: sc.id || scSlug,
        name: sc.name,
        status,
        durationMs: Date.now() - scStart,
        observation: notes.join(' | '),
        error: stepError,
        screenshots,
        steps: stepLogs,
        expectations,
        consoleErrors: newConsoleErrors,
      });
    }

    // Verificação global de saúde: Console da WebView e Logcat Android (item 3.g da Fase 7).
    const totalConsoleErrors = cdp.consoleErrors();
    const logcat = opts.skipAdb
      ? { appFatalCount: 0, matchedCount: 0, appMatches: [], hasFatal: false }
      : await logcatErrors(adbOpts);

    report.health = {
      consoleErrorCount: totalConsoleErrors.length,
      consoleErrors: totalConsoleErrors,
      logcatMatchedCount: logcat.matchedCount,
      logcatAppFatalCount: logcat.appFatalCount,
      logcatAppMatches: logcat.appMatches,
    };

    const healthShots = opts.skipAdb
      ? []
      : await captureEvidence(
          cdp,
          adbOpts,
          resolve(shotsDir, '99-saude-final-tela'),
          'adb'
        ).catch(() => []);

    const healthIssues = [];
    if (totalConsoleErrors.length > 0) {
      healthIssues.push(
        `${totalConsoleErrors.length} erro(s) no console (${totalConsoleErrors[0].text.slice(0, 100)})`
      );
    }
    if (logcat.hasFatal) {
      healthIssues.push(
        `${logcat.appFatalCount} linha(s) FATAL/ANR/Uncaught no logcat (${(
          logcat.appMatches[0] || ''
        ).slice(0, 100)})`
      );
    }

    report.scenarios.push({
      id: 'saude-console-e-logcat',
      name: 'Saúde global: Console da WebView e Logcat sem FATAL/ANR',
      status: healthIssues.length === 0 ? 'OK' : 'FALHA',
      durationMs: 0,
      observation:
        healthIssues.length === 0
          ? 'Zero erros no console da WebView e zero FATAL/ANR/Uncaught no logcat'
          : healthIssues.join(' | '),
      error: null,
      screenshots: healthShots,
      steps: [],
      expectations: [],
      consoleErrors: totalConsoleErrors,
    });
  } finally {
    if (cdp) {
      await cdp.close().catch(() => {});
    }
  }

  report.durationMs = Date.now() - startedDate.getTime();
  report.summary = {
    total: report.scenarios.length,
    ok: report.scenarios.filter((s) => s.status === 'OK').length,
    failed: report.scenarios.filter((s) => s.status === 'FALHA').length,
    pending: report.scenarios.filter((s) => s.status === 'PENDENTE').length,
  };

  const savedFiles = saveReportFiles(report, opts.outDir, fileStamp);
  console.log(
    `[run-audit] Concluído em ${(report.durationMs / 1000).toFixed(1)} s — ` +
      `OK: ${report.summary.ok}, FALHA: ${report.summary.failed}, PENDENTE: ${report.summary.pending}`
  );
  console.log(`[run-audit] JSON: ${savedFiles.jsonFile}`);
  console.log(`[run-audit] MD:   ${savedFiles.mdFile}`);

  if (report.summary.failed > 0) {
    process.exit(1);
  }
}

const isMain =
  process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));

if (isMain) {
  main().catch((err) => {
    console.error(`[run-audit] ERRO FATAL: ${err.message}`);
    process.exit(1);
  });
}
