/**
 * OpenClaw4j Control Panel — app.js
 * Handles WebSocket connection, RPC calls, and all UI interactions.
 */

const App = (() => {
  // ─── State ────────────────────────────────────────────────────────────────
  let ws = null;
  let wsReady = false;
  let token = null;
  let authMode = 'token';
  let pendingRequests = {};
  let reqIdCounter = 0;
  let currentView = 'chat';
  let currentSession = 'webchat:default';
  let isGenerating = false;
  let currentAssistantMsgEl = null;
  let currentAssistantContent = '';
  let logFollowEnabled = true;
  let allLogs = [];
  let agents = [];
  let reconnectTimer = null;
  let reconnectDelay = 1000;
  let heartbeatTimer = null;

  const WS_URL = `ws://${location.host}/ws`;
  const STORAGE_TOKEN_KEY = 'openclaw4j_token';

  // ─── WebSocket ─────────────────────────────────────────────────────────────
  function connect() {
    setConnStatus('connecting', 'Connecting...');
    try {
      ws = new WebSocket(WS_URL);
    } catch (e) {
      scheduleReconnect();
      return;
    }

    ws.onopen = () => {
      console.log('[WS] Connected');
      reconnectDelay = 1000;
    };

    ws.onmessage = (e) => {
      try {
        const frame = JSON.parse(e.data);
        if (frame.type === 'event') handleEvent(frame);
        else if (frame.type === 'res') handleResponse(frame);
      } catch (err) {
        console.error('[WS] Parse error:', err);
      }
    };

    ws.onclose = () => {
      wsReady = false;
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
      setConnStatus('offline', 'Disconnected');
      scheduleReconnect();
    };

    ws.onerror = () => {
      wsReady = false;
      clearInterval(heartbeatTimer);
      heartbeatTimer = null;
      setConnStatus('offline', 'Connection error');
    };
  }

  function scheduleReconnect() {
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(() => {
      setConnStatus('connecting', `Reconnecting...`);
      connect();
    }, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 1.5, 10000);
  }

  function handleEvent(frame) {
    switch (frame.event) {
      case 'connect.challenge':
        onChallenge(frame.payload);
        break;
      case 'presence':
        if (frame.payload?.status === 'online') {
          wsReady = true;
          setConnStatus('online', 'Connected');
          onConnected();
        }
        break;
      case 'chat':
        onChatEvent(frame.payload);
        break;
      case 'cron':
        onCronEvent(frame.payload);
        break;
      case 'tick':
        // heartbeat — ignore
        break;
      case 'skills.installed': {
        const p = frame.payload || {};
        const name = p.name || 'skill';
        showToast(`✅ Skill "${name}" installed successfully!`, 'success');
        // Update the install-from-url result box if visible
        const resultEl = document.getElementById('skill-url-result');
        if (resultEl && resultEl.innerHTML.includes('⏳')) {
          resultEl.innerHTML = `<span style="color:var(--green)">✅ Skill "<strong>${escHtml(name)}</strong>" installed successfully!</span>`;
        }
        // Refresh the installed list
        loadSkills();
        break;
      }
      case 'skills.install.error': {
        const p = frame.payload || {};
        const errMsg = p.error || 'Unknown error';
        showToast(`❌ Skill install failed: ${errMsg}`, 'error');
        const resultEl = document.getElementById('skill-url-result');
        if (resultEl && resultEl.innerHTML.includes('⏳')) {
          resultEl.innerHTML = `<span style="color:var(--red)">❌ Install failed: ${escHtml(errMsg)}</span>`;
        }
        break;
      }
      default:
        console.debug('[WS] Event:', frame.event, frame.payload);
    }
  }

  function handleResponse(frame) {
    const resolve = pendingRequests[frame.id];
    if (resolve) {
      delete pendingRequests[frame.id];
      resolve(frame);
    }
  }

  function rpc(method, params) {
    return new Promise((resolve, reject) => {
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        reject(new Error('WebSocket not connected'));
        return;
      }
      const id = String(++reqIdCounter);
      pendingRequests[id] = (frame) => {
        if (frame.ok) resolve(frame.payload);
        else reject(new Error(frame.error?.message || 'RPC error'));
      };
      ws.send(JSON.stringify({ id, method, params }));
    });
  }

  // ─── Auth ──────────────────────────────────────────────────────────────────
  function onChallenge(payload) {
    authMode = payload?.mode || 'token';
    const savedToken = sessionStorage.getItem(STORAGE_TOKEN_KEY);

    if (authMode === 'none') {
      document.getElementById('auth-none-form').style.display = 'block';
      document.getElementById('auth-token-form').style.display = 'none';
      doAuth();
    } else {
      showAuthOverlay();
      if (savedToken) {
        document.getElementById('auth-token-input').value = savedToken;
        doAuth(true);  // auto-try saved token
      }
    }
  }

  function showAuthOverlay() {
    document.getElementById('auth-overlay').classList.remove('hidden');
  }

  function hideAuthOverlay() {
    document.getElementById('auth-overlay').classList.add('hidden');
  }

  async function doAuth(silent = false) {
    token = document.getElementById('auth-token-input')?.value?.trim() || '';
    const errEl = document.getElementById('auth-error');
    const btnEl = document.querySelector('#auth-token-form button');

    if (!silent && btnEl) { btnEl.disabled = true; btnEl.textContent = 'Connecting...'; }

    // If WebSocket is not open yet, wait up to 5 seconds
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      if (!silent) {
        errEl.textContent = 'WebSocket not ready, waiting...';
        errEl.style.display = 'block';
      }
      await new Promise(r => setTimeout(r, 2000));
    }

    const params = authMode === 'none' ? {} : { token, clientName: 'OpenClaw4j UI' };
    try {
      await rpc('auth', params);
      sessionStorage.setItem(STORAGE_TOKEN_KEY, token);
      errEl.style.display = 'none';
      hideAuthOverlay();
      startHeartbeat();
    } catch (e) {
      if (!silent) {
        errEl.textContent = 'Error: ' + (e.message || 'Authentication failed');
        errEl.style.display = 'block';
      }
    } finally {
      if (!silent && btnEl) { btnEl.disabled = false; btnEl.textContent = 'Connect'; }
    }
  }

  function startHeartbeat() {
    clearInterval(heartbeatTimer);
    heartbeatTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN && wsReady) {
        rpc('health', {}).catch(() => {}); // silent ping
      }
    }, 30000); // every 30 seconds
  }

  // ─── Connected ─────────────────────────────────────────────────────────────
  async function onConnected() {
    try {
      agents = await rpc('agents.list') || [];
      populateAgentSelects();
      loadChatHistory(currentSession);
      loadSessions();
    } catch (e) {
      console.warn('onConnected init error:', e);
    }
  }

  function populateAgentSelects() {
    const selects = ['agent-select', 'cron-agent'];
    selects.forEach(id => {
      const el = document.getElementById(id);
      if (!el) return;
      el.innerHTML = agents.map(a =>
        `<option value="${esc(a.id)}">${esc(a.name || a.id)}</option>`
      ).join('') || '<option value="default">Default Agent</option>';
    });

    // Auto-select the last agent (highest permission: Assistant+Tools)
    const mainSelect = document.getElementById('agent-select');
    if (mainSelect && mainSelect.options.length > 0) {
      mainSelect.selectedIndex = mainSelect.options.length - 1;
    }
  }

  // ─── Chat ──────────────────────────────────────────────────────────────────
  async function sendMessage() {
    if (isGenerating) return;
    const input = document.getElementById('chat-input');
    const text = input.value.trim();
    if (!text) return;

    input.value = '';
    input.style.height = 'auto';

    const agentId = document.getElementById('agent-select').value || 'default';

    // Append user bubble
    appendMessage('user', text);

    // Show typing indicator
    const typingEl = appendTyping();
    isGenerating = true;
    currentAssistantContent = '';
    currentAssistantMsgEl = null;
    setSendBtn(false);

    try {
      await rpc('chat.send', {
        agentId,
        sessionKey: currentSession,
        text
      });
    } catch (e) {
      removeTyping(typingEl);
      appendMessage('system', `❌ Error: ${e.message}`);
      isGenerating = false;
      setSendBtn(true);
    }
  }

  function onChatEvent(payload) {
    if (!payload || payload.sessionKey !== currentSession) return;

    if (payload.type === 'chunk') {
      // First chunk — remove typing indicator, create assistant bubble
      if (!currentAssistantMsgEl) {
        removeAllToolCallCards();
        removeAllTyping();
        currentAssistantMsgEl = createStreamingBubble();
      }
      currentAssistantContent += payload.content;
      renderStreamingBubble(currentAssistantMsgEl, currentAssistantContent);
      scrollToBottom();

    } else if (payload.type === 'tool_call') {
      // Show tool call card
      removeAllTyping();
      appendToolCallCard(payload.toolName, payload.arguments);

    } else if (payload.type === 'tool_result') {
      // Update last tool call card with result
      updateToolCallCard(payload.toolName, payload.result, payload.success);
      // Show new typing indicator for next LLM response
      appendTypingIndicator();

    } else if (payload.type === 'complete') {
      removeAllTyping();
      if (!currentAssistantMsgEl) {
        if (payload.content && payload.content.trim()) {
          appendMessage('assistant', payload.content);
        }
      } else {
        finalizeStreamingBubble(currentAssistantMsgEl, payload.content, payload.usage);
      }
      currentAssistantMsgEl = null;
      currentAssistantContent = '';
      isGenerating = false;
      setSendBtn(true);
      if (payload.usage) {
        document.getElementById('usage-hint').textContent =
          `↑${payload.usage.inputTokens} ↓${payload.usage.outputTokens} tokens`;
      }

    } else if (payload.type === 'error') {
      removeAllTyping();
      appendMessage('system', `❌ ${payload.error}`);
      currentAssistantMsgEl = null;
      isGenerating = false;
      setSendBtn(true);
    }
  }

  // ─── Tool Call Cards ─────────────────────────────────────────────────────

  let lastToolCardEl = null;

  function appendToolCallCard(toolName, argsJson) {
    const container = document.getElementById('chat-messages');
    const card = document.createElement('div');
    card.className = 'tool-call-card running';
    card.dataset.toolName = toolName;

    let argsDisplay = '';
    try {
      const args = JSON.parse(argsJson || '{}');
      const entries = Object.entries(args);
      if (entries.length > 0) {
        argsDisplay = entries.map(([k, v]) => {
          const val = typeof v === 'string' && v.length > 80 ? v.substring(0, 80) + '...' : v;
          return `<span class="tc-arg-key">${k}</span>: <span class="tc-arg-val">${escapeHtml(String(val))}</span>`;
        }).join('<br>');
      }
    } catch(e) {
      argsDisplay = escapeHtml(argsJson || '');
    }

    card.innerHTML = `
      <div class="tc-header">
        <span class="tc-spinner">⚙</span>
        <span class="tc-name">${escapeHtml(toolName)}</span>
        <span class="tc-status">Running…</span>
      </div>
      ${argsDisplay ? `<div class="tc-args">${argsDisplay}</div>` : ''}
      <div class="tc-result"></div>
    `;
    container.appendChild(card);
    lastToolCardEl = card;
    scrollToBottom();
    return card;
  }

  function updateToolCallCard(toolName, result, success) {
    // Find the last card with this tool name
    const cards = document.querySelectorAll('.tool-call-card.running');
    let card = null;
    for (let i = cards.length - 1; i >= 0; i--) {
      if (cards[i].dataset.toolName === toolName) { card = cards[i]; break; }
    }
    if (!card && lastToolCardEl) card = lastToolCardEl;
    if (!card) return;

    card.classList.remove('running');
    card.classList.add(success ? 'success' : 'failed');

    const statusEl = card.querySelector('.tc-status');
    const spinnerEl = card.querySelector('.tc-spinner');
    const resultEl = card.querySelector('.tc-result');

    if (statusEl) statusEl.textContent = success ? 'Done' : 'Failed';
    if (spinnerEl) spinnerEl.textContent = success ? '✅' : '❌';
    if (resultEl && result) {
      const lines = result.split('\n').slice(0, 6);
      const preview = lines.join('\n') + (result.split('\n').length > 6 ? '\n...' : '');
      resultEl.innerHTML = `<pre class="tc-output">${escapeHtml(preview)}</pre>`;
    }
    scrollToBottom();
  }

  function removeAllToolCallCards() {
    // Don't remove — keep them for history context. Just reset lastToolCardEl.
    lastToolCardEl = null;
  }

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  async function loadChatHistory(sessionKey) {
    if (!wsReady) return;
    try {
      const data = await rpc('chat.history', { sessionKey, limit: 50 });
      const messages = data?.messages || [];
      const container = document.getElementById('chat-messages');
      container.innerHTML = '';

      if (messages.length === 0) {
        appendMessage('system', '👋 No messages yet. Start the conversation!');
        return;
      }

      messages.forEach(msg => {
        if (msg.role !== 'system') {
          appendMessage(msg.role, msg.content);
        }
      });
      scrollToBottom();
    } catch (e) {
      console.warn('loadChatHistory error:', e);
    }
  }

  function selectSession(sessionKey) {
    currentSession = sessionKey;
    document.querySelectorAll('.chat-session-item').forEach(el => {
      el.classList.toggle('active', el.dataset.session === sessionKey);
    });
    const parts = sessionKey.split(':');
    document.getElementById('chat-title').textContent = formatSessionTitle(sessionKey);
    document.getElementById('chat-meta').textContent = sessionKey;
    loadChatHistory(sessionKey);
  }

  function formatSessionTitle(key) {
    const icons = { webchat: '🌐', telegram: '✈️', discord: '💜', slack: '🟩', cron: '⏰', webhook: '🔗', api: '🔌' };
    const parts = key.split(':');
    const icon = icons[parts[0]] || '💬';
    return `${icon} ${parts.slice(1).join(':') || parts[0]}`;
  }

  function newChatSession() {
    const name = prompt('Session name (e.g. project-X):');
    if (!name) return;
    const key = `webchat:${name.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
    addChatSessionToSidebar(key);
    selectSession(key);
  }

  function addChatSessionToSidebar(key) {
    const list = document.getElementById('chat-session-list');
    const existing = list.querySelector(`[data-session="${key}"]`);
    if (existing) return;
    const item = document.createElement('div');
    item.className = 'chat-session-item';
    item.dataset.session = key;
    item.onclick = (e) => {
      if (!e.target.closest('.chat-session-delete')) {
        selectSession(key);
      }
    };
    item.innerHTML = `
      <div class="chat-session-info">
        <div class="chat-session-name">${formatSessionTitle(key)}</div>
        <div class="chat-session-meta">${esc(key)}</div>
      </div>
      <button class="chat-session-delete" onclick="App.deleteChatSession('${esc(key)}')" title="删除会话">✕</button>
    `;
    list.appendChild(item);
  }

  async function resetCurrentSession() {
    if (!confirm(`Reset session "${currentSession}"? This will clear the conversation history.`)) return;
    try {
      await rpc('sessions.reset', { sessionKey: currentSession });
      document.getElementById('chat-messages').innerHTML = '';
      appendMessage('system', '🔄 Session reset. Starting fresh!');
      showToast('Session reset', 'success');
    } catch (e) {
      showToast('Failed to reset: ' + e.message, 'error');
    }
  }

  async function deleteChatSession(sessionKey) {
    if (!confirm(`确定删除会话 "${sessionKey}"？此操作不可撤销。`)) return;
    try {
      await rpc('sessions.delete', { sessionKey });
      showToast('会话已删除', 'success');
      // 从列表中移除
      const item = document.querySelector(`.chat-session-item[data-session="${sessionKey}"]`);
      if (item) item.remove();
      // 如果删除的是当前会话，清空聊天区域并切换到默认会话
      if (currentSession === sessionKey) {
        currentSession = 'webchat:default';
        document.getElementById('chat-messages').innerHTML = '';
        appendMessage('system', '👋 会话已删除，已切换到默认会话。');
        // 确保默认会话存在
        addChatSessionToSidebar('webchat:default');
        selectSession('webchat:default');
      }
    } catch (e) {
      showToast(e.message, 'error');
    }
  }

  // ── Message DOM helpers ────────────────────────────────────────────────────
  function appendMessage(role, content) {
    const container = document.getElementById('chat-messages');
    const el = document.createElement('div');
    el.className = `chat-message ${role}`;

    if (role === 'system') {
      el.innerHTML = `<div class="msg-bubble system">${renderMarkdown(content)}</div>`;
    } else {
      const avatarContent = role === 'user' ? 'You' : '🦞';
      el.innerHTML = `
        <div class="msg-avatar ${role}">${avatarContent}</div>
        <div class="msg-content">
          <div class="msg-bubble ${role}">${renderMarkdown(content)}</div>
          <div class="msg-meta">${timeAgo(Date.now())}</div>
        </div>
      `;
    }
    container.appendChild(el);
    scrollToBottom();
    return el;
  }

  function appendTyping() {
    const container = document.getElementById('chat-messages');
    const el = document.createElement('div');
    el.className = 'chat-message assistant typing-wrapper';
    el.innerHTML = `
      <div class="msg-avatar assistant">🦞</div>
      <div class="msg-content">
        <div class="typing-indicator">
          <div class="typing-dot"></div>
          <div class="typing-dot"></div>
          <div class="typing-dot"></div>
        </div>
      </div>
    `;
    container.appendChild(el);
    scrollToBottom();
    return el;
  }

  function removeTyping(el) { if (el?.parentNode) el.remove(); }
  function removeAllTyping() {
    document.querySelectorAll('.typing-wrapper').forEach(el => el.remove());
  }

  function createStreamingBubble() {
    const container = document.getElementById('chat-messages');
    const el = document.createElement('div');
    el.className = 'chat-message assistant';
    el.innerHTML = `
      <div class="msg-avatar assistant">🦞</div>
      <div class="msg-content">
        <div class="msg-bubble assistant streaming"></div>
        <div class="msg-meta"></div>
      </div>
    `;
    container.appendChild(el);
    return el;
  }

  function renderStreamingBubble(el, content) {
    const bubble = el.querySelector('.msg-bubble');
    if (bubble) bubble.innerHTML = renderMarkdown(content) + '<span class="cursor">▋</span>';
    scrollToBottom();
  }

  function finalizeStreamingBubble(el, content, usage) {
    const bubble = el.querySelector('.msg-bubble');
    const meta = el.querySelector('.msg-meta');
    if (bubble) {
      bubble.classList.remove('streaming');
      bubble.innerHTML = renderMarkdown(content);
    }
    if (meta && usage) {
      meta.textContent = `${usage.inputTokens + usage.outputTokens} tokens · ${timeAgo(Date.now())}`;
    }
    scrollToBottom();
  }

  function scrollToBottom() {
    const el = document.getElementById('chat-messages');
    if (el) el.scrollTop = el.scrollHeight;
  }

  function setSendBtn(enabled) {
    const btn = document.getElementById('chat-send-btn');
    if (btn) btn.disabled = !enabled;
  }

  // ─── Markdown renderer (lightweight) ──────────────────────────────────────
  function renderMarkdown(text) {
    if (!text) return '';
    let html = escHtml(text);

    // Code blocks
    html = html.replace(/```(\w+)?\n([\s\S]*?)```/g, (_, lang, code) =>
      `<pre><code class="lang-${lang || 'text'}">${code.trimEnd()}</code></pre>`
    );
    // Inline code
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
    // Bold
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    // Italic
    html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');
    // Links
    html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener" style="color:var(--accent)">$1</a>');
    // Newlines
    html = html.replace(/\n/g, '<br>');

    return html;
  }

  // ─── Overview ──────────────────────────────────────────────────────────────
  async function refreshOverview() {
    try {
      const [usage, channels, agentList] = await Promise.all([
        rpc('usage.status'),
        rpc('channels.status'),
        rpc('agents.list')
      ]);

      document.getElementById('stat-requests').textContent = fmt(usage?.totalRequests);
      document.getElementById('stat-input-tokens').textContent = fmt(usage?.inputTokens);
      document.getElementById('stat-output-tokens').textContent = fmt(usage?.outputTokens);
      document.getElementById('stat-sessions').textContent = usage?.activeRuns ?? 0;

      // Channels
      const channelsEl = document.getElementById('overview-channels');
      channelsEl.innerHTML = (channels || []).map(ch => `
        <div style="display:flex;align-items:center;justify-content:space-between;padding:8px 0;border-bottom:1px solid var(--border)">
          <span style="font-size:13px">${esc(ch.name)}</span>
          <span class="badge-status badge-${ch.status}">${esc(ch.status)}</span>
        </div>
      `).join('') || '<p style="color:var(--text3);font-size:13px">No channels configured</p>';

      // Agents
      const agentsEl = document.getElementById('overview-agents');
      agentsEl.innerHTML = (agentList || []).map(a => `
        <div style="display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid var(--border)">
          <span style="font-size:13px;flex:1;font-weight:500">${esc(a.name || a.id)}</span>
          <span style="font-size:11px;color:var(--text3)">${esc(a.model || '')}</span>
        </div>
      `).join('') || '<p style="color:var(--text3);font-size:13px">No agents configured</p>';

    } catch (e) {
      console.warn('refreshOverview error:', e);
    }
  }

  // ─── Channels ──────────────────────────────────────────────────────────────
  async function loadChannels() {
    try {
      const channels = await rpc('channels.status') || [];
      const grid = document.getElementById('channel-grid');
      const channelDefs = {
        webchat:  { icon: '🌐', desc: 'Built-in web chat interface' },
        telegram: { icon: '✈️', desc: 'Telegram Bot API' },
        discord:  { icon: '💜', desc: 'Discord Bot' },
        slack:    { icon: '🟩', desc: 'Slack App' },
        signal:   { icon: '🔒', desc: 'Signal Messenger' },
        whatsapp: { icon: '💚', desc: 'WhatsApp Business API' },
      };

      grid.innerHTML = channels.map(ch => {
        const def = channelDefs[ch.id] || { icon: '📡', desc: '' };
        return `
          <div class="channel-card">
            <div class="channel-card-header">
              <div class="channel-icon">${def.icon}</div>
              <div>
                <div class="channel-name">${esc(ch.name)}</div>
                <div class="channel-id">${esc(ch.id)}</div>
              </div>
              <div style="margin-left:auto">
                <span class="badge-status badge-${ch.status}">${esc(ch.status)}</span>
              </div>
            </div>
            <p style="font-size:12px;color:var(--text3)">${def.desc}</p>
            <div class="channel-actions">
              ${ch.status === 'online'
                ? `<button class="btn btn-sm btn-danger" onclick="App.channelAction('${ch.id}','logout')">Logout</button>`
                : `<button class="btn btn-sm btn-ghost" onclick="App.channelAction('${ch.id}','info')">Configure</button>`
              }
            </div>
          </div>
        `;
      }).join('');
    } catch (e) {
      showToast('Failed to load channels: ' + e.message, 'error');
    }
  }

  function channelAction(channelId, action) {
    if (action === 'logout') {
      if (!confirm(`Log out from ${channelId}?`)) return;
      rpc('channels.logout', { channelId })
        .then(() => { showToast(`${channelId} logged out`, 'success'); loadChannels(); })
        .catch(e => showToast(e.message, 'error'));
    } else {
      showToast(`Configure ${channelId} in the Config editor`, 'info');
    }
  }

  // ─── Sessions ──────────────────────────────────────────────────────────────
  async function loadSessions() {
    try {
      const sessions = await rpc('sessions.list') || [];
      const tbody = document.getElementById('sessions-tbody');

      // Also sync chat sidebar
      const chatList = document.getElementById('chat-session-list');
      chatList.innerHTML = '';

      if (sessions.length === 0) {
        tbody.innerHTML = '<tr><td colspan="5" style="text-align:center;padding:40px;color:var(--text3)">No sessions found</td></tr>';
        // Add default
        addChatSessionToSidebar('webchat:default');
        return;
      }

      tbody.innerHTML = sessions.map(s => `
        <tr>
          <td><span class="session-key">${esc(s.sessionKey)}</span></td>
          <td>${esc(s.channel || '—')}</td>
          <td>${s.messageCount ?? 0}</td>
          <td>${s.lastMessageAt ? timeAgo(s.lastMessageAt) : '—'}</td>
          <td>
            <button class="btn btn-sm btn-ghost" onclick="App.openSessionChat('${esc(s.sessionKey)}')">Open</button>
            <button class="btn btn-sm btn-ghost" onclick="App.doResetSession('${esc(s.sessionKey)}')">Reset</button>
            <button class="btn btn-sm btn-danger" onclick="App.doDeleteSession('${esc(s.sessionKey)}')">Delete</button>
          </td>
        </tr>
      `).join('');

      sessions.forEach(s => addChatSessionToSidebar(s.sessionKey));
    } catch (e) {
      console.warn('loadSessions error:', e);
    }
  }

  function openSessionChat(sessionKey) {
    switchView('chat');
    selectSession(sessionKey);
  }

  async function doResetSession(sessionKey) {
    if (!confirm(`Reset session "${sessionKey}"?`)) return;
    try {
      await rpc('sessions.reset', { sessionKey });
      showToast('Session reset', 'success');
      loadSessions();
    } catch (e) { showToast(e.message, 'error'); }
  }

  async function doDeleteSession(sessionKey) {
    if (!confirm(`Delete session "${sessionKey}"? This cannot be undone.`)) return;
    try {
      await rpc('sessions.delete', { sessionKey });
      showToast('Session deleted', 'success');
      loadSessions();
    } catch (e) { showToast(e.message, 'error'); }
  }

  // ─── Cron ──────────────────────────────────────────────────────────────────
  async function loadCron() {
    try {
      const jobs = await rpc('cron.list') || [];
      const list = document.getElementById('cron-list');

      if (jobs.length === 0) {
        list.innerHTML = `
          <div class="empty-state">
            <span class="icon">⏰</span>
            <h3>No cron jobs yet</h3>
            <p>Add a scheduled job to run your AI agent automatically.</p>
          </div>`;
        return;
      }

      list.innerHTML = jobs.map(job => `
        <div class="cron-item">
          <div style="font-size:24px">${job.enabled ? '✅' : '⏸️'}</div>
          <div class="cron-item-info">
            <div class="cron-item-name">${esc(job.name || job.id)}</div>
            <div class="cron-item-meta">
              <span class="cron-schedule">${esc(job.schedule)}</span>
              &nbsp;·&nbsp; Agent: ${esc(job.agentId || 'default')}
              &nbsp;·&nbsp; ${esc(job.prompt?.slice(0, 60) || '')}${job.prompt?.length > 60 ? '...' : ''}
            </div>
          </div>
          <div class="cron-item-actions">
            <button class="btn btn-sm btn-ghost" onclick="App.runCronNow('${esc(job.id)}')">▶ Run Now</button>
            <button class="btn btn-sm btn-danger" onclick="App.removeCronJob('${esc(job.id)}')">🗑</button>
          </div>
        </div>
      `).join('');
    } catch (e) {
      console.warn('loadCron error:', e);
    }
  }

  function showAddCronModal() {
    document.getElementById('cron-modal').classList.remove('hidden');
  }

  function closeCronModal() {
    document.getElementById('cron-modal').classList.add('hidden');
    document.getElementById('cron-name').value = '';
    document.getElementById('cron-schedule').value = '';
    document.getElementById('cron-prompt').value = '';
  }

  async function addCronJob() {
    const name = document.getElementById('cron-name').value.trim();
    const schedule = document.getElementById('cron-schedule').value.trim();
    const prompt = document.getElementById('cron-prompt').value.trim();
    const agentId = document.getElementById('cron-agent').value || 'default';

    if (!name || !schedule || !prompt) {
      showToast('Please fill all fields', 'error');
      return;
    }

    try {
      await rpc('cron.add', { name, schedule, prompt, agentId, enabled: true });
      showToast('Cron job added', 'success');
      closeCronModal();
      loadCron();
    } catch (e) { showToast('Failed: ' + e.message, 'error'); }
  }

  async function removeCronJob(id) {
    if (!confirm('Remove this cron job?')) return;
    try {
      await rpc('cron.remove', { id });
      showToast('Cron job removed', 'success');
      loadCron();
    } catch (e) { showToast(e.message, 'error'); }
  }

  async function runCronNow(id) {
    try {
      await rpc('cron.run', { id });
      showToast('Job triggered!', 'info');
    } catch (e) { showToast(e.message, 'error'); }
  }

  function onCronEvent(payload) {
    if (!payload) return;
    const status = payload.status === 'completed' ? '✅' : '❌';
    showToast(`${status} Cron [${payload.jobName || payload.jobId}]: ${payload.status}`, payload.status === 'completed' ? 'success' : 'error');

    // 将定时任务结果添加到聊天界面
    if (payload.status === 'completed' && payload.output) {
      const jobName = payload.jobName || payload.jobId;
      appendMessage('assistant', `⏰ **定时任务: ${jobName}**\n\n${payload.output}`);
    } else if (payload.status === 'failed' && payload.error) {
      const jobName = payload.jobName || payload.jobId;
      appendMessage('system', `❌ 定时任务 "${jobName}" 执行失败: ${payload.error}`);
    }
  }

  // ─── Models ────────────────────────────────────────────────────────────────
  async function loadModels() {
    try {
      const models = await rpc('models.list') || [];
      const list = document.getElementById('models-list');

      const providerIcons = { openai: '🟢', anthropic: '🟠', ollama: '🦙', default: '🤖' };

      if (models.length === 0) {
        list.innerHTML = `
          <div class="empty-state">
            <span class="icon">🤖</span>
            <h3>No models configured</h3>
            <p>Add model providers in the Config editor.</p>
          </div>`;
        return;
      }

      list.innerHTML = models.map(m => {
        const icon = providerIcons[m.provider] || providerIcons.default;
        return `
          <div class="model-item">
            <div class="model-icon">${icon}</div>
            <div class="model-info">
              <div class="model-name">${esc(m.name || m.id)}</div>
              <div class="model-meta">
                Provider: ${esc(m.provider)} &nbsp;·&nbsp; ID: <code>${esc(m.modelId)}</code>
              </div>
            </div>
            <span class="model-badge">${esc(m.provider)}</span>
            ${m.available ? '<span style="color:var(--green);font-size:12px">✓ Available</span>' : '<span style="color:var(--text3);font-size:12px">— Not configured</span>'}
          </div>
        `;
      }).join('');
    } catch (e) {
      console.warn('loadModels error:', e);
    }
  }

  // ─── Config ────────────────────────────────────────────────────────────────
  async function loadConfig() {
    try {
      const config = await rpc('config.get');
      document.getElementById('config-editor').value = JSON.stringify(config, null, 2);
    } catch (e) {
      showToast('Failed to load config: ' + e.message, 'error');
    }
  }

  async function saveConfig() {
    const text = document.getElementById('config-editor').value;
    try {
      JSON.parse(text); // validate
    } catch (e) {
      showToast('Invalid JSON: ' + e.message, 'error');
      return;
    }
    try {
      await rpc('config.patch', JSON.parse(text));
      showToast('Configuration saved!', 'success');
    } catch (e) {
      showToast('Save failed: ' + e.message, 'error');
    }
  }

  // ─── Logs ──────────────────────────────────────────────────────────────────
  async function refreshLogs() {
    try {
      const logs = await rpc('logs.tail', { lines: 500 }) || [];
      allLogs = logs;
      renderLogs();
    } catch (e) {
      console.warn('refreshLogs error:', e);
    }
  }

  function renderLogs() {
    const filterText = (document.getElementById('log-filter')?.value || '').toLowerCase();
    const levelFilter = document.getElementById('log-level-filter')?.value || '';
    const container = document.getElementById('log-container');

    const filtered = allLogs.filter(line => {
      if (filterText && !line.toLowerCase().includes(filterText)) return false;
      if (levelFilter && !line.includes(`[${levelFilter}]`)) return false;
      return true;
    });

    container.innerHTML = filtered.map(line => {
      let cls = 'INFO';
      if (line.includes('[WARN]')) cls = 'WARN';
      else if (line.includes('[ERROR]')) cls = 'ERROR';
      else if (line.includes('[DEBUG]')) cls = 'DEBUG';
      return `<div class="log-line ${cls}">${escHtml(line)}</div>`;
    }).join('');

    if (logFollowEnabled) container.scrollTop = container.scrollHeight;
  }

  function filterLogs() { renderLogs(); }

  function clearLogs() {
    allLogs = [];
    document.getElementById('log-container').innerHTML = '';
  }

  function toggleLogFollow() {
    logFollowEnabled = !logFollowEnabled;
    const btn = document.getElementById('log-follow-btn');
    btn.style.color = logFollowEnabled ? 'var(--accent)' : '';
    if (logFollowEnabled) {
      document.getElementById('log-container').scrollTop = document.getElementById('log-container').scrollHeight;
    }
  }

  // ─── View switching ────────────────────────────────────────────────────────
  function switchView(view) {
    currentView = view;
    document.querySelectorAll('.view').forEach(el => el.classList.remove('active'));
    document.querySelectorAll('.nav-item').forEach(el => el.classList.remove('active'));

    const viewEl = document.getElementById(`view-${view}`);
    if (viewEl) viewEl.classList.add('active');

    const navEl = document.querySelector(`.nav-item[data-view="${view}"]`);
    if (navEl) navEl.classList.add('active');

    // Load data for the view
    if (wsReady) {
      switch (view) {
        case 'overview': refreshOverview(); break;
        case 'channels': loadChannels(); break;
        case 'sessions': loadSessions(); break;
        case 'cron':     loadCron(); break;
        case 'skills':   loadSkills(); break;
        case 'models':   loadModels(); break;
        case 'config':   loadConfig(); break;
        case 'logs':     refreshLogs(); break;
      }
    }
  }

  // ─── Connection status UI ──────────────────────────────────────────────────
  function setConnStatus(state, label) {
    const dot = document.getElementById('conn-dot');
    const lbl = document.getElementById('conn-label');
    dot.className = `conn-dot ${state}`;
    if (lbl) lbl.textContent = label;
  }

  // ─── Toast notifications ───────────────────────────────────────────────────
  function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    const icons = { success: '✅', error: '❌', info: 'ℹ️' };
    toast.innerHTML = `<span>${icons[type] || ''}</span><span>${escHtml(message)}</span>`;
    container.appendChild(toast);
    setTimeout(() => toast.remove(), 3500);
  }

  // ─── Utilities ────────────────────────────────────────────────────────────
  function esc(s) {
    if (s == null) return '';
    return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  function escHtml(s) {
    if (s == null) return '';
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;');
  }

  function fmt(n) {
    if (n == null) return '—';
    if (n >= 1e9) return (n/1e9).toFixed(1) + 'B';
    if (n >= 1e6) return (n/1e6).toFixed(1) + 'M';
    if (n >= 1e3) return (n/1e3).toFixed(1) + 'K';
    return String(n);
  }

  function timeAgo(ts) {
    const diff = Date.now() - ts;
    if (diff < 60000) return 'just now';
    if (diff < 3600000) return `${Math.floor(diff/60000)}m ago`;
    if (diff < 86400000) return `${Math.floor(diff/3600000)}h ago`;
    return new Date(ts).toLocaleDateString();
  }

  // ─── Skills ────────────────────────────────────────────────────────────────
  let currentSkillTab = 'installed';
  let skillsData = null;

  async function loadSkills() {
    try {
      skillsData = await rpc('skills.list', {});
      renderInstalledSkills(skillsData.knowledgeSkills || []);
      // Preload store featured when switching to skills view
      if (currentSkillTab === 'store') loadSkillStore();
    } catch (e) {
      document.getElementById('skills-installed-list').innerHTML =
        `<div class="empty-state"><span class="icon">⚠️</span><h3>Failed to load skills</h3><p>${esc(e.message)}</p></div>`;
    }
  }

  function renderInstalledSkills(skills) {
    const el = document.getElementById('skills-installed-list');
    if (!skills || skills.length === 0) {
      el.innerHTML = `
        <div class="empty-state">
          <span class="icon">🧩</span>
          <h3>No skills installed yet</h3>
          <p>Browse the <strong>Store</strong> tab or use <strong>Install from URL</strong> to add skills.</p>
        </div>`;
      return;
    }
    el.innerHTML = skills.map(s => `
      <div class="cron-item" style="align-items:flex-start">
        <div style="font-size:28px;line-height:1;margin-top:2px">${esc(s.emoji || '🧩')}</div>
        <div class="cron-item-info" style="flex:1;min-width:0">
          <div class="cron-item-name">${esc(s.name)}</div>
          <div class="cron-item-meta" style="margin-top:3px">
            ${s.description ? `<span style="color:var(--text2)">${esc(s.description)}</span><br>` : ''}
            <span style="color:var(--text3);font-size:11px">
              source: ${esc(s.source)} &nbsp;·&nbsp; v${esc(s.version || '?')}
              ${s.author ? ` &nbsp;·&nbsp; ${esc(s.author)}` : ''}
              &nbsp;·&nbsp; ${s.enabled ? '<span style="color:var(--green)">enabled</span>' : '<span style="color:var(--text3)">disabled</span>'}
            </span>
          </div>
        </div>
        <div class="cron-item-actions">
          ${s.source === 'managed' ? `<button class="btn btn-sm btn-danger" onclick="App.uninstallSkill('${esc(s.key)}')">🗑 Remove</button>` : ''}
        </div>
      </div>
    `).join('');
  }

  function switchSkillTab(tab) {
    currentSkillTab = tab;
    ['installed', 'store', 'install-url'].forEach(t => {
      const el = document.getElementById(`skill-tab-${t}`);
      if (el) el.style.display = t === tab ? '' : 'none';
      const btn = document.querySelector(`.skill-tab[data-tab="${t}"]`);
      if (btn) btn.classList.toggle('active', t === tab);
    });
    if (tab === 'store' && wsReady) loadSkillStore();
  }

  async function loadSkillStore() {
    const el = document.getElementById('skills-store-list');
    el.innerHTML = `<div class="empty-state"><span class="icon">⏳</span><h3>Loading store...</h3></div>`;
    try {
      const featured = await rpc('skills.store.featured', {});
      renderStoreSkills(featured.skills || featured || []);
    } catch (e) {
      el.innerHTML = `<div class="empty-state"><span class="icon">⚠️</span><h3>Store unavailable</h3><p>${esc(e.message)}</p></div>`;
    }
  }

  async function searchSkills() {
    const q = document.getElementById('skill-search-input').value.trim();
    const el = document.getElementById('skills-store-list');
    el.innerHTML = `<div class="empty-state"><span class="icon">⏳</span><h3>Searching...</h3></div>`;
    try {
      const res = await rpc('skills.store.search', { q, page: 1, pageSize: 20 });
      renderStoreSkills(res.skills || res.items || []);
    } catch (e) {
      el.innerHTML = `<div class="empty-state"><span class="icon">⚠️</span><h3>Search failed</h3><p>${esc(e.message)}</p></div>`;
    }
  }

  function renderStoreSkills(skills) {
    const el = document.getElementById('skills-store-list');
    if (!skills || skills.length === 0) {
      el.innerHTML = `<div class="empty-state"><span class="icon">🔍</span><h3>No skills found</h3></div>`;
      return;
    }
    el.innerHTML = skills.map(s => `
      <div class="cron-item" style="align-items:flex-start">
        <div style="font-size:28px;line-height:1;margin-top:2px">${esc(s.emoji || '🧩')}</div>
        <div class="cron-item-info" style="flex:1;min-width:0">
          <div class="cron-item-name">${esc(s.name)}</div>
          <div class="cron-item-meta" style="margin-top:3px">
            ${s.description ? `<span style="color:var(--text2)">${esc(s.description)}</span><br>` : ''}
            <span style="color:var(--text3);font-size:11px">
              ${s.author ? `by ${esc(s.author)}` : ''}
              ${s.version ? ` &nbsp;·&nbsp; v${esc(s.version)}` : ''}
              ${s.tags && s.tags.length ? ` &nbsp;·&nbsp; ${s.tags.map(t => `<span style="background:var(--bg3);padding:1px 5px;border-radius:3px">${esc(t)}</span>`).join(' ')}` : ''}
            </span>
          </div>
        </div>
        <div class="cron-item-actions">
          <button class="btn btn-sm btn-primary" onclick="App.installStoreSkill('${esc(s.id || s.name)}')">⬇️ Install</button>
        </div>
      </div>
    `).join('');
  }

  function openSkillStore() {
    switchSkillTab('store');
  }

  async function installStoreSkill(skillId) {
    showToast(`Installing ${skillId}...`, 'info');
    try {
      await rpc('skills.install', { id: skillId });
      showToast(`Skill "${skillId}" install started`, 'success');
      setTimeout(() => loadSkills(), 2000);
    } catch (e) {
      showToast(`Install failed: ${e.message}`, 'error');
    }
  }

  async function uninstallSkill(key) {
    if (!confirm(`Remove skill "${key}"?`)) return;
    try {
      await rpc('skills.uninstall', { key });
      showToast(`Skill removed`, 'success');
      loadSkills();
    } catch (e) {
      showToast(`Remove failed: ${e.message}`, 'error');
    }
  }

  async function installSkillFromUrl() {
    const url = document.getElementById('skill-url-input').value.trim();
    const nameOverride = document.getElementById('skill-name-input').value.trim();
    const resultEl = document.getElementById('skill-url-result');

    if (!url) { showToast('Please enter a URL', 'error'); return; }

    resultEl.innerHTML = `<span style="color:var(--text3)">⏳ Installing from ${escHtml(url)}...</span>`;

    try {
      // Let the backend (Java) download the SKILL.md to avoid CORS issues
      const params = { url };
      if (nameOverride) params.name = nameOverride;

      const res = await rpc('skills.install', params);
      // Async install: backend will push skills.installed / skills.install.error event
      resultEl.innerHTML = `<span style="color:var(--text3)">⏳ Download started, waiting for completion...</span>`;
      showToast('Install started, please wait...', 'info');
      // Poll / wait for the install event (simple: just reload after 3s)
      setTimeout(() => {
        loadSkills();
        switchSkillTab('installed');
        resultEl.innerHTML = `<span style="color:var(--green)">✅ Install request sent! Check the Installed tab.</span>`;
      }, 3000);

    } catch (e) {
      resultEl.innerHTML = `<span style="color:var(--red)">❌ ${escHtml(e.message)}</span>`;
      showToast(`Install failed: ${e.message}`, 'error');
    }
  }

  // ─── Event listeners ───────────────────────────────────────────────────────
  document.addEventListener('DOMContentLoaded', () => {
    // Textarea auto-resize + Enter to send
    const chatInput = document.getElementById('chat-input');
    if (chatInput) {
      chatInput.addEventListener('input', function () {
        this.style.height = 'auto';
        this.style.height = Math.min(this.scrollHeight, 160) + 'px';
      });
      chatInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
          e.preventDefault();
          sendMessage();
        }
      });
    }

    connect();
  });

  // ─── Public API ────────────────────────────────────────────────────────────
  return {
    doAuth: (silent) => doAuth(silent),
    switchView,
    sendMessage,
    selectSession,
    newChatSession,
    resetCurrentSession,
    deleteChatSession,
    refreshOverview,
    loadChannels,
    channelAction,
    loadSessions,
    openSessionChat,
    doResetSession,
    doDeleteSession,
    showAddCronModal,
    closeCronModal,
    addCronJob,
    removeCronJob,
    runCronNow,
    saveConfig,
    loadConfig,
    refreshLogs,
    filterLogs,
    clearLogs,
    toggleLogFollow,
    loadSkills,
    switchSkillTab,
    loadSkillStore,
    searchSkills,
    openSkillStore,
    installStoreSkill,
    uninstallSkill,
    installSkillFromUrl,
  };
})();
