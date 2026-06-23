const STORAGE_KEY = "life-manus-frontend-state";
const DEFAULT_API_BASE = "/api";
const TOOL_PERMISSION_MODES = [
  { value: "DEFAULT", label: "请求批准", detail: "每次工具调用前询问" },
  { value: "ACCEPT_EDITS", label: "接受编辑", detail: "文件编辑自动允许" },
  { value: "PLAN", label: "计划模式", detail: "只读工具可执行" },
  { value: "BYPASS", label: "自动允许", detail: "大部分工具自动执行" },
  { value: "YOLO", label: "YOLO", detail: "最高风险自动模式" },
];
const TOOL_PERMISSION_MODE_VALUES = new Set(TOOL_PERMISSION_MODES.map((mode) => mode.value));

const state = loadState();
let activeEventSource = null;
let streamCompleted = false;
const resolvedPermissionRequestIds = new Set();
/** 权限轮询定时器，流式生成期间每秒查询一次是否有待确认的工具权限 */
let permissionPollTimer = null;

const els = {
  body: document.body,
  threadList: document.getElementById("threadList"),
  messageRegion: document.getElementById("messageRegion"),
  emptyState: document.getElementById("emptyState"),
  chatForm: document.getElementById("chatForm"),
  messageInput: document.getElementById("messageInput"),
  sendBtn: document.getElementById("sendBtn"),
  stopBtn: document.getElementById("stopBtn"),
  newChatBtn: document.getElementById("newChatBtn"),
  clearBtn: document.getElementById("clearBtn"),
  menuBtn: document.getElementById("menuBtn"),
  themeBtn: document.getElementById("themeBtn"),
  settingsBtn: document.getElementById("settingsBtn"),
  settingsDialog: document.getElementById("settingsDialog"),
  apiBaseInput: document.getElementById("apiBaseInput"),
  saveSettingsBtn: document.getElementById("saveSettingsBtn"),
  connectionState: document.getElementById("connectionState"),
  toolModeBtn: document.getElementById("toolModeBtn"),
  toolModeLabel: document.getElementById("toolModeLabel"),
  toolModeMenu: document.getElementById("toolModeMenu"),
};

init();

function init() {
  migrateThreads();
  els.body.classList.toggle("dark", state.theme === "dark");
  if (!state.threads.length) {
    createThread();
  }
  renderThreads();
  renderMessages();
  renderToolModeMenu();
  updateToolModeUi();
  bindEvents();
  pingHealth();
}

function bindEvents() {
  els.chatForm.addEventListener("submit", (event) => {
    event.preventDefault();
    const text = els.messageInput.value.trim();
    if (!text || activeEventSource) {
      return;
    }
    sendMessage(text);
  });

  els.messageInput.addEventListener("input", () => {
    autoResizeInput();
    els.sendBtn.disabled = !els.messageInput.value.trim() || Boolean(activeEventSource);
  });

  els.messageInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      els.chatForm.requestSubmit();
    }
  });

  els.stopBtn.addEventListener("click", stopStreaming);

  els.newChatBtn.addEventListener("click", () => {
    stopStreaming();
    createThread();
    renderThreads();
    renderMessages();
    closeMobileSidebar();
  });

  els.clearBtn.addEventListener("click", () => {
    if (!confirm("确定清空本地对话记录吗？")) {
      return;
    }
    stopStreaming();
    state.threads = [];
    createThread();
    persist();
    renderThreads();
    renderMessages();
  });

  els.menuBtn.addEventListener("click", () => {
    els.body.classList.toggle("sidebar-open");
  });

  els.themeBtn.addEventListener("click", () => {
    state.theme = state.theme === "dark" ? "light" : "dark";
    els.body.classList.toggle("dark", state.theme === "dark");
    persist();
  });

  els.settingsBtn.addEventListener("click", () => {
    els.apiBaseInput.value = state.apiBase || DEFAULT_API_BASE;
    els.settingsDialog.showModal();
  });

  els.saveSettingsBtn.addEventListener("click", () => {
    state.apiBase = normalizeApiBase(els.apiBaseInput.value);
    persist();
    pingHealth();
  });

  bindToolModeEvents();

  document.querySelectorAll(".prompt-chip").forEach((button) => {
    button.addEventListener("click", () => {
      els.messageInput.value = button.textContent.trim();
      autoResizeInput();
      els.chatForm.requestSubmit();
    });
  });
}

function sendMessage(text) {
  const thread = currentThread();
  thread.messages.push({ role: "user", content: text, createdAt: Date.now() });
  if (!thread.title || thread.title === "新对话") {
    thread.title = text.slice(0, 22);
  }

  els.messageInput.value = "";
  autoResizeInput();
  renderThreads();
  renderMessages();
  setBusy(true);

  const assistantMessage = { role: "assistant", content: "", createdAt: Date.now() };
  thread.messages.push(assistantMessage);
  renderMessages();

  const params = new URLSearchParams({
    message: text,
    chatId: thread.chatId,
  });
  const url = `${state.apiBase || DEFAULT_API_BASE}/ai/life/chat/sse?${params.toString()}`;

  streamCompleted = false;
  activeEventSource = new EventSource(url);
  els.connectionState.textContent = "生成中";

  activeEventSource.addEventListener("permission", (event) => {
    handlePermissionEvent(event.data);
    els.connectionState.textContent = "等待工具确认";
  });

  // SSE permission 事件是主路径；轮询保留为代理/浏览器未及时分发自定义事件时的兜底。
  startPermissionPolling(thread.chatId);

  activeEventSource.onmessage = (event) => {
    assistantMessage.content += formatSseChunk(event.data);
    renderMessages();
    persist();
  };

  activeEventSource.addEventListener("done", () => {
    streamCompleted = true;
    stopStreaming();
    renderMessages();
    persist();
  });

  activeEventSource.onerror = () => {
    if (streamCompleted) {
      stopStreaming();
      return;
    }
    if (!assistantMessage.content.trim()) {
      assistantMessage.content = "连接失败。请确认后端、Redis 和 Nginx 代理已经启动，并检查 API 地址配置。";
    } else {
      assistantMessage.content += "\n\n[连接已结束]";
    }
    stopStreaming();
    renderMessages();
    persist();
  };
}

function stopStreaming() {
  if (activeEventSource) {
    activeEventSource.close();
    activeEventSource = null;
  }
  stopPermissionPolling();
  setBusy(false);
  els.connectionState.textContent = "就绪";
  persist();
}

function setBusy(isBusy) {
  els.sendBtn.disabled = isBusy || !els.messageInput.value.trim();
  els.stopBtn.disabled = !isBusy;
  els.messageInput.disabled = isBusy;
}

function renderThreads() {
  els.threadList.innerHTML = "";
  state.threads.forEach((thread) => {
    const row = document.createElement("div");
    row.className = `thread-row${thread.id === state.activeThreadId ? " active" : ""}`;

    const button = document.createElement("button");
    button.type = "button";
    button.className = `thread-item${thread.id === state.activeThreadId ? " active" : ""}`;
    button.textContent = thread.title || "新对话";
    button.title = `chatId: ${thread.chatId}`;
    button.addEventListener("click", () => {
      stopStreaming();
      state.activeThreadId = thread.id;
      persist();
      renderThreads();
      renderMessages();
      closeMobileSidebar();
    });

    const deleteBtn = document.createElement("button");
    deleteBtn.type = "button";
    deleteBtn.className = "thread-delete-btn";
    deleteBtn.textContent = "×";
    deleteBtn.title = "删除对话";
    deleteBtn.setAttribute("aria-label", `删除对话：${thread.title || "新对话"}`);
    deleteBtn.addEventListener("click", (event) => {
      event.stopPropagation();
      deleteThread(thread.id);
    });

    row.append(button, deleteBtn);
    els.threadList.appendChild(row);
  });
}

async function deleteThread(threadId) {
  const thread = state.threads.find((item) => item.id === threadId);
  if (!thread) {
    return;
  }
  const title = thread.title || "新对话";
  if (!confirm(`删除对话「${title}」？后端 Redis 和 PGVector 中的对应记忆也会删除。`)) {
    return;
  }

  const wasActive = state.activeThreadId === thread.id;
  if (wasActive) {
    stopStreaming();
  }

  try {
    await deleteRemoteConversation(thread.chatId);
    state.threads = state.threads.filter((item) => item.id !== thread.id);
    if (!state.threads.length) {
      createThread();
    } else if (wasActive) {
      state.activeThreadId = state.threads[0].id;
    }
    persist();
    renderThreads();
    renderMessages();
    closeMobileSidebar();
  } catch (error) {
    els.connectionState.textContent = "删除失败";
    alert(`删除失败：${error.message || "请检查后端服务"}`);
  }
}

async function deleteRemoteConversation(chatId) {
  if (!chatId) {
    return;
  }
  const url = `${state.apiBase || DEFAULT_API_BASE}/ai/life/conversations/${encodeURIComponent(chatId)}`;
  const response = await fetch(url, {
    method: "DELETE",
    cache: "no-store",
  });
  if (!response.ok) {
    const detail = await response.text();
    throw new Error(detail || `HTTP ${response.status}`);
  }
}

function renderMessages() {
  const thread = currentThread();
  const hasMessages = thread.messages.length > 0;
  els.emptyState.hidden = hasMessages;
  [...els.messageRegion.querySelectorAll(".message")].forEach((node) => node.remove());

  thread.messages.forEach((message, idx) => {
    if (message.role === "permission") {
      renderPermissionCard(message, idx);
      return;
    }

    const row = document.createElement("article");
    row.className = `message ${message.role}`;

    const avatar = document.createElement("div");
    avatar.className = "avatar";
    avatar.textContent = message.role === "user" ? "你" : "L";

    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.innerHTML = renderMarkdownLite(message.content || "");

    row.append(avatar, bubble);
    els.messageRegion.appendChild(row);
  });

  scrollMessagesToBottom();
}

function renderPermissionCard(msg, idx) {
  const row = document.createElement("article");
  row.className = "message permission";

  const avatar = document.createElement("div");
  avatar.className = "avatar";
  avatar.textContent = "L";

  const card = document.createElement("div");
  card.className = "permission-card";

  const isResolved = msg.status === "allowed" || msg.status === "denied";

  let statusBadge = "";
  if (msg.status === "allowed") {
    statusBadge = "<span class=\"perm-badge allowed\">已允许</span>";
  } else if (msg.status === "denied") {
    statusBadge = "<span class=\"perm-badge denied\">已拒绝</span>";
  }

  card.innerHTML = `<div class="perm-card-head">
      <span class="perm-card-icon">&#9888;</span>
      <strong>工具执行确认</strong>
      ${statusBadge}
    </div>
    <div class="perm-card-detail">
      <div class="perm-card-row"><span>工具名</span><span>${escapeHtml(msg.toolName)}</span></div>
      <div class="perm-card-row"><span>风险等级</span><span>${riskCategoryLabel(msg.riskCategory)}</span></div>
      <div class="perm-card-row"><span>原因</span><span>${escapeHtml(msg.reason)}</span></div>
    </div>` +
    (isResolved ? "" : `<div class="perm-card-actions">
      <button class="danger-btn perm-deny-btn" data-perm-idx="${idx}">拒绝</button>
      <button class="primary-btn perm-allow-btn" data-perm-idx="${idx}">允许</button>
    </div>`);

  row.append(avatar, card);
  els.messageRegion.appendChild(row);

  if (!isResolved) {
    card.querySelector(".perm-allow-btn")?.addEventListener("click", () => resolvePermission("ALLOW", idx));
    card.querySelector(".perm-deny-btn")?.addEventListener("click", () => resolvePermission("DENY", idx));
  }
}

function renderMarkdownLite(text) {
  const escaped = escapeHtml(text || "");
  return escaped
    .replace(/`([^`]+)`/g, "<code>$1</code>")
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/\n{2,}/g, "</p><p>")
    .replace(/\n/g, "<br>")
    .replace(/^(.+)$/, "<p>$1</p>");
}

function formatSseChunk(chunk) {
  if (!chunk) {
    return "";
  }
  const normalized = chunk
    .replaceAll("\\r\\n", "\n")
    .replaceAll("\\n", "\n")
    .replaceAll("\\t", "  ");
  return normalized;
}

function scrollMessagesToBottom() {
  requestAnimationFrame(() => {
    els.messageRegion.scrollTop = els.messageRegion.scrollHeight;
  });
}

function createThread() {
  const chatId = createUuid();
  const thread = {
    id: chatId,
    chatId,
    title: "新对话",
    messages: [],
    createdAt: Date.now(),
  };
  state.threads.unshift(thread);
  state.activeThreadId = thread.id;
  persist();
  return thread;
}

function currentThread() {
  let thread = state.threads.find((item) => item.id === state.activeThreadId);
  if (!thread) {
    thread = createThread();
  }
  if (!thread.chatId) {
    thread.chatId = createUuid();
    persist();
  }
  return thread;
}

function migrateThreads() {
  let changed = false;
  state.threads.forEach((thread) => {
    if (!thread.id) {
      thread.id = createUuid();
      changed = true;
    }
    if (!thread.chatId) {
      thread.chatId = isUuid(thread.id) ? thread.id : createUuid();
      changed = true;
    }
    if (!Array.isArray(thread.messages)) {
      thread.messages = [];
      changed = true;
    }
    const visibleMessages = thread.messages.filter((message) =>
      message.role !== "permission" || message.status === "pending"
    );
    if (visibleMessages.length !== thread.messages.length) {
      thread.messages = visibleMessages;
      changed = true;
    }
    if (!thread.title) {
      thread.title = "新对话";
      changed = true;
    }
  });
  if (changed) {
    persist();
  }
}

function loadState() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
    return {
      apiBase: normalizeApiBase(parsed.apiBase || DEFAULT_API_BASE),
      theme: parsed.theme || "light",
      toolPermissionMode: normalizeToolMode(parsed.toolPermissionMode || "DEFAULT"),
      activeThreadId: parsed.activeThreadId || null,
      threads: Array.isArray(parsed.threads) ? parsed.threads : [],
    };
  } catch {
    return {
      apiBase: DEFAULT_API_BASE,
      theme: "light",
      toolPermissionMode: "DEFAULT",
      activeThreadId: null,
      threads: [],
    };
  }
}

function persist() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

function normalizeApiBase(value) {
  const trimmed = String(value || DEFAULT_API_BASE).trim();
  if (trimmed === "/") {
    return "";
  }
  return trimmed.replace(/\/+$/, "");
}

function autoResizeInput() {
  els.messageInput.style.height = "auto";
  els.messageInput.style.height = `${Math.min(els.messageInput.scrollHeight, 180)}px`;
}

function closeMobileSidebar() {
  els.body.classList.remove("sidebar-open");
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

async function pingHealth() {
  try {
    const response = await fetch(`${state.apiBase || DEFAULT_API_BASE}/ai/life/health`, {
      method: "GET",
      cache: "no-store",
    });
    if (response.ok) {
      const health = await response.json().catch(() => null);
      if (health?.toolPermissionMode) {
        state.toolPermissionMode = normalizeToolMode(health.toolPermissionMode);
        updateToolModeUi();
        persist();
      }
      els.connectionState.textContent = "就绪";
    } else {
      els.connectionState.textContent = "接口异常";
    }
  } catch {
    els.connectionState.textContent = "未连接";
  }
}

function normalizeToolMode(value) {
  const normalized = String(value || "DEFAULT")
    .trim()
    .replaceAll("-", "_")
    .toUpperCase();
  if (normalized === "ACCEPTEDITS") {
    return "ACCEPT_EDITS";
  }
  return TOOL_PERMISSION_MODE_VALUES.has(normalized) ? normalized : "DEFAULT";
}

function toolModeMeta(value) {
  const normalized = normalizeToolMode(value);
  return TOOL_PERMISSION_MODES.find((mode) => mode.value === normalized) || TOOL_PERMISSION_MODES[0];
}

function renderToolModeMenu() {
  if (!els.toolModeMenu) {
    return;
  }
  els.toolModeMenu.innerHTML = "";
  TOOL_PERMISSION_MODES.forEach((mode) => {
    const option = document.createElement("button");
    option.type = "button";
    option.className = "tool-mode-option";
    option.dataset.toolMode = mode.value;
    option.setAttribute("role", "menuitemradio");
    option.innerHTML = `<span class="tool-mode-option-main">${escapeHtml(mode.label)}</span>
      <span class="tool-mode-option-detail">${escapeHtml(mode.detail)}</span>
      <span class="tool-mode-option-check" aria-hidden="true">✓</span>`;
    els.toolModeMenu.appendChild(option);
  });
}

function updateToolModeUi() {
  if (!els.toolModeBtn || !els.toolModeLabel || !els.toolModeMenu) {
    return;
  }
  const mode = toolModeMeta(state.toolPermissionMode);
  els.toolModeLabel.textContent = mode.label;
  els.toolModeBtn.title = `工具权限模式：${mode.label}`;
  els.toolModeMenu.querySelectorAll(".tool-mode-option").forEach((option) => {
    const active = option.dataset.toolMode === mode.value;
    option.classList.toggle("active", active);
    option.setAttribute("aria-checked", String(active));
  });
}

function bindToolModeEvents() {
  if (!els.toolModeBtn || !els.toolModeMenu) {
    return;
  }
  els.toolModeBtn.addEventListener("click", (event) => {
    event.stopPropagation();
    const open = els.toolModeMenu.hidden;
    els.toolModeMenu.hidden = !open;
    els.toolModeBtn.setAttribute("aria-expanded", String(open));
  });

  els.toolModeMenu.addEventListener("click", (event) => {
    const option = event.target.closest("[data-tool-mode]");
    if (!option) {
      return;
    }
    setToolPermissionMode(option.dataset.toolMode);
  });

  document.addEventListener("click", (event) => {
    if (!event.target.closest(".tool-mode-picker")) {
      closeToolModeMenu();
    }
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeToolModeMenu();
    }
  });
}

function closeToolModeMenu() {
  if (!els.toolModeBtn || !els.toolModeMenu) {
    return;
  }
  els.toolModeMenu.hidden = true;
  els.toolModeBtn.setAttribute("aria-expanded", "false");
}

async function setToolPermissionMode(modeValue) {
  const mode = normalizeToolMode(modeValue);
  const previousMode = normalizeToolMode(state.toolPermissionMode);
  closeToolModeMenu();
  if (mode === previousMode) {
    return;
  }

  state.toolPermissionMode = mode;
  updateToolModeUi();
  persist();
  els.connectionState.textContent = "切换模式中";

  try {
    const params = new URLSearchParams({ mode });
    const response = await fetch(
      `${state.apiBase || DEFAULT_API_BASE}/ai/life/tool-permission-mode?${params.toString()}`,
      { method: "POST", cache: "no-store" }
    );
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}`);
    }
    const payload = await response.json().catch(() => null);
    state.toolPermissionMode = normalizeToolMode(payload?.toolPermissionMode || mode);
    updateToolModeUi();
    persist();
    els.connectionState.textContent = "就绪";
  } catch {
    state.toolPermissionMode = previousMode;
    updateToolModeUi();
    persist();
    els.connectionState.textContent = "模式切换失败";
  }
}

function createUuid() {
  if (crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return "10000000-1000-4000-8000-100000000000".replace(/[018]/g, (char) =>
    (Number(char) ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> Number(char) / 4).toString(16)
  );
}

function isUuid(value) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value || "");
}

// ── 工具权限确认 ──

/**
 * 在消息流中插入一张权限确认卡片，用户点击允许/拒绝后 POST 到后端。
 * 卡片直接出现在对话区，如 Claude Code 的工具确认 UI。
 */
function handlePermissionEvent(jsonData) {
  let perm;
  try {
    perm = JSON.parse(jsonData);
  } catch {
    return;
  }
  if (perm.requestId && resolvedPermissionRequestIds.has(perm.requestId)) {
    return;
  }
  const thread = currentThread();
  // 如果同一请求的卡片已在展示中，跳过（轮询去重）
  if (thread.messages.some((m) => m.role === "permission" && m.status === "pending" && m.requestId === perm.requestId)) {
    return;
  }
  // 移除旧的内联权限卡片（同一轮对话只保留最新一张待处理请求）
  thread.messages = thread.messages.filter((m) => m.role !== "permission" || m.status !== "pending");
  thread.messages.push({
    role: "permission",
    status: "pending",
    requestId: perm.requestId,
    chatId: perm.chatId,
    toolName: perm.toolName || "-",
    riskCategory: perm.riskCategory || "UNKNOWN",
    mode: perm.mode || "-",
    reason: perm.reason || "-",
    createdAt: Date.now(),
  });
  renderMessages();
  persist();
}

async function resolvePermission(action, messageIdx) {
  const thread = currentThread();
  const msg = thread.messages[messageIdx];
  if (!msg || msg.role !== "permission" || msg.status !== "pending") {
    return;
  }
  resolvedPermissionRequestIds.add(msg.requestId);
  thread.messages.splice(messageIdx, 1);
  renderMessages();
  persist();

  const params = new URLSearchParams({ chatId: msg.chatId, requestId: msg.requestId, action });
  try {
    const response = await fetch(
      `${state.apiBase || DEFAULT_API_BASE}/ai/life/tool-permission?${params.toString()}`,
      { method: "POST", cache: "no-store" }
    );
    if (!response.ok) {
      els.connectionState.textContent = "权限请求失败";
    }
  } catch {
    els.connectionState.textContent = "权限请求失败";
  }
}

function riskCategoryLabel(category) {
  const labels = {
    READ_ONLY: "只读（安全）",
    COMPUTE_ONLY: "纯计算（安全）",
    FILE_EDIT: "文件编辑",
    MEMORY_WRITE: "记忆写入",
    DELEGATION: "Agent 委派",
    CODE_EXECUTION: "代码执行（高风险）",
    TERMINATE: "终止会话",
    UNKNOWN: "未知（需确认）",
  };
  return labels[category] || category || "-";
}

// ── 权限轮询 ──

function startPermissionPolling(rootChatId) {
  stopPermissionPolling();
  let shownRequestIds = new Set();
  permissionPollTimer = setInterval(async () => {
    try {
      const url = `${state.apiBase || DEFAULT_API_BASE}/ai/life/pending-permission?chatId=${encodeURIComponent(rootChatId)}`;
      const response = await fetch(url, { cache: "no-store" });
      if (!response.ok) return;
      const text = await response.text();
      if (!text || text === "null" || text === "") return;

      let perm;
      try { perm = JSON.parse(text); } catch { return; }
      if (!perm.requestId || !perm.chatId) return;
      if (resolvedPermissionRequestIds.has(perm.requestId)) return;
      // 同一个请求不重复弹窗
      if (shownRequestIds.has(perm.requestId)) return;
      shownRequestIds.add(perm.requestId);
      handlePermissionEvent(JSON.stringify(perm));
      els.connectionState.textContent = "等待工具确认";
    } catch {
      // 静默忽略
    }
  }, 1000);
}

function stopPermissionPolling() {
  if (permissionPollTimer != null) {
    clearInterval(permissionPollTimer);
    permissionPollTimer = null;
  }
}
