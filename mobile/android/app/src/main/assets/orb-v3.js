/* J.A.R.V.I.S. HUD v6 — point-cloud hologram with safe audio-driven lip sync.
 * The user-supplied Gideon reference is used as visual direction only.
 * The face remains a single intact image; speech deformation happens inside
 * the point-cloud renderer so no rectangular mouth blocks are created.
 */
(function initJarvisHudV6(){
  'use strict';
  const orb=document.getElementById('jarvisOrb');
  const area=document.getElementById('orbButton');
  const waveCanvas=document.getElementById('orbWave');
  const meshCanvas=document.getElementById('holoMesh');
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
    speaking:['ГОЛОСОВОЙ ОТВЕТ','JARVIS отвечает. Нажмите на голограмму, чтобы остановить'],
    thinking:['ОБРАБОТКА ДАННЫХ','JARVIS обрабатывает команду']
  };
  const avatarSources={
    'J.A.R.V.I.S.':'holograms/jarvis.webp',
    'Astra':'holograms/astra.webp',
    'Luna':'holograms/luna.webp',
    'Terra':'holograms/terra.webp',
    'Кибер':'holograms/kiber.webp'
  };

  const waveCtx=waveCanvas&&typeof waveCanvas.getContext==='function'?waveCanvas.getContext('2d'):null;
  const meshCtx=meshCanvas&&typeof meshCanvas.getContext==='function'?meshCanvas.getContext('2d'):null;
  let mode='idle',level=.12,target=.12,micUpdateAt=0,speechStart=0,speechSize=60;
  let voiceUpdateAt=0,voiceTarget=0,raf=0,lastFrame=0,wakeListening=false,blinkTimer=0;
  let meshPoints=[],meshBuiltFor='',mouthOpen=0;

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

  function buildMesh(){
    if(!meshCtx||!meshCanvas||!face||typeof document==='undefined'||typeof document.createElement!=='function')return;
    try{
      if(!face.complete||!face.naturalWidth)return;
      const sample=document.createElement('canvas');
      if(!sample||typeof sample.getContext!=='function')return;
      const w=96,h=96;
      sample.width=w;sample.height=h;
      const sctx=sample.getContext('2d',{willReadFrequently:true});
      if(!sctx||typeof sctx.drawImage!=='function'||typeof sctx.getImageData!=='function')return;
      sctx.clearRect(0,0,w,h);
      sctx.drawImage(face,0,0,w,h);
      const data=sctx.getImageData(0,0,w,h).data;
      const points=[];
      for(let y=1;y<h-1;y+=2){
        for(let x=1;x<w-1;x+=2){
          const i=(y*w+x)*4;
          const r=data[i],g=data[i+1],b=data[i+2],a=data[i+3]/255;
          const bright=(r+g+b)/3;
          const cyan=Math.max(g,b);
          if(a>.15&&(bright>32||cyan>54)){
            const strength=Math.min(1,(bright+cyan*.45)/310);
            points.push({x:x/w,y:y/h,s:strength,a:Math.min(1,.28+strength*.78)});
          }
        }
      }
      meshPoints=points;
      meshBuiltFor=face.getAttribute?.('src')||'';
      drawMesh(clock());
    }catch(_){
      meshPoints=[];
    }
  }

  function setPersona(name){
    const persona=canonicalPersona(name);
    const src=avatarSources[persona]||avatarSources['J.A.R.V.I.S.'];
    if(face){
      if(face.getAttribute?.('src')!==src){
        face.setAttribute('src',src);
        meshBuiltFor='';
      }
      face.style.left='0';
      face.onload=()=>{buildMesh();};
    }
    if(mouthFace){
      if(mouthFace.getAttribute?.('src')!==src)mouthFace.setAttribute('src',src);
      mouthFace.style.left='0';
    }
    if(avatar){
      avatar.dataset.persona=persona;
      avatar.style?.setProperty('--voice-scale','1');
      avatar.style?.setProperty('--talk-x','0px');
      avatar.style?.setProperty('--talk-y','0px');
      avatar.style?.setProperty('--talk-tilt','0deg');
      avatar.style?.setProperty('--brow-lift','0');
    }
    if(personaName)personaName.textContent=persona;
    defer(buildMesh,80);
  }

  function setMouth(v){
    const open=mode==='speaking'?clamp(v):0;
    mouthOpen=open;
    if(!avatar)return;
    const now=clock();
    const sway=Math.sin(now*.018)*open*1.1;
    const bob=Math.cos(now*.014+.6)*open*.7;
    const tilt=Math.sin(now*.010+.4)*open*.65;
    avatar.style?.setProperty('--voice-lift',(open*2.4).toFixed(2)+'px');
    avatar.style?.setProperty('--voice-scale',(1+open*.014).toFixed(3));
    avatar.style?.setProperty('--talk-x',sway.toFixed(2)+'px');
    avatar.style?.setProperty('--talk-y',bob.toFixed(2)+'px');
    avatar.style?.setProperty('--talk-tilt',tilt.toFixed(2)+'deg');
    avatar.style?.setProperty('--brow-lift',Math.min(1,open*.45).toFixed(3));
  }

  function drawMesh(now){
    if(!meshCtx||!meshCanvas)return;
    const w=meshCanvas.width||320,h=meshCanvas.height||320;
    meshCtx.clearRect(0,0,w,h);
    if(!meshPoints.length)return;

    const t=now*.001;
    const pulse=.93+.07*Math.sin(t*2.1);
    const speak=mode==='speaking'?mouthOpen:0;
    const headX=Math.sin(t*.72)*2.0 + Math.sin(t*.21)*1.4;
    const headY=Math.cos(t*.58)*1.5;
    const yaw=Math.sin(t*.43)*.018;
    const scan=(t*.20)%1;

    meshCtx.save();
    meshCtx.globalCompositeOperation='lighter';
    for(let i=0;i<meshPoints.length;i++){
      const p=meshPoints[i];
      const nx=p.x-.5, ny=p.y-.5;
      const curve=1-Math.min(.72,Math.abs(nx)*.75);
      let x=p.x*w + headX + nx*yaw*w*curve;
      let y=p.y*h + headY;

      // Speech only deforms the central lip/chin point cloud.
      if(speak>0 && p.y>.575 && p.y<.735 && p.x>.32 && p.x<.68){
        const lipCenter=1-Math.min(1,Math.abs(p.x-.5)/.18);
        const vertical=(p.y-.575)/.16;
        y += speak*lipCenter*(2.0+vertical*5.2);
        x += Math.sin(t*8+p.y*10)*speak*lipCenter*.7;
      }

      const band=Math.abs(p.y-scan)<.035?1.55:1;
      const flicker=.88+.12*Math.sin(t*5.5+p.x*17+p.y*11);
      const alpha=Math.min(.96,p.a*pulse*flicker*band);
      const radius=.55+p.s*1.05+(band>1?0.25:0);
      const blue=Math.round(205+45*p.s);
      meshCtx.fillStyle='rgba(100,'+blue+',255,'+alpha.toFixed(3)+')';
      meshCtx.beginPath();
      meshCtx.arc(x,y,radius,0,Math.PI*2);
      meshCtx.fill();
    }
    meshCtx.restore();
  }

  function scheduleBlink(){
    if(reducedMotion||!avatar||document.hidden)return;
    if(blinkTimer&&typeof window.clearTimeout==='function')window.clearTimeout(blinkTimer);
    const wait=2600+Math.floor(Math.random()*3900);
    blinkTimer=defer(()=>{
      if(document.hidden)return;
      avatar.classList?.add('is-blinking');
      defer(()=>avatar.classList?.remove('is-blinking'),95);
      if(Math.random()>.62)defer(()=>{avatar.classList?.add('is-blinking');defer(()=>avatar.classList?.remove('is-blinking'),75)},155);
      defer(scheduleBlink,450);
    },wait);
  }

  function sizeCanvas(){
    const cssSize=Math.max(1,Math.round(waveCanvas?.getBoundingClientRect?.().width||320));
    const dpr=Math.min(2,window.devicePixelRatio||1);
    const pixels=Math.round(cssSize*dpr);
    if(waveCanvas&&waveCtx){
      if(waveCanvas.width!==pixels)waveCanvas.width=pixels;
      if(waveCanvas.height!==pixels)waveCanvas.height=pixels;
    }
    if(meshCanvas&&meshCtx){
      const meshCss=Math.max(1,Math.round(meshCanvas.getBoundingClientRect?.().width||320));
      const meshPixels=Math.round(meshCss*dpr);
      if(meshCanvas.width!==meshPixels)meshCanvas.width=meshPixels;
      if(meshCanvas.height!==meshPixels)meshCanvas.height=meshPixels;
    }
    if(mode!=='idle'&&!document.hidden)drawWave();
  }

  function drawWave(){
    if(!waveCtx||!waveCanvas||!waveCanvas.width||!waveCanvas.height)return;
    const size=waveCanvas.width,mid=size/2;
    const wave=mode==='speaking'?level:mode==='listening'?level*.92:.06;
    const radius=mid*.31,spread=mid*.135;
    waveCtx.clearRect(0,0,size,size);waveCtx.lineCap='round';
    const bars=76;
    for(let i=0;i<bars;i++){
      const angle=i*Math.PI*2/bars-Math.PI/2;
      const detail=.54+.27*Math.sin(i*1.17)+.19*Math.sin(i*.39+clock()*.005);
      const length=Math.max(1.3,spread*(.05+wave*(.42+detail*.7)));
      const dx=Math.cos(angle),dy=Math.sin(angle);
      waveCtx.beginPath();waveCtx.lineWidth=Math.max(1.2,size/260);
      waveCtx.strokeStyle=mode==='speaking'?'rgba(154,241,255,.93)':'rgba(74,195,255,.82)';
      waveCtx.moveTo(mid+dx*radius,mid+dy*radius);
      waveCtx.lineTo(mid+dx*(radius+length),mid+dy*(radius+length));waveCtx.stroke();
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
    return Math.max(.04,Math.min(.70,(.10+syllable*.43+flicker*.10)*phraseGap));
  }
  function animate(now){
    raf=0;
    if(document.hidden||reducedMotion||(mode!=='listening'&&mode!=='speaking'))return;
    if(now-lastFrame<32){raf=window.requestAnimationFrame(animate);return}
    lastFrame=now;
    if(mode==='speaking'){
      target=now-voiceUpdateAt<220?voiceTarget:syntheticSpeech(now);
    }else if(now-micUpdateAt>230){
      target=.1+.035*Math.sin(now*.0047);
    }
    const factor=mode==='speaking'?(target>level?.52:.26):(target>level?.46:.18);
    level+=(target-level)*factor;level=Math.max(.02,Math.min(1,level));
    orb.style.setProperty('--local-energy',level.toFixed(3));
    orb.style.setProperty('--local-bloom',Math.min(1,.25+level*.7).toFixed(3));
    orb.style.setProperty('--hud-core-scale',(1+level*.10).toFixed(3));
    orb.style.setProperty('--hud-glow',Math.round(18+level*48)+'px');
    orb.style.setProperty('--hud-listen-glow',Math.round(23+level*56)+'px');
    orb.style.setProperty('--hud-speak-glow',Math.round(25+level*70)+'px');
    orb.style.setProperty('--hud-core-alpha',Math.min(1,.25+level*.7).toFixed(3));
    orb.style.setProperty('--hud-wave-alpha',Math.min(1,.56+level*.44).toFixed(3));
    setMouth(mode==='speaking'?Math.max(0,(level-.05)*.92):0);
    drawMesh(now);
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
    start();
    if(mode==='idle'||mode==='thinking'){drawMesh(clock());drawWave();}
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
    if(document.hidden){
      cancel();
      if(blinkTimer&&typeof window.clearTimeout==='function')window.clearTimeout(blinkTimer);
    }else{
      if(mode==='speaking')setMode('idle');
      sizeCanvas();drawMesh(clock());if(mode==='listening')start();scheduleBlink();
    }
  });
  window.addEventListener?.('resize',()=>{sizeCanvas();defer(buildMesh,80)},{passive:true});
  window.addEventListener?.('pagehide',cancel,{passive:true});
  window.JarvisHudState=Object.freeze({mode:()=>mode,energy:()=>level,pseudoSpeech:()=>syntheticSpeech(clock()),persona:()=>personaName?.textContent||'J.A.R.V.I.S.'});

  setPersona(window.localStorage?.getItem?.('jarvisPersona')||'J.A.R.V.I.S.');
  sizeCanvas();setMode('idle');scheduleBlink();
})();