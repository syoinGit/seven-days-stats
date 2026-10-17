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
