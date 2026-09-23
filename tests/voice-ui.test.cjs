const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('mobile/android/app/src/main/assets/app.js', 'utf8');
function load() {
  class Element {
    constructor() { this.value = ''; this.textContent = ''; this.children = []; this.hidden = false; this.attrs = {}; }
    setAttribute(k, v) { this.attrs[k] = v; }
    addEventListener() {}
    appendChild(child) { this.children.push(child); }
    querySelector(tag) { return this.children.find(child => child.tag === tag); }
  }
  const elements = new Map();
  const calls = { command: [], speak: [], start: 0, windows: 0, diagnostics: 0 };
  const context = { document: { getElementById(id) {
    if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id);
  }, createElement(tag) { const e = new Element(); e.tag = tag; return e; }, querySelectorAll() { return []; } },
  localStorage: { getItem() { return 'Test'; }, setItem() {} }, window: { AndroidJarvis: {
    command(text) { calls.command.push(text); return 'Ответ'; }, speak(text) { calls.speak.push(text); },
    startListening() { calls.start++; }, stopListening() {}, startConversationWindow() { calls.windows++; },
    diagnoseMicrophone() { calls.diagnostics++; },
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
