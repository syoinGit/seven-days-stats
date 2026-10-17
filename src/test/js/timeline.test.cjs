const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.resolve(__dirname, "../../main/resources/static/js/timeline.js"), "utf8");

// Exercise event and network behavior without a browser dependency or real requests.
function element(properties = {}) {
  return Object.assign({
    events: {}, dataset: {}, hidden: false, textContent: "",
    classList: { add() {}, remove() {} },
    setAttribute() {}, removeAttribute() {},
    addEventListener(name, callback) { this.events[name] = callback; }
  }, properties);
}

function page({ copyOnly = false } = {}) {
  const counts = { reloads: 0, disconnected: 0, confirmations: 0, appended: 0 };
  const intervals = [], timers = new Set(), documentEvents = {}, windowEvents = {};
  const refresh = element(), composer = element({ value: "" });
  const loader = element({ dataset: { nextOffset: "20" } }), progress = element();
  const timeline = element({
    dataset: { timelineUrl: "/dashboard/timeline" },
    append() { counts.appended++; }
  });
  const copy = element(), data = element({
    value: "payload", focus() { this.focused = true; }, select() { this.selected = true; }
  });
  const nodes = {
    "[data-refresh-page]": copyOnly ? null : refresh,
    ".timeline-compose textarea": composer,
    "#timeline": copyOnly ? null : timeline,
    "[data-timeline-loader]": copyOnly ? null : loader,
    "[data-timeline-progress]": progress,
    "[data-copy-generation]": copy,
    "#generation-data": data
  };
  const state = {
    meta: { dataset: { nextOffset: "40", hasMore: "true" } }, items: [{}],
    response: { ok: true, redirected: false, text: async () => "" }
  };
  const document = {
    hidden: false, activeElement: null,
    querySelector: selector => nodes[selector] || null,
    addEventListener: (name, callback) => { documentEvents[name] = callback; }
  };
  class Observer {
    observe() {}
    disconnect() { counts.disconnected++; }
  }
  const window = {
    location: { href: "http://localhost/dashboard", reload() { counts.reloads++; } },
    scrollY: 0,
    confirm() { counts.confirmations++; return state.confirm !== false; },
    setInterval: callback => intervals.push(callback),
    setTimeout(callback) { timers.add(callback); return callback; },
    clearTimeout: timer => timers.delete(timer),
    addEventListener: (name, callback) => { windowEvents[name] = callback; },
    IntersectionObserver: Observer
  };
  vm.runInNewContext(source, {
    document, window, navigator: {}, URL, AbortController, IntersectionObserver: Observer,
    DOMParser: class {
      parseFromString() {
        return { querySelector: () => state.meta, querySelectorAll: () => state.items };
      }
    },
    fetch: async (...args) => state.fetch ? state.fetch(...args) : state.response
  });
  documentEvents.DOMContentLoaded();
  return { counts, intervals, timers, documentEvents, windowEvents, nodes, state,
    document, window, refresh, composer, loader, progress, copy, data };
}

function submit(formSelector) {
  return {
    target: { matches: selector => selector.split(", ").includes(formSelector) },
    prevented: false,
    preventDefault() { this.prevented = true; }
  };
}

test("automatic refresh preserves drafts, reaction menus and reading position", () => {
  const view = page();
  view.composer.value = "unsent draft";
  view.intervals[0]();
  assert.equal(view.counts.reloads, 0);
  view.state.confirm = false;
  view.refresh.events.click();
  assert.equal(view.counts.reloads, 0);
  assert.equal(view.counts.confirmations, 1);
  view.composer.value = "";
  view.nodes[".reaction-menu[open]"] = {};
  view.intervals[0]();
  assert.equal(view.counts.reloads, 0);
  delete view.nodes[".reaction-menu[open]"];
  view.window.scrollY = 500;
  view.intervals[0]();
  assert.equal(view.counts.reloads, 0);
  view.window.scrollY = 0;
  view.intervals[0]();
  assert.equal(view.counts.reloads, 1);
});

test("dynamic delete forms require confirmation and duplicate submits are blocked", () => {
  const view = page();
  view.state.confirm = false;
  const cancelled = submit(".delete-post-form");
  view.documentEvents.submit(cancelled);
  assert.equal(cancelled.prevented, true);
  view.state.confirm = true;
  const accepted = submit(".delete-post-form");
  view.documentEvents.submit(accepted);
  assert.equal(accepted.prevented, false);
  const duplicate = submit(".reaction-form");
  view.documentEvents.submit(duplicate);
  assert.equal(duplicate.prevented, true);
  view.intervals[0]();
  assert.equal(view.counts.reloads, 0);
  view.windowEvents.pageshow();
  const afterBack = submit(".timeline-compose form");
  view.documentEvents.submit(afterBack);
  assert.equal(afterBack.prevented, false);
});

test("pagination rejects login pages and non-advancing cursors without losing retry", async () => {
  const view = page();
  await view.loader.events.click();
  assert.equal(view.counts.appended, 1);
  assert.equal(view.loader.dataset.nextOffset, "40");
  view.state.meta = null;
  await view.loader.events.click();
  assert.equal(view.loader.hidden, false);
  assert.match(view.progress.textContent, /失敗/);
  view.state.meta = { dataset: { nextOffset: "40", hasMore: "true" } };
  await view.loader.events.click();
  assert.equal(view.counts.appended, 1);
  view.state.meta = { dataset: { nextOffset: "41", hasMore: "false" } };
  view.state.response.redirected = true;
  await view.loader.events.click();
  assert.equal(view.counts.appended, 1);
  view.state.response.redirected = false;
  await view.loader.events.click();
  assert.equal(view.counts.appended, 2);
  assert.equal(view.loader.hidden, true);
  assert.equal(view.counts.disconnected, 1);
  assert.equal(view.timers.size, 0);
});

test("a stalled page request times out and can be retried", async () => {
  const view = page();
  view.state.fetch = (_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener("abort", () => reject(new Error("aborted")));
  });
  const pending = view.loader.events.click();
  for (const timeout of view.timers) timeout();
  await pending;
  assert.match(view.progress.textContent, /失敗/);
  assert.equal(view.loader.hidden, false);
  delete view.state.fetch;
  await view.loader.events.click();
  assert.equal(view.counts.appended, 1);
});

test("clipboard failure selects the payload for manual copying", async () => {
  const view = page({ copyOnly: true });
  await view.copy.events.click();
  assert.equal(view.data.focused, true);
  assert.equal(view.data.selected, true);
  assert.match(view.copy.textContent, /コピーしてください/);
  assert.equal(view.intervals.length, 0);
});
