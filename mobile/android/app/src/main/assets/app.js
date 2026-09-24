const state={mode:'phone',persona:localStorage.getItem('jarvisPersona')||'J.A.R.V.I.S.',waitingForCommand:false,diagnosticActive:false,wakeActive:false};
const $=id=>document.getElementById(id);const message=$('message'),command=$('command'),send=$('send'),modeButton=$('modeButton'),modeNav=$('modeNav'),appsNav=$('appsNav'),commandsNav=$('commandsNav'),appsPanel=$('appsPanel'),commandsPanel=$('commandsPanel'),appsList=$('appsList'),refreshApps=$('refreshApps'),settingsNav=$('settingsNav'),settingsPanel=$('settingsPanel'),personaList=$('personaList'),orbButton=$('orbButton'),fishApiKey=$('fishApiKey'),gigaApiKey=$('gigaApiKey'),saveApiKeys=$('saveApiKeys'),checkUpdates=$('checkUpdates'),apiStatus=$('apiStatus');
const personas=[['J.A.R.V.I.S.','Координация и общий помощник'],['Astra','Творчество и идеи'],['Luna','Анализ и знания'],['Terra','Практические задачи'],['Cyber','Безопасность и защита']];
const wakeWords=['джарвис','jarvis','астра','astra','луна','luna','сайбер','кибер','cyber','терра','terra'];
const activationPersonas={'джарвис':'J.A.R.V.I.S.','jarvis':'J.A.R.V.I.S.','астра':'Astra','astra':'Astra','луна':'Luna','luna':'Luna','сайбер':'Cyber','кибер':'Cyber','cyber':'Cyber','терра':'Terra','terra':'Terra'};
function showMessage(t){message.textContent=t||''}
function deferUi(fn,ms){
  if(typeof window!=='undefined'&&typeof window.setTimeout==='function')window.setTimeout(fn,ms);
}function activatePersona(word){const persona=activationPersonas[(word||'').toLowerCase()];if(!persona)return;state.persona=persona;localStorage.setItem('jarvisPersona',persona);window.AndroidJarvis?.setPersona?.(persona);showMessage('Активирован персонаж: '+persona)}function render(){modeButton.textContent=state.mode==='phone'?'📱 PHONE MODE':'🖥️ PC MODE';modeNav.textContent=state.mode==='phone'?'📱 Телефон':'🖥️ ПК';showMessage('Готов к работе, '+(localStorage.getItem('jarvisName')||'сэр')+'. Персонаж: '+state.persona)}
function sendCommand(v){const text=(v||command.value).trim();if(!text)return;state.waitingForCommand=false;window.AndroidJarvis?.stopListening?.();showMessage('Выполняю: «'+text+'»');try{const r=window.AndroidJarvis?.command(text);if(r){showMessage(r);const asyncNative=/^(Получаю свежую сводку новостей|Получаю местную новостную сводку|Получаю погоду по примерному местоположению|Запрашиваю доступ к местоположению|Читаю последнее сообщение WhatsApp|Открываю WhatsApp)/i.test(r);if(!asyncNative){window.AndroidJarvis?.speak(r)}}else showMessage('Обрабатываю запрос…')}catch(e){showMessage('Ошибка: '+e.message)}command.value=''}
function onSpeechState(phase,text){state.waitingForCommand=['starting','listening','processing','permission'].includes(phase);$('micStatus').textContent=text||'';orbButton.setAttribute('aria-busy',state.waitingForCommand?'true':'false');if(phase==='listening')state.waitingForCommand=true;if(!state.waitingForCommand)$('micLevel').value=0;}
window.onJarvisSpeechState=onSpeechState;window.onJarvisSpeechLevel=v=>{$('micLevel').value=Math.max(0,Math.min(100,(Number(v)+2)*8));};
function startListening(){if(state.diagnosticActive){showMessage('Завершите проверку микрофона, затем нажмите на круг.');return}if(!window.AndroidJarvis?.startListening){showMessage('Голосовой ввод доступен в APK.');return}state.waitingForCommand=true;showMessage('Слушаю…');window.AndroidJarvis.startListening()}
function onSpeechError(text){onSpeechState('idle',text||'Голосовой ввод остановлен.');showMessage(text||'Голосовой ввод остановлен.')}
function onSpeechReady(){showMessage('Микрофон доступен. Нажмите на круг, чтобы говорить.')}
function onSpeechResult(text){const clean=(text||'').trim();if(!clean){showMessage('Не удалось распознать речь.');return}const wake=/^(джарвис|джервис|жарвис|jarvis|астра|astra|луна|luna|сайбер|кибер|cyber|терра|terra)(?=[,\s.!?]|$)[,\s.!?]*/i;const matched=clean.match(wake);const activation=(matched?.[1]||'').toLowerCase().replace(/^(джервис|жарвис)$/,'джарвис');const onlyWake=!!matched&&!clean.replace(wake,'').trim();if(activation)activatePersona(activation);if(onlyWake){state.waitingForCommand=true;showMessage('Слушаю… Персонаж: '+state.persona);window.AndroidJarvis?.speak('Слушаю');return}if(state.waitingForCommand){sendCommand(matched?clean.replace(wake,'').trim():clean);return}if(!matched)return;const rest=clean.replace(wake,'').trim();if(!rest){state.waitingForCommand=true;showMessage('Слушаю… Персонаж: '+state.persona);window.AndroidJarvis?.speak('Слушаю');return}sendCommand(rest)}
window.onJarvisSpeechResult=onSpeechResult;window.onJarvisSpeechError=onSpeechError;window.onJarvisSpeechReady=onSpeechReady;window.onGigaChatResult=t=>{showMessage(t)};
window.onJarvisWakeStatus=(phase,text)=>{
  $('wakeStatus').textContent=text||'Ожидание имени';
  $('wakeStatus').setAttribute('data-status',phase);
  state.wakeActive=phase==='listening'||phase==='starting'||phase==='retry';
  if(state.wakeActive){
    $('micStatus').textContent=text||'Жду имя…';
    state.waitingForCommand=false;
    orbButton.setAttribute('aria-busy','false');
  }
};
window.onJarvisWakeModeChanged=enabled=>{
  $('wakeMode').checked=!!enabled;
  if(!enabled)state.wakeActive=false;
};
window.onJarvisWakeDetected=(persona,rest)=>{
  state.wakeActive=false;
  const key=Object.keys(activationPersonas).find(k=>activationPersonas[k]===persona);
  if(key)activatePersona(key);
  const commandText=(rest||'').trim();
  if(commandText)sendCommand(commandText);
  else showMessage('Активирован '+persona+'. Слушаю следующую команду…');
};
function filterApps(){const q=($('appsSearch')?.value||'').toLowerCase();document.querySelectorAll('#appsList .app-row').forEach(r=>r.hidden=!((r.querySelector('span')?.textContent||'').toLowerCase().includes(q)))}
function loadApps(){try{const apps=JSON.parse(window.AndroidJarvis?.listApps?.()||'[]');appsList.innerHTML='';apps.forEach(a=>{const row=document.createElement('label');row.className='app-row';const label=document.createElement('span');label.textContent=a.label;const checkbox=document.createElement('input');checkbox.type='checkbox';checkbox.checked=!!a.allowed;row.appendChild(label);row.appendChild(checkbox);row.querySelector('input').onchange=e=>showMessage(window.AndroidJarvis.setAppAllowed(a.packageName,e.target.checked));appsList.appendChild(row)});if(!apps.length)appsList.innerHTML='<div class="app-empty">Приложения не найдены.</div>';filterApps()}catch(e){appsList.innerHTML='<div class="app-empty">Не удалось загрузить приложения.</div>'}}
function selectAllApps(){document.querySelectorAll('#appsList input[type=checkbox]').forEach(i=>{if(!i.checked){i.checked=true;i.dispatchEvent(new Event('change'))}})}
function loadPersonas(){personaList.innerHTML='';personas.forEach(([n,d])=>{const r=document.createElement('label');r.className='app-row';r.innerHTML='<span><strong>'+n+'</strong><small> — '+d+'</small></span><input type="radio" name="persona" '+(state.persona===n?'checked':'')+'>';r.querySelector('input').onchange=()=>{state.persona=n;localStorage.setItem('jarvisPersona',n);window.AndroidJarvis?.setPersona(n);render()};personaList.appendChild(r)})}
function register(){const n=$('profileName').value.trim(),d=+$('profileDay').value,m=+$('profileMonth').value,y=+$('profileYear').value,rf=$('regFishApiKey')?.value.trim()||'',rg=$('regGigaApiKey')?.value.trim()||'';if(!n||!Number.isInteger(d)||!Number.isInteger(m)||!Number.isInteger(y)||y<1900||y>2100||new Date(y,m-1,d).getDate()!==d||new Date(y,m-1,d).getMonth()!==m-1){showMessage('Заполните имя и дату рождения корректно.');return}localStorage.setItem('jarvisName',n);localStorage.setItem('jarvisBirth',JSON.stringify({day:d,month:m,year:y}));if(rf||rg)window.AndroidJarvis?.setApiKeys?.(rf,rg);$('registration').hidden=true;showMessage('Добро пожаловать, '+n+'. Я JARVIS.');window.AndroidJarvis?.setUserProfile?.(n,d,m,y);window.AndroidJarvis?.command?.('меня зовут '+n);window.AndroidJarvis?.speak?.('Добро пожаловать, '+n);render()}
if($('saveProfile'))$('saveProfile').onclick=register;if(localStorage.getItem('jarvisName')||localStorage.getItem('jarvisOnboarded'))$('registration').hidden=true;else $('registration').hidden=false;
modeButton.onclick=()=>{showMessage('Сейчас подключено управление телефоном. Управление ПК требует отдельного подключения.')};modeNav.onclick=()=>{showMessage('Сейчас подключено управление телефоном. Управление ПК требует отдельного подключения.')};appsNav.onclick=()=>{appsPanel.hidden=!appsPanel.hidden;commandsPanel.hidden=true;settingsPanel.hidden=true;if(!appsPanel.hidden)loadApps()};commandsNav.onclick=()=>{commandsPanel.hidden=!commandsPanel.hidden;appsPanel.hidden=true;settingsPanel.hidden=true};settingsNav.onclick=()=>{settingsPanel.hidden=!settingsPanel.hidden;appsPanel.hidden=true;commandsPanel.hidden=true;if(!settingsPanel.hidden){loadPersonas();syncSettingSwitches();populateSkills();}};refreshApps.onclick=loadApps;if($('selectAllApps'))$('selectAllApps').onclick=selectAllApps;if($('appsSearch'))$('appsSearch').oninput=filterApps;send.onclick=()=>sendCommand();command.onkeydown=e=>{if(e.key==='Enter')sendCommand()};orbButton.onclick=startListening;orbButton.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();startListening()}};saveApiKeys.onclick=()=>{apiStatus.textContent=window.AndroidJarvis?.setApiKeys?.(fishApiKey.value.trim(),'')||'Сохранение доступно в APK';fishApiKey.value=''};$('saveMemory').onclick=()=>{$('memoryStatus').textContent=window.AndroidJarvis?.setMemoryGateway?.($('memoryUrl').value.trim(),$('memoryToken').value.trim())||'Недоступно';$('memoryToken').value=''};$('disableMemory').onclick=()=>{$('memoryStatus').textContent=window.AndroidJarvis?.setMemoryGateway?.('','')||'Недоступно';$('memoryUrl').value='';$('memoryToken').value=''};$('memoryUrl').value=window.AndroidJarvis?.getMemoryGatewayUrl?.()||'';settingsNav.addEventListener('click',()=>{$('memoryStatus').textContent=window.AndroidJarvis?.getMemoryGatewayStatus?.()||'Недоступно'});$('memoryStatus').textContent=window.AndroidJarvis?.getMemoryGatewayStatus?.()||'Локальная память работает.';checkUpdates.onclick=()=>{window.AndroidJarvis?.checkUpdates?.();$('updateInfo').textContent='Проверка подписанного релиза запущена…';};render();
$('microphoneSettings').onclick=()=>window.AndroidJarvis?.openMicrophoneSettings?.();

function syncSettingSwitches(){
  if($('wakeMode'))$('wakeMode').checked=!!window.AndroidJarvis?.getWakeModeEnabled?.();
  if($('backgroundWake'))$('backgroundWake').checked=!!window.AndroidJarvis?.getBackgroundWakeEnabled?.();
  if($('voiceInterrupt'))$('voiceInterrupt').checked=!!window.AndroidJarvis?.getInterruptByVoice?.();
  if($('overlayMode'))$('overlayMode').checked=!!window.AndroidJarvis?.overlayEnabled?.();
  try{
    const brain=JSON.parse(window.AndroidJarvis?.getGigaBrainStatus?.()||'{}');
    if($('gigaBrainMemory'))$('gigaBrainMemory').checked=!!brain.memory;
    if($('gigaShareMessages'))$('gigaShareMessages').checked=!!brain.shareMessages;
  }catch(_){}
}
function scheduleSettingSync(){
  deferUi(syncSettingSwitches,80);
  deferUi(syncSettingSwitches,350);
}
syncSettingSwitches();
window.onJarvisPermissionsStatus=(phase,text)=>{
  const info=$('micDiagnostics');
  if(info&&text)info.textContent=text;
  scheduleSettingSync();
};
window.onJarvisBackgroundWakeChanged=enabled=>{ $('backgroundWake').checked=!!enabled; };
window.onJarvisBackgroundWakeStatus=(status,text)=>{
  const info=$('backgroundWakeStatus');
  info.textContent=text||'Фоновое ожидание';
  info.setAttribute('data-status',status);
};
$('minimizeJarvis').onclick=()=>{
  const result=window.AndroidJarvis?.minimizeToBackground?.()||'Нужна обновлённая версия JARVIS.';
  $('backgroundWakeStatus').textContent=result;
};
$('backgroundWake').onchange=e=>{
  if(!window.AndroidJarvis?.setBackgroundWakeEnabled){
    e.target.checked=false;
    window.onJarvisBackgroundWakeStatus('error','Нужна версия J.A.R.V.I.S. BG.');
    return;
  }
  // The native side may reject the request if microphone, model, or visible
  // notification permission is absent; onJarvisBackgroundWakeChanged restores UI.
  // Keep the user's chosen value visible while Android completes permission
  // checks / foreground-service startup. The native callback below is the
  // authoritative correction if Android actually rejects the request.
  window.AndroidJarvis.setBackgroundWakeEnabled(!!e.target.checked);
};
window.onJarvisPendingBackgroundCommand=text=>{
  command.value=String(text||'').slice(0,240);
  showMessage('Команда из фонового режима: '+command.value+'. Нажмите ➤ для выполнения.');
};
window.onJarvisWakeModel=(status,text)=>{
  const info=$('wakeModelStatus');
  info.textContent=text||'Проверка офлайн-модели';
  info.setAttribute('data-status',status);
  $('installWakeModel').disabled=status==='download';
  if(status==='ready') $('wakeStatus').textContent='Офлайн-модель готова. Включите активацию по имени.';
};
$('wakeModelStatus').textContent=window.AndroidJarvis?.wakeModelInstalled?.()
  ? 'Тихая офлайн-модель установлена. Можно включить активацию.'
  : 'Для тихой активации установите офлайн-модель один раз (~46 МБ).';
$('installWakeModel').onclick=()=>{
  if(!window.AndroidJarvis?.installWakeModel){
    window.onJarvisWakeModel('error','Обновите APK для установки тихой модели.');
    return;
  }
  window.onJarvisWakeModel('download','Проверяю наличие модели…');
  window.AndroidJarvis.installWakeModel();
};
if($('wakeMode').checked)window.onJarvisWakeStatus('starting','Тихое ожидание имени включено при открытом приложении.');
$('wakeMode').onchange=e=>{
  if(!window.AndroidJarvis?.setWakeModeEnabled){
    e.target.checked=false;
    window.onJarvisWakeStatus('error','Обновите APK, чтобы включить активацию по имени.');
    return;
  }
  window.AndroidJarvis.setWakeModeEnabled(!!e.target.checked);
  scheduleSettingSync();
};
window.onJarvisMicDiagnostic=(status,text)=>{
  const info=$('micDiagnostics'),button=$('diagnoseMic');
  info.textContent=text||'Проверка завершена.';
  info.setAttribute('data-status',status);
  state.diagnosticActive=status==='checking';
  button.disabled=state.diagnosticActive;
};
$('diagnoseMic').onclick=()=>{
  if(!window.AndroidJarvis?.diagnoseMicrophone){
    window.onJarvisMicDiagnostic('error','Диагностика доступна только в обновлённом APK.');
    return;
  }
  window.onJarvisMicDiagnostic('checking','Запускаю проверку микрофона…');
  window.AndroidJarvis.diagnoseMicrophone();
};


window.onJarvisFeatureStatus=(feature,text)=>{
  const target=feature==='home'?'homeStatus':feature==='vision'?'visionStatus':feature==='updates'?'updateInfo':'reminderStatus';
  $(target).textContent=text||'Готово';
  if(feature==='vision')showMessage(text);
};
$('voiceInterrupt').checked=!!window.AndroidJarvis?.getInterruptByVoice?.();
$('voiceInterrupt').onchange=e=>{
  window.AndroidJarvis?.setInterruptByVoice?.(!!e.target.checked);
  scheduleSettingSync();
};
$('batteryMinutes').value=String(window.AndroidJarvis?.batteryMinutes?.()??0);
$('batteryMinutes').onchange=e=>{
  const ok=window.AndroidJarvis?.setBatteryMinutes?.(Number(e.target.value));
  if(!ok)$('batteryMinutes').value='0';
};
$('addReminder').onclick=()=>{
  const text=$('reminderText').value.trim(),n=Number($('reminderMinutes').value);
  if(!text||!Number.isInteger(n)||n<1||n>10080){
    $('reminderStatus').textContent='Укажите текст и время от 1 минуты до 7 дней.';return;
  }
  $('reminderStatus').textContent=window.AndroidJarvis?.scheduleReminder?.(text,n)||'Недоступно в этом APK';
};
$('listReminders').onclick=()=>{
  $('reminderStatus').textContent=window.AndroidJarvis?.listReminders?.()||'Недоступно';
};
$('visionCamera').onclick=()=>window.AndroidJarvis?.startVisionCamera?.();
$('visionPicker').onclick=()=>window.AndroidJarvis?.selectVisionPhoto?.();
$('homeStatus').textContent=window.AndroidJarvis?.homeStatus?.()||'Умный дом не подключён.';
$('connectHome').onclick=()=>{
  $('homeStatus').textContent=window.AndroidJarvis?.configureHome?.(
    $('homeUrl').value.trim(),$('homeToken').value.trim(),$('homeEntity').value.trim()
  )||'Недоступно';
  $('homeToken').value='';
};
$('homeLightOn').onclick=()=>{
  $('homeStatus').textContent=window.AndroidJarvis?.controlSmartLight?.(true)||'Недоступно';
};
$('homeLightOff').onclick=()=>{
  $('homeStatus').textContent=window.AndroidJarvis?.controlSmartLight?.(false)||'Недоступно';
};
$('disconnectHome').onclick=()=>{
  window.AndroidJarvis?.clearHome?.();$('homeStatus').textContent='Подключение очищено.';
  $('homeUrl').value='';$('homeToken').value='';$('homeEntity').value='';
};
function populateSkills(){
  let skills=[];
  try{skills=JSON.parse(window.AndroidJarvis?.skillsJson?.()||'[]')}catch(_){}
  $('skillCatalog').innerHTML='';
  skills.forEach(item=>{
    const row=document.createElement('label'),span=document.createElement('span'),input=document.createElement('input');
    row.className='app-row';span.textContent=item.title;
    input.type='checkbox';input.checked=!!item.enabled;
    input.onchange=e=>{
      if(!window.AndroidJarvis?.enableSkill?.(item.id,!!e.target.checked))e.target.checked=!e.target.checked;
      deferUi(populateSkills,150);
    };
    row.appendChild(span);row.appendChild(input);$('skillCatalog').appendChild(row);
  });
}
populateSkills();
$('overlayMode').checked=!!window.AndroidJarvis?.overlayEnabled?.();
window.onJarvisOverlayStatus=(enabled,text)=>{
  $('overlayMode').checked=!!enabled;
  $('overlayStatus').textContent=text||'';
};
$('overlayMode').onchange=e=>{
  window.AndroidJarvis?.setOverlayEnabled?.(!!e.target.checked);
  scheduleSettingSync();
};
window.onJarvisInterruptStatus=text=>{ $('micStatus').textContent=text;showMessage(text); };

$('skipRegistration').onclick=()=>{localStorage.setItem('jarvisOnboarded','1');$('registration').hidden=true;showMessage('Нажмите на круг, чтобы проверить голосовой ввод.');};

/* GigaChat is the ONLY cloud model for open-ended conversation.
 * Keys never return from Android into the DOM. Scope and model are preferences,
 * while local phone commands remain available without the cloud.
 */
(function initGigaBrainControls() {
  const badge=$('brainBadge'),info=$('brainStatus'),key=$('gigaApiKey');
  const model=$('gigaModel'),scope=$('gigaScope');
  const save=$('saveGigaBrain'),test=$('testGigaBrain');
  const brainMemory=$('gigaBrainMemory'),share=$('gigaShareMessages');
  if(!badge||!info||!model||!scope||!save||!test)return;
  let checking=false,connected=false,configured=false;
  function refresh(){
    let status={};
    try{status=JSON.parse(window.AndroidJarvis?.getGigaBrainStatus?.()||'{}')}catch(_){}
    configured=!!status.configured;
    connected=configured&&Number(status.verifiedAt)>0&&Date.now()-Number(status.verifiedAt)<24*60*60*1000;
    const allowedModels=['GigaChat-2','GigaChat-2-Pro','GigaChat-2-Max'];
    const allowedScopes=['GIGACHAT_API_PERS','GIGACHAT_API_B2B','GIGACHAT_API_CORP'];
    model.value=allowedModels.includes(status.model)?status.model:'GigaChat-2';
    scope.value=allowedScopes.includes(status.scope)?status.scope:'GIGACHAT_API_PERS';
    brainMemory.checked=!!status.memory;
    share.checked=!!status.shareMessages;
    if(!configured)info.textContent='Для ответов на вопросы введите личный Authorization Key GigaChat.';
    else if(connected)info.textContent='GigaChat подключён · '+model.value+' · голосовой помощник готов.';
    else info.textContent='Ключ сохранён · '+model.value+'. Нажмите «Проверить подключение».';
    badge.textContent=!configured?'◇  GIGACHAT · НЕ НАСТРОЕН':connected?'●  GIGACHAT · НА СВЯЗИ':'◈  GIGACHAT · КЛЮЧ СОХРАНЁН';
    badge.dataset.state=!configured?'disconnected':connected?'ready':'saved';
    test.disabled=checking;
  }
  window.onJarvisBrainState=(phase,text)=>{
    if(phase==='testing')checking=true;
    else checking=false;
    if(phase==='connected'||phase==='ready')connected=true;
    else if(phase==='error'||phase==='disconnected'||phase==='saved')connected=false;
    info.textContent=String(text||'GigaChat');
    if(phase==='disconnected')configured=false;
    else if(phase==='saved'||phase==='connected'||phase==='ready')configured=true;
    badge.textContent=phase==='thinking'||phase==='testing'?'◉  GIGACHAT · ДУМАЕТ':
      phase==='error'?'◇  GIGACHAT · ПРОВЕРЬТЕ СВЯЗЬ':
      phase==='connected'||phase==='ready'?'●  GIGACHAT · НА СВЯЗИ':
      !configured?'◇  GIGACHAT · НЕ НАСТРОЕН':'◈  GIGACHAT · '+model.value;
    badge.dataset.state=phase;
    test.disabled=checking;
  };
  save.onclick=()=>{
    const response=window.AndroidJarvis?.configureGigaChatBrain?.(
      key.value.trim(),scope.value,model.value
    )||'Нужна обновлённая версия JARVIS HUD3.';
    // Do not keep the secret in the rendered DOM once it was sent to native.
    key.value='';
    info.textContent=response;
    refresh();
  };
  test.onclick=()=>{
    if(checking)return;
    checking=true;
    test.disabled=true;
    window.onJarvisBrainState('testing','Проверяю связь. Это один запрос к GigaChat API…');
    if(!window.AndroidJarvis?.testGigaChatBrain){
      window.onJarvisBrainState('error','Проверка недоступна в этом APK.');
      return;
    }
    window.AndroidJarvis.testGigaChatBrain();
  };
  brainMemory.onchange=e=>{
    window.AndroidJarvis?.setGigaBrainMemory?.(!!e.target.checked);
    deferUi(refresh,150);
  };
  share.onchange=e=>{
    window.AndroidJarvis?.setGigaShareMessages?.(!!e.target.checked);
    deferUi(refresh,150);
  };
  $('clearGigaHistory').onclick=()=>{
    info.textContent=window.AndroidJarvis?.clearGigaBrainHistory?.()||'Недоступно';
  };
  $('disconnectGigaBrain').onclick=()=>{
    info.textContent=window.AndroidJarvis?.disconnectGigaChatBrain?.()||'Недоступно';
    key.value='';refresh();
  };
  // Local renderer never treats saved credentials as a verified connection.
  refresh();
  settingsNav.addEventListener('click',refresh);
})();

/* Weather/local-news access is requested only after a user action. */
(function initLocalInfoControls(){
  const status=$('locationStatus'),allow=$('allowLocation'),weather=$('weatherNow'),
    localNews=$('localNewsNow'),globalNews=$('globalNewsNow');
  if(!status||!allow||!weather||!localNews||!globalNews)return;
  const refresh=()=>{
    const granted=!!window.AndroidJarvis?.hasApproximateLocation?.();
    status.textContent=granted
      ? 'Примерное местоположение разрешено. Точное местоположение и фоновый доступ не используются.'
      : 'Местоположение выключено. Разрешение запрашивается только для погоды и местных новостей.';
    allow.textContent=granted?'Примерное местоположение разрешено':'Разрешить примерное местоположение';
    allow.disabled=granted;
  };
  window.onJarvisLocationStatus=(phase,text)=>{
    status.textContent=text||'Статус местоположения обновлён.';
    if(phase==='granted'){allow.disabled=true;allow.textContent='Примерное местоположение разрешено';}
    else if(phase==='denied'){allow.disabled=false;allow.textContent='Разрешить примерное местоположение';}
  };
  window.onJarvisLocalInfo=(kind,text)=>{
    status.textContent=text||'Готово.';
    showMessage(text||'Готово.');
  };
  allow.onclick=()=>window.AndroidJarvis?.requestApproximateLocation?.();
  weather.onclick=()=>window.AndroidJarvis?.requestWeather?.();
  localNews.onclick=()=>window.AndroidJarvis?.requestLocalNews?.();
  globalNews.onclick=()=>window.AndroidJarvis?.requestNewsSummary?.();
  settingsNav.addEventListener('click',refresh);
  refresh();
})();
