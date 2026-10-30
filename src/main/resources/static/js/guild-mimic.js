// 潜む者: a solo game of deduction in a labyrinth, with seven of the residents (fragments/guild-windows :: mimic,
// GuildController.mimic). No AI and nothing sent back.
//
// Eight are lost in a labyrinth that lets no one out while a mimic is inside: two of them are mimics, one is a madman
// (human, but on the mimics' side, without knowing who they are), the rest are adventurers. Each day they go out to
// one of five places and gather mana there (each place gives two of four colours, how much is chance); by the fire
// they say what their spells showed, name whom they suspect and vote one out (the labyrinth swallows them and shows
// what they were); at night everyone may cast one spell. White spells (sight, veil, scent) are anyone's; black ones
// are the mimics' (curse, guise, seal) and the madman's (seal, whisper). Every colour serves both sides.
//
// The game is the traveller's deduction. Everyone starts with no mana, where everyone went is seen by all, and by the
// fire the traveller may question a few of them each day: what mana they gathered, what they cast. Adventurers tell
// it as it was; the mimics and the madman cover their black spells with white ones and hide the colours a curse needs,
// carefully or not. A story whose mana does not add up to the spells it claims is a lie, and the traveller can hold it
// up to the room. The notebook (who went where, what each said) and the chart (who suspects whom and why, what each
// sight showed) are there to work it out.
//
// How a resident thinks follows the werewolf game (guild-werewolf.js): a suspicion of everyone else made of parts with
// reasons they can say, swayed by how they stand with each other in the guild (data-ties, data-bonds), by whose spell
// results they believe, and overridden by what they know for certain.
//
// What is seen: the labyrinth first-hand, like the guild's conversation scenes. The place's backdrop, those who are
// there standing in it (the one speaking lit), and a message box below that is read line by line.
const guildMimic = () => {
  const box = document.querySelector("[data-mimic]");
  if (!box || box.dataset.ready) return;
  box.dataset.ready = "1";
  const $ = (selector) => box.querySelector(selector);
  const seats = [...box.querySelectorAll("[data-mimic-player]")];
  const stage = $("[data-mz-stage]");
  const cast = $("[data-mz-cast]");
  const messageBox = $("[data-mz-box]");
  const nameTag = $("[data-mz-name]");
  const text = $("[data-mz-text]");
  const choices = $("[data-mz-choices]");
  const status = $("[data-mz-status]");
  const manaNote = $("[data-mz-mana]");
  const fx = $("[data-mz-fx]");
  const graph = $("[data-mz-graph]");
  const graphList = $("[data-mz-graph-list]");
  const note = $("[data-mz-note]");
  const log = $("[data-mz-log]");
  const recordNote = $("[data-mz-record]");
  const skipButton = $("[data-mz-skip]");
  const tabs = [...box.querySelectorAll("[data-mz-tab]")];
  if (seats.length < 7 || !stage || !cast || !messageBox || !text || !choices || !log) return;

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
  const WHITE = ["sight", "veil", "scent"];
  // The curse needs three colours no single place gives: hiding one of them is what a careful mimic does.
  const CURSE = ["fire", "water", "dark"];
  const QUESTIONS = 3;
  const BONDS = { friend: "友達", close_friend: "親友", companion: "仲間", lover: "恋人", spouse: "夫婦", rival: "宿敵", mentor: "師弟" };
  const BOND_WARMTH = { friend: 0.3, companion: 0.3, close_friend: 0.6, mentor: 0.4, lover: 0.9, spouse: 1, rival: -0.8 };
  // The residents' lines are their werewolf ones; these words make them fit the labyrinth.
  const REWORD = [["人狼", "ミミック"], ["村人", "人間"], ["この村", "この迷宮"], ["村が", "みんなが"], ["吊っても", "追い出しても"],
    ["吊ったら", "追い出したら"], ["吊るせば", "追い出せば"], ["吊る", "追い出す"]];
  const MEMOS = ["", "白", "黒", "灰"];
  const YOU = "旅の人";
  const RECORD = "guild-mimic-record";
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
  const el = (tag, className = "", content = "") => {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (content) node.textContent = content;
    return node;
  };

  // ---- How the residents stand with each other (from the guild) --------------------------------------------------

  const byId = new Map(seats.map((seat, index) => [seat.dataset.mimicPlayer, index]));
  const fondness = seats.map(() => seats.map(() => 0));
  const rivals = new Set();
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
  }
  // The traveller is a stranger to all of them: each sees them as they see the others on average.
  const usual = fondness.map((row, a) => row.reduce((total, value, b) => total + (a === b ? 0 : clamp(value, -1.5, 1.5)), 0) / Math.max(1, row.length - 1));
  const fond = (a, b) => (a.you ? 0 : b.you ? usual[a.index] : clamp(fondness[a.index][b.index], -1.5, 1.5));

  let people = [];
  let day = 0;
  let story = [];
  let marks = new Set();
  let focus = null;

  // ---- The party ----------------------------------------------------------------------------------------------------

  const voice = (data) => {
    const lines = {};
    for (const kind of ["greet", "suspect", "defend", "vote", "cover"]) {
      lines[kind] = REWORD.reduce((said, [from, to]) => said.replaceAll(from, to), data[kind] || "");
    }
    return lines;
  };
  const noMana = () => ({ light: 0, dark: 0, water: 0, fire: 0 });

  function deal() {
    const party = seats.map((seat) => ({ name: seat.dataset.name, figure: seat.querySelector(".guild-sprite"), you: false, lines: voice(seat.dataset) }));
    party.push({ name: "あなた", figure: null, you: true, lines: {} });
    const roles = shuffle(DEAL);
    people = party.map((person, index) => ({
      ...person, index, role: roles[index], alive: true, diedBy: null, diedOn: null, revealed: null, mana: noMana(), history: [],
      gains: [], spells: [], said: { gains: new Map(), spells: new Map() }, goal: null, known: new Map(), allies: new Set(),
      hunch: [], heat: [], why: [], prior: [], claims: [], fresh: [], exposed: false, guised: false, named: new Set(),
      covered: new Set(), lastSuspect: null, votedFor: [], memo: 0, threat: 0,
    }));
    for (const self of people) {
      self.known.set(self.index, self.role === "mimic" ? "mimic" : "human");
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
    marks = new Set();
    focus = null;
    log.replaceChildren();
    chapter("はじまり");
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
    return covers(person.mana, key);
  }

  /** Whether mana is enough for spell's cost. */
  function covers(mana, key) {
    const need = noMana();
    for (const color of SPELLS[key].cost) need[color]++;
    return Object.keys(need).every((color) => mana[color] >= need[color]);
  }

  function pay(person, key) {
    for (const color of SPELLS[key].cost) person.mana[color] = Math.max(0, person.mana[color] - 1);
  }

  // ---- What they say they did (testimony) ---------------------------------------------------------------------------

  /**
   * What person says they gathered on day d. An adventurer tells it as it was; one on the mimics' side often moves one
   * of the curse's colours to the place's other colour, to hide it (always a colour that place gives).
   */
  function toldGains(person, d) {
    if (person.said.gains.has(d)) return person.said.gains.get(d);
    const real = person.gains[d - 1] || noMana();
    const told = { ...real };
    if (person.role !== "human" && Math.random() < (person.role === "mimic" ? 0.6 : 0.35)) {
      const spot = placeOf(person.history[d - 1]);
      const hide = spot.colors.find((color) => CURSE.includes(color) && told[color] > 0);
      const other = spot.colors.find((color) => color !== hide);
      if (hide && other) {
        told[hide]--;
        told[other]++;
      }
    }
    person.said.gains.set(d, told);
    return told;
  }

  /**
   * The mana person's own story leaves them before night n's spell: what they say they gathered up to day n, less what
   * they say they cast on the nights before (a night they said nothing of counts as nothing cast). Null when they have
   * not said what they gathered on every one of those days (fill: make up what they would say instead).
   */
  function storyMana(person, n, fill = false) {
    const mana = noMana();
    for (let d = 1; d <= n; d++) {
      if (!person.said.gains.has(d) && !fill) return null;
      const got = toldGains(person, d);
      for (const color of Object.keys(mana)) mana[color] += got[color];
      if (d < n) {
        const spent = person.said.spells.get(d);
        if (spent?.spell) for (const color of SPELLS[spent.spell].cost) mana[color]--;
      }
    }
    return mana;
  }

  /** Whether person's story (filled in as they would tell it) leaves the mana for spell on night n. */
  const fits = (person, n, spell) => n >= 1 && covers(storyMana(person, n, true), spell);

  /**
   * What person says they cast on night n ({ spell: null } for nothing). An adventurer tells it as it was; a black spell
   * is covered by a white one or by nothing, which a careful liar makes sure their mana story allows.
   */
  function toldSpell(person, n) {
    if (person.said.spells.has(n)) return person.said.spells.get(n);
    const real = person.spells[n - 1] || null;
    let told;
    if (!real) told = { spell: null };
    else if (!SPELLS[real.spell].black) told = { ...real };
    else {
      const careful = Math.random() < 0.6;
      const options = careful ? WHITE.filter((spell) => fits(person, n, spell)) : WHITE;
      if (!options.length || Math.random() < 0.35) told = { spell: null };
      else {
        const spell = pick(options);
        const target = pick(people.filter((other) => other !== person && (other.alive || other.diedOn > n)));
        told = { spell, target: target.index, result: spell === "sight" ? "human" : spell === "scent" ? "white" : null };
      }
    }
    person.said.spells.set(n, told);
    return told;
  }

  /**
   * Whether what person said they cast on night n is more than their own story's mana allows: the missing colours, an
   * empty list when it adds up, or null when they have not said enough to tell.
   */
  function shortfall(person, n) {
    const told = person.said.spells.get(n);
    if (!told?.spell) return [];
    const mana = storyMana(person, n);
    if (!mana) return null;
    const missing = [];
    for (const color of SPELLS[told.spell].cost) {
      if (mana[color] > 0) mana[color]--;
      else missing.push(color);
    }
    return missing;
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
  function warm(self, target, amount, key, reason) {
    self.heat[target.index] += amount;
    if (amount <= 0) return;
    const part = self.why[target.index].get(key) || { weight: 0, text: reason };
    part.weight += amount;
    part.text = reason;
    self.why[target.index].set(key, part);
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
    for (const part of self.why[target.index].values()) parts.push(part);
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
    const reason = namers.length > 1 ? "何人もが怪しいと言っている" : short(speaker) + "も怪しいと言っていた";
    for (const person of people) {
      if (person === speaker || person === target || person.you) continue;
      warm(person, target, weight * (1 + 0.4 * fond(person, speaker)), "named", reason);
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

  /** Everyone turns on one whose lie came out (a false sight, or a story whose mana does not add up). */
  function expose(liar, reason) {
    liar.exposed = true;
    for (const person of living()) if (!person.you && person !== liar) warm(person, liar, 3, "liar", reason);
  }

  /**
   * The labyrinth showed what target was (sent out, or cursed): everyone knows it now. Whoever called a human a mimic
   * lied; whoever called a mimic human may have been fooled by a guise; whoever stood up for a mimic is weighed by it.
   */
  function shown(target) {
    target.revealed = target.role === "mimic" ? "mimic" : "human";
    for (const person of people) person.known.set(target.index, target.revealed);
    for (const c of living()) {
      for (const claim of c.claims.filter((one) => one.spell === "sight" && one.target === target.index)) {
        if (claim.result === "mimic" && target.revealed === "human" && !c.exposed) {
          narrate(short(c) + "の看破はうそだった。" + nameOf(target) + "は人間だったのだから。", "air");
          expose(c, "看破でうそをついた");
        } else if (claim.result === "human" && target.revealed === "mimic") {
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

  // ---- The scene: what the traveller sees -------------------------------------------------------------------------

  // Lines are read one at a time (a click, Enter or Space goes on); "skip" runs them through to the next choice. Changes
  // to the scene are queued with them, so the place and who stands in it change when the line before is read.
  let queue = [];
  let pumping = false;
  let waiting = [];
  let skipping = false;
  let advanceLine = null;
  let page = null;

  function run(change) {
    try {
      change();
    } catch (failure) {
      console.error(failure);
    }
  }

  function pump() {
    if (pumping) return;
    pumping = true;
    (async () => {
      while (queue.length) {
        const entry = queue.shift();
        if (typeof entry === "function") run(entry);
        else await showLine(entry);
      }
      pumping = false;
      waiting.splice(0).forEach((resolve) => resolve());
    })();
  }

  const drained = () => (queue.length || pumping ? new Promise((resolve) => waiting.push(resolve)) : Promise.resolve());

  function cue(change) {
    queue.push(change);
    pump();
  }

  /** A line in the box: said by person (or told, when there is none), with the reason they give (why). */
  function line(person, said, kind = "", why = null) {
    queue.push({ person, said, kind, why });
    pump();
  }

  const narrate = (said, kind = "") => line(null, said, kind);

  async function showLine({ person, said, kind, why }) {
    logLine(person, said, kind, why);
    choices.replaceChildren();
    messageBox.className = "mz-box" + (kind ? " " + kind : "") + (person ? " said" : "");
    if (nameTag) nameTag.textContent = person ? person.name : "";
    text.textContent = said;
    if (why) text.append(el("small", "mz-why", "理由：" + why));
    light(person);
    if (skipping) return;
    messageBox.classList.add("reading");
    await new Promise((resolve) => { advanceLine = resolve; });
    messageBox.classList.remove("reading");
  }

  function advance() {
    const go = advanceLine;
    advanceLine = null;
    go?.();
  }

  messageBox.addEventListener("click", (event) => {
    if (!event.target.closest("button")) advance();
  });
  messageBox.addEventListener("keydown", (event) => {
    if ((event.key === "Enter" || event.key === " ") && !event.target.closest("button")) {
      event.preventDefault();
      advance();
    }
  });
  skipButton?.addEventListener("click", () => {
    skipping = true;
    advance();
  });

  /** Lights the one speaking, if they stand in the scene; the others dim while someone there speaks. */
  function light(person) {
    let speaking = false;
    for (const figure of cast.children) {
      const on = !!person && figure.dataset.index === String(person.index);
      figure.classList.toggle("speaking", on);
      speaking ||= on;
    }
    cast.classList.toggle("has-speaker", speaking);
  }

  /** The place shown (a backdrop key) and who stands in it; solo: one face to face. */
  function scene(key, figures = [], solo = false) {
    const list = figures.filter((person) => !person.you);
    cue(() => {
      stage.dataset.bg = key;
      cast.classList.toggle("solo", solo);
      cast.replaceChildren(...list.map((person) => {
        const figure = el("div", "mz-fig");
        figure.dataset.index = person.index;
        if (person.figure) figure.append(person.figure.cloneNode(true));
        figure.append(el("b", "", person.name));
        return figure;
      }));
    });
  }

  /** A flash of a colour over the scene (a spell, gathered mana, a death). */
  const flash = (tone) => cue(() => {
    if (!fx) return;
    fx.className = "mz-fx";
    void fx.offsetWidth;
    fx.className = "mz-fx on " + tone;
  });

  /** One that leaves the scene (sent out, cursed). */
  const fade = (person) => cue(() => cast.querySelector('[data-index="' + person.index + '"]')?.classList.add("leaving"));

  // ---- The log ------------------------------------------------------------------------------------------------------

  function chapter(title) {
    for (const open of log.querySelectorAll("details[open]")) open.open = false;
    const item = el("li", "wolf-day");
    const fold = el("details");
    fold.open = true;
    page = el("ol");
    fold.append(el("summary", "", title), page);
    item.append(fold);
    log.append(item);
  }

  function logLine(person, said, kind, why) {
    if (!page) chapter("");
    const item = el("li", person ? "wolf-say" + (person.you ? " you" : "") : "wolf-narr" + (kind ? " " + kind : ""));
    if (person) {
      item.append(el("b", "", person.name), el("span", "", said));
      if (why) item.append(el("small", "wolf-why", "理由：" + why));
    } else item.textContent = said;
    page.append(item);
  }

  // ---- Choices ------------------------------------------------------------------------------------------------------

  /** Buttons in the box, once every line so far is read; resolves with the value of the one pressed. */
  async function ask(prompt, options) {
    await drained();
    skipping = false;
    return new Promise((resolve) => {
      messageBox.className = "mz-box choosing";
      if (nameTag) nameTag.textContent = "";
      text.textContent = prompt || "";
      light(null);
      choices.replaceChildren(...options.map((option) => {
        const button = el("button", "meal-button" + (option.quiet ? " quiet" : ""), option.label);
        button.type = "button";
        button.addEventListener("click", () => {
          choices.replaceChildren();
          resolve(option.value);
        });
        return button;
      }));
      choices.querySelector("button")?.focus({ preventScroll: true });
    });
  }

  const next = (label = "次へ") => ask(null, [{ label, value: true }]);
  const pickPerson = (prompt, list, back = false) =>
    ask(prompt, [...list.map((person) => ({ label: short(person), value: person })), ...(back ? [{ label: "やめる", value: null, quiet: true }] : [])]);

  // ---- The status, the chart and the notebook ---------------------------------------------------------------------

  const chip = (color, words) => el("span", "mana mana-" + color, words);
  const visible = (name) => tabs.find((tab) => tab.dataset.mzTab === name)?.getAttribute("aria-selected") === "true";

  /** The day, the traveller's role and mana over the scene; the chart and notebook if open. Shown with the lines. */
  function refresh() {
    cue(() => {
      const self = you();
      if (status) status.textContent = (day === 0 ? "迷宮の入口" : day + "日目") + " ・ あなたは" + ROLES[self.role] + (self.alive ? "" : "（脱落）")
        + " ・ 残り" + living().length + "人／ミミック" + mimics().length;
      manaNote?.replaceChildren(...Object.entries(COLORS).map(([key, name]) => chip(key, name + self.mana[key])));
      if (visible("chart")) drawChart();
      if (visible("note")) drawNote();
    });
  }

  for (const tab of tabs) {
    tab.addEventListener("click", () => {
      for (const other of tabs) {
        const on = other === tab;
        other.setAttribute("aria-selected", on ? "true" : "false");
        const panel = box.querySelector('[data-mz-panel="' + other.dataset.mzTab + '"]');
        if (panel) panel.hidden = !on;
      }
      if (tab.dataset.mzTab === "chart") drawChart();
      if (tab.dataset.mzTab === "note") drawNote();
    });
  }

  const SVG = "http://www.w3.org/2000/svg";
  const svg = (tag, attrs = {}, content = "") => {
    const node = document.createElementNS(SVG, tag);
    for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
    if (content) node.textContent = content;
    return node;
  };

  /**
   * The chart: everyone in a ring; red arrows for whom each named today and why, blue for whom they stood up for,
   * dashed white or black for what each sight said (all days). A press on someone keeps only the lines about them.
   */
  function drawChart() {
    if (!graph || !people.length) return;
    const width = 640;
    const height = 460;
    graph.setAttribute("viewBox", "0 0 " + width + " " + height);
    const at = people.map((person, i) => {
      const angle = Math.PI / 2 + (i * 2 * Math.PI) / people.length;
      return [width / 2 + 230 * Math.cos(angle), height / 2 + 185 * Math.sin(angle)];
    });
    const record = story.at(-1);
    const edges = [];
    for (const { by, target, why } of record?.named || []) edges.push({ from: by, to: target, tone: "point", words: why || "" });
    for (const { by, target } of record?.covers || []) edges.push({ from: by, to: target, tone: "cover", words: "かばった" });
    for (const teller of people) {
      for (const claim of teller.claims.filter((one) => one.spell === "sight")) {
        edges.push({ from: teller, to: people[claim.target], tone: claim.result === "mimic" ? "black" : "white",
          words: claim.day + "日目 看破：" + (claim.result === "mimic" ? "●ミミック" : "○人間") });
      }
    }
    const kept = focus === null ? edges : edges.filter((edge) => edge.from.index === focus || edge.to.index === focus);
    const defs = svg("defs");
    for (const tone of ["point", "cover", "black", "white"]) {
      const marker = svg("marker", { id: "mz-head-" + tone, viewBox: "0 0 10 10", refX: "9", refY: "5", markerWidth: "6", markerHeight: "6", orient: "auto" });
      marker.append(svg("path", { d: "M0 0L10 5L0 10z", class: "mz-head " + tone }));
      defs.append(marker);
    }
    const lines = kept.map((edge, i) => {
      const [x1, y1] = at[edge.from.index];
      const [x2, y2] = at[edge.to.index];
      const length = Math.hypot(x2 - x1, y2 - y1) || 1;
      const [ux, uy] = [(x2 - x1) / length, (y2 - y1) / length];
      // Bowed by kind, so that lines between the same two lie apart.
      const bow = 18 + 14 * ["point", "cover", "black", "white"].indexOf(edge.tone) + (i % 2) * 6;
      const [sx, sy, ex, ey] = [x1 + ux * 34, y1 + uy * 34, x2 - ux * 38, y2 - uy * 38];
      const [cx, cy] = [(sx + ex) / 2 - uy * bow, (sy + ey) / 2 + ux * bow];
      const path = svg("path", { d: "M" + sx.toFixed(1) + " " + sy.toFixed(1) + " Q" + cx.toFixed(1) + " " + cy.toFixed(1) + " " + ex.toFixed(1) + " " + ey.toFixed(1),
        class: "mz-edge " + edge.tone, "marker-end": "url(#mz-head-" + edge.tone + ")" });
      path.append(svg("title", {}, short(edge.from) + "→" + short(edge.to) + "：" + edge.words));
      return path;
    });
    const nodes = people.map((person) => {
      const [x, y] = at[person.index];
      const group = svg("g", { class: "mz-node" + (person.alive ? "" : " gone") + (person.revealed ? " " + person.revealed : "")
        + (person.exposed ? " exposed" : "") + (focus === person.index ? " focus" : "") + (person.memo ? " memo" + person.memo : ""), tabindex: "0", role: "button" });
      group.append(svg("circle", { cx: x, cy: y, r: "30" }));
      group.append(svg("text", { x, y: y + 4, "text-anchor": "middle" }, short(person).slice(0, 5)));
      const tag = person.revealed ? (person.revealed === "mimic" ? "ミミック" : "人間") + (person.diedBy === "vote" ? "・追放" : person.diedBy ? "・呪殺" : "")
        : person.exposed ? "うそ発覚" : MEMOS[person.memo] ? "メモ：" + MEMOS[person.memo] : "";
      if (tag) group.append(svg("text", { x, y: y + 46, "text-anchor": "middle", class: "mz-node-tag" }, tag));
      const choose = () => {
        focus = focus === person.index ? null : person.index;
        drawChart();
      };
      group.addEventListener("click", choose);
      group.addEventListener("keydown", (event) => {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          choose();
        }
      });
      return group;
    });
    graph.replaceChildren(defs, ...lines, ...nodes);
    if (!graphList) return;
    const section = (title, items) => {
      if (!items.length) return [];
      const list = el("ul");
      list.append(...items.map((words) => el("li", "", words)));
      return [el("h4", "", title), list];
    };
    graphList.replaceChildren(
      ...section("看破の結果（白だし・黒だし）", kept.filter((edge) => edge.tone === "black" || edge.tone === "white").map((edge) => short(edge.from) + " → " + short(edge.to) + "：" + edge.words)),
      ...section((record ? record.day + "日目の" : "") + "疑いの向き", kept.filter((edge) => edge.tone === "point").map((edge) => short(edge.from) + " → " + short(edge.to) + "：" + edge.words)),
      ...section("かばった", kept.filter((edge) => edge.tone === "cover").map((edge) => short(edge.from) + " → " + short(edge.to))),
      ...section("正体が分かった人", people.filter((person) => person.revealed && person.diedBy).map((person) => short(person) + "：" + (person.revealed === "mimic" ? "ミミック" : "人間") + "（" + (person.diedBy === "vote" ? "追放" : "呪殺") + "）")),
      el("p", "mz-hint", focus === null ? "人物を押すと、その人に関わる線だけになります。" : "もう一度押すと、すべての線に戻ります。"),
    );
  }

  /**
   * The notebook: a row for each one, a column for each day. Where they went (seen by all), what they said they
   * gathered and cast (once asked, or told by the fire), the traveller's own as it was. A press on a cell marks it.
   */
  function drawNote() {
    if (!note || !people.length) return;
    const days = story.map((record) => record.day);
    const table = el("table", "mz-table");
    const head = el("tr");
    head.append(el("th", "", "人物"), ...days.map((d) => el("th", "", d + "日目")));
    table.append(head);
    for (const person of [...people.filter((one) => !one.you), you()]) {
      const row = el("tr", person.alive ? "" : "gone");
      const who = el("th");
      who.append(el("b", "", short(person)));
      const state = person.revealed && person.diedBy ? (person.revealed === "mimic" ? "ミミック" : "人間") + "・" + (person.diedBy === "vote" ? "追放" : "呪殺")
        : person.revealed ? ROLES[person.role] : person.exposed ? "うそ発覚" : person.you ? ROLES[person.role] : "";
      if (state) who.append(el("small", "", state));
      if (!person.you) {
        const memo = el("button", "wolf-memo" + (person.memo ? " m" + person.memo : ""), MEMOS[person.memo] || "メモ");
        memo.type = "button";
        memo.addEventListener("click", () => {
          person.memo = (person.memo + 1) % MEMOS.length;
          drawNote();
        });
        who.append(memo);
      }
      row.append(who);
      for (const d of days) {
        const cell = el("td", "mz-cell");
        const key = person.index + ":" + d;
        if (marks.has(key)) cell.classList.add("marked");
        const went = person.history[d - 1];
        if (went) {
          const spot = placeOf(went);
          const where = el("span", "mz-where", spot.name);
          where.prepend(...spot.colors.map((color) => el("i", "dot " + color)));
          cell.append(where);
          const got = person.you ? person.gains[d - 1] : person.said.gains.get(d);
          cell.append(el("span", "mz-got", got ? "得た：" + manaText(got) : "得た：？"));
        } else if (!person.alive) cell.append(el("span", "mz-gone", "—"));
        const spell = person.you ? person.spells[d - 1] : person.said.spells.get(d);
        if (spell) {
          const words = !spell.spell ? "夜：なし" : "夜：" + SPELLS[spell.spell].name + (spell.target !== undefined && spell.target !== null && !SPELLS[spell.spell].self ? "→" + short(people[spell.target]) : "")
            + (spell.result === "mimic" ? "●" : spell.result === "human" ? "○" : spell.result === "black" ? "（黒）" : spell.result === "white" ? "（白）" : "");
          cell.append(el("span", "mz-cast-note" + (spell.spell && SPELLS[spell.spell].black ? " black" : ""), words));
        } else if (went && (d < day || story.at(-1).casts.length) && !(person.you && d <= day)) cell.append(el("span", "mz-cast-note unknown", "夜：？"));
        else if (person.you && went && d < day) cell.append(el("span", "mz-cast-note", "夜：なし"));
        cell.title = "押すと印を付けます";
        cell.addEventListener("click", () => {
          if (marks.has(key)) marks.delete(key);
          else marks.add(key);
          drawNote();
        });
        row.append(cell);
      }
      table.append(row);
    }
    const hint = el("p", "mz-hint", "「得た」と「夜」は本人の申告です（尋問で聞くか、焚き火で話した分）。最初は誰もマナを持っていないので、申告したマナで申告した魔法が唱えられなければ、その話はうそです。セルを押すと印が付きます。");
    note.replaceChildren(table, hint);
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
    scene("entrance", people);
    refresh();
    narrate("気がつくと、" + people.length + "人は石造りの迷宮にいた。出口の扉には、こう刻まれている。");
    narrate("「潜む者すべてが倒れるまで、この扉は開かない」", "big");
    narrate("この中にミミックが2体、そしてミミックに味方する狂人が1人いる。昼は場所を巡ってマナを集め、焚き火で話し合って1人を置いていき、夜は魔法を唱える。");
    let role = "あなたは【" + ROLES[self.role] + "】だ。";
    if (self.role === "mimic") {
      const partner = people.find((person) => person !== self && person.role === "mimic");
      role += "仲間のミミックは" + partner.name + "。正体を隠し、呪殺で人間を減らせ。人間と同じ数になれば勝ちだ。";
    }
    if (self.role === "madman") role += "人間だが、ミミックの勝ちを願っている。ミミックが誰かは知らない。囁きで探し当てれば、手を組める。";
    if (self.role === "human") role += "皆の話を聞き、つじつまの合わない者を見つけ出そう。ミミックを2体とも追い出せば、扉が開く。";
    narrate(role, "secret");
    narrate("誰もまだマナを持っていない。どこで何を集め、何を唱えたか——その話の食い違いが、潜む者への手がかりになる。");
    await next("迷宮へ踏み出す");
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
    person.gains.push(got);
    return got;
  }

  async function explore() {
    const self = you();
    scene("corridor", living());
    refresh();
    narrate(day + "日目の朝。分かれ道の前で、それぞれが行き先を決める。", "big");
    const went = today().places;
    if (self.alive) {
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
      if (person.you) {
        const here = [...went].filter(([other, there]) => there === key && !other.you).map(([other]) => other);
        scene(key, here);
        narrate(placeOf(key).name + "に着いた。" + (here.length ? here.map((other) => other.name).join("と") + "も来ている。" : "ほかには誰もいない。"));
        flash("mana " + placeOf(key).colors.join(" "));
        narrate(manaText(got) + "のマナが、手のひらに集まった。", "secret");
      }
    }
    if (!self.alive) scene("corridor", living());
    refresh();
    narrate("ほかの者たちの行き先：" + PLACES.map((one) => one.name + "＝" + ([...went].filter(([person, key]) => key === one.key && !person.you).map(([person]) => person.name).join("・") || "なし")).join("、"), "air");
  }

  /** A spell result said aloud by person, true or not; it becomes part of their story. */
  function claim(person, spell, target, result) {
    if (!person.claims.length) {
      for (const other of people) other.prior[person.index] = clamp(0.4 + Math.random() * 0.3 + 0.25 * fond(other, person), 0.05, 0.9);
    }
    person.claims.push({ day, spell, target: target.index, result });
    if (!person.you) person.said.spells.set(day - 1, { spell, target: target.index, result });
    today().claims.push({ by: person, spell, target, result });
    const name = nameOf(target);
    const said = spell === "sight" ? "昨夜、" + name + "を看破した。……" + (result === "mimic" ? "ミミックだ！" : "人間だった。")
      : "昨夜、" + name + "に残り香をかけた。……" + (result === "black" ? "黒魔法の気配がした！" : "黒魔法の気配はなかった。");
    line(person, said);
    const black = result === "mimic" || result === "black";
    if (black) heard(person, target, spell === "sight" ? 0.5 : 0.3);
  }

  /**
   * A made-up sight result from one on the mimics' side, if their story can bear it (a careless one tells it anyway). A
   * mimic calls a partner the room is set on human, or a human (one who has named a mimic, else the room's favourite) a
   * mimic; the madman, without allies, calls a mimic one who reads others or anyone; with allies, as a mimic would.
   */
  function lie(liar) {
    if (!fits(liar, day - 1, "sight") && Math.random() < 0.65) return null;
    const told = (person) => liar.claims.some((one) => one.target === person.index);
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
    const npcs = shuffle(living().filter((person) => !person.you));
    scene("fire", npcs);
    refresh();
    narrate("焚き火のまわりに、皆が戻ってきた。" + day + "日目の話し合いが始まる。", "big");
    if (day === 1) for (const person of npcs.slice(0, 2)) line(person, pick(person.lines.greet.split("\n")) || "……");
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
    refresh();

    if (self.alive) await yourTurn();
    else await next("話し合いを見守る");
    self.fresh = [];
    scene("fire", living().filter((person) => !person.you));

    // Everyone else names whom they suspect most, and why.
    const named = new Map();
    for (const person of npcs.filter((npc) => !speakers.includes(npc) && npc.alive)) {
      const target = choice(person);
      if (!target) continue;
      const why = reasonFor(person, target);
      line(person, sayAbout(person, "suspect", target), "", why);
      heard(person, target);
      person.lastSuspect = target.index;
      today().named.push({ by: person, target, why });
      named.set(target, (named.get(target) || 0) + 1);
    }
    // Someone who cares for one who was named stands up for them (at most two a day); a mimic may for its partner.
    let covered = 0;
    for (const [target] of [...named].sort((a, b) => b[1] - a[1])) {
      if (covered >= 2) break;
      const friend = top(npcs.filter((npc) => npc !== target && npc.alive && !target.named.has(npc.index))
        .map((npc) => [npc, fond(npc, target) + (npc.allies.has(target.index) ? 0.8 : 0) + Math.random() * 0.3]).concat([[null, 0.75]]));
      if (!friend) continue;
      line(friend, sayAbout(friend, "cover", target));
      vouched(friend, target);
      covered++;
    }
    // Whoever is named twice (or by the traveller) answers for themselves.
    for (const [target, count] of named) {
      if (target.you || count < 2 && !target.namedByYou) continue;
      line(target, pick(target.lines.defend.split("\n")) || "……");
      for (const person of people) if (person !== target) person.heat[target.index] = Math.max(0, person.heat[target.index] - 0.3);
    }
    for (const person of people) person.namedByYou = false;
    refresh();
    return vote();
  }

  /** A resident's line of this kind about target, one of its wordings. */
  function sayAbout(person, kind, target) {
    const name = nameOf(target);
    return (pick((person.lines[kind] || "").split("\n")) || "……").replaceAll("{t}さん", target.you ? name : name + "さん").replaceAll("{t}", name);
  }

  /**
   * The traveller's turn by the fire: question a few of them (QUESTIONS a day), hold a story up to them, tell their own
   * results, name or stand up for someone, until they let the talk go on.
   */
  async function yourTurn() {
    const self = you();
    let left = QUESTIONS;
    let spoke = false;
    for (;;) {
      const options = [];
      if (left > 0) options.push({ label: "尋問する（あと" + left + "回）", value: "question" });
      options.push({ label: "矛盾を突きつける", value: "confront" });
      if (!spoke) {
        options.push({ label: "看破の結果を伝える", value: "sight" }, { label: "残り香の結果を伝える", value: "scent" },
          { label: "怪しい人を名指しする", value: "suspect" }, { label: "誰かをかばう", value: "cover" });
      }
      options.push({ label: "話し合いを進める", value: "done", quiet: true });
      const action = await ask("あなたの番だ。どうする？（推理ノートと相関図は上のタブで見られる）", options);
      if (action === "done") return;
      if (action === "question") {
        const person = await pickPerson("誰に聞く？", others(self), true);
        if (!person) continue;
        if (await question(person)) left--;
        scene("fire", living().filter((one) => !one.you));
        continue;
      }
      if (action === "confront") {
        await confront();
        scene("fire", living().filter((one) => !one.you));
        continue;
      }
      if (action === "suspect" || action === "cover") {
        const target = await pickPerson(action === "suspect" ? "誰を名指しする？" : "誰をかばう？", others(self), true);
        if (!target) continue;
        if (action === "suspect") {
          line(self, target.name + "が怪しいと思う。");
          heard(self, target);
          today().named.push({ by: self, target, why: "あなたの推理" });
          target.namedByYou = true;
        } else {
          line(self, target.name + "はミミックじゃないと思う。");
          vouched(self, target);
        }
        spoke = true;
        refresh();
        continue;
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
      spoke = true;
      refresh();
    }
  }

  /** Questioning one, face to face; true when a question was asked. */
  async function question(person) {
    scene("fire", [person], true);
    const what = await ask(person.name + "に何を聞く？", [
      { label: "これまでに、どこで何色のマナを手に入れた？", value: "gains" },
      { label: "これまでの夜、何を唱えた？", value: "spells" },
      { label: "いま、誰を一番疑っている？", value: "suspect" },
      { label: "やめる", value: null, quiet: true },
    ]);
    if (!what) return false;
    if (what === "gains") {
      line(you(), "これまでに、どこで何色のマナを手に入れた？");
      const told = person.history.map((key, i) => (i + 1) + "日目は" + placeOf(key).name + "で" + manaText(toldGains(person, i + 1)));
      line(person, told.join("。") + "。");
    } else if (what === "spells") {
      line(you(), "これまでの夜、何を唱えた？");
      const nights = [];
      for (let n = 1; n < day; n++) {
        const told = toldSpell(person, n);
        nights.push(n + "日目の夜は" + (!told.spell ? "何も唱えていない" : SPELLS[told.spell].name + (SPELLS[told.spell].self || told.target === undefined || told.target === null ? "" : "を" + nameOf(people[told.target]) + "に")
          + (told.result === "mimic" ? "。ミミックだった" : told.result === "human" ? "。人間だった" : told.result === "black" ? "。黒魔法の気配がした" : told.result === "white" ? "。気配はなかった" : "")));
      }
      line(person, nights.length ? nights.join("。") + "。" : "まだ夜を越していない。");
    } else {
      line(you(), "いま、誰を一番疑っている？");
      const target = choice(person);
      line(person, target ? sayAbout(person, "suspect", target) : "……分からない。", "", target ? reasonFor(person, target) : null);
    }
    refresh();
    return true;
  }

  /**
   * Holding up one night of someone's story whose mana does not add up. Right, and they are found out before everyone;
   * wrong (it adds up, or they have not said enough), and the room thinks less of the traveller.
   */
  async function confront() {
    const self = you();
    const tellers = others(self).filter((one) => [...one.said.spells.values()].some((told) => told.spell));
    if (!tellers.length) {
      narrate("まだ誰からも、魔法を唱えた話を聞いていない。尋問するか、焚き火で結果を話すのを待とう。", "air");
      return;
    }
    const person = await pickPerson("誰の話を突きつける？", tellers, true);
    if (!person) return;
    const nights = [...person.said.spells].filter(([, told]) => told.spell).sort((a, b) => a[0] - b[0]);
    const n = await ask("どの夜の話がおかしい？", [
      ...nights.map(([night, told]) => ({ label: night + "日目の夜：" + SPELLS[told.spell].name + "（" + SPELLS[told.spell].cost.map((color) => COLORS[color]).join("＋") + "）", value: night })),
      { label: "やめる", value: null, quiet: true },
    ]);
    if (!n) return;
    scene("fire", [person], true);
    const told = person.said.spells.get(n);
    const missing = shortfall(person, n);
    line(self, n + "日目の夜に" + SPELLS[told.spell].name + "を唱えたと言ったな。でも、あなたの話だと、そのときのマナが足りないはずだ。");
    if (missing === null) {
      line(person, "……何の話だ？ どこで何を手に入れたかは、まだ話していないだろう。");
      narrate("まだ確かめられない。まずは手に入れたマナの話を聞く必要がありそうだ。", "air");
      return;
    }
    if (!missing.length) {
      line(person, "足りているさ。数え直してみるといい。");
      narrate("確かに、" + person.name + "の話は帳尻が合っている。皆の視線が、あなたに冷たく向いた。", "air");
      for (const other of living()) if (!other.you && other !== person) warm(other, self, 0.8, "baseless", "言いがかりをつけた");
      return;
    }
    line(person, "そ、それは……");
    narrate(person.name + "の話では、" + n + "日目の夜までに" + missing.map((color) => COLORS[color]).join("と") + "のマナが足りない。" + SPELLS[told.spell].name + "は唱えられなかったはずだ。", "secret");
    flash("exposed");
    narrate("焚き火のまわりがざわめいた。" + person.name + "の話は、うそだったのだ。", "air");
    expose(person, "旅の人に話の矛盾を突かれた");
    refresh();
  }

  /** One round of votes for someone in pool (not oneself); the tally and who voted whom. */
  async function ballot(pool, prompt) {
    const self = you();
    const ballots = new Map();
    const tally = new Map();
    const tick = (voter, target) => {
      ballots.set(voter, target);
      tally.set(target, (tally.get(target) || 0) + 1);
      voter.votedFor.push(target.index);
    };
    const candidates = (voter) => pool.filter((person) => person !== voter);
    if (self.alive && candidates(self).length) {
      const target = await pickPerson(prompt, candidates(self));
      tick(self, target);
      today().yourVote = target;
    }
    for (const person of shuffle(living().filter((npc) => !npc.you))) {
      const target = choice(person, candidates(person), tally, ballots);
      if (target) tick(person, target);
    }
    narrate("票：" + [...ballots].map(([voter, target]) => short(voter) + "→" + short(target)).join("、"), "vote");
    return { tally, ballots };
  }

  /** The vote, with a run-off between those tied; the one sent out shows what they were. True when the game ended. */
  async function vote() {
    narrate("投票の時間だ。迷宮に置いていく1人を決める。", "big");
    let { tally, ballots } = await ballot(living(), "誰に投票する？");
    let most = Math.max(...tally.values());
    let tied = [...tally].filter(([, count]) => count === most).map(([target]) => target);
    if (tied.length > 1) {
      today().runoff = true;
      narrate(tied.map((person) => short(person)).join("と") + "が" + most + "票で並んだ。決選投票だ。", "big");
      for (const person of tied) if (!person.you) line(person, pick(person.lines.defend.split("\n")) || "……");
      ({ tally, ballots } = await ballot(tied, "決選投票。誰に入れる？"));
      most = Math.max(...tally.values());
      tied = [...tally].filter(([, count]) => count === most).map(([target]) => target);
      if (tied.length > 1) narrate("それでも決まらず、くじ引きになった。");
    }
    const exiled = pick(tied);
    const voiced = [...ballots].find(([voter, target]) => target === exiled && !voter.you);
    if (voiced) line(voiced[0], sayAbout(voiced[0], "vote", exiled));
    exiled.alive = false;
    exiled.diedBy = "vote";
    exiled.diedOn = day;
    today().exiled = exiled;
    today().votes = most;
    narrate(nameOf(exiled) + "は" + most + "票を集め、迷宮の壁に呑まれていく。", "death");
    fade(exiled);
    flash(exiled.role === "mimic" ? "exposed" : "death");
    narrate("……その姿は" + (exiled.role === "mimic" ? "、ミミックだった！" : "、人間のままだった。"), exiled.role === "mimic" ? "secret" : "death");
    shown(exiled);
    refresh();
    return finish() || (await next("夜になる"), false);
  }

  /** Whom the mimics curse tonight: one who reads or names them, more than anyone else; one they are fond of, less. */
  function prey() {
    const pool = living().filter((person) => person.role !== "mimic" && !mimics().some((mimic) => mimic.allies.has(person.index)));
    if (!pool.length) return null;
    return draw(pool.map((person) => [person, Math.max(0.2, 1 + person.threat * 1.2
      + 2 * person.claims.filter((one) => one.spell === "sight" && one.result === "mimic" && people[one.target].role === "mimic").length
      + (person.claims.length ? 1.2 : 0) - mimics().reduce((total, mimic) => total + 0.4 * fond(mimic, person), 0))]));
  }

  /** What a resident casts tonight ({ spell, target }), or null. The mimics' curse is decided by the pair (below). */
  function intent(self) {
    const unknown = others(self).filter((person) => !self.known.has(person.index));
    const suspect = (pool) => top(pool.map((target) => [target, suspicion(self, target) + Math.random()]));
    const reader = (pool) => top(pool.map((target) => [target, target.claims.filter((one) => one.spell === "sight").length * 1.5 + Math.random()]));
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
      const target = top(others(self).map((person) => [person, person.claims.filter((one) => one.spell === "sight").length * trust(self, person) * 2
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
    let hint = "あなたのマナ：" + manaText(self.mana);
    if (self.role === "mimic" && partner && !partner.you) hint += can(partner, "curse") ? "。仲間の" + partner.name + "は呪殺を唱えられる" : "。仲間の" + partner.name + "は呪殺のマナが足りない";
    for (;;) {
      const key = await ask("今夜、どの魔法を唱える？（" + hint + "）", [
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
    scene("night");
    refresh();
    narrate("迷宮に" + day + "日目の夜が来た。焚き火が細り、それぞれがひそかに呪文を唱える。", "big");
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
    for (const one of casts) one.by.spells[day - 1] = { spell: one.spell, target: one.target.index, result: null, sealed: one.spell !== "seal" && sealed.has(one.by) };
    const mine = casts.find((one) => one.by === self);
    if (mine) flash("cast " + mine.spell);
    if (mine && mine.spell !== "seal" && sealed.has(self)) narrate("唱えた呪文が、何かにかき消された。", "secret");
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
      one.by.spells[day - 1].result = result;
      one.by.fresh.push({ spell: "sight", target: one.target.index, result });
      if (!one.by.known.has(one.target.index)) one.by.known.set(one.target.index, result);
      if (one.by.you) narrate(one.target.name + "を看破した。……" + (result === "mimic" ? "ミミックだ！" : "人間に見える。"), "secret");
    }
    for (const one of active.filter((cast) => cast.spell === "scent")) {
      const result = black.has(one.target) ? "black" : "white";
      one.by.spells[day - 1].result = result;
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
      if (self.role === "mimic") narrate(nameOf(one.by) + "が囁いてきた。狂人だ。これで手を組める。", "secret");
    }
    refresh();
    await next("朝を迎える");
    scene("morning", living());
    narrate((day + 1) + "日目の朝。", "big");
    if (victim) {
      victim.alive = false;
      victim.diedBy = "curse";
      victim.diedOn = day + 1;
      today().killed = victim;
      flash("death");
      fade(victim);
      narrate("目を覚ますと、" + nameOf(victim) + "が冷たくなっていた。……呪殺だ。", "death");
      shown(victim);
      // Whom the cursed one suspected is taken more seriously; and whoever could have gathered the curse's colours lately.
      const suspect = victim.lastSuspect === null ? null : people[victim.lastSuspect];
      for (const person of living()) {
        if (person.you) continue;
        if (suspect?.alive && suspect !== person) warm(person, suspect, 0.45, "mourn:" + victim.index, "呪われた" + victim.name + "が疑っていた");
        for (const other of others(person)) {
          const colors = new Set(other.history.slice(-2).flatMap((key) => placeOf(key).colors));
          if (CURSE.every((color) => colors.has(color))) warm(person, other, 0.3, "colors", "呪殺に要るマナを集められた");
        }
      }
    } else {
      narrate("誰も欠けずに朝を迎えた。" + (today().blocked ? "どこかで呪いが帳に阻まれたらしい。" : ""));
    }
    refresh();
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
    scene(partyWon ? "exit" : "night", people.filter((person) => person.role !== "human" || person.alive));
    flash(partyWon ? "dawn" : "death");
    narrate(partyWon ? "最後のミミックが壁に呑まれ、出口の扉が重い音を立てて開いた。冒険者の勝利！" : "灯りが消えた。迷宮に残ったのは、潜む者たちだけ……ミミックの勝利。", "big");
    narrate(won ? "あなたの陣営の勝ちだ。" : "あなたの陣営の負けだ。", "secret");
    narrate("正体：" + people.filter((person) => !person.you).map((person) => person.name + "＝" + ROLES[person.role]).join("、") + "。");
    review();
    keepRecord(won);
    for (const person of people) person.revealed ||= person.role === "mimic" ? "mimic" : "human";
    refresh();
    ask("振り返りは「記録」のタブに。推理ノートには、皆の本当の行動も載った。", [{ label: "もう一度挑む", value: true }]).then(play);
    return true;
  }

  // ---- The review ---------------------------------------------------------------------------------------------------

  const lab = (person) => short(person) + "〔" + ROLES[person.role] + "〕";

  /** Who went where, said what and cast what each day, with every role shown; and the truth into the notebook. */
  function review() {
    cue(() => chapter("振り返り"));
    const add = (said, kind = "review") => cue(() => logLine(null, said, kind));
    for (const record of story) {
      add(record.day + "日目", "big review");
      add("行き先：" + [...record.places].map(([person, key]) => short(person) + "→" + placeOf(key).name).join("、"));
      for (const said of record.claims) {
        const truth = said.spell === "sight" ? (said.target.role === "mimic" ? "mimic" : "human") : null;
        const lying = truth && said.result !== truth;
        add(SPELLS[said.spell].name + "の報告：" + lab(said.by) + "→" + lab(said.target) + "は" + ({ mimic: "ミミック", human: "人間", black: "黒魔法あり", white: "黒魔法なし" })[said.result]
          + (lying ? (said.result === "human" && said.target.role === "mimic" && said.by.role === "human" ? "（擬態に騙された）" : "（うそ）") : ""));
      }
      if (record.named.length) add("名指し：" + record.named.map(({ by, target }) => short(by) + "→" + short(target)).join("、"));
      if (record.exiled) add("追放：" + lab(record.exiled) + "（" + record.votes + "票" + (record.runoff ? "・決選投票" : "") + "）" + (record.yourVote ? " ・ あなたの票：" + lab(record.yourVote) : ""));
      if (record.casts.length) add("夜の魔法：" + record.casts.map((one) => short(one.by) + "の" + SPELLS[one.spell].name + (SPELLS[one.spell].self ? "" : "→" + short(one.target))).join("、"));
      if (record.killed) add("呪殺：" + lab(record.killed));
      if (record.blocked) add("帳が呪いを防いだ：" + lab(record.blocked));
    }
    // The notebook shows what everyone really gathered and cast.
    for (const person of people) {
      if (person.you) continue;
      person.gains.forEach((got, i) => person.said.gains.set(i + 1, got));
      person.spells.forEach((done, i) => done && person.said.spells.set(i + 1, done));
    }
  }

  play();
};
guildMimic();
