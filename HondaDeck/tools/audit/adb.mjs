// Wrappers não interativos com timeout estrito para ADB no tablet Galaxy Tab A7 (SM-T500).
// Regras do plano mestre: nunca digitar PIN/senha, nunca usar pm clear nem desinstalar o app.

import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const DEFAULT_ADB_PATH =
  process.env.ADB_PATH || 'D:\\car\\multimidia\\.tools\\android-sdk\\platform-tools\\adb.exe';
export const APP_PACKAGE = 'com.autodeck.hondadeck';
export const APP_ACTIVITY = 'com.autodeck.headunit.MainActivity';
export const MAX_CALL_TIMEOUT_MS = 20_000;
export const DEFAULT_TIMEOUT_MS = 15_000;

const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

export function clampTimeout(timeoutMs, fallback = DEFAULT_TIMEOUT_MS) {
  const n = Number(timeoutMs);
  if (!Number.isFinite(n) || n <= 0) return Math.min(fallback, MAX_CALL_TIMEOUT_MS);
  return Math.min(Math.max(100, Math.round(n)), MAX_CALL_TIMEOUT_MS);
}

export function resolveAdbBinary(customPath) {
  const candidate = customPath || DEFAULT_ADB_PATH;
  if (existsSync(candidate)) return candidate;
  return 'adb';
}

/**
 * Executa o binário adb sem entrada interativa (stdin ignorado) e com limite rígido de tempo.
 */
export function execAdb(args, opts = {}) {
  const {
    adbPath,
    serial = process.env.ANDROID_SERIAL || '',
    timeoutMs = DEFAULT_TIMEOUT_MS,
    binary = false,
    allowNonZero = false,
    maxOutputBytes = 8 * 1024 * 1024,
  } = opts;

  const bin = resolveAdbBinary(adbPath);
  const fullArgs = serial ? ['-s', String(serial), ...args] : [...args];
  const limitMs = clampTimeout(timeoutMs);

  return new Promise((resolvePromise, rejectPromise) => {
    let settled = false;
    let timedOut = false;
    const outChunks = [];
    const errChunks = [];
    let outLen = 0;
    let errLen = 0;

    const child = spawn(bin, fullArgs, {
      stdio: ['ignore', 'pipe', 'pipe'],
      windowsHide: true,
    });

    const timer = setTimeout(() => {
      timedOut = true;
      try {
        child.kill('SIGKILL');
      } catch {
        // Ignora falha ao encerrar processo já finalizado.
      }
    }, limitMs);

    const finish = (err, result) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (err) rejectPromise(err);
      else resolvePromise(result);
    };

    child.stdout.on('data', (chunk) => {
      const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      if (outLen < maxOutputBytes) {
        const remaining = maxOutputBytes - outLen;
        outChunks.push(buf.length <= remaining ? buf : buf.subarray(0, remaining));
      } else if (!binary) {
        // Para logs longos (ex.: logcat -d), preserva o trecho mais recente.
        outChunks.shift();
        outChunks.push(buf);
      }
      outLen += buf.length;
    });

    child.stderr.on('data', (chunk) => {
      const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      if (errLen < 512 * 1024) {
        errChunks.push(buf);
        errLen += buf.length;
      }
    });

    child.on('error', (err) => {
      finish(new Error(`Falha ao iniciar adb (${bin} ${fullArgs.join(' ')}): ${err.message}`));
    });

    child.on('close', (code) => {
      const stdoutBuf = Buffer.concat(outChunks);
      const stderrStr = Buffer.concat(errChunks).toString('utf8').trim();

      if (timedOut) {
        finish(
          new Error(
            `Timeout de ${limitMs} ms excedido ao executar: adb ${fullArgs.join(' ')}${
              stderrStr ? ` | stderr: ${stderrStr}` : ''
            }`
          )
        );
        return;
      }

      const exitCode = code ?? -1;
      if (exitCode !== 0 && !allowNonZero) {
        const preview = binary ? `(${stdoutBuf.length} bytes)` : stdoutBuf.toString('utf8').trim().slice(0, 400);
        finish(
          new Error(
            `adb ${fullArgs.join(' ')} falhou com código ${exitCode}: ${stderrStr || preview || 'sem saída'}`
          )
        );
        return;
      }

      finish(null, {
        code: exitCode,
        stdout: binary ? stdoutBuf : stdoutBuf.toString('utf8'),
        stderr: stderrStr,
      });
    });
  });
}

/**
 * Lista os dispositivos conectados via ADB.
 */
export async function listDevices(opts = {}) {
  const { stdout } = await execAdb(['devices', '-l'], opts);
  const lines = stdout
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('List of devices') && !l.startsWith('*'));

  return lines.map((line) => {
    const parts = line.split(/\s+/);
    const serial = parts[0] || '';
    const state = parts[1] || 'unknown';
    const modelMatch = line.match(/\bmodel:([^\s]+)/);
    return {
      serial,
      state,
      model: modelMatch ? modelMatch[1] : '',
      raw: line,
    };
  });
}

/**
 * Instala o APK mantendo todos os dados do usuário (-r -t). Nunca usa pm clear nem uninstall.
 */
export async function installApk(apkPath, opts = {}) {
  const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
  const resolvedApk = resolve(
    repoRoot,
    apkPath || 'app/build/outputs/apk/completo/debug/app-completo-debug.apk'
  );
  if (!existsSync(resolvedApk)) {
    throw new Error(`APK não encontrado para instalação: ${resolvedApk}`);
  }
  const { stdout, stderr } = await execAdb(['install', '-r', '-t', resolvedApk], {
    ...opts,
    timeoutMs: opts.timeoutMs ?? MAX_CALL_TIMEOUT_MS,
  });
  return {
    apkPath: resolvedApk,
    output: (stdout || stderr).trim(),
  };
}

export { installApk as install };

/**
 * Inicia a MainActivity do HondaDeck sem apagar dados.
 */
export async function startApp(opts = {}) {
  const pkg = opts.packageName || APP_PACKAGE;
  const activity = opts.activityName || APP_ACTIVITY;
  const component = `${pkg}/${activity}`;
  const args = opts.wait !== false
    ? ['shell', 'am', 'start', '-W', '-n', component]
    : ['shell', 'am', 'start', '-n', component];
  const { stdout } = await execAdb(args, opts);
  return { component, output: stdout.trim() };
}

/**
 * Descobre o PID atual do processo principal do app (ou null se não estiver em execução).
 */
export async function pidof(packageName = APP_PACKAGE, opts = {}) {
  const res = await execAdb(['shell', 'pidof', packageName], {
    ...opts,
    allowNonZero: true,
    timeoutMs: opts.timeoutMs ?? 8_000,
  });
  const tokens = String(res.stdout || '')
    .trim()
    .split(/\s+/)
    .filter((t) => /^\d+$/.test(t));

  if (tokens.length > 0) {
    return Number(tokens[0]);
  }

  // Fallback para ps -A caso pidof não retorne em alguma revisão de shell.
  const psRes = await execAdb(['shell', 'ps', '-A'], {
    ...opts,
    allowNonZero: true,
    timeoutMs: opts.timeoutMs ?? 8_000,
  });
  for (const line of String(psRes.stdout || '').split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed.endsWith(packageName)) continue;
    const cols = trimmed.split(/\s+/);
    const pidCandidate = cols[1];
    if (/^\d+$/.test(pidCandidate)) return Number(pidCandidate);
  }
  return null;
}

/**
 * Configura redirecionamento TCP local para socket abstrato (ex.: WebView DevTools).
 */
export async function forwardTcp(localPort, remoteAbstract, opts = {}) {
  await execAdb(['forward', '--remove', `tcp:${localPort}`], {
    ...opts,
    allowNonZero: true,
    timeoutMs: 5_000,
  });
  const { stdout } = await execAdb(
    ['forward', `tcp:${localPort}`, `localabstract:${remoteAbstract}`],
    { ...opts, timeoutMs: 8_000 }
  );
  return { localPort, remoteAbstract, output: stdout.trim() };
}

export async function removeForward(localPort, opts = {}) {
  await execAdb(['forward', '--remove', `tcp:${localPort}`], {
    ...opts,
    allowNonZero: true,
    timeoutMs: 5_000,
  });
}

/**
 * Mantém a tela ligada enquanto conectado (`svc power stayon true`).
 */
export async function keepScreenOn(mode = true, opts = {}) {
  const val = mode === true ? 'true' : mode === false ? 'false' : String(mode);
  await execAdb(['shell', 'svc', 'power', 'stayon', val], {
    ...opts,
    timeoutMs: opts.timeoutMs ?? 8_000,
  });
  return { stayOn: val };
}

export { keepScreenOn as stayOn };

/**
 * Verifica se a tela está bloqueada (isKeyguardShowing / mDreamingLockscreen) via dumpsys window.
 * Nunca tenta desbloquear nem digitar senha/PIN.
 */
export async function checkScreenLock(opts = {}) {
  const { stdout } = await execAdb(['shell', 'dumpsys', 'window'], {
    ...opts,
    timeoutMs: opts.timeoutMs ?? 12_000,
  });

  const isKeyguardShowing = /\bisKeyguardShowing\s*=\s*true\b/i.test(stdout);
  const mDreamingLockscreen = /\bmDreamingLockscreen\s*=\s*true\b/i.test(stdout);
  const mShowingLockscreen = /\bmShowingLockscreen\s*=\s*true\b/i.test(stdout);
  const occluded = /\bmKeyguardOccluded\s*=\s*true\b/i.test(stdout) || /\boccluded\s*=\s*true\b/i.test(stdout);
  const mainFocused = /mCurrentFocus=Window\{[^\r\n]*com\.autodeck\.hondadeck/i.test(stdout);
  const notificationShadeOnScreen = /Window\{[^\r\n]*NotificationShade\}[\s\S]{0,220}isOnScreen=true/i.test(stdout);
  const awakeFalse = /\bmAwake\s*=\s*false\b/i.test(stdout);

  const rawMatches = [];
  for (const line of stdout.split(/\r?\n/)) {
    if (/(isKeyguardShowing|mDreamingLockscreen|mShowingLockscreen|mKeyguardOccluded|mCurrentFocus=|mAwake=)/i.test(line)) {
      rawMatches.push(line.trim());
      if (rawMatches.length >= 12) break;
    }
  }

  // MainActivity usa showWhenLocked=true; quando o keyguard está ocluído por ela e não há dream/shade, a UI está acessível.
  const occludedByApp = occluded && mainFocused && !notificationShadeOnScreen;
  const locked = Boolean(
    mDreamingLockscreen ||
    mShowingLockscreen ||
    (isKeyguardShowing && !occludedByApp) ||
    notificationShadeOnScreen
  );
  return {
    locked,
    isKeyguardShowing,
    mDreamingLockscreen,
    occluded,
    mainFocused,
    awake: !awakeFalse,
    rawMatches,
  };
}

export { checkScreenLock as isScreenLocked };

/**
 * Captura a tela inteira do Android (incluindo overlays nativos como câmera de ré) para arquivo PNG.
 */
export async function screencap(filePath, opts = {}) {
  const outFile = resolve(filePath);
  mkdirSync(dirname(outFile), { recursive: true });

  const fast = await execAdb(['exec-out', 'screencap', '-p'], {
    ...opts,
    binary: true,
    allowNonZero: true,
    timeoutMs: opts.timeoutMs ?? 15_000,
  });

  if (
    fast.code === 0 &&
    Buffer.isBuffer(fast.stdout) &&
    fast.stdout.length > 64 &&
    fast.stdout.subarray(0, 8).equals(PNG_MAGIC)
  ) {
    writeFileSync(outFile, fast.stdout);
    return { file: outFile, bytes: fast.stdout.length, method: 'exec-out' };
  }

  // Fallback via arquivo temporário no dispositivo se exec-out falhar ou corromper cabeçalho PNG.
  const remoteTmp = '/data/local/tmp/hondadeck_audit_screencap.png';
  try {
    await execAdb(['shell', 'screencap', '-p', remoteTmp], {
      ...opts,
      timeoutMs: opts.timeoutMs ?? 15_000,
    });
    await execAdb(['pull', remoteTmp, outFile], {
      ...opts,
      timeoutMs: opts.timeoutMs ?? 15_000,
    });
    const saved = readFileSync(outFile);
    if (saved.length < 64 || !saved.subarray(0, 8).equals(PNG_MAGIC)) {
      throw new Error('Arquivo capturado via pull não possui cabeçalho PNG válido.');
    }
    return { file: outFile, bytes: saved.length, method: 'pull' };
  } finally {
    await execAdb(['shell', 'rm', '-f', remoteTmp], {
      ...opts,
      allowNonZero: true,
      timeoutMs: 5_000,
    }).catch(() => {});
  }
}

/**
 * Toque físico na tela via ADB (coordenadas em pixels da tela 2000x1200).
 */
export async function tap(x, y, opts = {}) {
  const ix = Math.round(Number(x));
  const iy = Math.round(Number(y));
  if (!Number.isFinite(ix) || !Number.isFinite(iy)) {
    throw new Error(`Coordenadas inválidas para adb tap: (${x}, ${y})`);
  }
  await execAdb(['shell', 'input', 'tap', String(ix), String(iy)], {
    ...opts,
    timeoutMs: opts.timeoutMs ?? 8_000,
  });
  return { x: ix, y: iy };
}

/**
 * Lê `adb logcat -d` e filtra erros críticos (FATAL|ANR|AndroidRuntime|chromium.*Uncaught).
 */
export async function logcatErrors(opts = {}) {
  const filterRegex =
    opts.pattern || /(FATAL|ANR in |ANR\b|AndroidRuntime|chromium.*Uncaught)/i;
  const pkgFilter = opts.onlyAppPackage === false ? null : opts.packageName || APP_PACKAGE;

  const args = ['logcat', '-d', '-v', 'time'];
  if (opts.tailLines) {
    args.push('-t', String(opts.tailLines));
  }

  const { stdout } = await execAdb(args, {
    ...opts,
    timeoutMs: opts.timeoutMs ?? 15_000,
  });

  const lines = stdout.split(/\r?\n/);
  const matchedLines = [];
  for (const line of lines) {
    if (!filterRegex.test(line)) continue;
    matchedLines.push(line);
  }

  // Destaca ocorrências fatais diretamente ligadas ao HondaDeck ou chromium Uncaught.
  const criticalLines = matchedLines.filter((line) => {
    if (/chromium.*Uncaught/i.test(line)) return true;
    if (/FATAL EXCEPTION|ANR in /i.test(line)) {
      return !pkgFilter || line.includes(pkgFilter) || true;
    }
    if (/AndroidRuntime:\s*(FATAL|Process:\s*com\.autodeck)/i.test(line)) return true;
    return pkgFilter ? line.includes(pkgFilter) : false;
  });

  const appFatalLines = matchedLines.filter(
    (line) =>
      (pkgFilter && line.includes(pkgFilter)) ||
      /chromium.*Uncaught/i.test(line) ||
      /Process:\s*com\.autodeck/i.test(line)
  );

  return {
    totalLinesScanned: lines.length,
    matchedCount: matchedLines.length,
    appFatalCount: appFatalLines.length,
    hasFatal: appFatalLines.length > 0,
    matches: matchedLines.slice(-80),
    appMatches: appFatalLines.slice(-50),
    criticalMatches: criticalLines.slice(-50),
  };
}

/**
 * Limpa apenas o buffer volátil do logcat (`adb logcat -c`) antes de iniciar uma sessão de auditoria.
 */
export async function clearLogcat(opts = {}) {
  await execAdb(['logcat', '-c'], {
    ...opts,
    allowNonZero: true,
    timeoutMs: 6_000,
  });
}

// Execução direta via linha de comando para uso avulso na Fase 7:
// node tools/audit/adb.mjs <devices|check-lock|stayon|pidof|start|install|screencap|logcat> [arg]
const isMain =
  process.argv[1] && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url));

if (isMain) {
  const [, , subcmd = 'devices', arg1] = process.argv;
  (async () => {
    switch (subcmd) {
      case 'devices':
        console.log(JSON.stringify(await listDevices(), null, 2));
        break;
      case 'check-lock':
        console.log(JSON.stringify(await checkScreenLock(), null, 2));
        break;
      case 'stayon':
        console.log(JSON.stringify(await keepScreenOn(arg1 ?? 'true'), null, 2));
        break;
      case 'pidof':
        console.log(JSON.stringify({ pid: await pidof(arg1 || APP_PACKAGE) }, null, 2));
        break;
      case 'start':
        console.log(JSON.stringify(await startApp(), null, 2));
        break;
      case 'install':
        console.log(JSON.stringify(await installApk(arg1), null, 2));
        break;
      case 'screencap': {
        if (!arg1) throw new Error('Uso: node adb.mjs screencap <arquivo.png>');
        console.log(JSON.stringify(await screencap(arg1), null, 2));
        break;
      }
      case 'logcat':
        console.log(JSON.stringify(await logcatErrors(), null, 2));
        break;
      default:
        throw new Error(
          `Subcomando desconhecido: ${subcmd}. Use: devices | check-lock | stayon | pidof | start | install | screencap | logcat`
        );
    }
  })().catch((err) => {
    console.error(`[adb.mjs] ERRO: ${err.message}`);
    process.exit(1);
  });
}
