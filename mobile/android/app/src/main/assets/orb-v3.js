/* J.A.R.V.I.S. HUD v7 — volumetric point-cloud turntable hologram.
 * Visual direction comes from the user-supplied hologram reference video.
 * Local persona images are sampled only to shape the point cloud; the visible
 * result is rendered as a 3D front/back particle volume with perspective.
 */
(function initJarvisHudV7(){
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
  const personaPalettes={
    'J.A.R.V.I.S.':[132,214,255],
    'Astra':[174,167,255],
    'Luna':[135,196,255],
    'Terra':[124,221,255],
    'Кибер':[109,183,255]
  };

  const waveCtx=waveCanvas&&typeof waveCanvas.getContext==='function'?waveCanvas.getContext('2d'):null;
  const meshCtx=meshCanvas&&typeof meshCanvas.getContext==='function'?meshCanvas.getContext('2d'):null;
  let mode='idle',level=.12,target=.12,micUpdateAt=0,speechStart=0,speechSize=60;
  let voiceUpdateAt=0,voiceTarget=0,raf=0,lastFrame=0,wakeListening=false,blinkTimer=0;
  let meshRaf=0,meshLast=0,meshPoints=[],mouthOpen=0,currentPersona='J.A.R.V.I.S.';

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
      const sw=84,sh=108;
      sample.width=sw;sample.height=sh;
      const sctx=sample.getContext('2d',{willReadFrequently:true});
      if(!sctx||typeof sctx.drawImage!=='function'||typeof sctx.getImageData!=='function')return;
      sctx.clearRect(0,0,sw,sh);
      sctx.drawImage(face,0,0,sw,sh);
      const data=sctx.getImageData(0,0,sw,sh).data;
      const points=[];
      for(let py=1;py<sh-1;py+=2){
        for(let px=1;px<sw-1;px+=2){
          const i=(py*sw+px)*4;
          const r=data[i],g=data[i+1],b=data[i+2],a=data[i+3]/255;
          const bright=(r+g+b)/3;
          const cyan=Math.max(g,b);
          if(a<.15||(bright<25&&cyan<48))continue;

          const u=px/sw,v=py/sh;
          const x=(u-.5)*2;
          const y=(v-.52)*2.05;
          const faceWidth=Math.max(.18,1-Math.pow(Math.max(0,Math.abs(y)-.18)*.34,2));
          const ell=Math.max(0,1-Math.pow(x/(.92*faceWidth),2));
          let depth=Math.sqrt(ell)*.46;

          // Neck / shoulder region should be shallower than the skull.
          if(v>.70)depth*=Math.max(.32,1-(v-.70)*1.75);
          if(v>.86)depth*=.55;

          const strength=Math.min(1,(bright+cyan*.55)/330);
          const alpha=Math.min(1,.24+strength*.78);
          const mouth=u>.34&&u<.66&&v>.57&&v<.70;
          const eye=(v>.405&&v<.515&&((u>.24&&u<.47)||(u>.53&&u<.76)));

          points.push({x,y,z:depth,u,v,s:strength,a:alpha,mouth,eye,back:false});
          // Sparse rear surface is what makes the rotation read as a volume.
          if(((px+py)>>1)%3===0){
            points.push({x:x*.98,y,z:-depth*.78,u,v,s:strength*.82,a:alpha*.48,mouth,eye,back:true});
          }
        }
      }
      meshPoints=points;
      drawMesh(clock());
      startMesh();
    }catch(_){
      meshPoints=[];
    }
  }

  function setPersona(name){
    const persona=canonicalPersona(name);
    currentPersona=persona;
    const src=avatarSources[persona]||avatarSources['J.A.R.V.I.S.'];
    if(face){
      if(face.getAttribute?.('src')!==src)face.setAttribute('src',src);
      face.style.left='0';
      face.onload=()=>buildMesh();
    }
    if(mouthFace){
      if(mouthFace.getAttribute?.('src')!==src)mouthFace.setAttribute('src',src);
      mouthFace.style.left='0';
    }
    if(avatar){
      avatar.dataset.persona=persona;
      avatar.style?.setProperty('--voice-scale','1');
      avatar.style?.setProperty('--brow-lift','0');
    }
    if(personaName)personaName.textContent=persona;
    defer(buildMesh,90);
  }

  function setMouth(v){
    const open=mode==='speaking'?clamp(v):0;
    mouthOpen=open;
    if(!avatar)return;
    avatar.style?.setProperty('--voice-scale',(1+open*.010).toFixed(3));
    avatar.style?.setProperty('--brow-lift',Math.min(1,open*.42).toFixed(3));
  }

  function drawMesh(now){
    if(!meshCtx||!meshCanvas||!meshPoints.length)return;
    const w=meshCanvas.width||320,h=meshCanvas.height||320;
    meshCtx.clearRect(0,0,w,h);

    const t=now*.001;
    const speak=mode==='speaking'?mouthOpen:0;
    const blink=!!avatar?.classList?.contains?.('is-blinking');
    const yaw=(Math.sin(t*.42)*.33)+(Math.sin(t*.115)*.055);
    const pitch=Math.sin(t*.23)*.025;
    const cy=Math.cos(yaw),sy=Math.sin(yaw),cp=Math.cos(pitch),sp=Math.sin(pitch);
    const bob=Math.sin(t*.62)*.018;
    const scan=((t*.155)%1)*2-1;
    const palette=personaPalettes[currentPersona]||personaPalettes['J.A.R.V.I.S.'];
    const projected=[];

    for(let i=0;i<meshPoints.length;i++){
      const p=meshPoints[i];
      let px=p.x,py=p.y+bob,pz=p.z;

      if(p.mouth&&speak>0){
        const center=1-Math.min(1,Math.abs(p.u-.5)/.16);
        const lip=(p.v-.57)/.13;
        py += speak*center*(.018+lip*.050);
        pz += speak*center*.012*Math.sin(t*8+p.u*12);
      }
      if(blink&&p.eye){
        const eyeY=(p.v<.46?-.16:-.10);
        py=py*.88+eyeY*.12;
      }

      // Yaw.
      let xr=px*cy+pz*sy;
      let zr=-px*sy+pz*cy;
      // Tiny pitch.
      let yr=py*cp-zr*sp;
      zr=py*sp+zr*cp;

      const focal=2.75;
      const persp=focal/(focal-zr*.72);
      const sx=w*.5+xr*persp*w*.375;
      const sy2=h*.51+yr*persp*h*.385;
      if(sx<-8||sx>w+8||sy2<-8||sy2>h+8)continue;

      const depthLight=.65+Math.max(0,zr+.35)*.55;
      const scanBoost=Math.abs(yr-scan)<.055?1.52:1;
      projected.push({sx,sy:sy2,z:zr,p,scale:persp,boost:depthLight*scanBoost});
    }

    projected.sort((a,b)=>a.z-b.z);
    meshCtx.save();
    meshCtx.globalCompositeOperation='lighter';

    for(let i=0;i<projected.length;i++){
      const q=projected[i],p=q.p;
      const flicker=.88+.12*Math.sin(t*5.7+p.u*15+p.v*21);
      const rear=p.back?.52:1;
      const alpha=Math.min(.98,p.a*flicker*q.boost*rear);
      const size=(.48+p.s*.92)*q.scale*(p.back?.78:1);
      const r=Math.min(255,Math.round(palette[0]+p.s*50));
      const g=Math.min(255,Math.round(palette[1]+p.s*30));
      const b=255;
      meshCtx.fillStyle='rgba('+r+','+g+','+b+','+alpha.toFixed(3)+')';
      meshCtx.beginPath();
      meshCtx.arc(q.sx,q.sy,size,0,Math.PI*2);
      meshCtx.fill();
    }

    // A faint violet core gives the same floating-volume feeling as the reference.
    const glow=meshCtx.createRadialGradient?.(w*.5,h*.52,8,w*.5,h*.52,w*.34);
    if(glow){
      glow.addColorStop(0,'rgba(94,84,255,.10)');
      glow.addColorStop(.55,'rgba(95,124,255,.035)');
      glow.addColorStop(1,'rgba(40,80,255,0)');
      meshCtx.fillStyle=glow;
      meshCtx.fillRect(0,0,w,h);
    }
    meshCtx.restore();
  }

  function meshLoop(now){
    meshRaf=0;
    if(document.hidden||reducedMotion||!meshCtx||!meshPoints.length)return;
    const minDelta=mode==='speaking'||mode==='listening'?32:62;
    if(now-meshLast>=minDelta){
      meshLast=now;
      drawMesh(now);
    }
    if(typeof window.requestAnimationFrame==='function')meshRaf=window.requestAnimationFrame(meshLoop);
  }
  function startMesh(){
    if(meshRaf||document.hidden||reducedMotion||!meshCtx||!meshPoints.length||typeof window.requestAnimationFrame!=='function')return;
    meshRaf=window.requestAnimationFrame(meshLoop);
  }
  function stopMesh(){
    if(meshRaf&&typeof window.cancelAnimationFrame==='function')window.cancelAnimationFrame(meshRaf);
    meshRaf=0;
  }

  function scheduleBlink(){
    if(reducedMotion||!avatar||document.hidden)return;
    if(blinkTimer&&typeof window.clearTimeout==='function')window.clearTimeout(blinkTimer);
    const wait=2800+Math.floor(Math.random()*3900);
    blinkTimer=defer(()=>{
      if(document.hidden)return;
      avatar.classList?.add('is-blinking');
      defer(()=>avatar.classList?.remove('is-blinking'),100);
      if(Math.random()>.72)defer(()=>{avatar.classList?.add('is-blinking');defer(()=>avatar.classList?.remove('is-blinking'),70)},165);
      defer(scheduleBlink,450);
    },wait);
  }

  function sizeCanvas(){
    const dpr=Math.min(2,window.devicePixelRatio||1);
    if(waveCanvas&&waveCtx){
      const cssSize=Math.max(1,Math.round(waveCanvas.getBoundingClientRect?.().width||320));
      const pixels=Math.round(cssSize*dpr);
      if(waveCanvas.width!==pixels)waveCanvas.width=pixels;
      if(waveCanvas.height!==pixels)waveCanvas.height=pixels;
    }
    if(meshCanvas&&meshCtx){
      const rect=meshCanvas.getBoundingClientRect?.()||{width:320,height:320};
      const mw=Math.max(1,Math.round((rect.width||320)*dpr));
      const mh=Math.max(1,Math.round((rect.height||320)*dpr));
      if(meshCanvas.width!==mw)meshCanvas.width=mw;
      if(meshCanvas.height!==mh)meshCanvas.height=mh;
      defer(buildMesh,50);
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
    return Math.max(.04,Math.min(.68,(.10+syllable*.42+flicker*.10)*phraseGap));
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
    setMouth(mode==='speaking'?Math.max(0,(level-.05)*.90):0);
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
    start();startMesh();
    if(mode==='idle'||mode==='thinking')drawWave();
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
      cancel();stopMesh();
      if(blinkTimer&&typeof window.clearTimeout==='function')window.clearTimeout(blinkTimer);
    }else{
      if(mode==='speaking')setMode('idle');
      sizeCanvas();startMesh();if(mode==='listening')start();scheduleBlink();
    }
  });
  window.addEventListener?.('resize',sizeCanvas,{passive:true});
  window.addEventListener?.('pagehide',()=>{cancel();stopMesh()},{passive:true});
  window.JarvisHudState=Object.freeze({mode:()=>mode,energy:()=>level,pseudoSpeech:()=>syntheticSpeech(clock()),persona:()=>personaName?.textContent||'J.A.R.V.I.S.'});

  setPersona(window.localStorage?.getItem?.('jarvisPersona')||'J.A.R.V.I.S.');
  sizeCanvas();setMode('idle');scheduleBlink();startMesh();
})();