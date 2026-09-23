const state={mode:'phone',persona:localStorage.getItem('jarvisPersona')||'J.A.R.V.I.S.',waitingForCommand:false,diagnosticActive:false,wakeActive:false};
const $=id=>document.getElementById(id);const message=$('message'),command=$('command'),send=$('send'),modeButton=$('modeButton'),modeNav=$('modeNav'),appsNav=$('appsNav'),commandsNav=$('commandsNav'),appsPanel=$('appsPanel'),commandsPanel=$('commandsPanel'),appsList=$('appsList'),refreshApps=$('refreshApps'),settingsNav=$('settingsNav'),settingsPanel=$('settingsPanel'),personaList=$('personaList'),orbButton=$('orbButton'),fishApiKey=$('fishApiKey'),gigaApiKey=$('gigaApiKey'),saveApiKeys=$('saveApiKeys'),checkUpdates=$('checkUpdates'),apiStatus=$('apiStatus');
const personas=[['J.A.R.V.I.S.','Координация и общий помощник'],['Astra','Творчество и идеи'],['Luna','Анализ и знания'],['Terra','Практические задачи'],['Cyber','Безопасность и защита']];
const wakeWords=['джарвис','jarvis','астра','astra','луна','luna','сайбер','кибер','cyber','терра','terra'];
const activationPersonas={'джарвис':'J.A.R.V.I.S.','jarvis':'J.A.R.V.I.S.','астра':'Astra','astra':'Astra','луна':'Luna','luna':'Luna','сайбер':'Cyber','кибер':'Cyber','cyber':'Cyber','терра':'Terra','terra':'Terra'};
function showMessage(t){message.textContent=t||''}function activatePersona(word){const persona=activationPersonas[(word||'').toLowerCase()];if(!persona)return;state.persona=persona;localStorage.setItem('jarvisPersona',persona);window.AndroidJarvis?.setPersona?.(persona);showMessage('Активирован персонаж: '+persona)}function render(){modeButton.textContent=state.mode==='phone'?'📱 PHONE MODE':'🖥️ PC MODE';modeNav.textContent=state.mode==='phone'?'📱 Телефон':'🖥️ ПК';showMessage('Готов к работе, '+(localStorage.getItem('jarvisName')||'сэр')+'. Персонаж: '+state.persona)}
function sendCommand(v){const text=(v||command.value).trim();if(!text)return;state.waitingForCommand=false;window.AndroidJarvis?.stopListening?.();showMessage('Выполняю: «'+text+'»');try{const r=window.AndroidJarvis?.command(text);if(r){showMessage(r);const asyncNative=/^(Получаю свежую сводку новостей|Читаю последнее сообщение WhatsApp|Открываю WhatsApp)/i.test(r);if(!asyncNative){window.AndroidJarvis?.speak(r)}}else showMessage('Обрабатываю запрос…')}catch(e){showMessage('Ошибка: '+e.message)}command.value=''}
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
modeButton.onclick=()=>{showMessage('Сейчас подключено управление телефоном. Управление ПК требует отдельного подключения.')};modeNav.onclick=()=>{showMessage('Сейчас подключено управление телефоном. Управление ПК требует отдельного подключения.')};appsNav.onclick=()=>{appsPanel.hidden=!appsPanel.hidden;commandsPanel.hidden=true;settingsPanel.hidden=true;if(!appsPanel.hidden)loadApps()};commandsNav.onclick=()=>{commandsPanel.hidden=!commandsPanel.hidden;appsPanel.hidden=true;settingsPanel.hidden=true};settingsNav.onclick=()=>{settingsPanel.hidden=!settingsPanel.hidden;appsPanel.hidden=true;commandsPanel.hidden=true;if(!settingsPanel.hidden)loadPersonas()};refreshApps.onclick=loadApps;if($('selectAllApps'))$('selectAllApps').onclick=selectAllApps;if($('appsSearch'))$('appsSearch').oninput=filterApps;send.onclick=()=>sendCommand();command.onkeydown=e=>{if(e.key==='Enter')sendCommand()};orbButton.onclick=startListening;orbButton.onkeydown=e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();startListening()}};saveApiKeys.onclick=()=>{apiStatus.textContent=window.AndroidJarvis?.setApiKeys?.(fishApiKey.value.trim(),gigaApiKey.value.trim())||'Сохранение доступно в APK'};$('saveMemory').onclick=()=>{$('memoryStatus').textContent=window.AndroidJarvis?.setMemoryGateway?.($('memoryUrl').value.trim(),$('memoryToken').value.trim())||'Недоступно';$('memoryToken').value=''};$('disableMemory').onclick=()=>{$('memoryStatus').textContent=window.AndroidJarvis?.setMemoryGateway?.('','')||'Недоступно';$('memoryUrl').value='';$('memoryToken').value=''};$('memoryUrl').value=window.AndroidJarvis?.getMemoryGatewayUrl?.()||'';settingsNav.addEventListener('click',()=>{$('memoryStatus').textContent=window.AndroidJarvis?.getMemoryGatewayStatus?.()||'Недоступно'});$('memoryStatus').textContent=window.AndroidJarvis?.getMemoryGatewayStatus?.()||'Локальная память работает.';checkUpdates.onclick=()=>window.AndroidJarvis?.checkUpdates?.();render();
$('microphoneSettings').onclick=()=>window.AndroidJarvis?.openMicrophoneSettings?.();
$('wakeMode').checked=!!window.AndroidJarvis?.getWakeModeEnabled?.();
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

$('skipRegistration').onclick=()=>{localStorage.setItem('jarvisOnboarded','1');$('registration').hidden=true;showMessage('Нажмите на круг, чтобы проверить голосовой ввод.');};
