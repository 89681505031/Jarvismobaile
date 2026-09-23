/* J.A.R.V.I.S. HUD v3 — lightweight persistent reactor with audio-aware states.
 * Incoming mic level: Android speech recognizer (actual level) or Vosk partial
 * speech activity (estimated; Vosk does not expose raw volume here).
 * Outgoing voice: deliberately SYNTHETIC animation (Variant A); starts/stops
 * only on real native TextToSpeech/FishAudio lifecycle events, not actual PCM.
 * Idle motion is CSS-only. JS uses <=30fps only when actively listening/talking.
 */
(function initJarvisHudV3() {
  'use strict';
  const orb = document.getElementById('jarvisOrb');
  const area = document.getElementById('orbButton');
  const canvas = document.getElementById('orbWave');
  const label = document.getElementById('orbModeLabel');
  if (!orb || !area || !label) return;

  const reducedMotion = !!window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches;
  const states = {
    idle: ['СИСТЕМА ГОТОВА', 'Начать голосовой ввод'],
    listening: ['ПРИНИМАЮ СИГНАЛ', 'Джарвис слушает. Нажмите, чтобы говорить'],
    speaking: ['ГОЛОСОВОЙ ОТВЕТ', 'Джарвис отвечает. Нажмите, чтобы остановить'],
    thinking: ['ОБРАБОТКА ДАННЫХ', 'Джарвис обрабатывает команду']
  };
  const ctx = canvas && typeof canvas.getContext === 'function' ? canvas.getContext('2d') : null;
  let mode = 'idle';
  let level = .12;
  let target = .12;
  let micUpdateAt = 0;
  let speechStart = 0;
  let speechSize = 60;
  let raf = 0;
  let lastFrame = 0;
  let wakeListening = false;
  const clock = () => (window.performance && typeof window.performance.now === 'function'
    ? window.performance.now() : Date.now());

  function sizeCanvas() {
    if (!canvas || !ctx) return;
    const cssSize = Math.max(1, Math.round(canvas.getBoundingClientRect?.().width || 320));
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const pixels = Math.round(cssSize * dpr);
    if (canvas.width !== pixels) canvas.width = pixels;
    if (canvas.height !== pixels) canvas.height = pixels;
    if (mode !== 'idle' && !document.hidden) drawWave();
  }

  function drawWave() {
    if (!ctx || !canvas || !canvas.width || !canvas.height) return;
    const size = canvas.width;
    const mid = size / 2;
    const wave = mode === 'speaking' ? level : mode === 'listening' ? level * .92 : .06;
    const radius = mid * .31;
    const spread = mid * .135;
    ctx.clearRect(0, 0, size, size);
    ctx.lineCap = 'round';
    const bars = 76;
    for (let i = 0; i < bars; i++) {
      const angle = i * Math.PI * 2 / bars - Math.PI / 2;
      const detail = .54 + .27 * Math.sin(i * 1.17) + .19 * Math.sin(i * .39 + clock() * .005);
      const length = Math.max(1.3, spread * (.05 + wave * (.42 + detail * .7)));
      const r = radius;
      const dx = Math.cos(angle), dy = Math.sin(angle);
      ctx.beginPath();
      ctx.lineWidth = Math.max(1.2, size / 260);
      ctx.strokeStyle = mode === 'speaking' ? 'rgba(154,241,255,.93)' : 'rgba(74,195,255,.82)';
      ctx.moveTo(mid + dx * r, mid + dy * r);
      ctx.lineTo(mid + dx * (r + length), mid + dy * (r + length));
      ctx.stroke();
    }
  }

  function cancel() {
    if (raf && typeof window.cancelAnimationFrame === 'function') window.cancelAnimationFrame(raf);
    raf = 0;
    lastFrame = 0;
  }

  function syntheticSpeech(now) {
    const t = Math.max(0, now - speechStart);
    // Different consonant/syllable bursts and natural micro-pauses: intentional
    // aesthetic approximation, not a claim of exact mouth/audio sync.
    const tempo = 1 + (speechSize % 17) / 55;
    const syllable = Math.abs(Math.sin(t * .012 * tempo) * Math.cos(t * .0051 + .7));
    const flicker = .5 + .5 * Math.sin(t * .029 + Math.sin(t * .002));
    const phraseGap = Math.sin(t * .0035 + speechSize) < -.80 ? .23 : 1;
    const result = (.19 + syllable * .57 + flicker * .18) * phraseGap;
    return Math.max(.11, Math.min(.94, result));
  }

  function animate(now) {
    raf = 0;
    if (document.hidden || reducedMotion || (mode !== 'listening' && mode !== 'speaking')) return;
    if (now - lastFrame < 32) {
      raf = window.requestAnimationFrame(animate);
      return;
    }
    lastFrame = now;
    if (mode === 'speaking') {
      target = syntheticSpeech(now);
    } else if (now - micUpdateAt > 230) {
      // Gentle baseline when a listening engine has no recent mic measurements.
      target = .1 + .035 * Math.sin(now * .0047);
    }
    const factor = mode === 'speaking' ? .38 : target > level ? .46 : .18;
    level += (target - level) * factor;
    level = Math.max(.03, Math.min(1, level));
    orb.style.setProperty('--local-energy', level.toFixed(3));
    orb.style.setProperty('--local-bloom', Math.min(1, .25 + level * .7).toFixed(3));
    drawWave();
    raf = window.requestAnimationFrame(animate);
  }

  function start() {
    cancel();
    if (!document.hidden && !reducedMotion && typeof window.requestAnimationFrame === 'function' &&
      (mode === 'listening' || mode === 'speaking')) {
      raf = window.requestAnimationFrame(animate);
    }
  }

  function setMode(next, detail) {
    if (!states[next]) next = 'idle';
    if (next === mode && next !== 'speaking') return;
    mode = next;
    orb.dataset.mode = next;
    label.textContent = states[next][0];
    area.setAttribute('aria-label', states[next][1]);
    if (next === 'speaking') {
      speechStart = clock();
      speechSize = Number(detail) > 0 ? Math.min(8000, Number(detail)) : 60;
      level = .12;
    } else if (next === 'idle' || next === 'thinking') {
      target = .12;
      level = .12;
      orb.style.setProperty('--local-energy', '.12');
    }
    start();
    if (mode === 'idle' || mode === 'thinking') drawWave();
  }

  function setMicLevel(value, source) {
    if (mode !== 'listening' || !Number.isFinite(Number(value))) return;
    const raw = Number(value);
    // SpeechRecognizer's level is dB-ish. Vosk's onPartialResult is an
    // estimated voice activity pulse, NOT microphone amplitude.
    const measured = source === 'partial' ? raw : (raw + 2) / 13;
    target = Math.min(1, Math.max(.06, measured));
    micUpdateAt = clock();
    if (!raf && !reducedMotion) start();
  }

  function chain(name, observer) {
    const original = window[name];
    window[name] = function () {
      const args = Array.from(arguments);
      let result;
      if (typeof original === 'function') result = original.apply(this, args);
      observer.apply(null, args);
      return result;
    };
  }

  chain('onJarvisSpeechState', function (phase) {
    if (phase === 'speaking') setMode('speaking');
    else if (phase === 'processing') {
      if (mode !== 'speaking') setMode('thinking');
    } else if (phase === 'starting' || phase === 'listening') {
      if (mode !== 'speaking') setMode('listening');
    } else if (phase === 'idle' || phase === 'paused' || phase === 'error') {
      if (mode !== 'speaking') setMode(wakeListening ? 'listening' : 'idle');
    }
  });
  chain('onJarvisSpeechLevel', function (db) { setMicLevel(db, 'mic'); });
  chain('onJarvisWakeStatus', function (phase) {
    wakeListening = ['listening', 'starting', 'retry'].includes(phase);
    if (mode !== 'speaking') setMode(wakeListening ? 'listening' : 'idle');
  });
  chain('onJarvisWakeModeChanged', function (enabled) {
    if (!enabled) {
      wakeListening = false;
      if (mode !== 'speaking') setMode('idle');
    }
  });
  chain('onJarvisWakeDetected', function (_, command) {
    if (mode !== 'speaking') setMode(command ? 'thinking' : 'listening');
  });
  chain('onJarvisSpeechError', function () {
    if (mode !== 'speaking') setMode(wakeListening ? 'listening' : 'idle');
  });
  chain('onJarvisMicDiagnostic', function (phase) {
    if (mode === 'speaking') return;
    if (phase === 'checking') setMode('listening');
    else if (!wakeListening) setMode('idle');
  });
  chain('onJarvisInterruptStatus', function () { setMode(wakeListening ? 'listening' : 'idle'); });

  // New native lifecycle event; old APK remains compatible via SpeechState.
  window.onJarvisSpeakState = function (phase, spokenTextLength) {
    if (phase === 'start') setMode('speaking', spokenTextLength);
    else if (phase === 'stop') setMode(wakeListening && !document.hidden ? 'listening' : 'idle');
  };
  window.onJarvisWakeActivity = function (voiceActivity) {
    if (mode !== 'speaking') {
      if (mode !== 'listening') setMode('listening');
      setMicLevel(voiceActivity, 'partial');
    }
  };

  // Close icon never changes app behaviour or sensitive Android permissions.
  document.querySelectorAll?.('.hud-close').forEach(button => {
    button.addEventListener('click', () => {
      const id = button.getAttribute('data-close-panel');
      const panel = id && document.getElementById(id);
      if (panel) panel.hidden = true;
    });
  });

  document.addEventListener?.('visibilitychange', () => {
    if (document.hidden) cancel();
    else {
      if (mode === 'speaking') setMode('idle'); // Native stops TTS in onPause.
      sizeCanvas();
      if (mode === 'listening') start();
    }
  });
  window.addEventListener?.('resize', sizeCanvas, {passive:true});
  window.addEventListener?.('pagehide', cancel, {passive:true});
  window.JarvisHudState = Object.freeze({
    mode: () => mode,
    energy: () => level,
    pseudoSpeech: () => syntheticSpeech(clock())
  });
  sizeCanvas();
  setMode('idle');
})();
