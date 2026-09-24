/* J.A.R.V.I.S. HUD v4 — persona holograms with audio-driven lip sync.
 * Incoming mic level is used for listening animation.
 * Outgoing voice amplitude comes from native Fish Audio playback or Android TTS
 * audio callbacks. A conservative synthetic envelope is only a compatibility
 * fallback when a device does not expose playback waveform data.
 */
(function initJarvisHudV4() {
  'use strict';
  const orb=document.getElementById('jarvisOrb');
  const area=document.getElementById('orbButton');
  const canvas=document.getElementById('orbWave');
  const label=document.getElementById('orbModeLabel');
  const avatar=document.getElementById('holoAvatar');
  const face=document.getElementById('holoFace');
  const mouthFace=document.getElementById('holoMouthFace');
  const personaName=document.getElementById('holoPersonaName');
  if(!orb||!area||!label)return;

  const reducedMotion=!!window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches;
  const states={
    idle:['СИСТЕМА ГОТОВА','Начать голосовой ввод'],
    listening:['ПРИНИМАЮ СИГНАЛ','JARVIS слушает. Нажмите, чтобы говорить'],
    speaking:['ГОЛОСОВОЙ ОТВЕТ','JARVIS отвечает. Нажмите, чтобы остановить'],
    thinking:['ОБРАБОТКА ДАННЫХ','JARVIS обрабатывает команду']
  };
  const avatarIndex={
    'J.A.R.V.I.S.':0,
    'Astra':1,
    'Luna':2,
    'Terra':3,
    'Кибер':4
  };
  const avatarSprite='hologram-sprite.webp';
  const ctx=canvas&&typeof canvas.getContext==='function'?canvas.getContext('2d'):null;
  let mode='idle',level=.12,target=.12,micUpdateAt=0,speechStart=0,speechSize=60;
  let voiceUpdateAt=0,voiceTarget=0,raf=0,lastFrame=0,wakeListening=false,blinkTimer=0;
  const clock=()=>window.performance&&typeof window.performance.now==='function'?window.performance.now():Date.now();
  const clamp=v=>Math.max(0,Math.min(1,Number(v)||0));
  const defer=(fn,ms)=>{
    if(typeof window!=='undefined'&&typeof window.setTimeout==='function')return window.setTimeout(fn,ms);
    return 0;
  };
  function canonicalPersona(name){
    const n=String(name||'').trim().toLowerCase();
    if(['cyber','сайбер','кибер'].includes(n))return 'Кибер';
    if(['astra','астра'].includes(n))return 'Astra';
    if(['luna','луна'].includes(n))return 'Luna';
    if(['terra','терра'].includes(n))return 'Terra';
    return 'J.A.R.V.I.S.';
  }
  function setPersona(name){
    const persona=canonicalPersona(name);
    const index=avatarIndex[persona]??0;
    if(face){
      if(face.getAttribute?.('src')!==avatarSprite)face.setAttribute('src',avatarSprite);
      face.style.left=(-index*100)+'%';
    }
    if(mouthFace){
      if(mouthFace.getAttribute?.('src')!==avatarSprite)mouthFace.setAttribute('src',avatarSprite);
      mouthFace.style.left=(-index*100)+'%';
    }
    if(avatar){avatar.dataset.persona=persona;avatar.style?.setProperty('--holo-mouth-scale','1');}
    if(personaName)personaName.textContent=persona;
  }
  function setMouth(v){
    if(!avatar)return;
    const open=mode==='speaking'?clamp(v):0;
    avatar.style?.setProperty('--mouth-open',open.toFixed(3));
    avatar.style?.setProperty('--holo-mouth-scale',(1+open*.34).toFixed(3));
    avatar.style?.setProperty('--voice-lift',(open*2.2).toFixed(2)+'px');
    avatar.style?.setProperty('--voice-scale',(1+open*.012).toFixed(3));
  }
  function scheduleBlink(){
    if(reducedMotion||!avatar||document.hidden)return;
    if(blinkTimer&&typeof window.clearTimeout==='function')window.clearTimeout(blinkTimer);
    const wait=2800+Math.floor(Math.random()*3600);
    blinkTimer=defer(()=>{
      if(document.hidden)return;
      avatar.classList?.add('is-blinking');
      defer(()=>avatar.classList?.remove('is-blinking'),105);
      defer(()=>{avatar.classList?.add('is-blinking');defer(()=>avatar.classList?.remove('is-blinking'),85)},165);
      defer(scheduleBlink,500);
    },wait);
  }
  function sizeCanvas(){
    if(!canvas||!ctx)return;
    const cssSize=Math.max(1,Math.round(canvas.getBoundingClientRect?.().width||320));
    const dpr=Math.min(2,window.devicePixelRatio||1);
    const pixels=Math.round(cssSize*dpr);
    if(canvas.width!==pixels)canvas.width=pixels;
    if(canvas.height!==pixels)canvas.height=pixels;
    if(mode!=='idle'&&!document.hidden)drawWave();
  }
  function drawWave(){
    if(!ctx||!canvas||!canvas.width||!canvas.height)return;
    const size=canvas.width,mid=size/2;
    const wave=mode==='speaking'?level:mode==='listening'?level*.92:.06;
    const radius=mid*.31,spread=mid*.135;
    ctx.clearRect(0,0,size,size);ctx.lineCap='round';
    const bars=76;
    for(let i=0;i<bars;i++){
      const angle=i*Math.PI*2/bars-Math.PI/2;
      const detail=.54+.27*Math.sin(i*1.17)+.19*Math.sin(i*.39+clock()*.005);
      const length=Math.max(1.3,spread*(.05+wave*(.42+detail*.7)));
      const dx=Math.cos(angle),dy=Math.sin(angle);
      ctx.beginPath();ctx.lineWidth=Math.max(1.2,size/260);
      ctx.strokeStyle=mode==='speaking'?'rgba(154,241,255,.93)':'rgba(74,195,255,.82)';
      ctx.moveTo(mid+dx*radius,mid+dy*radius);
      ctx.lineTo(mid+dx*(radius+length),mid+dy*(radius+length));ctx.stroke();
    }
  }
  function cancel(){
    if(raf&&typeof window.cancelAnimationFrame==='function')window.cancelAnimationFrame(raf);
    raf=0;lastFrame=0;
  }
  function syntheticSpeech(now){
    const t=Math.max(0,now-speechStart),tempo=1+(speechSize%17)/55;
    const syllable=Math.abs(Math.sin(t*.012*tempo)*Math.cos(t*.0051+.7));
    const flicker=.5+.5*Math.sin(t*.029+Math.sin(t*.002));
    const phraseGap=Math.sin(t*.0035+speechSize)<-.80?.18:1;
    return Math.max(.04,Math.min(.78,(.12+syllable*.48+flicker*.12)*phraseGap));
  }
  function animate(now){
    raf=0;
    if(document.hidden||reducedMotion||(mode!=='listening'&&mode!=='speaking'))return;
    if(now-lastFrame<32){raf=window.requestAnimationFrame(animate);return}
    lastFrame=now;
    if(mode==='speaking'){
      // Real audio wins. Fallback only if native has supplied no waveform recently.
      target=now-voiceUpdateAt<220?voiceTarget:syntheticSpeech(now);
    }else if(now-micUpdateAt>230){
      target=.1+.035*Math.sin(now*.0047);
    }
    const factor=mode==='speaking'?(target>level?.58:.31):(target>level?.46:.18);
    level+=(target-level)*factor;level=Math.max(.02,Math.min(1,level));
    orb.style.setProperty('--local-energy',level.toFixed(3));
    orb.style.setProperty('--local-bloom',Math.min(1,.25+level*.7).toFixed(3));
    orb.style.setProperty('--hud-core-scale',(1+level*.12).toFixed(3));
    orb.style.setProperty('--hud-glow',Math.round(18+level*48)+'px');
    orb.style.setProperty('--hud-listen-glow',Math.round(23+level*56)+'px');
    orb.style.setProperty('--hud-speak-glow',Math.round(25+level*74)+'px');
    orb.style.setProperty('--hud-core-alpha',Math.min(1,.25+level*.7).toFixed(3));
    orb.style.setProperty('--hud-wave-alpha',Math.min(1,.56+level*.44).toFixed(3));
    setMouth(mode==='speaking'?Math.max(0,(level-.025)*1.08):0);
    drawWave();raf=window.requestAnimationFrame(animate);
  }
  function start(){
    cancel();
    if(!document.hidden&&!reducedMotion&&typeof window.requestAnimationFrame==='function'&&(mode==='listening'||mode==='speaking')){
      raf=window.requestAnimationFrame(animate);
    }
  }
  function setMode(next,detail){
    if(!states[next])next='idle';
    if(next===mode&&next!=='speaking')return;
    mode=next;orb.dataset.mode=next;label.textContent=states[next][0];area.setAttribute('aria-label',states[next][1]);
    if(next==='speaking'){
      speechStart=clock();speechSize=Number(detail)>0?Math.min(8000,Number(detail)):60;
      level=.06;target=.06;voiceTarget=0;voiceUpdateAt=0;
    }else if(next==='idle'||next==='thinking'){
      target=.12;level=.12;orb.style.setProperty('--local-energy','.12');setMouth(0);
    }
    start();if(mode==='idle'||mode==='thinking')drawWave();
  }
  function setMicLevel(value,source){
    if(mode!=='listening'||!Number.isFinite(Number(value)))return;
    const raw=Number(value),measured=source==='partial'?raw:(raw+2)/13;
    target=Math.min(1,Math.max(.06,measured));micUpdateAt=clock();if(!raf&&!reducedMotion)start();
  }
  function setVoiceAmplitude(value){
    if(mode!=='speaking')return;
    voiceTarget=clamp(value);voiceUpdateAt=clock();
    if(!raf&&!reducedMotion)start();
  }
  function chain(name,observer){
    const original=window[name];
    window[name]=function(){
      const args=Array.from(arguments);let result;
      if(typeof original==='function')result=original.apply(this,args);
      observer.apply(null,args);return result;
    };
  }
  chain('onJarvisSpeechState',phase=>{
    if(phase==='speaking')setMode('speaking');
    else if(phase==='processing'){if(mode!=='speaking')setMode('thinking')}
    else if(phase==='starting'||phase==='listening'){if(mode!=='speaking')setMode('listening')}
    else if(phase==='idle'||phase==='paused'||phase==='error'){if(mode!=='speaking')setMode(wakeListening?'listening':'idle')}
  });
  chain('onJarvisSpeechLevel',db=>setMicLevel(db,'mic'));
  chain('onJarvisWakeStatus',phase=>{wakeListening=['listening','starting','retry'].includes(phase);if(mode!=='speaking')setMode(wakeListening?'listening':'idle')});
  chain('onJarvisWakeModeChanged',enabled=>{if(!enabled){wakeListening=false;if(mode!=='speaking')setMode('idle')}});
  chain('onJarvisWakeDetected',(_,command)=>{if(mode!=='speaking')setMode(command?'thinking':'listening')});
  chain('onJarvisSpeechError',()=>{if(mode!=='speaking')setMode(wakeListening?'listening':'idle')});
  chain('onJarvisMicDiagnostic',phase=>{if(mode==='speaking')return;if(phase==='checking')setMode('listening');else if(!wakeListening)setMode('idle')});
  chain('onJarvisInterruptStatus',()=>setMode(wakeListening?'listening':'idle'));
  chain('onJarvisPersonaChanged',setPersona);

  window.onJarvisSpeakState=function(phase,spokenTextLength){
    if(phase==='start')setMode('speaking',spokenTextLength);
    else if(phase==='stop'){setMouth(0);setMode(wakeListening&&!document.hidden?'listening':'idle')}
  };
  window.onJarvisVoiceAmplitude=setVoiceAmplitude;
  window.onJarvisWakeActivity=function(voiceActivity){
    if(mode!=='speaking'){if(mode!=='listening')setMode('listening');setMicLevel(voiceActivity,'partial')}
  };

  document.querySelectorAll?.('.hud-close').forEach(button=>{
    button.addEventListener('click',()=>{const id=button.getAttribute('data-close-panel');const panel=id&&document.getElementById(id);if(panel)panel.hidden=true});
  });
  document.addEventListener?.('visibilitychange',()=>{
    if(document.hidden){cancel();if(blinkTimer&&typeof window.clearTimeout==='function')window.clearTimeout(blinkTimer)}
    else{if(mode==='speaking')setMode('idle');sizeCanvas();if(mode==='listening')start();scheduleBlink()}
  });
  window.addEventListener?.('resize',sizeCanvas,{passive:true});
  window.addEventListener?.('pagehide',cancel,{passive:true});
  window.JarvisHudState=Object.freeze({mode:()=>mode,energy:()=>level,pseudoSpeech:()=>syntheticSpeech(clock()),persona:()=>personaName?.textContent||'J.A.R.V.I.S.'});
  setPersona(window.localStorage?.getItem?.('jarvisPersona')||'J.A.R.V.I.S.');
  sizeCanvas();setMode('idle');scheduleBlink();
})();