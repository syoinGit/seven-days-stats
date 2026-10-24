const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/js/portal.js"), "utf8");

function page({ open = false, supported = true, present = true } = {}) {
  const clicks = [];
  const dialog = {
    open, shows: 0, closes: 0,
    classList: { add() {} },
    close() { this.open = false; this.closes++; },
    showModal() { this.open = true; this.shows++; }
  };
  if (!supported) delete dialog.showModal;
  const document = {
    querySelector: (selector) => selector === "#portal-login" && present ? dialog : null,
    querySelectorAll: (selector) => selector === "[data-open-login]"
      ? [{ addEventListener: (_event, callback) => clicks.push(callback) }] : []
  };
  vm.runInNewContext(source, { document });
  return { dialog, clicks };
}

test("the public home stays visible until the login link is activated", () => {
  const { dialog, clicks } = page();
  assert.equal(dialog.open, false);
  let prevented = false;
  clicks[0]({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.equal(dialog.open, true);
  clicks[0]({ preventDefault() {} });
  assert.equal(dialog.shows, 1);
});

test("login and failure URLs upgrade the server-rendered dialog to a modal", () => {
  const { dialog } = page({ open: true });
  assert.equal(dialog.closes, 1);
  assert.equal(dialog.shows, 1);
  assert.equal(dialog.open, true);
});

test("unsupported dialogs keep normal links and the visible fallback form", () => {
  const { dialog, clicks } = page({ open: true, supported: false });
  assert.equal(dialog.open, true);
  assert.equal(clicks.length, 0);
  assert.doesNotThrow(() => page({ present: false }));
});

function serverPage(fetch, { live = false, csrf = "csrf-test", budget = 2560,
  memory = 1024, extraSlots = [], status = "offline", confirm = () => true } = {}) {
  const handlers = {};
  const stopHandlers = {};
  const confirmations = [];
  const stopButton = { disabled: false };
  const timers = [];
  const button = { disabled: false };
  const message = { textContent: "" };
  const reason = { textContent: "" };
  const summary = { textContent: "" };
  const form = {
    dataset: { startServer: "7dtd", status },
    action: "/portal/actions/games/7dtd/start",
    querySelector: () => button,
    closest: () => ({ querySelector: (selector) => selector === "[data-memory-reason]" ? reason : message }),
    addEventListener: (name, callback) => { handlers[name] = callback; }
  };
  const stopForm = {
    dataset: { stopServer: "7dtd", status, serverName: "7 Days to Die" },
    action: "/portal/actions/games/7dtd/stop",
    querySelector: () => stopButton,
    closest: form.closest,
    addEventListener: (name, callback) => { stopHandlers[name] = callback; }
  };
  const chip = {
    dataset: { serverStatus: "7dtd" }, textContent: "停止中",
    classList: { add() {}, remove() {} }
  };
  const servers = [{ dataset: { server: "7dtd", status, memoryMib: String(memory) } },
    ...extraSlots.map((slot) => ({ dataset: { server: slot.id, status: slot.status, memoryMib: String(slot.memory) } }))];
  const chips = [chip, ...extraSlots.map((slot) => ({
    dataset: { serverStatus: slot.id }, textContent: "", classList: { add() {}, remove() {} }
  }))];
  const controls = { dataset: { liveServerStatus: String(live), memoryBudgetMib: String(budget) },
    querySelectorAll: (selector) => ({ "[data-server]": servers, "[data-start-server]": [form],
      "[data-stop-server]": [stopForm] })[selector] || [],
    querySelector: () => summary };
  const document = {
    visibilityState: "visible",
    querySelector: (selector) => {
      if (selector === ".server-controls") return controls;
      if (selector === 'meta[name="_csrf"]') return { content: csrf };
      if (selector === 'meta[name="_csrf_header"]') return { content: "X-CSRF-TOKEN" };
      return null;
    },
    querySelectorAll: (selector) => selector === "[data-server-status]" ? chips : []
  };
  vm.runInNewContext(source, { document, fetch, AbortSignal, setTimeout: (callback) => timers.push(callback),
    confirm: (text) => { confirmations.push(text); return confirm(text); } });
  return { form, button, stopForm, stopButton, message, reason, summary, chip, timers, confirmations,
    submit: () => handlers.submit({ preventDefault() {} }),
    stop: () => stopHandlers.submit({ preventDefault() {} }) };
}

test("start sends a CSRF protected request and blocks duplicate submissions", async () => {
  let finish;
  const calls = [];
  const page = serverPage((...args) => {
    calls.push(args);
    return new Promise((resolve) => { finish = resolve; });
  });
  const pending = page.submit();
  await page.submit();
  assert.equal(calls.length, 1);
  assert.equal(page.button.disabled, true);
  assert.equal(calls[0][0], "/portal/actions/games/7dtd/start");
  assert.equal(calls[0][1].method, "POST");
  assert.equal(calls[0][1].headers["X-CSRF-TOKEN"], "csrf-test");
  finish({ ok: true, json: async () => ({ accepted: true, status: "starting", message: "起動しました" }) });
  await pending;
  assert.equal(page.chip.textContent, "起動中");
  assert.equal(page.button.disabled, true);
  assert.equal(page.message.textContent, "起動しました");
});

test("failed or expired login responses leave status unknown and avoid blind retries", async () => {
  const page = serverPage(async () => ({ ok: false, status: 403 }));
  await page.submit();
  assert.equal(page.form.dataset.status, "unknown");
  assert.equal(page.button.disabled, true);
  assert.match(page.message.textContent, /確認できません/);
});

test("missing CSRF token prevents a network operation", async () => {
  let calls = 0;
  const page = serverPage(async () => { calls++; }, { csrf: "" });
  await page.submit();
  assert.equal(calls, 0);
  assert.equal(page.button.disabled, true);
});

test("live polling follows external stops and disables controls if status lookup fails", async () => {
  let ok = true;
  const page = serverPage(async () => ({ ok, json: async () => ({ "7dtd": { code: "offline" } }) }), { live: true });
  page.form.dataset.status = "online";
  page.button.disabled = true;
  await page.timers.shift()();
  assert.equal(page.button.disabled, false);
  assert.equal(page.chip.textContent, "停止中");
  ok = false;
  await page.timers.shift()();
  assert.equal(page.button.disabled, true);
  assert.equal(page.chip.textContent, "未確認");
});


test("memory budget blocks the button and network request even for an offline game", async () => {
  let calls = 0;
  const page = serverPage(async () => { calls++; }, {
    budget: 2560, memory: 2048,
    extraSlots: [{ id: "terraria", status: "online", memory: 1024 }]
  });
  assert.equal(page.button.disabled, true);
  assert.match(page.reason.textContent, /メモリ枠が不足/);
  assert.equal(page.summary.textContent, "1.0 GiB");
  await page.submit();
  assert.equal(calls, 0);
});

test("polling releases stopped reservations and accepted starts immediately reserve their full budget", async () => {
  const page = serverPage(async (_url, options) => options.method === "POST"
    ? { ok: true, json: async () => ({ accepted: true, status: "starting" }) }
    : { ok: true, json: async () => ({ "7dtd": { code: "offline" }, terraria: { code: "offline" } }) }, {
    live: true, budget: 2048, memory: 2048,
    extraSlots: [{ id: "terraria", status: "online", memory: 1024 }]
  });
  assert.equal(page.button.disabled, true);
  await page.timers.shift()();
  assert.equal(page.button.disabled, false);
  assert.equal(page.reason.textContent, "");
  await page.submit();
  assert.equal(page.summary.textContent, "2.0 GiB");
  assert.equal(page.button.disabled, true);
});

test("an unknown other game blocks a start and marks the reservation total unknown", () => {
  const page = serverPage(async () => {}, {
    extraSlots: [{ id: "terraria", status: "error", memory: 1024 }]
  });
  assert.equal(page.button.disabled, true);
  assert.equal(page.summary.textContent, "未確認");
  assert.match(page.reason.textContent, /状態を確認できない/);
});

test("stop is enabled only for running games, asks for confirmation, and reports stopping", async () => {
  const calls = [];
  const page = serverPage(async (...args) => {
    calls.push(args);
    return { ok: true, json: async () => ({ accepted: true, status: "stopping", message: "停止を開始しました" }) };
  }, { status: "online" });
  assert.equal(page.stopButton.disabled, false);
  assert.equal(page.button.disabled, true);
  await page.stop();
  assert.equal(page.confirmations.length, 1);
  assert.match(page.confirmations[0], /7 Days to Die.*切断/);
  assert.equal(calls[0][0], "/portal/actions/games/7dtd/stop");
  assert.equal(calls[0][1].method, "POST");
  assert.equal(calls[0][1].headers["X-CSRF-TOKEN"], "csrf-test");
  assert.equal(page.chip.textContent, "停止処理中");
  assert.equal(page.message.textContent, "停止を開始しました");
  assert.equal(page.stopButton.disabled, true);
  assert.equal(page.button.disabled, true);
  assert.equal(page.summary.textContent, "1.0 GiB");
});

test("a declined confirmation or a stopped game sends no stop request", async () => {
  let calls = 0;
  const declined = serverPage(async () => { calls++; }, { status: "online", confirm: () => false });
  await declined.stop();
  assert.equal(declined.confirmations.length, 1);
  const offline = serverPage(async () => { calls++; });
  assert.equal(offline.stopButton.disabled, true);
  await offline.stop();
  assert.equal(offline.confirmations.length, 0);
  assert.equal(calls, 0);
});

test("polling re-enables stop for a running game and start once it has stopped", async () => {
  let status = "online";
  const page = serverPage(async () => ({ ok: true, json: async () => ({ "7dtd": { code: status } }) }),
    { live: true, status: "stopping" });
  assert.equal(page.stopButton.disabled, true);
  await page.timers.shift()();
  assert.equal(page.stopButton.disabled, false);
  status = "offline";
  await page.timers.shift()();
  assert.equal(page.stopButton.disabled, true);
  assert.equal(page.button.disabled, false);
});
