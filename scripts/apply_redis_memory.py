from pathlib import Path

root = Path('mobile/android/app/src/main')
java = root / 'java/com/jarvis/phone'
m = java / 'MainActivity.kt'
s = m.read_text(encoding='utf-8')
needle = '            val answer = gigaChat.ask(text, selectedPersona, memory.memoryContext())'
assert s.count(needle) == 1
s = s.replace(needle, '''            val remote = cloudMemory.recall(memoryText)
            val context = memory.memoryContext() + if (remote.isNotBlank()) "\\n\\nРелевантные воспоминания:\\n" + remote else ""
            val answer = gigaChat.ask(text, selectedPersona, context)''')
needle = '                speak(answer, resumeAfterSpeech = true)\n            }\n        }\n    }'
assert s.count(needle) == 1
s = s.replace(needle, '                speak(answer, resumeAfterSpeech = true)\n            }\n            cloudMemory.record(memoryText, answer)\n        }\n    }')
needle = '    private lateinit var memory: JarvisMemory'
assert s.count(needle) == 1
s = s.replace(needle, needle + '\n    private lateinit var cloudMemory: RedisMemoryGateway')
needle = '        memory = JarvisMemory(this)'
assert s.count(needle) == 1
s = s.replace(needle, needle + '\n        cloudMemory = RedisMemoryGateway(this)')
needle = '        @JavascriptInterface fun getMemorySummary(): String = memory.memoryContext()'
assert s.count(needle) == 1
s = s.replace(needle, needle + '''
        @JavascriptInterface fun setMemoryGateway(url: String, token: String): String {
            return cloudMemory.configure(url, token)
        }
        @JavascriptInterface fun getMemoryGatewayStatus(): String = cloudMemory.status()
        @JavascriptInterface fun getMemoryGatewayUrl(): String = cloudMemory.endpoint()
''')
m.write_text(s, encoding='utf-8')

html = root / 'assets/index.html'
s = html.read_text(encoding='utf-8')
needle = '<button id="checkUpdates"'
assert s.count(needle) == 1
s = s.replace(needle, '<div class="apps-title" style="margin-top:16px">🧠 Облачная память</div><p>При включении вопросы к ИИ и его ответы сохраняются в облаке для будущих разговоров.</p><div class="api-field"><label for="memoryUrl">URL шлюза памяти (HTTPS)</label><input id="memoryUrl" type="url" autocomplete="off" placeholder="https://example.vercel.app/api/memory"></div><div class="api-field"><label for="memoryToken">Токен шлюза</label><input id="memoryToken" type="password" autocomplete="off"></div><button id="saveMemory" class="apps-action touch-button" type="button">Сохранить память</button><button id="disableMemory" class="apps-action touch-button" type="button">Отключить облачную память</button><div id="memoryStatus" class="app-empty">Локальная память работает.</div>' + needle)
html.write_text(s, encoding='utf-8')

js = root / 'assets/app.js'
s = js.read_text(encoding='utf-8')
needle = 'checkUpdates.onclick='
assert s.count(needle) == 1
s = s.replace(needle, "$('saveMemory').onclick=()=>{$('memoryStatus').textContent=window.AndroidJarvis?.setMemoryGateway?.($('memoryUrl').value.trim(),$('memoryToken').value.trim())||'Недоступно';$('memoryToken').value=''};$('disableMemory').onclick=()=>{$('memoryStatus').textContent=window.AndroidJarvis?.setMemoryGateway?.('','')||'Недоступно';$('memoryUrl').value='';$('memoryToken').value=''};$('memoryUrl').value=window.AndroidJarvis?.getMemoryGatewayUrl?.()||'';settingsNav.addEventListener('click',()=>{$('memoryStatus').textContent=window.AndroidJarvis?.getMemoryGatewayStatus?.()||'Недоступно'});$('memoryStatus').textContent=window.AndroidJarvis?.getMemoryGatewayStatus?.()||'Локальная память работает.';" + needle)
js.write_text(s, encoding='utf-8')
print('Optional Redis memory gateway integrated')
(java / 'RedisMemoryGateway.kt').write_text(Path('scripts/RedisMemoryGateway.kt').read_text(encoding='utf-8'), encoding='utf-8')
