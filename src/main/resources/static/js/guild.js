// Guild windows: links inside the guild open their page as a window over the tavern instead of
// leaving it. The address follows, so Back closes a window and a shared link opens the same one.
(() => {
  const dialog = document.getElementById("guild-window");
  const scrim = document.querySelector("[data-guild-scrim]");
  const tabbar = document.querySelector("[data-guild-tabbar]");
  if (!dialog || !scrim || !tabbar) return;
  const WINDOW = /^\/guild\/(visitors|board|characters(\/\d+)?|log(\/\d+)?|pairs\/\d+-\d+|dungeon(\/\d+)?)$/;
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
  const SHEET = "/img/guild-signboard.svg?v=1#mire-";
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

// Talking with a resident (in their window): the first line and three answers. One talk with each resident a day;
// "talked today", the answer and the reply, and which answers were picked before stay in this browser only.
(() => {
  const today = new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Tokyo" });
  const get = (key) => { try { return JSON.parse(localStorage.getItem(key)); } catch { return null; } };
  const put = (key, value) => { try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* private window: not remembered */ } };

  function setUp(box) {
    if (box.dataset.ready) return;
    box.dataset.ready = "1";
    const talkId = box.dataset.talkId;
    const dayKey = "guild-talk-day-" + box.dataset.characterId;
    const seenKey = "guild-talk-seen-" + talkId;
    const choices = box.querySelector("[data-talk-choices]");
    const buttons = [...box.querySelectorAll("[data-talk-choice]")];
    const reply = box.querySelector("[data-talk-reply]");
    const note = box.querySelector("[data-talk-note]");
    const done = (choice, text) => {
      buttons.forEach((button) => {
        button.disabled = true;
        button.classList.toggle("chosen", button.dataset.talkChoice === String(choice));
      });
      reply.textContent = text;
      reply.hidden = false;
      note.textContent = "今日はもう話しました。また明日、別の返事もできます。";
      note.hidden = false;
    };
    choices.hidden = false;
    const day = get(dayKey);
    if (day && day.date === today && day.talk === talkId) {
      done(day.choice, day.reply);
      return;
    }
    const seen = get(seenKey) || [];
    buttons.forEach((button) => {
      if (seen.includes(Number(button.dataset.talkChoice))) {
        button.classList.add("seen");
        button.title = "前に選んだ返事";
      }
    });
    if (day && day.date === today) {
      // Talked with them today already, about an older talk: the new one waits until tomorrow.
      buttons.forEach((button) => { button.disabled = true; });
      note.textContent = "今日はもう話しました。また明日。";
      note.hidden = false;
      return;
    }
    note.hidden = true;
    buttons.forEach((button) => button.addEventListener("click", async () => {
      const choice = Number(button.dataset.talkChoice);
      buttons.forEach((other) => { other.disabled = true; });
      try {
        const response = await fetch("/guild/talks/" + talkId + "/choices/" + choice, { headers: { Accept: "application/json" } });
        if (!response.ok) throw new Error(String(response.status));
        const text = (await response.json()).reply;
        put(dayKey, { date: today, talk: talkId, choice, reply: text });
        put(seenKey, [...new Set([...seen, choice])]);
        done(choice, text);
      } catch {
        buttons.forEach((other) => { other.disabled = false; });
        note.textContent = "返事を読み込めませんでした。もう一度選んでください。";
        note.hidden = false;
      }
    }));
  }

  const scan = () => document.querySelectorAll("[data-talk]").forEach(setUp);
  document.addEventListener("guild-window", scan);
  scan();
})();
