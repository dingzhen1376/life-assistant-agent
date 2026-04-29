const STORAGE_KEY = "life-manus-frontend-state";
const DEFAULT_API_BASE = "http://localhost:8124/api";

const state = loadState();
let activeEventSource = null;

const els = {
  body: document.body,
  sidebar: document.getElementById("sidebar"),
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
};

init();

function init() {
  els.body.classList.toggle("dark", state.theme === "dark");
  if (!state.threads.length) {
    createThread();
  }
  renderThreads();
  renderMessages();
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
  if (thread.title === "新对话") {
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

  const url = `${state.apiBase || DEFAULT_API_BASE}/ai/life/chat/sse?message=${encodeURIComponent(text)}`;
  activeEventSource = new EventSource(url);
  els.connectionState.textContent = "生成中";

  activeEventSource.onmessage = (event) => {
    assistantMessage.content += formatSseChunk(event.data);
    renderMessages();
    persist();
  };

  activeEventSource.onerror = () => {
    if (!assistantMessage.content.trim()) {
      assistantMessage.content = "连接失败。请确认后端已启动、API 地址正确，并检查浏览器跨域限制。";
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
    const button = document.createElement("button");
    button.type = "button";
    button.className = `thread-item${thread.id === state.activeThreadId ? " active" : ""}`;
    button.textContent = thread.title || "新对话";
    button.title = thread.title || "新对话";
    button.addEventListener("click", () => {
      state.activeThreadId = thread.id;
      persist();
      renderThreads();
      renderMessages();
      closeMobileSidebar();
    });
    els.threadList.appendChild(button);
  });
}

function renderMessages() {
  const thread = currentThread();
  const hasMessages = thread.messages.length > 0;
  els.emptyState.hidden = hasMessages;
  [...els.messageRegion.querySelectorAll(".message")].forEach((node) => node.remove());

  thread.messages.forEach((message) => {
    const row = document.createElement("article");
    row.className = `message ${message.role}`;

    const avatar = document.createElement("div");
    avatar.className = "avatar";
    avatar.textContent = message.role === "user" ? "你" : "L";

    const bubble = document.createElement("div");
    bubble.className = "bubble";
    bubble.innerHTML = renderMarkdownLite(message.content);

    row.append(avatar, bubble);
    els.messageRegion.appendChild(row);
  });

  els.messageRegion.scrollTop = els.messageRegion.scrollHeight;
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
  return chunk.endsWith("\n") ? chunk : `${chunk}\n`;
}

function createThread() {
  const thread = {
    id: crypto.randomUUID ? crypto.randomUUID() : String(Date.now()),
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
  return thread;
}

function loadState() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
    return {
      apiBase: normalizeApiBase(parsed.apiBase || DEFAULT_API_BASE),
      theme: parsed.theme || "light",
      activeThreadId: parsed.activeThreadId || null,
      threads: Array.isArray(parsed.threads) ? parsed.threads : [],
    };
  } catch {
    return {
      apiBase: DEFAULT_API_BASE,
      theme: "light",
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
    els.connectionState.textContent = response.ok ? "就绪" : "接口异常";
  } catch {
    els.connectionState.textContent = "未连接";
  }
}
