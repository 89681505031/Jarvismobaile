const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('mobile/android/app/src/main/assets/app.js', 'utf8');
function load() {
  class Element {
    constructor() { this.value = ''; this.textContent = ''; this.children = []; this.hidden = false; this.attrs = {}; this.dataset = {}; }
    setAttribute(k, v) { this.attrs[k] = v; }
    addEventListener() {}
    appendChild(child) { this.children.push(child); }
    querySelector(tag) { return this.children.find(child => child.tag === tag); }
  }
  const elements = new Map();
  const calls = { command: [], speak: [], start: 0, windows: 0, diagnostics: 0, wakeSettings: [], backgroundSettings: [], minimize: 0 };
  const context = { document: { getElementById(id) {
    if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id);
  }, createElement(tag) { const e = new Element(); e.tag = tag; return e; }, querySelectorAll() { return []; } },
  localStorage: { getItem() { return 'Test'; }, setItem() {} }, window: { AndroidJarvis: {
    command(text) { calls.command.push(text); return 'Ответ'; }, speak(text) { calls.speak.push(text); },
    startListening() { calls.start++; }, stopListening() {}, startConversationWindow() { calls.windows++; },
    diagnoseMicrophone() { calls.diagnostics++; },
    getBackgroundWakeEnabled() { return false; },
    setBackgroundWakeEnabled(enabled) { calls.backgroundSettings.push(enabled); },
    minimizeToBackground() { calls.minimize++; return 'Перехожу в фоновый режим.'; },
    getWakeModeEnabled() { return false; },
    setWakeModeEnabled(enabled) { calls.wakeSettings.push(enabled); },
    setPersona() {}, listApps() { return JSON.stringify([{ label: '<img src=x onerror=alert(1)>', packageName: 'app', allowed: false }]); }
  } } };
  vm.createContext(context);
  vm.runInContext(source, context);
  return { context, elements, calls, voice: context.window };
}
test('tap, permission-ready and final speech reach native command without wake word', () => {
  const { context, voice, calls } = load();
  context.startListening();
  voice.onJarvisSpeechReady();
  voice.onJarvisSpeechState('listening', 'Слушаю');
  voice.onJarvisSpeechState('processing', 'Распознаю');
  voice.onJarvisSpeechResult('Открой камеру');
  assert.deepEqual(calls.command, ['Открой камеру']);
  assert.equal(calls.speak.length, 1);
  assert.equal(calls.windows, 0);
});
test('follow-up after assistant speech accepts a normal phrase', () => {
  const { voice, calls } = load();
  voice.onJarvisSpeechState('speaking', 'Ответ');
  voice.onJarvisSpeechState('listening', 'Слушаю');
  voice.onJarvisSpeechResult('А какая завтра погода');
  assert.deepEqual(calls.command, ['А какая завтра погода']);
});
test('wake prefix is stripped from a manual command', () => {
  const { voice, calls } = load();
  voice.onJarvisSpeechState('listening', 'Слушаю');
  voice.onJarvisSpeechResult('Джарвис, открой браузер');
  assert.deepEqual(calls.command, ['открой браузер']);
});
test('recognition error clears listening and shows the actual error', () => {
  const { voice, calls, elements } = load();
  voice.onJarvisSpeechState('listening', 'Слушаю');
  voice.onJarvisSpeechError('Нет доступа');
  voice.onJarvisSpeechResult('посторонняя речь');
  assert.equal(calls.command.length, 0);
  assert.equal(elements.get('micStatus').textContent, 'Нет доступа');
  assert.equal(elements.get('orbButton').attrs['aria-busy'], 'false');
});
test('only wake word asks for command without scheduling a competing microphone start', () => {
  const { voice, calls } = load();
  voice.onJarvisSpeechState('listening', 'Слушаю');
  voice.onJarvisSpeechResult('Джарвис');
  assert.deepEqual(calls.speak, ['Слушаю']);
  assert.equal(calls.command.length, 0);
  assert.equal(calls.windows, 0);
});
test('external app labels are inserted as text, not HTML', () => {
  const { context, elements } = load();
  context.loadApps();
  const label = elements.get('appsList').children[0].children[0];
  assert.equal(label.textContent, '<img src=x onerror=alert(1)>');
  assert.equal(label.innerHTML, undefined);
});

test('mic diagnostics are explicit and prevent competing recognizer sessions', () => {
  const { context, voice, calls, elements } = load();
  elements.get('diagnoseMic').onclick();
  assert.equal(calls.diagnostics, 1);
  assert.equal(elements.get('diagnoseMic').disabled, true);
  context.startListening();
  assert.equal(calls.start, 0);
  voice.onJarvisMicDiagnostic('ok', 'Аудиопоток работает.');
  assert.equal(elements.get('diagnoseMic').disabled, false);
  assert.equal(elements.get('micDiagnostics').textContent, 'Аудиопоток работает.');
  context.startListening();
  assert.equal(calls.start, 1);
});
test('missing native diagnostic bridge shows an actionable error', () => {
  const { context, elements, voice } = load();
  delete context.window.AndroidJarvis.diagnoseMicrophone;
  elements.get('diagnoseMic').onclick();
  assert.equal(elements.get('diagnoseMic').disabled, false);
  assert.equal(elements.get('micDiagnostics').attrs['data-status'], 'error');
});

test('foreground wake option is opt-in and sends explicit native settings change', () => {
  const { calls, elements } = load();
  assert.equal(elements.get('wakeMode').checked, false);
  elements.get('wakeMode').onchange({ target: { checked: true } });
  assert.deepEqual(calls.wakeSettings, [true]);
});
test('spoken persona name without command waits for the next phrase', () => {
  const { voice, calls, elements } = load();
  voice.onJarvisWakeStatus('listening', 'Жду имя');
  voice.onJarvisWakeDetected('Astra', '');
  assert.deepEqual(calls.command, []);
  assert.match(elements.get('message').textContent, /Astra/);
});
test('wake name followed by command switches persona and executes exactly once', () => {
  const { voice, calls } = load();
  voice.onJarvisWakeDetected('Cyber', 'открой браузер');
  assert.deepEqual(calls.command, ['открой браузер']);
  assert.equal(calls.speak.length, 1);
});
test('wake standby is not treated as unrestricted dictation', () => {
  const { voice, calls, elements } = load();
  voice.onJarvisWakeStatus('listening', 'Жду имя');
  assert.equal(elements.get('micStatus').textContent, 'Жду имя');
  assert.equal(elements.get('orbButton').attrs['aria-busy'], 'false');
  assert.deepEqual(calls.command, []);
});

test('background microphone is opt-in and native owns enable/disable', () => {
  const { calls, elements } = load();
  assert.equal(elements.get('backgroundWake').checked, false);
  elements.get('backgroundWake').onchange({ target: { checked: true } });
  elements.get('backgroundWake').onchange({ target: { checked: false } });
  assert.deepEqual(calls.backgroundSettings, [true, false]);
});
test('background spoken command requires explicit tap instead of auto-executing', () => {
  const { voice, calls, elements } = load();
  voice.onJarvisPendingBackgroundCommand('открой браузер');
  assert.deepEqual(calls.command, []);
  assert.equal(elements.get('command').value, 'открой браузер');
  assert.match(elements.get('message').textContent, /Нажмите/);
});
test('native can reject background permission and reset the checkbox', () => {
  const { voice, elements } = load();
  voice.onJarvisBackgroundWakeChanged(true);
  assert.equal(elements.get('backgroundWake').checked, true);
  voice.onJarvisBackgroundWakeChanged(false);
  assert.equal(elements.get('backgroundWake').checked, false);
});

test('PLUS skills, reminder and battery settings are opt-in and validate user actions', () => {
  const { context, elements, calls } = load();
  const reminders = [];
  context.window.AndroidJarvis.getInterruptByVoice = () => false;
  context.window.AndroidJarvis.setInterruptByVoice = enabled => reminders.push('interrupt:' + enabled);
  context.window.AndroidJarvis.scheduleReminder = (text, n) => { reminders.push(text + ':' + n); return 'Напоминание создано'; };
  context.window.AndroidJarvis.batteryMinutes = () => 30;
  context.window.AndroidJarvis.setBatteryMinutes = n => reminders.push('battery:' + n) || true;
  elements.get('voiceInterrupt').onchange({ target: { checked: true } });
  context.document.getElementById('reminderText').value = 'проверить уроки';
  context.document.getElementById('reminderMinutes').value = '10';
  elements.get('addReminder').onclick();
  elements.get('batteryMinutes').onchange({ target: { value: '15' } });
  assert.deepEqual(reminders, ['interrupt:true', 'проверить уроки:10', 'battery:15']);
  assert.equal(elements.get('reminderStatus').textContent, 'Напоминание создано');
});
test('PLUS home actions are manually triggered and cloud credentials are not reflected', () => {
  const { context, elements } = load();
  const calls=[];
  context.window.AndroidJarvis.configureHome = (url, token, entity) => { calls.push({url,token,entity}); return 'Подключено'; };
  context.window.AndroidJarvis.controlSmartLight = on => {calls.push(on);return 'Отправляю команду';};
  context.document.getElementById('homeUrl').value='https://home.example.com';
  context.document.getElementById('homeToken').value='example-secret-123456789';
  context.document.getElementById('homeEntity').value='light.desk';
  elements.get('connectHome').onclick();
  assert.equal(elements.get('homeToken').value, '');
  elements.get('homeLightOn').onclick();
  assert.deepEqual(calls,[{url:'https://home.example.com',token:'example-secret-123456789',entity:'light.desk'},true]);
});
test('PLUS local vision only starts after a user tap', () => {
  const { context, elements } = load();
  let taps=0;
  context.window.AndroidJarvis.startVisionCamera = () => taps++;
  context.window.AndroidJarvis.selectVisionPhoto = () => taps++;
  assert.equal(taps,0);
  elements.get('visionCamera').onclick();
  elements.get('visionPicker').onclick();
  assert.equal(taps,2);
});

test('JARVIS BRAIN is local and exposes the GGUF picker without a cloud key', () => {
  const { context, elements } = load();
  const calls=[];
  context.window.AndroidJarvis.chooseJarvisBrainModel=()=>calls.push('choose');
  assert.match(elements.get('brainBadge').textContent,/JARVIS BRAIN/);
  assert.match(elements.get('brainBadge').textContent,/ЛОКАЛЬНО/);
  elements.get('chooseBrainModel').onclick();
  assert.deepEqual(calls,['choose']);
  context.window.onJarvisBrainState('thinking','JARVIS BRAIN думает локально…');
  assert.match(elements.get('brainBadge').textContent,/ДУМАЕТ/);
  context.window.onJarvisBrainState('ready','Локальный ответ готов');
  assert.match(elements.get('brainBadge').textContent,/ЛОКАЛЬНО/);
});

test('built-in local model buttons select the expected verified profiles',()=>{
  const {context,elements}=load();
  const calls=[];
  context.window.AndroidJarvis.downloadJarvisBrainModel=id=>{
    calls.push(id);
    return 'Загрузка запущена';
  };
  elements.get('downloadBrainInstant').onclick();
  elements.get('downloadBrainLite').onclick();
  elements.get('downloadBrainStandard').onclick();
  assert.deepEqual(calls,['qwen25-0.5b-q4_0','qwen3-0.6b-q4km','qwen3-1.7b-q4km']);
  context.window.onJarvisBrainModelDownload('progress','Загрузка JARVIS Lite: 42%');
  assert.match(elements.get('brainModelStatus').textContent,/42%/);
  context.window.onJarvisBrainModelDownload('stored','Модель установлена локально');
  assert.match(elements.get('brainModelStatus').textContent,/установлена локально/);
});

test('local brain self-test runs through Android bridge and reports timing',()=>{
  const {context,elements}=load();
  const calls=[];
  context.window.AndroidJarvis.testJarvisBrainModel=()=>{
    calls.push('test');
    return 'Самопроверка JARVIS BRAIN запущена.';
  };
  elements.get('testBrainModel').onclick();
  assert.deepEqual(calls,['test']);
  assert.match(elements.get('brainModelStatus').textContent,/Самопроверка/);
  context.window.onJarvisBrainSelfTest('running','Загружаю локальную модель в RAM…');
  assert.match(elements.get('brainModelStatus').textContent,/RAM/);
  context.window.onJarvisBrainSelfTest('success','Самотест пройден за 1.2 с.');
  assert.match(elements.get('brainModelStatus').textContent,/1\.2 с/);
});

test('local GGUF status is explicit and model removal stays on-device',()=>{
  const {context,elements}=load();
  const calls=[];
  context.window.AndroidJarvis.removeJarvisBrainModel=()=>{
    calls.push('remove'); return 'Локальный файл модели удалён.';
  };
  context.window.onJarvisBrainModelStatus('stored','GGUF сохранён локально · 900 МБ');
  assert.match(elements.get('brainModelStatus').textContent,/GGUF сохранён локально/);
  elements.get('removeBrainModel').onclick();
  assert.deepEqual(calls,['remove']);
  assert.match(elements.get('brainModelStatus').textContent,/удалён/);
});

test('local chat history is cleared only by the explicit history button',()=>{
  const {context}=load();
  const calls=[];
  context.window.AndroidJarvis.clearJarvisBrainHistory=()=>{
    calls.push('history'); return 'Очищено';
  };
  context.document.getElementById('clearBrainHistory').onclick();
  assert.deepEqual(calls,['history']);
});

test('weather and local news request only user-initiated approximate location access',()=>{
  const {context,elements}=load();
  const calls=[];
  context.window.AndroidJarvis.hasApproximateLocation=()=>false;
  context.window.AndroidJarvis.requestApproximateLocation=()=>calls.push('permission');
  context.window.AndroidJarvis.requestWeather=()=>calls.push('weather');
  context.window.AndroidJarvis.requestLocalNews=()=>calls.push('local-news');
  context.window.AndroidJarvis.requestNewsSummary=()=>calls.push('news');
  context.document.getElementById('allowLocation').onclick();
  context.document.getElementById('weatherNow').onclick();
  context.document.getElementById('localNewsNow').onclick();
  context.document.getElementById('globalNewsNow').onclick();
  assert.deepEqual(calls,['permission','weather','local-news','news']);
  assert.match(context.document.getElementById('locationStatus').textContent,/Местоположение выключено/);
});
test('native location result is displayed without exposing coordinates in UI',()=>{
  const {context,elements}=load();
  context.window.onJarvisLocationStatus('granted','Примерное местоположение разрешено.');
  assert.equal(elements.get('allowLocation').disabled,true);
  context.window.onJarvisLocalInfo('weather','Погода по приблизительному местоположению — город. Сейчас 5 °C.');
  assert.match(elements.get('message').textContent,/Сейчас 5/);
});

test('explicit tray button asks native Activity to move task to background', () => {
  const { calls, elements } = load();
  elements.get('minimizeJarvis').onclick();
  assert.equal(calls.minimize, 1);
  assert.match(elements.get('backgroundWakeStatus').textContent, /фоновый режим/i);
});
