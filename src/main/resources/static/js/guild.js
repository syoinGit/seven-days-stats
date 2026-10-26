// Guild windows: links inside the guild open their page as a window over the tavern instead of
// leaving it. The address follows, so Back closes a window and a shared link opens the same one.
(() => {
  const dialog = document.getElementById("guild-window");
  const scrim = document.querySelector("[data-guild-scrim]");
  const tabbar = document.querySelector("[data-guild-tabbar]");
  if (!dialog || !scrim || !tabbar) return;
  const WINDOW = /^\/guild\/(characters\/\d+|log(\/\d+)?|pairs\/\d+-\d+|dungeon(\/\d+)?)$/;
  // The place under the windows: the guild, or a nation's tavern.
  const HOME = document.body.dataset.placeUrl || "/guild";
  const TAVERN_TITLE = document.body.dataset.placeTitle || "無人の冒険者ギルド | Rubinco";
  let opener = null;

  function show() {
    if (!dialog.open) {
      opener = document.activeElement;
      dialog.show();
    }
    scrim.hidden = false;
    document.body.classList.add("guild-window-open");
    dialog.querySelector(".guild-window-body")?.scrollTo(0, 0);
    dialog.querySelector("[data-guild-close]")?.focus({ preventScroll: true });
    // Window content may be new: let parts that live inside windows set themselves up.
    document.dispatchEvent(new Event("guild-window"));
  }

  function hide() {
    if (dialog.open) dialog.close();
    scrim.hidden = true;
    document.body.classList.remove("guild-window-open");
    document.title = TAVERN_TITLE;
    tabbar.querySelectorAll("a").forEach((tab) => {
      if (new URL(tab.href).pathname === "/guild") tab.setAttribute("aria-current", "page");
      else tab.removeAttribute("aria-current");
    });
    if (opener && document.contains(opener)) opener.focus({ preventScroll: true });
    opener = null;
  }

  async function load(url, push) {
    let page;
    try {
      const response = await fetch(url, { headers: { Accept: "text/html" } });
      if (!response.ok) throw new Error(String(response.status));
      page = new DOMParser().parseFromString(await response.text(), "text/html");
    } catch {
      location.href = url;
      return;
    }
    const next = page.getElementById("guild-window");
    const nextTabs = page.querySelector("[data-guild-tabbar]");
    if (!next || !next.hasAttribute("open") || !nextTabs) {
      location.href = url;
      return;
    }
    dialog.replaceChildren(...next.childNodes);
    tabbar.replaceChildren(...nextTabs.childNodes);
    document.title = page.title;
    // The depth counts windows opened here, so closing goes straight back to the tavern.
    if (push) history.pushState({ guildDepth: (history.state?.guildDepth || 0) + 1 }, "", url);
    show();
  }

  function close() {
    const depth = history.state?.guildDepth || 0;
    if (depth > 0) {
      history.go(-depth);
    } else {
      history.pushState(null, "", HOME);
      hide();
    }
  }

  document.addEventListener("click", (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const link = event.target.closest("a[href]");
    if (!link || link.target) return;
    const url = new URL(link.href, location.href);
    if (url.origin !== location.origin) return;
    if (link.closest("form")) return;
    if (url.pathname === HOME && !url.search) {
      event.preventDefault();
      if (dialog.open) close();
    } else if (WINDOW.test(url.pathname)) {
      event.preventDefault();
      load(url.href, true);
    }
  });
  // The log's filter form is a GET: load its result as a window too.
  document.addEventListener("submit", (event) => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || form.method.toLowerCase() !== "get" || !dialog.contains(form)) return;
    const url = new URL(form.action, location.href);
    if (!WINDOW.test(url.pathname)) return;
    event.preventDefault();
    url.search = new URLSearchParams([...new FormData(form)].filter(([, value]) => value !== "")).toString();
    load(url.href, true);
  });
  scrim.addEventListener("click", close);
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && dialog.open) close();
  });
  window.addEventListener("popstate", () => {
    if (WINDOW.test(location.pathname)) load(location.href, false);
    else hide();
  });
  // Opened from a shared link: put the tavern underneath, so closing or Back lands there.
  if (dialog.open) {
    const here = location.href;
    history.replaceState(null, "", HOME);
    history.pushState({ guildDepth: 1 }, "", here);
    show();
  }
})();

// The tavern keeps moving while it is left open: the current talk plays line by line, one speaker's bubble
// at a time, and now and then someone else in view says something to no one in particular.
(() => {
  const room = document.querySelector(".guild-room");
  if (!room || matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const says = [...room.querySelectorAll(".say")].sort((a, b) => a.dataset.turn - b.dataset.turn);
  if (says.length > 1) {
    room.classList.add("playing");
    let turn = 0;
    const play = () => {
      says.forEach((say) => { say.hidden = say !== says[turn]; });
      room.querySelectorAll(".speaking").forEach((person) => person.classList.remove("speaking"));
      says[turn].closest(".room-person")?.classList.add("speaking");
      const last = turn === says.length - 1;
      turn = last ? 0 : turn + 1;
      setTimeout(play, last ? 9000 : 4500);
    };
    play();
  }
  const murmur = () => {
    const near = [...room.querySelectorAll(".room-person:not(.talking)")].filter((person) => person.offsetParent !== null);
    if (near.length && !document.hidden) {
      const person = near[Math.floor(Math.random() * near.length)];
      person.classList.add("murmur");
      setTimeout(() => person.classList.remove("murmur"), 4000);
    }
    setTimeout(murmur, 9000 + Math.random() * 8000);
  };
  setTimeout(murmur, 5000);
})();

// Mire, the girl painted on the sign: one short talk a day. The script comes from /guild/signboard; the visitor
// reads it line by line and picks from set choices. "Talked today" (the Tokyo date) stays in this browser only.
(() => {
  const open = document.querySelector("[data-signboard-open]");
  const board = document.getElementById("guild-signboard");
  if (!open || !board || typeof board.showModal !== "function") return;
  const KEY = "guild-signboard-day";
  const SHEET = "/img/guild-signboard.svg?v=2#mire-";
  const OPEN_EYES = new Set(["normal", "think", "surprise", "shy", "sad"]);
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const face = board.querySelector(".sb-face");
  const figure = board.querySelector(".sb-mire");
  const text = board.querySelector(".sb-text");
  const choices = board.querySelector(".sb-choices");
  const next = board.querySelector(".sb-next");
  const today = new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Tokyo" });
  const read = () => { try { return localStorage.getItem(KEY); } catch { return null; } };
  const mark = () => { try { localStorage.setItem(KEY, today); } catch { /* private window: the talk is just not remembered */ } };
  const fresh = () => read() !== today;
  const badge = () => { open.querySelector("[data-signboard-new]").hidden = !fresh(); };
  let talk = null;
  let queue = [];
  let current = null;
  let after = null;
  let typing = null;
  let menuTurn = 0;
  let left = [];

  function show(line) {
    face.setAttribute("href", SHEET + line.face);
    figure.classList.toggle("blinks", OPEN_EYES.has(line.face));
    figure.classList.remove("hop");
    void figure.getBoundingClientRect();
    figure.classList.add("hop");
    clearInterval(typing);
    if (still) {
      text.textContent = line.text;
      typing = null;
      return;
    }
    let shown = 0;
    text.textContent = "";
    typing = setInterval(() => {
      shown += 1;
      text.textContent = line.text.slice(0, shown);
      if (shown >= line.text.length) {
        clearInterval(typing);
        typing = null;
      }
    }, 38);
  }

  // Lines one at a time; then runs. A click finishes the line being typed, the next click moves on.
  function say(lines, then) {
    choices.replaceChildren();
    queue = [...lines];
    after = then;
    advance();
  }

  function advance() {
    if (typing) {
      clearInterval(typing);
      typing = null;
      text.textContent = current.text;
      return;
    }
    const line = queue.shift();
    if (!line) {
      next.hidden = true;
      const then = after;
      after = null;
      if (then) then();
      return;
    }
    current = line;
    next.hidden = false;
    show(line);
  }

  function ask(options) {
    next.hidden = true;
    choices.replaceChildren(...options.map(([label, run]) => {
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = label;
      button.addEventListener("click", (event) => {
        event.stopPropagation();
        run();
      });
      return button;
    }));
    choices.querySelector("button")?.focus({ preventScroll: true });
  }

  function menu(first) {
    if (!left.length) return farewell();
    const line = first ? [] : [talk.menu[menuTurn++ % talk.menu.length]];
    const offer = () => ask([...left.map((topic) => [topic.label, () => {
      left = left.filter((other) => other !== topic);
      node(topic.start);
    }]), ["今日はこのへんで", farewell]]);
    if (line.length) say(line, offer);
    else offer();
  }

  function node(key) {
    const here = talk.nodes[key];
    say(here.lines, () => {
      if (here.choices.length) ask(here.choices.map((choice) => [choice.label, () => node(choice.next)]));
      else if (here.next === "menu" || !here.next) menu(false);
      else node(here.next);
    });
  }

  function farewell() {
    say(talk.farewell, () => ask([["またね", close]]));
  }

  function close() {
    clearInterval(typing);
    typing = null;
    if (board.open) board.close();
  }

  async function begin() {
    board.classList.add("loading");
    text.textContent = "……";
    choices.replaceChildren();
    next.hidden = true;
    board.showModal();
    try {
      const response = await fetch("/guild/signboard", { headers: { Accept: "application/json" } });
      if (!response.ok) throw new Error(String(response.status));
      talk = await response.json();
    } catch {
      board.classList.remove("loading");
      text.textContent = "……看板の絵の具がかすれて、うまく話せないみたい。また少ししてから来てね。";
      ask([["閉じる", close]]);
      return;
    }
    board.classList.remove("loading");
    if (!fresh()) {
      say(talk.done, () => ask([["またね", close]]));
      return;
    }
    mark();
    badge();
    left = [...talk.topics];
    menuTurn = 0;
    say(talk.opener, () => menu(true));
  }

  board.querySelector(".sb-box").addEventListener("click", (event) => {
    if (event.target.closest(".sb-choices")) return;
    if (!next.hidden) advance();
  });
  board.addEventListener("keydown", (event) => {
    // Escape closes only this, not the window under it.
    if (event.key === "Escape") event.stopPropagation();
    if ((event.key === "Enter" || event.key === " ") && !next.hidden && !event.target.closest("button")) {
      event.preventDefault();
      advance();
    }
  });
  board.querySelector(".sb-close").addEventListener("click", close);
  board.addEventListener("close", () => open.focus({ preventScroll: true }));
  open.addEventListener("click", begin);
  open.hidden = false;
  badge();
})();

// GuildAdv: reads a conversation one line at a time, visual-novel style, as if from a seat at the counter of the place it
// happens in (each place has its own backdrop). The speakers' figures stand behind the counter at the sides (the one
// talking lit, the other dimmed), and a message box at the bottom types each line out. Narration is not boxed: it drifts
// in over a mist, like a monologue. A click, Enter or Space shows the whole line, then goes on. Used for scenes between
// residents and for talking with a resident.
const GuildAdv = (() => {
  const still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let dialog = null;
  let opener = null;
  let advance = null;

  function build() {
    dialog = document.createElement("dialog");
    dialog.className = "adv-player";
    dialog.setAttribute("aria-label", "会話");
    dialog.innerHTML = '<div class="adv-bg" aria-hidden="true"><span class="ab-wall"></span><span class="ab-deco"></span>'
      + '<span class="ab-lamp one"></span><span class="ab-lamp two"></span><span class="ab-lamp three"></span></div>'
      + '<div class="adv-stage"><div class="adv-figure left"></div><div class="adv-figure right"></div></div>'
      + '<div class="adv-counter" aria-hidden="true"><span class="ab-glass"></span></div>'
      + '<div class="adv-mist" aria-hidden="true"></div><p class="adv-mono" aria-live="polite"></p>'
      + '<div class="adv-box" aria-live="polite"><span class="adv-name"></span><p class="adv-text"></p><div class="adv-choices"></div>'
      + '<span class="adv-next" aria-hidden="true">▼</span></div><button type="button" class="adv-close" aria-label="会話を閉じる">×</button>';
    document.body.append(dialog);
    const box = dialog.querySelector(".adv-box");
    box.tabIndex = 0;
    box.addEventListener("click", () => advance?.());
    dialog.querySelector(".adv-mono").addEventListener("click", () => advance?.());
    dialog.querySelector(".adv-mist").addEventListener("click", () => advance?.());
    dialog.addEventListener("keydown", (event) => {
      if ((event.key === "Enter" || event.key === " ") && !event.target.closest("button")) {
        event.preventDefault();
        advance?.();
      }
    });
    dialog.querySelector(".adv-close").addEventListener("click", close);
    dialog.addEventListener("cancel", (event) => { event.preventDefault(); close(); });
  }

  const PLACES = ["guild", "grantford", "elmwood", "tsukikage"];

  /** figures: { left: svg, right: svg } (cloned), either may be missing; place: whose counter we sit at. */
  function open(figures, place = "guild") {
    if (!dialog) build();
    opener = document.activeElement;
    dialog.className = "adv-player adv-bg-" + (PLACES.includes(place) ? place : "guild");
    for (const side of ["left", "right"]) {
      const slot = dialog.querySelector(".adv-figure." + side);
      slot.replaceChildren();
      slot.classList.remove("active", "dim");
      if (figures[side]) slot.append(figures[side].cloneNode(true));
    }
    dialog.querySelector(".adv-choices").replaceChildren();
    dialog.showModal();
    dialog.querySelector(".adv-box").focus?.();
  }

  function close() {
    advance = null;
    if (dialog?.open) dialog.close();
    if (opener && document.contains(opener)) opener.focus({ preventScroll: true });
  }

  /** Narration: the box gives way to a mist, and the text surfaces in it. Resolves once read; rejects if closed. */
  function muse(text) {
    return new Promise((resolve, reject) => {
      if (!dialog?.open) { reject(new Error("closed")); return; }
      dialog.querySelectorAll(".adv-figure").forEach((figure) => figure.classList.remove("active"));
      const mono = dialog.querySelector(".adv-mono");
      mono.classList.remove("shown", "now");
      mono.textContent = text;
      dialog.classList.add("misty");
      void mono.offsetWidth;
      mono.classList.add("shown");
      let ready = still;
      const timer = still ? null : setTimeout(() => { ready = true; }, 1400);
      const closed = () => { clearTimeout(timer); reject(new Error("closed")); };
      dialog.addEventListener("close", closed, { once: true });
      advance = () => {
        if (!ready) { clearTimeout(timer); ready = true; mono.classList.add("now"); return; }
        dialog.removeEventListener("close", closed);
        advance = null;
        resolve();
      };
    });
  }

  /** One line. side: "left", "right" or null (narration, or the visitor). Resolves once read; rejects if closed. */
  function say({ side = null, name = "", text, kind = "" }) {
    if (side === null && !kind) return muse(text);
    return new Promise((resolve, reject) => {
      if (!dialog?.open) { reject(new Error("closed")); return; }
      dialog.classList.remove("misty");
      dialog.querySelectorAll(".adv-figure").forEach((figure) => {
        const speaking = side !== null && figure.classList.contains(side);
        figure.classList.toggle("active", speaking);
        figure.classList.toggle("dim", side !== null && !speaking && figure.childElementCount > 0);
      });
      const box = dialog.querySelector(".adv-box");
      box.className = "adv-box" + (kind ? " " + kind : side ? " " + side : " narration");
      dialog.querySelector(".adv-name").textContent = name;
      dialog.querySelector(".adv-name").hidden = !name;
      const out = dialog.querySelector(".adv-text");
      const next = dialog.querySelector(".adv-next");
      let shown = still ? text.length : 0;
      let timer = null;
      const render = () => { out.textContent = text.slice(0, shown); next.hidden = shown < text.length; };
      const finish = () => { clearInterval(timer); shown = text.length; render(); };
      render();
      if (shown < text.length) timer = setInterval(() => { shown++; render(); if (shown >= text.length) clearInterval(timer); }, 35);
      const closed = () => { clearInterval(timer); reject(new Error("closed")); };
      dialog.addEventListener("close", closed, { once: true });
      advance = () => {
        if (shown < text.length) { finish(); return; }
        dialog.removeEventListener("close", closed);
        advance = null;
        resolve();
      };
    });
  }

  /** The visitor's answers, as lines they could say; resolves with the index picked. seen: indexes picked before. */
  function choose(labels, seen = []) {
    return new Promise((resolve, reject) => {
      if (!dialog?.open) { reject(new Error("closed")); return; }
      dialog.classList.remove("misty");
      dialog.querySelectorAll(".adv-figure").forEach((figure) => figure.classList.remove("active"));
      const box = dialog.querySelector(".adv-box");
      box.className = "adv-box choosing";
      dialog.querySelector(".adv-name").textContent = "あなた";
      dialog.querySelector(".adv-name").hidden = false;
      dialog.querySelector(".adv-text").textContent = "";
      dialog.querySelector(".adv-next").hidden = true;
      const list = dialog.querySelector(".adv-choices");
      list.replaceChildren(...labels.map((label, index) => {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = "「" + label + "」";
        if (seen.includes(index)) {
          button.classList.add("seen");
          button.title = "前に話したこと";
        }
        button.addEventListener("click", (event) => {
          event.stopPropagation();
          list.replaceChildren();
          dialog.removeEventListener("close", closed);
          resolve(index);
        });
        return button;
      }));
      const closed = () => reject(new Error("closed"));
      dialog.addEventListener("close", closed, { once: true });
      advance = null;
      list.querySelector("button")?.focus();
    });
  }

  return { open, close, say, choose };
})();

// A scene between residents (the log's entry window): "会話を聞く" plays it line by line, overheard from the counter.
(() => {
  function setUp(scene) {
    if (scene.dataset.ready) return;
    scene.dataset.ready = "1";
    const start = scene.querySelector("[data-adv-start]");
    if (!start) return;
    start.hidden = false;
    start.addEventListener("click", async () => {
      const figures = {};
      scene.querySelectorAll("[data-adv-figures] [data-side]").forEach((slot) => { figures[slot.dataset.side] = slot.querySelector("svg"); });
      GuildAdv.open(figures, scene.dataset.place);
      try {
        await GuildAdv.say({ text: (scene.dataset.placeName || "酒場") + "のカウンター。グラスを傾けていると、近くの席から話し声が聞こえてきた。" });
        for (const line of scene.querySelectorAll("[data-adv-line]")) {
          if (line.classList.contains("adv-narration")) await GuildAdv.say({ text: line.textContent });
          else await GuildAdv.say({ side: line.dataset.side, name: line.dataset.name, text: line.querySelector(".adv-bubble p").textContent });
        }
        await GuildAdv.say({ text: "……話し声は、やがて酒場のざわめきに溶けていった。" });
        GuildAdv.close();
      } catch { /* closed midway */ }
    });
  }
  const scan = () => document.querySelectorAll("[data-adv-scene]").forEach(setUp);
  document.addEventListener("guild-window", scan);
  scan();
})();

// Talking with a resident (in their window), as a conversation: they speak first, the visitor answers in their own
// words (one of three), and they reply. One talk with each resident a day; "talked today", the answer and the reply,
// and which answers were picked before stay in this browser only.
(() => {
  const today = new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Tokyo" });
  const get = (key) => { try { return JSON.parse(localStorage.getItem(key)); } catch { return null; } };
  const put = (key, value) => { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* private window: not remembered */ } };

  function setUp(box) {
    if (box.dataset.ready) return;
    box.dataset.ready = "1";
    const talkId = box.dataset.talkId;
    const name = box.dataset.name;
    const dayKey = "guild-talk-day-" + box.dataset.characterId;
    const seenKey = "guild-talk-seen-" + talkId;
    const opening = box.querySelector("[data-talk-opening]").textContent;
    const labels = [...box.querySelectorAll("[data-talk-labels] li")].map((item) => item.textContent);
    const figure = box.querySelector(".adv-say svg");
    const log = box.querySelector("[data-talk-log]");
    const note = box.querySelector("[data-talk-note]");
    const start = box.querySelector("[data-talk-start]");
    const place = box.dataset.place;
    // How the talk begins: where we sit, and who turns to us.
    const settings = { guild: "カウンターの隣の席で", grantford: "燭台の灯りの下、長卓の向かいで", elmwood: "苔の灯りに照らされた卓の隣で",
      tsukikage: "提灯の明かりの下、低い卓の隣で" };
    const intro = (box.dataset.placeName || "酒場") + "。" + (settings[place] || settings.guild) + "、" + name + "がこちらに気づいた。";

    // What was said today, under the first line: the visitor's answer on the right, their reply on the left.
    const written = (label, reply) => {
      log.querySelectorAll(".adv-say:not(:first-child)").forEach((line) => line.remove());
      const mine = document.createElement("div");
      mine.className = "adv-say right you";
      mine.innerHTML = '<div class="adv-bubble"><b>あなた</b><p></p></div>';
      mine.querySelector("p").textContent = label;
      const theirs = log.firstElementChild.cloneNode(true);
      theirs.querySelector("p").removeAttribute("data-talk-opening");
      theirs.querySelector("p").textContent = reply;
      log.append(mine, theirs);
    };
    const day = get(dayKey);
    const doneToday = day && day.date === today;
    if (doneToday && day.talk === talkId) written(day.label, day.reply);
    note.textContent = doneToday ? name + "とは今日もう話しました。また明日。" : "";
    note.hidden = !doneToday;
    start.hidden = false;

    start.addEventListener("click", async () => {
      const again = get(dayKey);
      GuildAdv.open({ left: figure }, place);
      try {
        await GuildAdv.say({ text: intro });
        if (again && again.date === today) {
          if (again.talk === talkId) {
            await GuildAdv.say({ side: "left", name, text: opening });
            await GuildAdv.say({ name: "あなた", text: again.label, kind: "you" });
            await GuildAdv.say({ side: "left", name, text: again.reply });
          }
          await GuildAdv.say({ text: name + "とは今日もう話した。また明日、話しかけてみよう。" });
          GuildAdv.close();
          return;
        }
        await GuildAdv.say({ side: "left", name, text: opening });
        const seen = get(seenKey) || [];
        const choice = await GuildAdv.choose(labels, seen);
        await GuildAdv.say({ name: "あなた", text: labels[choice], kind: "you" });
        let reply;
        try {
          const response = await fetch("/guild/talks/" + talkId + "/choices/" + choice, { headers: { Accept: "application/json" } });
          if (!response.ok) throw new Error(String(response.status));
          reply = (await response.json()).reply;
        } catch {
          await GuildAdv.say({ text: "……声がうまく届かなかったようだ。少し待って、もう一度話しかけてみよう。" });
          GuildAdv.close();
          return;
        }
        put(dayKey, { date: today, talk: talkId, choice, label: labels[choice], reply });
        put(seenKey, [...new Set([...seen, choice])]);
        written(labels[choice], reply);
        note.textContent = name + "とは今日もう話しました。また明日。";
        note.hidden = false;
        await GuildAdv.say({ side: "left", name, text: reply });
        await GuildAdv.say({ text: "また明日、話しかけてみよう。" });
        GuildAdv.close();
      } catch { /* closed midway: what was decided is already kept */ }
    });
  }

  const scan = () => document.querySelectorAll("[data-talk]").forEach(setUp);
  document.addEventListener("guild-window", scan);
  scan();
})();
