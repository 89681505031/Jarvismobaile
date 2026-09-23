const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const hud = fs.readFileSync('mobile/android/app/src/main/assets/orb-v3.js', 'utf8');
const html = fs.readFileSync('mobile/android/app/src/main/assets/index.html', 'utf8');
const css = fs.readFileSync('mobile/android/app/src/main/assets/hud-v3.css', 'utf8');

function load() {
  let time = 300;
  let seq = 0;
  const frames = new Map();
  const events = {};
  const cssVars = {};
  const drawn = {bars: 0};
  const elements = {
    jarvisOrb: {dataset: {mode: 'idle'}, style: {setProperty(k,v){cssVars[k]=v;}}},
    orbButton: {attrs:{},setAttribute(k,v){this.attrs[k]=v;}},
    orbModeLabel: {textContent:'СИСТЕМА ГОТОВА'},
    orbWave: {
      width:360,height:360,
      getBoundingClientRect(){return {width:320}},
      getContext(){return {
        clearRect(){},beginPath(){},moveTo(){},lineTo(){},
        stroke(){drawn.bars++},
        set lineCap(v){},set lineWidth(v){},set strokeStyle(v){}
      }}
    },
    appsPanel: {hidden:false}
  };
  const close = {
    getAttribute(name){return name === 'data-close-panel' ? 'appsPanel' : null},
    addEventListener(name,cb){this[name]=cb}
  };
  const document = {
    hidden:false,
    getElementById(id){return elements[id] || null},
    querySelectorAll(selector){return selector==='.hud-close'?[close]:[]},
    addEventListener(name,cb){events[name]=cb}
  };
  let existing=0;
  const window = {
    performance: {now(){return time}},
    devicePixelRatio: 1,
    matchMedia(){return {matches:false}},
    requestAnimationFrame(cb){const id=++seq;frames.set(id,cb);return id},
    cancelAnimationFrame(id){frames.delete(id)},
    addEventListener(name,cb){events[name]=cb},
    onJarvisSpeechState(){existing++},
    onJarvisSpeechLevel(){existing++},
    onJarvisWakeStatus(){existing++}
  };
  vm.runInNewContext(hud, {document,window,Date,Math});
  function frame(t) {
    time=t;
    const current=Array.from(frames.values());
    frames.clear();
    current.forEach(fn=>fn(t));
  }
  return {window,document,elements,events,frames,frame,cssVars,drawn,close,existing:()=>existing};
}

test('HUD v3 has the requested radial layers, does not remove the existing orb button',()=>{
  for(const id of ['jarvisOrb','orbWave','orbModeLabel','orbButton','appsPanel','settingsPanel'])
    assert.match(html,new RegExp('id="'+id+'"'));
  assert.match(html,/hud-v3\.css/);
  assert.match(html,/orb-v3\.js/);
  assert.match(css,/hud-ring--segments/);
  assert.match(css,/@keyframes hudBreathe/);
  assert.match(css, /prefers-reduced-motion/);
});
test('idle stays alive with CSS breathing but does not waste JavaScript frames',()=>{
  const {window,elements,frames}=load();
  assert.equal(window.JarvisHudState.mode(),'idle');
  assert.equal(elements.jarvisOrb.dataset.mode,'idle');
  assert.equal(frames.size,0);
});
test('native listening microphone level drives the glow and radial energy',()=>{
  const {window,elements,frames,frame,cssVars,drawn,existing}=load();
  window.onJarvisSpeechState('listening','Слушаю');
  assert.equal(window.JarvisHudState.mode(),'listening');
  assert.equal(elements.orbModeLabel.textContent,'ПРИНИМАЮ СИГНАЛ');
  assert.equal(existing(),1);
  window.onJarvisSpeechLevel(11);
  frame(1000);
  assert.ok(Number(cssVars['--local-energy'])>.13,cssVars['--local-energy']);
  assert.ok(drawn.bars>0);
  assert.ok(frames.size>0);
  window.onJarvisSpeechState('idle','Готово');
  assert.equal(window.JarvisHudState.mode(),'idle');
  assert.equal(frames.size,0);
});
test('Vosk partial activity is approximate and cannot interrupt speaking',()=>{
  const {window,frame}=load();
  window.onJarvisWakeStatus('listening','Жду имя');
  window.onJarvisWakeActivity(.78);
  frame(1050);
  assert.ok(window.JarvisHudState.energy()>.14);
  window.onJarvisSpeakState('start',180);
  window.onJarvisWakeActivity(1);
  assert.equal(window.JarvisHudState.mode(),'speaking');
});
test('Variant A fakes speech only between actual native start/stop events',()=>{
  const {window,frame,frames,elements}=load();
  window.onJarvisSpeakState('start',120);
  assert.equal(elements.jarvisOrb.dataset.mode,'speaking');
  frame(1000);
  const first=window.JarvisHudState.energy();
  frame(1050);
  frame(1130);
  assert.notEqual(window.JarvisHudState.energy(),first);
  assert.ok(frames.size>0);
  window.onJarvisSpeakState('stop');
  assert.equal(elements.jarvisOrb.dataset.mode,'idle');
  assert.equal(frames.size,0);
});
test('pausing the WebView cancels animation; panel close never triggers voice',()=>{
  const {window,document,events,frames,close,elements}=load();
  window.onJarvisSpeakState('start',60);
  assert.ok(frames.size>0);
  document.hidden=true;
  events.visibilitychange();
  assert.equal(frames.size,0);
  document.hidden=false;
  events.visibilitychange();
  assert.equal(window.JarvisHudState.mode(),'idle');
  close.click();
  assert.equal(elements.appsPanel.hidden,true);
});
