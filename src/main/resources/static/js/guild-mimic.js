// 潜む者: a solo game of hidden roles in a labyrinth, with seven of the residents (fragments/guild-windows :: mimic,
// GuildController.mimic). No AI and nothing sent back, like the werewolf game (guild-werewolf.js), whose table, log and
// look it shares.
//
// Eight are lost in a labyrinth that lets no one out while a mimic is inside: two of them are mimics, one is a madman
// (human, but on the mimics' side, without knowing who they are), the rest are adventurers. Each day they go out to
// one of five places and gather mana there (each place gives two of four colours, how much is chance); by the fire
// they say what their spells showed, name whom they suspect and vote one out (the labyrinth swallows them and shows
// what they were); at night everyone may cast one spell. White spells (sight, veil, scent) are anyone's; black ones
// are the mimics' (curse, guise, seal) and the madman's (seal, whisper). Every colour serves both sides, so what one
// gathers proves nothing by itself; where everyone went is seen by all.
//
// How a resident thinks follows the werewolf game: a suspicion of everyone else made of parts with reasons they can
// say, swayed by how they stand with each other in the guild (data-ties, data-bonds), by whose spell results they
// believe, and overridden by what they know for certain. A result that called a human a mimic is a lie, once the
// labyrinth shows what that one was; a mimic called human may only have been in guise.
const guildMimic = () => {
  const box = document.querySelector("[data-mimic]");
  if (!box || box.dataset.ready) return;
  box.dataset.ready = "1";
  const $ = (selector) => box.querySelector(selector);
  const seats = [...box.querySelectorAll("[data-mimic-player]")];
  const youSeat = $("[data-mimic-you]");
  const log = $("[data-mimic-log]");
  const actions = $("[data-mimic-actions]");
  const status = $("[data-mimic-status]");
  const board = $("[data-mimic-board]");
  const manaNote = $("[data-mimic-mana]");
  const map = $("[data-mimic-map]");
  const recordNote = $("[data-mimic-record]");
  const speed = $("[data-mimic-speed]");
  const table = $("[data-mimic-table]");
  const arrows = $("[data-mimic-arrows]");
  const phaseNote = $("[data-mimic-phase]");
  const headline = $("[data-mimic-headline]");
  if (seats.length < 7 || !youSeat || !log || !actions || !status) return;

  const ROLES = { mimic: "ミミック", madman: "狂人", human: "冒険者" };
  const DEAL = ["mimic", "mimic", "madman", "human", "human", "human", "human", "human"];
  const COLORS = { light: "光", dark: "闇", water: "水", fire: "火" };
  const PLACES = [
    { key: "altar", name: "祭壇の間", colors: ["light", "fire"] },
    { key: "crypt", name: "墓所", colors: ["dark", "water"] },
    { key: "lake", name: "地底湖", colors: ["water", "light"] },
    { key: "lava", name: "溶岩の通路", colors: ["fire", "dark"] },
    { key: "library", name: "古書庫", colors: ["light", "dark"] },
  ];
  // Each two-colour pair is one spell, so whatever pair one holds has more than one use.
  const SPELLS = {
    sight: { name: "看破", cost: ["light", "dark"], who: null, black: false, about: "1人がミミックかどうかを見る" },
    veil: { name: "帳", cost: ["dark", "water"], who: null, black: false, about: "今夜、1人を黒魔法から守る" },
    scent: { name: "残り香", cost: ["fire", "water"], who: null, black: false, about: "1人が今夜、黒魔法を使ったかが分かる" },
    curse: { name: "呪殺", cost: ["fire", "water", "dark"], who: ["mimic"], black: true, about: "1人を倒す（ミミックの誰か1体だけ）" },
    guise: { name: "擬態", cost: ["light", "water"], who: ["mimic"], black: true, self: true, about: "次の看破を1回だけ人間と見せる" },
    seal: { name: "封印", cost: ["light", "fire"], who: ["mimic", "madman"], black: true, about: "1人の今夜の魔法をかき消す" },
    whisper: { name: "囁き", cost: ["dark", "fire"], who: ["madman"], black: true, about: "相手がミミックなら、互いに正体を知る" },
  };
  const BONDS = { friend: "友達", close_friend: "親友", companion: "仲間", lover: "恋人", spouse: "夫婦", rival: "宿敵", mentor: "師弟" };
  const BOND_WARMTH = { friend: 0.3, companion: 0.3, close_friend: 0.6, mentor: 0.4, lover: 0.9, spouse: 1, rival: -0.8 };
  // The residents' lines are their werewolf ones; these words make them fit the labyrinth.
  const REWORD = [["人狼", "ミミック"], ["村人", "人間"], ["この村", "この迷宮"], ["村が", "みんなが"], ["吊っても", "追い出しても"],
    ["吊ったら", "追い出したら"], ["吊るせば", "追い出せば"], ["吊る", "追い出す"]];
  const MEMOS = ["", "白", "黒", "灰"];
  const YOU = "旅の人";
  const RECORD = "guild-mimic-record";
  const FAST = "guild-wolf-fast";
  const random = (n) => Math.floor(Math.random() * n);
  const pick = (list) => list[random(list.length)];
  const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
  const shuffle = (list) => {
    const out = [...list];
    for (let i = out.length - 1; i > 0; i--) {
      const j = random(i + 1);
      [out[i], out[j]] = [out[j], out[i]];
    }
    return out;
  };
  /** The first of [value, score] pairs with the highest score (null when there are none). */
  const top = (pairs) => (pairs.length ? pairs.sort((a, b) => b[1] - a[1])[0][0] : null);
  /** One of [value, weight] pairs, drawn by weight. */
  const draw = (pairs) => {
    let roll = Math.random() * pairs.reduce((total, [, weight]) => total + weight, 0);
    return (pairs.find(([, weight]) => (roll -= weight) < 0) || pairs.at(-1) || [null])[0];
  };
  const stored = (key) => { try { return localStorage.getItem(key); } catch { return null; } };
  const store = (key, value) => { try { localStorage.setItem(key, value); } catch { /* private window: not kept */ } };

  // ---- How the residents stand with each other (from the guild) --------------------------------------------------

  const byId = new Map(seats.map((seat, index) => [seat.dataset.mimicPlayer, index]));
  const fondness = seats.map(() => seats.map(() => 0));
  const rivals = new Set();
  const bonds = [];
  for (const tie of (box.dataset.ties || "").split(";").filter(Boolean)) {
    const parts = tie.split(":");
    const a = byId.get(parts[0]);
    const b = byId.get(parts[1]);
    if (a === undefined || b === undefined) continue;
    // One fondness, or (from an older page) the numbers it is made of.
    const fond = parts.length === 3 ? Number(parts[2])
      : (Number(parts[3]) + 0.5 * Number(parts[2]) - Number(parts[4])) / 100 + (parts[5] === "1" ? 0.3 : 0);
    if (Number.isFinite(fond)) fondness[a][b] += fond;
  }
  for (const bond of (box.dataset.bonds || "").split(";").filter(Boolean)) {
    const [low, high, label] = bond.split(":");
    const a = byId.get(low);
    const b = byId.get(high);
    if (a === undefined || b === undefined || !BONDS[label]) continue;
    fondness[a][b] += BOND_WARMTH[label];
    fondness[b][a] += BOND_WARMTH[label];
    if (label === "rival") rivals.add(a + ":" + b).add(b + ":" + a);
    bonds.push({ a, b, label });
  }
  // The traveller is a stranger to all of them: each sees them as they see the others on average.
  const usual = fondness.map((row, a) => row.reduce((total, value, b) => total + (a === b ? 0 : clamp(value, -1.5, 1.5)), 0) / Math.max(1, row.length - 1));
  const fond = (a, b) => (a.you ? 0 : b.you ? usual[a.index] : clamp(fondness[a.index][b.index], -1.5, 1.5));

  let people = [];
  let day = 0;
  let story = [];

  // ---- The party ----------------------------------------------------------------------------------------------------

  const voice = (data) => {
    const lines = {};
    for (const kind of ["greet", "suspect", "defend", "vote", "cover"]) {
      lines[kind] = REWORD.reduce((text, [from, to]) => text.replaceAll(from, to), data[kind] || "");
    }
    return lines;
  };
  const noMana = () => ({ light: 0, dark: 0, water: 0, fire: 0 });

  function deal() {
    const cast = seats.map((seat) => ({ name: seat.dataset.name, seat, you: false, lines: voice(seat.dataset) }));
    cast.push({ name: "あなた", seat: youSeat, you: true, lines: {} });
    const roles = shuffle(DEAL);
    people = cast.map((person, index) => ({
      ...person, index, role: roles[index], alive: true, diedBy: null, revealed: null, mana: noMana(), history: [], goal: null,
      known: new Map(), allies: new Set(), hunch: [], heat: [], why: [], prior: [], claims: [], fresh: [], exposed: false,
      guised: false, named: new Set(), covered: new Set(), lastSuspect: null, votedFor: [], memo: 0, threat: 0,
    }));
    for (const self of people) {
      self.known.set(self.index, self.role === "mimic" ? "mimic" : "human");
      // Two colours of mana to start with, so that the first night is not empty.
      for (let i = 0; i < 2; i++) self.mana[pick(Object.keys(COLORS))]++;
      for (const other of people) {
        self.hunch[other.index] = Math.random();
        self.heat[other.index] = 0;
        self.why[other.index] = new Map();
        self.prior[other.index] = 0;
        if (self !== other && self.role === "mimic" && other.role === "mimic") {
          self.known.set(other.index, "mimic");
          self.allies.add(other.index);
        }
      }
    }
    day = 0;
    story = [];
    log.replaceChildren();
    chapter("はじまり");
    clearTable();
    for (const person of people) {
      person.seat.classList.remove("dead", "wolf-mark", "human-mark", "revealed", "hot", "warm", ...Object.keys(ROLES).map((role) => "role-" + role));
      tag(person, "");
      person.seat.querySelector("[data-wolf-marks]")?.replaceChildren();
      memoButton(person);
    }
  }

  const you = () => people.find((person) => person.you);
  const living = () => people.filter((person) => person.alive);
  const others = (self) => living().filter((person) => person !== self);
  const mimics = () => living().filter((person) => person.role === "mimic");
  const nameOf = (person) => (person.you ? YOU : person.name);
  const short = (person) => (person.you ? "あなた" : person.name);
  const side = (role) => (role === "human" ? "party" : "mimic");
  const today = () => story.at(-1);
  const placeOf = (key) => PLACES.find((place) => place.key === key);
  const manaText = (mana) => Object.entries(COLORS).filter(([key]) => mana[key]).map(([key, name]) => name + mana[key]).join("・") || "なし";

  /** Whether person may cast spell (their role allows it) and holds the mana for it. */
  function can(person, key) {
    const spell = SPELLS[key];
    if (spell.who && !spell.who.includes(person.role)) return false;
    const need = noMana();
    for (const color of spell.cost) need[color]++;
    return Object.keys(need).every((color) => person.mana[color] >= need[color]);
  }

  function pay(person, key) {
    for (const color of SPELLS[key].cost) person.mana[color] = Math.max(0, person.mana[color] - 1);
  }

  // ---- What a resident thinks ---------------------------------------------------------------------------------------

  /** Whether one of c's sight results says the opposite of what self knows for certain. */
  const caught = (self, c) => c.claims.some((claim) => claim.spell === "sight" && self.known.has(claim.target)
    && (self.known.get(claim.target) === "mimic") !== (claim.result === "mimic"));

  /** How far self believes what c says their spells showed (0..1). */
  function trust(self, c) {
    if (self === c) return 1;
    if (c.exposed || caught(self, c)) return 0;
    const known = self.known.get(c.index);
    if (known) return known === "mimic" ? 0 : 0.9;
    return clamp(self.prior[c.index] - 0.12 * (self.hunch[c.index] + self.heat[c.index]), 0.05, 0.9);
  }

  /** Raises self's suspicion of target, for a reason self can give (key: the reason, so that the same one adds up). */
  function warm(self, target, amount, key, text) {
    self.heat[target.index] += amount;
    if (amount <= 0) return;
    const reason = self.why[target.index].get(key) || { weight: 0, text };
    reason.weight += amount;
    reason.text = text;
    self.why[target.index].set(key, reason);
  }

  /** What c's spells said of target, each with how much it weighs on self. */
  function results(self, target) {
    const out = [];
    for (const c of people) {
      if (c === self || c === target) continue;
      const believed = trust(self, c);
      for (const claim of c.claims.filter((one) => one.target === target.index)) {
        if (claim.spell === "sight") out.push({ weight: believed * (claim.result === "mimic" ? 5 : -2), text: short(c) + "の看破でミミックと出た" });
        else out.push({ weight: believed * (claim.result === "black" ? 2.5 : -0.8), text: short(c) + "の残り香で黒魔法の気配があった" });
      }
    }
    return out;
  }

  /** Why self suspects target, each part with its weight; the strongest first. */
  function reasons(self, target) {
    const parts = [{ weight: self.hunch[target.index] * 0.8, text: "どうも引っかかる" }];
    for (const reason of self.why[target.index].values()) parts.push(reason);
    const dislike = -0.9 * fond(self, target);
    if (dislike > 0.2) parts.push({ weight: dislike, text: rivals.has(self.index + ":" + target.index) ? "宿敵に気を許すわけにはいかない" : "前から反りが合わない" });
    for (const part of results(self, target)) if (part.weight > 0) parts.push(part);
    return parts.sort((a, b) => b.weight - a.weight);
  }

  /** How much self suspects target: the higher, the more likely a mimic in self's eyes. */
  function suspicion(self, target) {
    const known = self.known.get(target.index);
    if (known) return known === "mimic" ? 100 : -100;
    let score = self.hunch[target.index] + self.heat[target.index] - 0.9 * fond(self, target);
    for (const part of results(self, target)) score += part.weight;
    return score;
  }

  /** The room's suspicion of target, as the adventurers and the madman see it. */
  const roomOn = (target) => {
    const room = living().filter((person) => person.role !== "mimic" && !person.you && person !== target);
    return room.reduce((total, person) => total + suspicion(person, target), 0) / Math.max(1, room.length);
  };

  /**
   * Whom self names or votes for among pool. A mimic: against the room's favourite among those not on their side (and
   * now and then a partner the vote is set on, to look clean). The madman: with the mimics if they know them, else
   * against whom they take for human. Anyone else: their top suspect, leaning towards whom someone dear voted for
   * (ballots). All lean towards whoever already has votes (votes: the tally so far).
   */
  function choice(self, pool = others(self), votes = new Map(), ballots = new Map()) {
    const candidates = pool.filter((person) => person !== self);
    if (!candidates.length) return null;
    const lean = (target) => 0.35 * (votes.get(target) || 0);
    if (self.role === "mimic") {
      const doomed = candidates.find((person) => person.role === "mimic" && (votes.get(person) || 0) >= living().length / 2 - 1);
      if (doomed && Math.random() < 0.4) return doomed;
      const prey = candidates.filter((person) => !self.allies.has(person.index) && person.role !== "mimic");
      if (!prey.length) return pick(candidates);
      return top(prey.map((target) => [target, roomOn(target) + lean(target) + Math.random() * 0.8 - 0.3 * fond(self, target)]));
    }
    if (self.role === "madman") {
      if (self.allies.size) {
        const prey = candidates.filter((person) => !self.allies.has(person.index));
        if (prey.length) return top(prey.map((target) => [target, roomOn(target) + lean(target) + Math.random() * 0.8]));
      }
      return top(candidates.map((target) => [target, -suspicion(self, target) + lean(target) + Math.random() * 0.6
        + (target.claims.some((claim) => claim.spell === "sight") ? 1 : 0)]));
    }
    return top(candidates.map((target) => {
      let score = suspicion(self, target) + lean(target) + Math.random() * 0.6;
      for (const [voter, voted] of ballots) if (voted === target && fond(self, voter) > 0.6) score += 0.5 * fond(self, voter);
      return [target, score];
    }));
  }

  /** The reason self gives for suspecting target (one on the mimics' side gives one the room would accept). */
  function reasonFor(self, target) {
    if (self.role !== "human") {
      const room = living().filter((person) => person !== self && person !== target && person.role === "human" && !person.you);
      const best = room.flatMap((person) => reasons(person, target).slice(0, 1)).sort((a, b) => b.weight - a.weight)[0];
      return best ? best.text : "どうも引っかかる";
    }
    return reasons(self, target)[0].text;
  }

  /** Everyone else hears speaker name target; it weighs on them as far as they like the speaker. */
  function heard(speaker, target, weight = 0.5) {
    target.named.add(speaker.index);
    const namers = [...target.named].filter((index) => index !== target.index);
    const text = namers.length > 1 ? "何人もが怪しいと言っている" : short(speaker) + "も怪しいと言っていた";
    for (const person of people) {
      if (person === speaker || person === target || person.you) continue;
      warm(person, target, weight * (1 + 0.4 * fond(person, speaker)), "named", text);
      if (fond(person, target) > 0.5) warm(person, speaker, 0.25 * fond(person, target), "accused:" + target.index, "大事な" + nameOf(target) + "を疑った");
      if (person.known.get(target.index) === "human") warm(person, speaker, 0.8, "wrong:" + target.index, "人間の" + nameOf(target) + "を疑った");
    }
    if (target.role === "mimic") speaker.threat += 1;
  }

  /** Everyone else hears speaker stand up for target. */
  function vouched(speaker, target) {
    target.covered.add(speaker.index);
    today()?.covers.push({ by: speaker, target });
    for (const person of people) {
      if (person === speaker || person === target || person.you) continue;
      person.heat[target.index] -= 0.35 * (1 + 0.5 * fond(person, speaker));
      if (person.known.get(target.index) === "mimic") warm(person, speaker, 1, "cover:" + target.index, "ミミックの" + nameOf(target) + "をかばった");
    }
  }

  /**
   * The labyrinth showed what target was (sent out, or cursed): everyone knows it now. Whoever called a human a mimic
   * lied; whoever called a mimic human may have been fooled by a guise; whoever stood up for a mimic is weighed by it.
   */
  function shown(target) {
    target.revealed = target.role === "mimic" ? "mimic" : "human";
    for (const person of people) person.known.set(target.index, target.revealed);
    for (const c of people) {
      for (const claim of c.claims.filter((one) => one.spell === "sight" && one.target === target.index)) {
        if (claim.result === "mimic" && target.revealed === "human" && !c.exposed && c.alive) {
          c.exposed = true;
          narrate(short(c) + "の看破はうそだった。" + nameOf(target) + "は人間だったのだから。", "air");
          for (const person of living()) if (!person.you && person !== c) warm(person, c, 3, "liar", "看破でうそをついた");
        } else if (claim.result === "human" && target.revealed === "mimic" && c.alive) {
          for (const person of living()) if (!person.you && person !== c) warm(person, c, 1, "missed:" + target.index, "ミミックを人間だと言っていた");
        }
      }
    }
    for (const person of living()) {
      if (person.you) continue;
      for (const other of living()) {
        if (other === person) continue;
        if (target.revealed === "mimic" && target.covered.has(other.index)) warm(person, other, 0.9, "cover:" + target.index, "ミミックの" + nameOf(target) + "をかばっていた");
        if (target.revealed === "human" && target.named.has(other.index)) warm(person, other, 0.35, "named:" + target.index, "人間の" + nameOf(target) + "を疑っていた");
      }
    }
  }

  // ---- Showing it (as the werewolf game does) -----------------------------------------------------------------------

  let fast = stored(FAST) === "1";
  let queue = [];
  let pumping = false;
  let waiting = [];
  let page = null;

  /** Shows a log item or makes a change to the table; how long to wait before the next. */
  function reveal(entry) {
    const run = (change) => {
      try {
        change?.();
      } catch (failure) {
        console.error(failure);
      }
    };
    if (typeof entry === "function") {
      run(entry);
      return 0;
    }
    entry.hidden = false;
    run(entry.cue);
    log.scrollTop = log.scrollHeight;
    return entry.classList.contains("vote") ? 280 : entry.classList.contains("wolf-say") ? 1100 : 650;
  }

  function pump() {
    if (pumping) return;
    pumping = true;
    const step = () => {
      while (queue.length) {
        const wait = reveal(queue.shift());
        if (wait && !fast) {
          setTimeout(step, wait);
          return;
        }
      }
      pumping = false;
      waiting.splice(0).forEach((resolve) => resolve());
    };
    step();
  }

  const drained = () => (queue.length || pumping ? new Promise((resolve) => waiting.push(resolve)) : Promise.resolve());

  function post(item, change = null) {
    if (!page) chapter("");
    item.hidden = true;
    item.cue = change;
    page.append(item);
    queue.push(item);
    pump();
  }

  function cue(change) {
    queue.push(change);
    pump();
  }

  /** A new part of the log (a day), the earlier ones folded away. */
  function chapter(title) {
    for (const open of log.querySelectorAll("details[open]")) open.open = false;
    const item = document.createElement("li");
    item.className = "wolf-day";
    const fold = document.createElement("details");
    fold.open = true;
    const summary = document.createElement("summary");
    summary.textContent = title;
    page = document.createElement("ol");
    fold.append(summary, page);
    item.append(fold);
    log.append(item);
  }

  /** A line of the story; the big ones (a new phase, a death, what only the traveller learns) also at the centre. */
  function narrate(text, kind = "", change = null) {
    const item = document.createElement("li");
    item.className = "wolf-narr" + (kind ? " " + kind : "");
    item.textContent = text;
    const told = /^(big|death|secret)$/.test(kind);
    post(item, () => {
      if (told && headline) {
        headline.textContent = text;
        headline.className = "wolf-headline " + kind;
      }
      change?.();
    });
  }

  /** A line said by person, with the reason they give (why): a bubble over their seat and an arrow to whom it is about. */
  function speak(person, text, why = null, target = null, tone = "point") {
    const item = document.createElement("li");
    item.className = "wolf-say" + (person.you ? " you" : "");
    const figure = person.seat.querySelector(".guild-sprite, .wolf-you-mark");
    if (figure) {
      const face = document.createElement("span");
      face.className = "wolf-face";
      face.append(figure.cloneNode(true));
      item.append(face);
    }
    const name = document.createElement("b");
    name.textContent = person.name;
    const line = document.createElement("span");
    line.textContent = text;
    item.append(name, line);
    if (why) {
      const note = document.createElement("small");
      note.className = "wolf-why";
      note.textContent = "理由：" + why;
      item.append(note);
    }
    post(item, () => {
      bubble(person, text);
      clearArrows("point", "cover");
      if (target) arrow(person, target, tone);
    });
  }

  /** A resident's line of this kind about target, one of its wordings. */
  function say(person, kind, target = null, why = null) {
    let text = pick((person.lines[kind] || "").split("\n")) || "……";
    if (target) {
      const name = nameOf(target);
      text = text.replaceAll("{t}さん", target.you ? name : name + "さん").replaceAll("{t}", name);
    }
    speak(person, text, why, target, kind === "cover" ? "cover" : "point");
  }

  /** The seats around the table: the traveller nearest (at the bottom), the residents round from their left. */
  function place() {
    const order = [youSeat, ...seats];
    order.forEach((seat, i) => {
      const angle = Math.PI / 2 + (i * 2 * Math.PI) / order.length;
      const x = 50 + 40 * Math.cos(angle);
      const y = 50 + 37 * Math.sin(angle);
      seat.style.left = x.toFixed(1) + "%";
      seat.style.top = y.toFixed(1) + "%";
      seat.dataset.side = x < 36 ? "left" : x > 64 ? "right" : "middle";
      seat.dataset.half = y < 50 ? "top" : "bottom";
    });
  }

  /** What part of the day it is, at the centre: mode day, vote, night or end. The table is cleared for it. */
  function phase(title, mode) {
    cue(() => {
      clearTable();
      if (phaseNote) phaseNote.textContent = title;
      if (table) table.className = "wolf-table " + mode;
    });
  }

  function clearTable() {
    clearArrows();
    bubble(null);
    for (const person of people) showVotes(person, 0);
    if (headline) headline.textContent = "";
  }

  function bubble(person, text = "") {
    for (const seat of [...seats, youSeat]) {
      seat.classList.remove("speaking");
      seat.querySelector(".wolf-bubble")?.remove();
    }
    if (!person) return;
    const note = document.createElement("span");
    note.className = "wolf-bubble";
    note.textContent = text;
    person.seat.append(note);
    person.seat.classList.add("speaking");
  }

  /** An arrow across the table from one seat to another, of a tone: vote, point or cover. */
  function arrow(from, to, tone) {
    if (!arrows || !table || from === to) return;
    const frame = table.getBoundingClientRect();
    if (!frame.width) return;
    arrows.setAttribute("viewBox", "0 0 " + frame.width.toFixed(0) + " " + frame.height.toFixed(0));
    const at = (person) => {
      const figure = (person.seat.querySelector(".guild-sprite, .wolf-you-mark") || person.seat).getBoundingClientRect();
      return [figure.left + figure.width / 2 - frame.left, figure.top + figure.height / 2 - frame.top];
    };
    const [x1, y1] = at(from);
    const [x2, y2] = at(to);
    const length = Math.hypot(x2 - x1, y2 - y1);
    if (length < 70) return;
    const ux = (x2 - x1) / length;
    const uy = (y2 - y1) / length;
    const bow = Math.min(36, length * 0.12);
    const [sx, sy, ex, ey] = [x1 + ux * 28, y1 + uy * 28, x2 - ux * 32, y2 - uy * 32];
    const [cx, cy] = [(sx + ex) / 2 - uy * bow, (sy + ey) / 2 + ux * bow];
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", [["M", sx, sy], ["Q", cx, cy], ["", ex, ey]].map(([c, x, y]) => c + x.toFixed(1) + " " + y.toFixed(1)).join(" "));
    path.setAttribute("class", "wolf-arrow " + tone);
    path.setAttribute("marker-end", "url(#wolf-head-" + tone + ")");
    arrows.append(path);
  }

  function clearArrows(...tones) {
    for (const path of [...(arrows?.querySelectorAll(".wolf-arrow") || [])]) {
      if (!tones.length || tones.some((tone) => path.classList.contains(tone))) path.remove();
    }
  }

  function showVotes(person, count) {
    let badge = person.seat.querySelector(".wolf-votes");
    if (!count) {
      badge?.remove();
      return;
    }
    if (!badge) {
      badge = document.createElement("span");
      badge.className = "wolf-votes";
      person.seat.append(badge);
    }
    badge.textContent = count + "票";
  }

  /** The badges under a seat: what the sight results said of them (who said it, ○ human, ● mimic). */
  function badges(person) {
    const out = [];
    if (person.claims.some((claim) => claim.spell === "sight")) out.push({ text: "看", kind: "claim", label: "看破の結果を話した" });
    for (const teller of people) {
      for (const claim of teller.claims.filter((one) => one.spell === "sight" && one.target === person.index)) {
        out.push({ text: short(teller).slice(0, 1) + (claim.result === "mimic" ? "●" : "○"), kind: claim.result === "mimic" ? "black" : "white",
          label: short(teller) + "の看破：" + (claim.result === "mimic" ? "ミミック" : "人間") });
      }
    }
    return out;
  }

  function tag(person, text) {
    const mark = person.seat.querySelector("[data-wolf-tag]");
    if (mark) mark.textContent = text;
  }

  /** The traveller's own note on a seat: none, white, black or grey, changed by pressing it. */
  function memoButton(person) {
    if (person.you) return;
    let button = person.seat.querySelector("[data-wolf-memo]");
    if (!button) {
      button = document.createElement("button");
      button.type = "button";
      button.className = "wolf-memo";
      button.dataset.wolfMemo = "";
      button.addEventListener("click", () => {
        const owner = people.find((other) => other.seat === person.seat);
        owner.memo = (owner.memo + 1) % MEMOS.length;
        showMemo(owner);
      });
      person.seat.append(button);
    }
    showMemo(person);
  }

  function showMemo(person) {
    const button = person.seat.querySelector("[data-wolf-memo]");
    if (!button) return;
    button.textContent = MEMOS[person.memo] || "メモ";
    button.className = "wolf-memo" + (person.memo ? " m" + person.memo : "");
    button.setAttribute("aria-label", person.name + "のメモ：" + (MEMOS[person.memo] || "なし"));
  }

  /** How many of the living residents would name each one right now. */
  function mood() {
    const counts = new Map();
    for (const person of living().filter((npc) => !npc.you)) {
      const target = choice(person);
      if (target) counts.set(target, (counts.get(target) || 0) + 1);
    }
    return counts;
  }

  /** The seats, the traveller's mana, the places and the board as the traveller sees them now; shown in turn. */
  function mark(counts = null) {
    const self = you();
    const looks = people.map((person) => {
      const known = self.known.get(person.index);
      const classes = { dead: !person.alive, "wolf-mark": person !== self && known === "mimic", "human-mark": person !== self && known === "human" };
      if (counts || !person.alive) {
        const heat = person.alive ? counts.get(person) || 0 : 0;
        Object.assign(classes, { hot: heat >= 2, warm: heat === 1 });
      }
      const words = person.alive ? (today()?.places.get(person) ? placeOf(today().places.get(person)).name : "")
        : (person.diedBy === "vote" ? "追放" : "呪殺") + "・" + (person.revealed === "mimic" ? "ミミック" : "人間");
      return { person, classes, words, badges: badges(person) };
    });
    const line = (day === 0 ? "迷宮の入口" : day + "日目") + " ・ あなたは" + ROLES[self.role] + (self.alive ? "" : "（脱落）")
      + " ・ 残り " + living().length + "人（ミミック残り" + mimics().length + "体）";
    const sheet = drawBoard();
    const mana = drawMana();
    const places = drawMap();
    cue(() => {
      for (const look of looks) show(look);
      status.textContent = line;
      board?.replaceChildren(...(sheet ? [sheet] : []));
      manaNote?.replaceChildren(...mana);
      map?.replaceChildren(...places);
    });
  }

  function show({ person, classes, words, badges }) {
    for (const [name, on] of Object.entries(classes)) person.seat.classList.toggle(name, on);
    tag(person, words);
    person.seat.querySelector("[data-wolf-marks]")?.replaceChildren(...badges.map(({ text, kind, label }) => {
      const badge = document.createElement("span");
      badge.className = "wolf-badge " + kind;
      badge.textContent = text;
      badge.title = label;
      return badge;
    }));
  }

  const chip = (color, text) => {
    const item = document.createElement("span");
    item.className = "mana mana-" + color;
    item.textContent = text;
    return item;
  };

  /** The traveller's mana, and the spells it is enough for. */
  function drawMana() {
    const self = you();
    const title = document.createElement("b");
    title.textContent = "あなたのマナ";
    const chips = Object.entries(COLORS).map(([key, name]) => chip(key, name + " " + self.mana[key]));
    const castable = Object.keys(SPELLS).filter((key) => can(self, key)).map((key) => SPELLS[key].name);
    const note = document.createElement("small");
    note.textContent = castable.length ? "唱えられる魔法：" + castable.join("・") : "いまは唱えられる魔法がない";
    return [title, ...chips, note];
  }

  /** The five places, their colours, and who went to each today (seen by all). */
  function drawMap() {
    const went = today()?.places || new Map();
    return PLACES.map((spot) => {
      const card = document.createElement("div");
      card.className = "mimic-place";
      const name = document.createElement("b");
      name.textContent = spot.name;
      const colors = document.createElement("span");
      colors.append(...spot.colors.map((color) => chip(color, COLORS[color])));
      const who = document.createElement("small");
      const there = [...went].filter(([, key]) => key === spot.key).map(([person]) => short(person));
      who.textContent = there.length ? there.join("・") : "だれも来ていない";
      card.append(name, colors, who);
      return card;
    });
  }

  /** What each one said their spells showed, and who has left the party how (none when there is nothing yet). */
  function drawBoard() {
    const tellers = people.filter((person) => person.claims.length);
    const gone = people.filter((person) => !person.alive);
    if (!tellers.length && !gone.length) return null;
    const list = document.createElement("dl");
    for (const person of tellers) {
      const term = document.createElement("dt");
      term.textContent = short(person) + (person.exposed ? "（うそが発覚）" : "");
      const said = document.createElement("dd");
      for (const claim of person.claims) {
        const item = document.createElement("span");
        const black = claim.result === "mimic" || claim.result === "black";
        item.className = "wolf-chip " + (black ? "black" : "white");
        item.textContent = claim.day + "日目 " + SPELLS[claim.spell].name + "：" + short(people[claim.target])
          + (claim.spell === "sight" ? (black ? " ●ミミック" : " ○人間") : (black ? " 黒魔法あり" : " 黒魔法なし"));
        said.append(item);
      }
      list.append(term, said);
    }
    if (gone.length) {
      const term = document.createElement("dt");
      term.textContent = "いなくなった人";
      const said = document.createElement("dd");
      said.textContent = gone.map((person) => short(person) + "（" + (person.diedBy === "vote" ? "追放" : "呪殺") + "・"
        + (person.revealed === "mimic" ? "ミミック" : "人間") + "）").join("、");
      list.append(term, said);
    }
    return list;
  }

  /** Buttons for the traveller, once every line so far is shown; resolves with the value of the one pressed. */
  async function ask(prompt, options) {
    await drained();
    return new Promise((resolve) => {
      actions.replaceChildren();
      if (prompt) {
        const text = document.createElement("p");
        text.className = "wolf-prompt";
        text.textContent = prompt;
        actions.append(text);
      }
      const row = document.createElement("div");
      row.className = "wolf-buttons";
      for (const option of options) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "meal-button" + (option.quiet ? " quiet" : "");
        button.textContent = option.label;
        button.addEventListener("click", () => {
          actions.replaceChildren();
          resolve(option.value);
        });
        row.append(button);
      }
      actions.append(row);
      row.querySelector("button")?.focus({ preventScroll: true });
    });
  }

  const next = (label = "次へ") => ask(null, [{ label, value: true }]);
  const pickPerson = (prompt, list, back = false) =>
    ask(prompt, [...list.map((person) => ({ label: short(person), value: person })), ...(back ? [{ label: "やめる", value: null, quiet: true }] : [])]);

  if (speed) {
    const label = () => { speed.textContent = fast ? "ゆっくり表示" : "すぐ表示"; };
    speed.hidden = false;
    label();
    speed.addEventListener("click", () => {
      fast = !fast;
      store(FAST, fast ? "1" : "0");
      label();
    });
  }

  // ---- The record (this browser only) -----------------------------------------------------------------------------

  function readRecord() {
    try {
      const record = JSON.parse(stored(RECORD));
      if (record && typeof record.games === "number") return record;
    } catch { /* none yet */ }
    return { games: 0, wins: 0, roles: {} };
  }

  function showRecord(record = readRecord()) {
    if (!recordNote) return;
    if (!record.games) {
      recordNote.textContent = "";
      return;
    }
    const roles = Object.entries(record.roles).map(([role, [games, wins]]) => (ROLES[role] || role) + " " + wins + "/" + games).join("・");
    recordNote.textContent = "戦績（このブラウザ）：" + record.games + "戦" + record.wins + "勝（" + roles + "）";
  }

  function keepRecord(won) {
    const record = readRecord();
    const self = you();
    record.games++;
    if (won) record.wins++;
    const [games, wins] = record.roles[self.role] || [0, 0];
    record.roles[self.role] = [games + 1, wins + (won ? 1 : 0)];
    store(RECORD, JSON.stringify(record));
    showRecord(record);
  }

  // ---- The game -----------------------------------------------------------------------------------------------------

  async function play() {
    deal();
    const self = you();
    showRecord();
    phase("迷宮の入口", "night");
    mark();
    narrate("気がつくと、" + people.length + "人は石造りの迷宮にいた。出口の扉には、こう刻まれている。「潜む者すべてが倒れるまで、この扉は開かない」", "big");
    narrate("この中にミミックが2体、そしてミミックに味方する狂人が1人いる。昼は場所を巡ってマナを集め、焚き火で話し合って1人を追い出し、夜は魔法を唱える。");
    let role = "あなたは【" + ROLES[self.role] + "】だ。";
    if (self.role === "mimic") {
      const partner = people.find((person) => person !== self && person.role === "mimic");
      role += "仲間のミミックは" + partner.name + "。正体を隠し、呪殺で人間を減らせ。人間と同じ数になれば勝ちだ。";
    }
    if (self.role === "madman") role += "人間だが、ミミックの勝ちを願っている。ミミックが誰かは知らない。囁きで探し当てれば、手を組める。";
    if (self.role === "human") role += "マナを集めて看破を唱え、ミミックを2体とも追い出そう。";
    narrate(role, "secret");
    narrate("あなたの手元には、" + manaText(self.mana) + "のマナがある。", "secret");
    await next("探索に出る");
    for (day = 1; ; day++) {
      story.push({ day, places: new Map(), claims: [], named: [], covers: [], exiled: null, votes: 0, runoff: false, yourVote: null, casts: [], killed: null, blocked: null });
      chapter(day + "日目");
      await explore();
      if (await fire()) return;
      if (await night()) return;
    }
  }

  /** What self wants to cast tonight, and so which colours to look for. */
  function goal(self) {
    if (self.role === "mimic") {
      if (!mimics().some((mimic) => can(mimic, "curse"))) return "curse";
      return Math.random() < 0.5 ? "guise" : "seal";
    }
    if (self.role === "madman") return self.allies.size ? "seal" : Math.random() < 0.75 ? "whisper" : "sight";
    return Math.random() < 0.65 ? "sight" : Math.random() < 0.5 ? "veil" : "scent";
  }

  /** Mana gathered at a place: two (now and then three) of its colours, by chance. */
  function gather(person, spot) {
    const got = noMana();
    for (let i = 2 + (Math.random() < 0.25 ? 1 : 0); i > 0; i--) got[pick(spot.colors)]++;
    for (const color of Object.keys(got)) person.mana[color] += got[color];
    person.history.push(spot.key);
    return got;
  }

  async function explore() {
    const self = you();
    phase(day + "日目の昼・探索", "day");
    narrate(day + "日目の昼。それぞれが迷宮へ散っていく。", "big");
    const went = today().places;
    if (self.alive) {
      mark();
      const spot = await ask("どこへ行く？（あなたのマナ：" + manaText(self.mana) + "）",
        PLACES.map((one) => ({ label: one.name + "（" + one.colors.map((color) => COLORS[color]).join("・") + "）", value: one })));
      went.set(self, spot.key);
    }
    // Each resident goes where the colours they lack are, a little drawn to where someone dear already went.
    for (const person of shuffle(living().filter((npc) => !npc.you))) {
      person.goal = goal(person);
      const need = noMana();
      for (const color of SPELLS[person.goal].cost) need[color]++;
      for (const color of Object.keys(need)) need[color] = Math.max(0, need[color] - person.mana[color]);
      const spot = top(PLACES.map((one) => [one, one.colors.reduce((total, color) => total + need[color], 0) + Math.random() * 0.9
        + [...went].filter(([other, key]) => key === one.key && fond(person, other) > 0.4).length * 0.4]));
      went.set(person, spot.key);
    }
    for (const [person, key] of went) {
      const got = gather(person, placeOf(key));
      if (person.you) narrate(placeOf(key).name + "で、" + manaText(got) + "のマナを手に入れた。", "secret");
    }
    narrate("行き先：" + PLACES.map((one) => one.name + "＝" + ([...went].filter(([, key]) => key === one.key).map(([person]) => short(person)).join("・") || "なし")).join("、"), "air");
    mark();
  }

  /** A spell result said aloud by person, true or not. */
  function claim(person, spell, target, result) {
    if (!person.claims.length) {
      for (const other of people) other.prior[person.index] = clamp(0.4 + Math.random() * 0.3 + 0.25 * fond(other, person), 0.05, 0.9);
    }
    person.claims.push({ day, spell, target: target.index, result });
    today().claims.push({ by: person, spell, target, result });
    const name = nameOf(target);
    const text = spell === "sight" ? "昨夜、" + name + "を看破した。……" + (result === "mimic" ? "ミミックだ！" : "人間だった。")
      : "昨夜、" + name + "に残り香をかけた。……" + (result === "black" ? "黒魔法の気配がした！" : "黒魔法の気配はなかった。");
    const black = result === "mimic" || result === "black";
    speak(person, text, null, target, black ? "point" : "cover");
    if (black) heard(person, target, spell === "sight" ? 0.5 : 0.3);
  }

  /**
   * A made-up sight result from one on the mimics' side. A mimic calls a partner the room is set on human, or calls a
   * human (one who has named a mimic, else the room's favourite) a mimic; the madman, without allies, calls a mimic one
   * who reads others (a rival) or anyone; with allies, as a mimic would.
   */
  function lie(liar) {
    const told = (person) => liar.claims.some((claim) => claim.target === person.index);
    const pool = others(liar).filter((person) => !told(person) && !person.revealed);
    if (!pool.length) return null;
    const partner = pool.find((person) => liar.allies.has(person.index) && roomOn(person) > 1.2);
    if (partner && Math.random() < 0.5) return { target: partner, result: "human" };
    const humans = pool.filter((person) => !liar.allies.has(person.index) && (liar.role !== "mimic" || person.role !== "mimic"));
    if (!humans.length) return null;
    const target = top(humans.map((person) => [person, Math.random() + person.threat * 0.8 + (person.claims.length ? 0.8 : 0)
      + (liar.allies.size ? roomOn(person) * 0.3 : 0) - 0.5 * fond(liar, person)]));
    return { target, result: Math.random() < 0.8 ? "mimic" : "human" };
  }

  async function fire() {
    const self = you();
    phase(day + "日目の夕・焚き火", "day");
    narrate("焚き火を囲み、" + day + "日目の話し合いが始まる。", "big");
    const npcs = shuffle(living().filter((person) => !person.you));
    if (day === 1) for (const person of npcs.slice(0, 2)) say(person, "greet");
    const speakers = [];
    // What last night's spells showed: the adventurers tell it as it was; those on the mimics' side may make one up.
    for (const person of npcs) {
      if (person.role === "human") {
        for (const found of person.fresh) claim(person, found.spell, people[found.target], found.result);
        if (person.fresh.length) speakers.push(person);
      } else if (!person.exposed && day > 1 && Math.random() < (person.role === "madman" ? 0.55 : 0.3)) {
        const made = lie(person);
        if (made) {
          claim(person, "sight", made.target, made.result);
          speakers.push(person);
        }
      }
      person.fresh = [];
    }
    mark();

    if (self.alive) await yourTurn();
    else await next("話し合いを見守る");
    self.fresh = [];

    // Everyone else names whom they suspect most, and why.
    const named = new Map();
    for (const person of npcs.filter((npc) => !speakers.includes(npc))) {
      const target = choice(person);
      if (!target) continue;
      say(person, "suspect", target, reasonFor(person, target));
      heard(person, target);
      person.lastSuspect = target.index;
      today().named.push({ by: person, target });
      named.set(target, (named.get(target) || 0) + 1);
    }
    // Someone who cares for one who was named stands up for them (at most two a day); a mimic may for its partner.
    let covers = 0;
    for (const [target] of [...named].sort((a, b) => b[1] - a[1])) {
      if (covers >= 2) break;
      const friend = top(npcs.filter((npc) => npc !== target && !target.named.has(npc.index))
        .map((npc) => [npc, fond(npc, target) + (npc.allies.has(target.index) ? 0.8 : 0) + Math.random() * 0.3]).concat([[null, 0.75]]));
      if (!friend) continue;
      say(friend, "cover", target);
      vouched(friend, target);
      covers++;
    }
    // Whoever is named twice (or by the traveller) answers for themselves.
    for (const [target, count] of named) {
      if (target.you || count < 2 && !target.namedByYou) continue;
      say(target, "defend");
      for (const person of people) if (person !== target) person.heat[target.index] = Math.max(0, person.heat[target.index] - 0.3);
    }
    for (const person of people) person.namedByYou = false;
    const after = mood();
    mark(after);
    const ranked = [...after].sort((a, b) => b[1] - a[1]).slice(0, 2);
    if (ranked.length) narrate("場の空気：" + ranked.map(([target, count]) => short(target) + "に" + count + "人").join("、") + "の疑いが向いている。", "air");
    return vote();
  }

  async function yourTurn() {
    const self = you();
    for (;;) {
      const action = await ask("あなたの番だ。どうする？", [
        { label: "看破の結果を伝える", value: "sight" },
        { label: "残り香の結果を伝える", value: "scent" },
        { label: "怪しい人を名指しする", value: "suspect" },
        { label: "誰かをかばう", value: "cover" },
        { label: "黙って聞いている", value: "quiet", quiet: true },
      ]);
      if (action === "quiet") {
        narrate("あなたは黙って皆の話に耳を傾けた。");
        return;
      }
      if (action === "suspect" || action === "cover") {
        const target = await pickPerson(action === "suspect" ? "誰を名指しする？" : "誰をかばう？", others(self), true);
        if (!target) continue;
        if (action === "suspect") {
          speak(self, target.name + "が怪しいと思う。", null, target);
          heard(self, target);
          today().named.push({ by: self, target });
          target.namedByYou = true;
        } else {
          speak(self, target.name + "はミミックじゃないと思う。", null, target, "cover");
          vouched(self, target);
        }
        return;
      }
      // A result is told as the traveller chooses: what they saw, or not.
      const seen = self.fresh.filter((found) => found.spell === action);
      const hint = seen.length ? "（昨夜の結果：" + seen.map((found) => short(people[found.target]) + "は" + (found.result === "mimic" ? "ミミック" : found.result === "black" ? "黒魔法を使った"
        : found.result === "human" ? "人間" : "黒魔法を使っていない")).join("、") + "）" : "（昨夜は唱えていない。うその結果も言える）";
      const target = await pickPerson("誰の結果を伝える？" + hint, others(self).filter((person) => !person.revealed), true);
      if (!target) continue;
      const result = await ask(target.name + "の結果は？", action === "sight"
        ? [{ label: "人間だった", value: "human" }, { label: "ミミックだった", value: "mimic" }, { label: "やめる", value: null, quiet: true }]
        : [{ label: "黒魔法の気配はなかった", value: "white" }, { label: "黒魔法の気配がした", value: "black" }, { label: "やめる", value: null, quiet: true }]);
      if (!result) continue;
      claim(self, action, target, result);
      self.fresh = [];
      mark();
      return;
    }
  }

  /** One round of votes for someone in pool (not oneself); the tally and who voted whom. */
  async function ballot(pool, prompt) {
    const self = you();
    const ballots = new Map();
    const tally = new Map();
    const cast = (voter, target) => {
      ballots.set(voter, target);
      tally.set(target, (tally.get(target) || 0) + 1);
      voter.votedFor.push(target.index);
    };
    const candidates = (voter) => pool.filter((person) => person !== voter);
    if (self.alive && candidates(self).length) {
      const target = await pickPerson(prompt, candidates(self));
      cast(self, target);
      today().yourVote = target;
    }
    for (const person of shuffle(living().filter((npc) => !npc.you))) {
      const target = choice(person, candidates(person), tally, ballots);
      if (target) cast(person, target);
    }
    cue(() => {
      clearArrows();
      for (const person of people) showVotes(person, 0);
    });
    const counted = new Map();
    for (const [voter, target] of ballots) {
      const count = (counted.get(target) || 0) + 1;
      counted.set(target, count);
      narrate(short(voter) + " → " + short(target), "vote", () => {
        arrow(voter, target, "vote");
        showVotes(target, count);
      });
    }
    return { tally, ballots };
  }

  /** The vote, with a run-off between those tied; the one sent out shows what they were. True when the game ended. */
  async function vote() {
    phase(day + "日目の投票", "vote");
    narrate("投票の時間だ。迷宮に置いていく1人を決める。", "big");
    let { tally, ballots } = await ballot(living(), "誰に投票する？");
    let most = Math.max(...tally.values());
    let tied = [...tally].filter(([, count]) => count === most).map(([target]) => target);
    if (tied.length > 1) {
      today().runoff = true;
      narrate(tied.map((person) => short(person)).join("と") + "が" + most + "票で並んだ。決選投票だ。", "big");
      for (const person of tied) if (!person.you) say(person, "defend");
      ({ tally, ballots } = await ballot(tied, "決選投票。誰に入れる？"));
      most = Math.max(...tally.values());
      tied = [...tally].filter(([, count]) => count === most).map(([target]) => target);
      if (tied.length > 1) narrate("それでも決まらず、くじ引きになった。");
    }
    const exiled = pick(tied);
    const voiced = [...ballots].find(([voter, target]) => target === exiled && !voter.you);
    if (voiced) say(voiced[0], "vote", exiled);
    exiled.alive = false;
    exiled.diedBy = "vote";
    today().exiled = exiled;
    today().votes = most;
    narrate(nameOf(exiled) + "は" + most + "票を集め、迷宮の壁に呑まれた。……その姿は" + (exiled.role === "mimic" ? "、ミミックだった！" : "、人間のままだった。"), "death");
    shown(exiled);
    mark();
    return finish() || (await next("夜になる"), false);
  }

  /** Whom the mimics curse tonight: one who reads or names them, more than anyone else; one they are fond of, less. */
  function prey() {
    const pool = living().filter((person) => person.role !== "mimic" && !mimics().some((mimic) => mimic.allies.has(person.index)));
    if (!pool.length) return null;
    return draw(pool.map((person) => [person, Math.max(0.2, 1 + person.threat * 1.2
      + 2 * person.claims.filter((claim) => claim.spell === "sight" && claim.result === "mimic" && people[claim.target].role === "mimic").length
      + (person.claims.length ? 1.2 : 0) - mimics().reduce((total, mimic) => total + 0.4 * fond(mimic, person), 0))]));
  }

  /** What a resident casts tonight ({ spell, target }), or null. The mimics' curse is decided by the pair (below). */
  function intent(self) {
    const unknown = others(self).filter((person) => !self.known.has(person.index));
    const suspect = (pool) => top(pool.map((target) => [target, suspicion(self, target) + Math.random()]));
    const reader = (pool) => top(pool.map((target) => [target, target.claims.filter((claim) => claim.spell === "sight").length * 1.5 + Math.random()]));
    if (self.role === "madman") {
      if (!self.allies.size && can(self, "whisper") && unknown.length) return { spell: "whisper", target: suspect(unknown) };
      const foes = others(self).filter((person) => !self.allies.has(person.index));
      if (can(self, "seal") && foes.length && Math.random() < 0.7) return { spell: "seal", target: reader(foes) };
      if (can(self, "sight") && unknown.length && Math.random() < 0.5) return { spell: "sight", target: suspect(unknown) };
      return null;
    }
    if (self.role === "mimic") {
      if (!self.guised && can(self, "guise") && (roomOn(self) > 1 || Math.random() < 0.3)) return { spell: "guise", target: self };
      const foes = others(self).filter((person) => person.role !== "mimic" && !self.allies.has(person.index));
      if (can(self, "seal") && foes.length && Math.random() < 0.6) return { spell: "seal", target: reader(foes) };
      return null;
    }
    if (can(self, "sight") && unknown.length) return { spell: "sight", target: suspect(unknown) };
    if (can(self, "veil") && Math.random() < 0.7) {
      const target = top(others(self).map((person) => [person, person.claims.filter((claim) => claim.spell === "sight").length * trust(self, person) * 2
        + 0.5 * fond(self, person) + Math.random()]));
      if (target) return { spell: "veil", target };
    }
    if (can(self, "scent") && unknown.length) return { spell: "scent", target: suspect(unknown) };
    return null;
  }

  /** The traveller's spell tonight: { spell, target } or null. */
  async function yourSpell() {
    const self = you();
    const castable = Object.keys(SPELLS).filter((key) => can(self, key));
    const partner = mimics().find((mimic) => mimic !== self);
    let note = "あなたのマナ：" + manaText(self.mana);
    if (self.role === "mimic" && partner && !partner.you) note += can(partner, "curse") ? "。仲間の" + partner.name + "は呪殺を唱えられる" : "。仲間の" + partner.name + "は呪殺のマナが足りない";
    for (;;) {
      const key = await ask("今夜、どの魔法を唱える？（" + note + "）", [
        ...castable.map((one) => ({ label: SPELLS[one].name + "（" + SPELLS[one].cost.map((color) => COLORS[color]).join("＋") + "）：" + SPELLS[one].about, value: one })),
        { label: "何もしない", value: null, quiet: true },
      ]);
      if (!key) return null;
      if (SPELLS[key].self) return { spell: key, target: self };
      const pool = others(self).filter((person) => key !== "curse" || person.role !== "mimic");
      const target = await pickPerson(SPELLS[key].name + "を誰に？", pool, true);
      if (target) return { spell: key, target };
    }
  }

  /** The night: everyone may cast one spell; seals first, then guises and veils, the curse, and what spells show. */
  async function night() {
    const self = you();
    phase(day + "日目の夜", "night");
    narrate("迷宮に" + day + "日目の夜が来た。それぞれが、ひそかに呪文を唱える。", "big");
    const casts = [];
    if (self.alive) {
      const mine = await yourSpell();
      if (mine) casts.push({ by: self, ...mine });
    }
    for (const person of living().filter((npc) => !npc.you)) {
      const plan = intent(person);
      if (plan) casts.push({ by: person, ...plan });
    }
    // The mimics curse one between them: the traveller's if they cast it, else one of the residents who can.
    if (!casts.some((one) => one.spell === "curse")) {
      const caster = shuffle(mimics().filter((mimic) => !mimic.you && can(mimic, "curse")))[0];
      const target = caster && prey();
      if (target) {
        const planned = casts.findIndex((one) => one.by === caster);
        if (planned >= 0) casts.splice(planned, 1);
        casts.push({ by: caster, spell: "curse", target });
      }
    }
    for (const one of casts) pay(one.by, one.spell);
    today().casts = casts;
    const sealed = new Set(casts.filter((one) => one.spell === "seal").map((one) => one.target));
    const black = new Set(casts.filter((one) => SPELLS[one.spell].black).map((one) => one.by));
    const active = casts.filter((one) => one.spell === "seal" || !sealed.has(one.by));
    if (sealed.has(self) && casts.some((one) => one.by === self && one.spell !== "seal")) narrate("唱えた呪文が、何かにかき消された。", "secret");
    for (const one of active.filter((cast) => cast.spell === "guise")) one.by.guised = true;
    const veiled = new Set(active.filter((one) => one.spell === "veil").map((one) => one.target));
    let victim = null;
    for (const one of active.filter((cast) => cast.spell === "curse")) {
      if (veiled.has(one.target)) today().blocked = one.target;
      else victim = one.target;
    }
    for (const one of active.filter((cast) => cast.spell === "sight")) {
      const fooled = one.target.role === "mimic" && one.target.guised;
      const result = one.target.role === "mimic" && !fooled ? "mimic" : "human";
      if (fooled) one.target.guised = false;
      one.by.fresh.push({ spell: "sight", target: one.target.index, result });
      if (!one.by.known.has(one.target.index)) one.by.known.set(one.target.index, result);
      if (one.by.you) narrate(one.target.name + "を看破した。……" + (result === "mimic" ? "ミミックだ！" : "人間に見える。"), "secret");
    }
    for (const one of active.filter((cast) => cast.spell === "scent")) {
      const result = black.has(one.target) ? "black" : "white";
      one.by.fresh.push({ spell: "scent", target: one.target.index, result });
      if (one.by.you) narrate(one.target.name + "の残り香は……" + (result === "black" ? "黒魔法の気配がする！" : "黒魔法の気配はない。"), "secret");
    }
    for (const one of active.filter((cast) => cast.spell === "whisper")) {
      if (one.target.role !== "mimic") {
        if (one.by.you) narrate(one.target.name + "に囁いた。……何も返ってこない。ミミックではないようだ。", "secret");
        continue;
      }
      for (const mimic of people.filter((person) => person.role === "mimic")) {
        one.by.allies.add(mimic.index);
        one.by.known.set(mimic.index, "mimic");
        mimic.allies.add(one.by.index);
        mimic.known.set(one.by.index, "human");
      }
      if (one.by.you) narrate(one.target.name + "に囁いた。……低い声が返ってきた。ミミックだ。仲間は" + people.filter((person) => person.role === "mimic").map((person) => person.name).join("と") + "。", "secret");
      if (one.target.you || self.role === "mimic") narrate(nameOf(one.by) + "が囁いてきた。狂人だ。これで手を組める。", "secret");
    }
    mark();
    await next("朝を迎える");
    phase((day + 1) + "日目の朝", "day");
    narrate((day + 1) + "日目の朝。", "big");
    if (victim) {
      victim.alive = false;
      victim.diedBy = "curse";
      today().killed = victim;
      narrate("目を覚ますと、" + nameOf(victim) + "が冷たくなっていた。……呪殺だ。", "death");
      shown(victim);
      // Whom the cursed one suspected is taken more seriously; and whoever could have gathered the curse's colours lately.
      const suspect = victim.lastSuspect === null ? null : people[victim.lastSuspect];
      for (const person of living()) {
        if (person.you) continue;
        if (suspect?.alive && suspect !== person) warm(person, suspect, 0.45, "mourn:" + victim.index, "呪われた" + victim.name + "が疑っていた");
        for (const other of others(person)) {
          const colors = new Set(other.history.slice(-2).flatMap((key) => placeOf(key).colors));
          if (["fire", "water", "dark"].every((color) => colors.has(color))) warm(person, other, 0.3, "colors", "呪殺に要るマナを集められた");
        }
      }
    } else {
      narrate("誰も欠けずに朝を迎えた。" + (today().blocked ? "どこかで呪いが帳に阻まれたらしい。" : ""));
    }
    mark();
    return finish();
  }

  /** Ends the game when one side has won: shows every role, the review and the record, and offers another round. */
  function finish() {
    const left = mimics().length;
    const humans = living().length - left;
    if (left > 0 && left < humans) return false;
    const partyWon = left === 0;
    const self = you();
    const won = partyWon === (side(self.role) === "party");
    phase(partyWon ? "冒険者の勝利" : "ミミックの勝利", "end " + (partyWon ? "village" : "wolves"));
    narrate(partyWon ? "最後のミミックが壁に呑まれ、出口の扉が重い音を立てて開いた。冒険者の勝利！" : "灯りが消えた。迷宮に残ったのは、潜む者たちだけ……ミミックの勝利。", "big");
    narrate(won ? "あなたの陣営の勝ちだ。" : "あなたの陣営の負けだ。", "secret");
    narrate("正体：" + people.filter((person) => !person.you).map((person) => person.name + "＝" + ROLES[person.role]).join("、") + "。");
    const roles = people.map((person) => [person, ROLES[person.role] + (person.alive ? "" : person.diedBy === "vote" ? "・追放" : "・呪殺")]);
    cue(() => {
      for (const [person, words] of roles) {
        person.seat.classList.add("revealed", "role-" + person.role);
        person.seat.classList.remove("hot", "warm");
        tag(person, words);
      }
      status.textContent = "決着 ・ あなたは" + ROLES[self.role];
    });
    review();
    keepRecord(won);
    ask(null, [{ label: "もう一度挑む", value: true }]).then(play);
    return true;
  }

  // ---- The review ---------------------------------------------------------------------------------------------------

  const lab = (person) => short(person) + "〔" + ROLES[person.role] + "〕";

  /** Who went where, said what and cast what each day, with every role shown. */
  function review() {
    chapter("振り返り");
    for (const record of story) {
      narrate(record.day + "日目", "big review");
      narrate("行き先：" + [...record.places].map(([person, key]) => short(person) + "→" + placeOf(key).name).join("、"), "review");
      for (const said of record.claims) {
        const truth = said.spell === "sight" ? (said.target.role === "mimic" ? "mimic" : "human") : null;
        const lying = truth && said.result !== truth;
        narrate(SPELLS[said.spell].name + "の報告：" + lab(said.by) + "→" + lab(said.target) + "は" + ({ mimic: "ミミック", human: "人間", black: "黒魔法あり", white: "黒魔法なし" })[said.result]
          + (lying ? (said.result === "human" && said.target.role === "mimic" && said.by.role === "human" ? "（擬態に騙された）" : "（うそ）") : ""), "review");
      }
      if (record.named.length) narrate("名指し：" + record.named.map(({ by, target }) => short(by) + "→" + short(target)).join("、"), "review");
      if (record.exiled) narrate("追放：" + lab(record.exiled) + "（" + record.votes + "票" + (record.runoff ? "・決選投票" : "") + "）" + (record.yourVote ? " ・ あなたの票：" + lab(record.yourVote) : ""), "review");
      if (record.casts.length) narrate("夜の魔法：" + record.casts.map((one) => short(one.by) + "の" + SPELLS[one.spell].name + (SPELLS[one.spell].self ? "" : "→" + short(one.target))).join("、"), "review");
      if (record.killed) narrate("呪殺：" + lab(record.killed), "review");
      if (record.blocked) narrate("帳が呪いを防いだ：" + lab(record.blocked), "review");
    }
  }

  actions.replaceChildren();
  place();
  addEventListener("resize", () => clearArrows());
  play();
};
guildMimic();
