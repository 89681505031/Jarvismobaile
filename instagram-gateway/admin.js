let accessToken = '';
const $ = id => document.getElementById(id);
const access = $('access'), panel = $('panel'), notice = $('notice'), inbox = $('inbox');
function show(text) { notice.textContent = text; }
async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: {
    Authorization: 'Bearer ' + accessToken, ...(options.headers || {}),
  }, cache: 'no-store' });
  const result = await response.json();
  if (!response.ok) throw Error(result.error || 'Ошибка подключения');
  return result;
}
async function refresh() {
  show('Загружаю входящие сообщения…');
  try {
    const data = await api('/admin/inbox');
    inbox.replaceChildren();
    if (!data.items.length) { show('Новых сообщений нет.'); return; }
    show('Получено сообщений: ' + data.items.length);
    for (const item of data.items) {
      const card = document.createElement('article');
      const time = document.createElement('p');
      time.className = 'muted';
      time.textContent = 'Получено: ' + new Date(item.receivedAt).toLocaleString();
      const text = document.createElement('p');
      text.textContent = item.text;
      const label = document.createElement('label');
      label.textContent = 'Ваш ответ:';
      const answer = document.createElement('textarea');
      answer.maxLength = 1000;
      label.appendChild(answer);
      const send = document.createElement('button');
      send.type = 'button';
      send.textContent = 'Подтвердить и отправить';
      send.addEventListener('click', async () => {
        const reply = answer.value.trim();
        if (!reply) return show('Сначала напишите ответ.');
        if (!window.confirm('Отправить это сообщение через Instagram?')) return;
        send.disabled = true;
        try {
          await api('/admin/reply', { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ messageId: item.id, text: reply }) });
          show('Ответ отправлен.'); card.remove();
        } catch (error) { show(error.message); }
        finally { send.disabled = false; }
      });
      card.append(time, text, label, send);
      inbox.appendChild(card);
    }
  } catch (error) { show(error.message); }
}
access.addEventListener('submit', async event => {
  event.preventDefault();
  accessToken = $('token').value;
  $('token').value = '';
  access.hidden = true;
  panel.hidden = false;
  await refresh();
});
$('refresh').addEventListener('click', refresh);
$('logout').addEventListener('click', () => {
  accessToken = ''; panel.hidden = true; access.hidden = false; inbox.replaceChildren();
});
