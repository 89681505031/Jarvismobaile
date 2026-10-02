(() => {
'use strict';
const byId = id => document.getElementById(id);
const native = () => window.AndroidJarvis;
let pending = null, counter = 0, fileSha = '', readTarget = '', tools = [], generated = '', proposal = null;
const status = text => { byId('studioStatus').textContent = text; };
const target = () => [byId('githubRepo').value.trim(), byId('githubBranch').value.trim(), byId('githubPath').value.trim()].join('|');
function sync() {
  try { const state = JSON.parse(native()?.connectorStatus?.() || '{}');
    byId('connectorStatus').textContent = ['github','vercel','openai'].map(id => id + ': ' + (state[id] ? 'подключён' : 'не подключён')).join(' · ');
  } catch (_) { status('Не удалось проверить подключения'); }
}
function run(action, args = {}) {
  if (pending) { status('Дождитесь завершения текущей операции'); return; }
  if (!native()?.connectorAction) { status('Эта функция доступна в новой Android-версии Джарвиса'); return; }
  const id = String(++counter); pending = {id, action, target: target()};
  status('Выполняю…'); byId('connectorResult').textContent = '';
  native().connectorAction(id, action, JSON.stringify(args));
}
byId('openStudio').onclick = () => { byId('studioPanel').hidden = false; sync(); };
byId('closeStudio').onclick = () => { byId('studioPanel').hidden = true; };
byId('studioPanel').querySelectorAll('[data-tab]').forEach(button => button.onclick = () => {
  byId('studioPanel').querySelectorAll('[data-pane]').forEach(pane => { pane.hidden = pane.dataset.pane !== button.dataset.tab; });
});
byId('saveConnector').onclick = () => {
  const token = byId('connectorToken').value.trim(); if (!token) { status('Введите ключ или токен'); return; }
  status(native()?.configureConnector?.(byId('connectorProvider').value, token) || 'Доступно в APK');
  byId('connectorToken').value = ''; sync();
};
byId('removeConnector').onclick = () => { status(native()?.configureConnector?.(byId('connectorProvider').value, '') || 'Доступно в APK'); sync(); };
byId('studioAttach').onclick = () => { status('Выберите файл'); native()?.attachFile?.(); };
byId('studioClear').onclick = () => { native()?.clearAttachment?.(); byId('attachmentResult').textContent = 'Файл не выбран'; byId('attachmentConsent').checked = false; };
byId('attachmentConsent').onchange = event => native()?.setAttachmentConsent?.(event.target.checked);
window.onJarvisAttachment = json => { try {
  const data = JSON.parse(json); status(data.error || 'Файл прочитан');
  byId('attachmentResult').textContent = data.error || data.name + '\n' + data.text + (data.totalPages ? `\nПрочитано страниц: ${data.pagesScanned}/${data.totalPages}` : '');
  byId('attachmentConsent').checked = false;
} catch (_) { status('Не удалось прочитать ответ сканера'); } };
byId('generateImage').onclick = () => run('image.generate', {prompt: byId('imagePrompt').value});
byId('editImage').onclick = () => { if (confirm('Отправить прикреплённое фото и описание в OpenAI для редактирования?')) run('image.edit', {prompt: byId('imagePrompt').value}); };
byId('shareStudioImage').onclick = () => native()?.shareGeneratedImage?.(generated);
const githubArgs = () => ({repo: byId('githubRepo').value.trim(), branch: byId('githubBranch').value.trim(), path: byId('githubPath').value.trim()});
byId('githubRepos').onclick = () => run('github.repos');
byId('githubRead').onclick = () => run('github.file', githubArgs());
byId('githubWrite').onclick = () => {
  const args = {...githubArgs(), content: byId('githubContent').value, message: byId('githubMessage').value, confirmed: true};
  if (readTarget === target() && fileSha) args.sha = fileSha;
  if (confirm(`Записать ${args.path} в ${args.repo}, ветка ${args.branch}?\n${args.content.slice(0,500)}`)) run('github.write', args);
};
const vercelArgs = () => ({team: byId('vercelTeam').value.trim(), project: byId('vercelProject').value.trim()});
byId('vercelProjects').onclick = () => run('vercel.projects', vercelArgs());
byId('vercelDeployments').onclick = () => run('vercel.deployments', vercelArgs());
byId('vercelDeploy').onclick = () => {
  const args = {...vercelArgs(), name: byId('vercelName').value.trim(), repoId: byId('vercelRepoId').value.trim(), branch: byId('vercelBranch').value.trim(), confirmed: true};
  if (confirm(`Создать preview ${args.name} из ветки ${args.branch}?`)) run('vercel.deploy', args);
};
byId('saveMcp').onclick = () => { status(native()?.configureMcp?.(byId('mcpEndpoint').value.trim(), byId('mcpToken').value.trim()) || 'Доступно в APK'); byId('mcpToken').value = ''; };
byId('projectAgent').onclick = () => {
  proposal = null; byId('applyProposal').hidden = true; byId('projectProposal').textContent = '';
  run('agent.plan', {task: byId('projectTask').value, context: {...githubArgs(), ...vercelArgs(), name: byId('vercelName').value.trim(), repoId: byId('vercelRepoId').value.trim()}});
};
byId('applyProposal').onclick = () => {
  if (!proposal) return;
  if (confirm('Выполнить подготовленное действие?\n' + JSON.stringify(proposal, null, 2))) {
    const next = proposal; proposal = null; byId('applyProposal').hidden = true;
    run(next.action, {...next.args, confirmed: true});
  }
};
byId('mcpList').onclick = () => run('mcp.list');
byId('mcpTool').onchange = () => { const tool = tools.find(t => t.name === byId('mcpTool').value); byId('mcpSchema').textContent = tool ? tool.description + '\n' + JSON.stringify(tool.inputSchema, null, 2) : ''; };
byId('mcpCall').onclick = () => { try {
  const args = JSON.parse(byId('mcpArgs').value);
  if (!args || Array.isArray(args) || typeof args !== 'object') throw Error('Параметры должны быть объектом JSON');
  const name = byId('mcpTool').value; if (!name) throw Error('Сначала получите инструменты');
  if (confirm(`Выполнить ${name} на подключённом сервере?\n${JSON.stringify(args)}`)) run('mcp.call', {name, arguments: args, confirmed: true});
} catch (error) { status(error.message); } };
window.onJarvisConnectorResult = (id, json) => {
  try {
    const result = JSON.parse(json), task = pending?.id === id ? pending : null;
    if (task) pending = null;
    status(result.error || 'Готово');
    byId('connectorResult').textContent = result.error || result.text || JSON.stringify(result, null, 2);
    if (result.proposal) { proposal = result.proposal; byId('projectProposal').textContent = JSON.stringify(proposal, null, 2); byId('applyProposal').hidden = false; }
    if (result.image) { byId('studioImage').src = result.image; byId('studioImage').hidden = false; generated = result.file; byId('shareStudioImage').hidden = false; }
    if (task?.action === 'github.file' && !result.error) { fileSha = result.sha || ''; readTarget = task.target; byId('githubContent').value = result.decoded || ''; }
    if (task?.action === 'github.write' && !result.error) { fileSha = ''; readTarget = ''; status('Файл сохранён. Перед следующим изменением прочитайте его снова.'); }
    if (result.tools) { tools = result.tools; byId('mcpTool').replaceChildren(); tools.forEach(tool => { const opt = document.createElement('option'); opt.value = tool.name; opt.textContent = tool.name; byId('mcpTool').appendChild(opt); }); byId('mcpTool').onchange(); }
    if (id.startsWith('voice:')) { byId('studioPanel').hidden = false; byId('studioPanel').querySelectorAll('[data-pane]').forEach(p => { p.hidden = p.dataset.pane !== id.substring(6); }); }
  } catch (_) { status('Не удалось прочитать ответ сервиса'); pending = null; }
};
})();
