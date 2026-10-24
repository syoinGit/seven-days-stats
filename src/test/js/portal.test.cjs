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

function serverPage(fetch, { live = false, csrf = "csrf-test" } = {}) {
  const handlers = {};
  const timers = [];
  const button = { disabled: false };
  const message = { textContent: "" };
  const form = {
    dataset: { startServer: "7dtd", status: "offline" },
    action: "/portal/actions/games/7dtd/start",
    querySelector: () => button,
    closest: () => ({ querySelector: () => message }),
    addEventListener: (name, callback) => { handlers[name] = callback; }
  };
  const chip = {
    dataset: { serverStatus: "7dtd" }, textContent: "停止中",
    classList: { add() {}, remove() {} }
  };
  const controls = { dataset: { liveServerStatus: String(live) }, querySelectorAll: () => [form] };
  const document = {
    visibilityState: "visible",
    querySelector: (selector) => {
      if (selector === ".server-controls") return controls;
      if (selector === 'meta[name="_csrf"]') return { content: csrf };
      if (selector === 'meta[name="_csrf_header"]') return { content: "X-CSRF-TOKEN" };
      return null;
    },
    querySelectorAll: (selector) => selector === "[data-server-status]" ? [chip] : []
  };
  vm.runInNewContext(source, { document, fetch, AbortSignal, setTimeout: (callback) => timers.push(callback) });
  return { form, button, message, chip, timers, submit: () => handlers.submit({ preventDefault() {} }) };
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
