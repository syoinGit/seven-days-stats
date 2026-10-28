// A solo werewolf game with the residents (fragments/guild-windows :: werewolf, GuildWerewolf). No AI and nothing sent
// back: the roles are dealt here, and the residents claim, suspect, stand up for each other, vote and hunt by the rules
// below, each saying it in their own voice (guild/werewolf.yml). The traveller plays one seat.
//
// How a resident thinks: each one keeps a suspicion of everyone else, made of parts that each have a reason they can
// say (a hunch, being named by others, a seer's result, standing up for one who turned out a wolf, ...). It follows the
// seers' and mediums' words as far as they believe the one who said it. How they feel about each other in the guild
// (data-ties, data-bonds) colours all of it: they are slow to suspect someone they are fond of, believe a claimant they
// trust, stand up for a friend who is named, and a couple tends to vote together. What they know for certain
// (themselves, a wolf's partner, their own readings) overrides all of it. The wolves vote with the room against whoever
// is not a wolf, may claim to be the seer or the medium, stand up for their partner or cut them loose, and hunt whoever
// claims or names them. The madman is human but on the wolves' side, without knowing who they are, and lies as a seer.
//
// What the traveller sees: the claims and results in a table, their own notes on each seat, the room's mood, and at the
// end a review of who did what. Their record stays in this browser only (localStorage).
(() => {
  const box = document.querySelector("[data-wolf]");
  if (!box) return;
  const $ = (selector) => box.querySelector(selector);
  const seats = [...box.querySelectorAll("[data-wolf-player]")];
  const youSeat = $("[data-wolf-you]");
  const log = $("[data-wolf-log]");
  const actions = $("[data-wolf-actions]");
  const status = $("[data-wolf-status]");
  const tiesNote = $("[data-wolf-ties]");
  const board = $("[data-wolf-board]");
  const recordNote = $("[data-wolf-record]");
  const speed = $("[data-wolf-speed]");
  if (seats.length < 4 || !youSeat || !log || !actions || !status) return;

  const ROLES = { wolf: "人狼", seer: "占い師", medium: "霊媒師", hunter: "狩人", madman: "狂人", villager: "村人" };
  // By how many sit at the table, the traveller included.
  const DEAL = {
    5: ["wolf", "seer", "madman", "villager", "villager"],
    6: ["wolf", "seer", "hunter", "madman", "villager", "villager"],
    7: ["wolf", "wolf", "seer", "medium", "hunter", "villager", "villager"],
    8: ["wolf", "wolf", "seer", "medium", "hunter", "madman", "villager", "villager"],
  };
  const BONDS = { friend: "友達", close_friend: "親友", companion: "仲間", lover: "恋人", spouse: "夫婦", rival: "宿敵", mentor: "師弟" };
  // How much a bond adds to how fond each of the two is of the other.
  const BOND_WARMTH = { friend: 0.3, companion: 0.3, close_friend: 0.6, mentor: 0.4, lover: 0.9, spouse: 1, rival: -0.8 };
  const MEMOS = ["", "白", "黒", "灰"];
  const YOU = "旅の人";
  const RECORD = "guild-wolf-record";
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
  const stored = (key) => { try { return localStorage.getItem(key); } catch { return null; } };
  const store = (key, value) => { try { localStorage.setItem(key, value); } catch { /* private window: not kept */ } };

  // ---- How the residents stand with each other (from the guild) --------------------------------------------------

  const byId = new Map(seats.map((seat, index) => [seat.dataset.wolfPlayer, index]));
  const fondness = seats.map(() => seats.map(() => 0));
  const rivals = new Set();
  const bonds = [];
  for (const tie of (box.dataset.ties || "").split(";").filter(Boolean)) {
    const [from, to, trust, affinity, wariness, smitten] = tie.split(":");
    const a = byId.get(from);
    const b = byId.get(to);
    if (a === undefined || b === undefined) continue;
    fondness[a][b] += (Number(affinity) + 0.5 * Number(trust) - Number(wariness)) / 100 + (smitten === "1" ? 0.3 : 0);
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
  // The traveller is a stranger to all of them: each one sees them as they see the others on average, neither friend
  // nor foe, so that being new to the table is no handicap.
  const usual = fondness.map((row, a) => row.reduce((total, value, b) => total + (a === b ? 0 : clamp(value, -1.5, 1.5)), 0) / Math.max(1, row.length - 1));
  /** How fond a is of b, about -1.5 (can't stand them) to 1.5 (would die for them). */
  const fond = (a, b) => (a.you ? 0 : b.you ? usual[a.index] : clamp(fondness[a.index][b.index], -1.5, 1.5));

  let people = [];
  let day = 0;
  let executions = [];
  let story = [];
  // How far the residents take the traveller's word: up when what they said turns out right, down when wrong.
  let credit = 0;
  let talked = false;
  // Whom the room asked the seers to read tonight, and (the next day) whom they were asked to have read.
  let request = null;
  let asked = null;

  // ---- The table ----------------------------------------------------------------------------------------------------

  function deal() {
    const cast = seats.map((seat) => ({ name: seat.dataset.name, seat, you: false, lines: { ...seat.dataset } }));
    cast.push({ name: "あなた", seat: youSeat, you: true, lines: {} });
    const roles = shuffle(DEAL[cast.length]);
    people = cast.map((person, index) => ({
      ...person, index, role: roles[index], alive: true, diedBy: null, claim: null, results: [], readings: [], spirits: [],
      known: new Map(), hunch: [], heat: [], why: [], prior: [], threat: 0, named: new Set(), covered: new Set(),
      votedFor: [], lastSuspect: null, memo: 0,
    }));
    for (const self of people) {
      self.known.set(self.index, self.role === "wolf" ? "wolf" : "human");
      for (const other of people) {
        self.hunch[other.index] = Math.random();
        self.heat[other.index] = 0;
        self.why[other.index] = new Map();
        self.prior[other.index] = 0;
        if (self.role === "wolf" && other.role === "wolf") self.known.set(other.index, "wolf");
      }
    }
    day = 0;
    executions = [];
    story = [];
    credit = 0;
    request = null;
    asked = null;
    log.replaceChildren();
    chapter("はじまり");
    for (const person of people) {
      person.seat.classList.remove("dead", "wolf-mark", "human-mark", "revealed", "hot", "warm");
      tag(person, "");
      memoButton(person);
    }
  }

  const you = () => people.find((person) => person.you);
  const living = () => people.filter((person) => person.alive);
  const others = (self) => living().filter((person) => person !== self);
  const wolves = () => living().filter((person) => person.role === "wolf");
  const seers = () => people.filter((person) => person.claim === "seer");
  const mediums = () => people.filter((person) => person.claim === "medium");
  const nameOf = (person) => (person.you ? YOU : person.name);
  const short = (person) => (person.you ? "あなた" : person.name);
  const side = (role) => (role === "wolf" || role === "madman" ? "wolf" : "village");
  const today = () => story.at(-1);

  // ---- What a resident thinks ---------------------------------------------------------------------------------------

  /** Whether self knows for certain that one of these results is a lie. */
  const caught = (self, results) => results.some((result) => {
    const known = self.known.get(result.target);
    return known && (known === "wolf") !== result.black;
  });

  /**
   * How far self believes that c, one of those claiming role (seer, medium, hunter), is the real one (0..1): one who claims it
   * themselves takes the others for fakes; a claim that lies about what self knows is a lie; one the wolves killed was
   * the real one; between two, the one self trusts more.
   */
  function trustIn(self, c, role, weigh = () => 1) {
    if (self === c) return self.role === role ? 1 : 0;
    if (self.role === role && !self.you || self.claim === role) return 0;
    if (caught(self, role === "seer" ? c.results : role === "medium" ? c.spirits : [])) return 0;
    const all = people.filter((other) => other.claim === role && other !== self);
    if (all.length <= 1) return (role === "seer" ? 0.85 : role === "medium" ? 0.9 : 0.75) * Math.min(1, weigh(c));
    const eaten = all.find((other) => other.diedBy === "wolf");
    if (eaten) return (eaten === c ? 0.95 : 0.1) * Math.min(1, weigh(c));
    const weight = (other) => self.prior[other.index] * weigh(other);
    const sum = all.reduce((total, other) => total + weight(other), 0);
    return sum > 0 ? weight(c) / sum : 0.5;
  }

  const mediumBelief = (self, m) => trustIn(self, m, "medium");
  const hunterBelief = (self, h) => trustIn(self, h, "hunter");

  /**
   * How far self believes that claimant c is the real seer. What a believed medium said of the executed weighs on each
   * seer claimant: a reading it contradicts counts against them, a wolf it confirms counts for them.
   */
  function belief(self, c) {
    const weigh = (claimant) => {
      let factor = 1;
      for (const m of mediums()) {
        if (m === claimant || m === self && self.role !== "medium") continue;
        const trust = mediumBelief(self, m);
        for (const spirit of m.spirits) {
          const said = claimant.results.find((result) => result.target === spirit.target);
          if (!said) continue;
          factor *= said.black !== spirit.black ? 1 - 0.85 * trust : said.black ? 1 + 2 * trust : 1;
        }
      }
      return factor;
    };
    return trustIn(self, c, "seer", weigh);
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

  /** Why self suspects target, each part with its weight; the strongest first. */
  function reasons(self, target) {
    const parts = [{ weight: self.hunch[target.index] * 0.8, text: "どうも引っかかる" }];
    for (const reason of self.why[target.index].values()) parts.push(reason);
    const dislike = -0.9 * fond(self, target);
    if (dislike > 0.2) parts.push({ weight: dislike, text: rivals.has(self.index + ":" + target.index) ? "宿敵に気を許すわけにはいかない" : "前から反りが合わない" });
    for (const c of seers()) {
      if (c === self) continue;
      const trust = belief(self, c);
      const quarrel = target.claim === "seer" ? 0.3 : 1;
      for (const result of c.results) if (result.target === target.index && result.black) parts.push({ weight: trust * quarrel * 4, text: c.name + "の占いで人狼と出た" });
    }
    if (target.claim === "seer") {
      const doubt = (0.5 - belief(self, target)) * 3;
      if (doubt > 0) parts.push({ weight: doubt, text: seers().length > 1 ? "本物の占い師とは思えない" : "占い師を名乗ったのが信じられない" });
    }
    if (target.claim === "medium") {
      const doubt = (0.5 - mediumBelief(self, target)) * 3;
      if (doubt > 0) parts.push({ weight: doubt, text: "本物の霊媒師とは思えない" });
    }
    if (target.claim === "hunter") {
      const doubt = (0.5 - hunterBelief(self, target)) * 3;
      if (doubt > 0) parts.push({ weight: doubt, text: "狩人を名乗ったのは追い出されたくないからだ" });
    }
    return parts.sort((a, b) => b.weight - a.weight);
  }

  /** How much self suspects target: the higher, the more likely a wolf in self's eyes. */
  function suspicion(self, target) {
    const known = self.known.get(target.index);
    if (known) return known === "wolf" ? 100 : -100;
    let score = self.hunch[target.index] + self.heat[target.index] - 0.9 * fond(self, target);
    for (const c of seers()) {
      if (c === self) continue;
      const trust = belief(self, c);
      // A black between rival seers is part of their quarrel, and counts for little.
      const quarrel = target.claim === "seer" ? 0.3 : 1;
      for (const result of c.results) if (result.target === target.index) score += trust * quarrel * (result.black ? 5.5 : -2.5);
    }
    // A claimant they believe is someone they would keep; one they doubt, someone they would hang.
    if (target.claim === "seer") {
      const trust = belief(self, target);
      // While it is still unclear which seer is real, they keep both and look among the others first.
      score += self.role === "seer" && !self.you ? 5 : (0.5 - trust) * 3 - (trust >= 0.25 && trust <= 0.75 && living().filter((p) => !p.claim).length > 3 ? 1.2 : 0);
    }
    if (target.claim === "medium") score += self.role === "medium" && !self.you ? 5 : (0.5 - mediumBelief(self, target)) * 3;
    if (target.claim === "hunter") score += self.role === "hunter" && !self.you ? 5 : (0.5 - hunterBelief(self, target)) * 3;
    return score;
  }

  /** The room's suspicion of target, as the residents who are not wolves see it. */
  const roomOn = (target) => {
    const room = living().filter((person) => person.role !== "wolf" && !person.you && person !== target);
    return room.reduce((total, person) => total + suspicion(person, target), 0) / Math.max(1, room.length);
  };

  /**
   * Whom self names or votes for among pool: a wolf against the room's favourite non-wolf, anyone else their top
   * suspect, leaning towards whoever already has votes (votes: the tally so far) and whom someone dear voted for
   * (ballots: who voted whom).
   */
  function choice(self, pool = others(self), votes = new Map(), ballots = new Map()) {
    const candidates = pool.filter((person) => person !== self);
    if (!candidates.length) return null;
    if (self.role === "wolf") {
      // A partner the vote is already going against may be dropped, to look clean.
      const doomed = candidates.find((person) => person.role === "wolf" && (votes.get(person) || 0) >= living().length / 2 - 1);
      if (doomed && Math.random() < 0.5) return doomed;
      const prey = candidates.filter((person) => person.role !== "wolf");
      if (!prey.length) return pick(candidates);
      return top(prey.map((target) => [target, roomOn(target) + Math.random() * 0.8 - 0.3 * fond(self, target)]));
    }
    return top(candidates.map((target) => {
      let score = suspicion(self, target) + 0.35 * (votes.get(target) || 0) + Math.random() * 0.6;
      for (const [voter, voted] of ballots) if (voted === target && fond(self, voter) > 0.6) score += 0.5 * fond(self, voter);
      return [target, score];
    }));
  }

  /** The reason self gives for suspecting target (a wolf gives one the room would accept). */
  function reasonFor(self, target) {
    if (self.role === "wolf") {
      const room = living().filter((person) => person !== self && person !== target && person.role !== "wolf" && !person.you);
      const all = room.flatMap((person) => reasons(person, target).slice(0, 1));
      const best = all.sort((a, b) => b.weight - a.weight)[0];
      return best ? best.text : "どうも引っかかる";
    }
    return reasons(self, target)[0].text;
  }

  /**
   * Everyone else hears speaker name target. It weighs on them as far as they trust the speaker (and the traveller as
   * far as their word has held); one who is fond of the target holds it against the speaker, and so does one who knows
   * the target is human.
   */
  function heard(speaker, target) {
    target.named.add(speaker.index);
    const namers = [...target.named].filter((index) => index !== target.index);
    const text = namers.length > 1 ? "何人もが怪しいと言っている" : short(speaker) + "も怪しいと言っていた";
    for (const person of people) {
      if (person === speaker || person === target || person.you) continue;
      const weight = speaker.you ? 0.5 * clamp(1 + 0.4 * credit, 0.3, 2) : 0.5 * (1 + 0.4 * fond(person, speaker));
      warm(person, target, weight, "named", text);
      if (fond(person, target) > 0.5) warm(person, speaker, 0.25 * fond(person, target), "accused:" + target.index, "大事な" + nameOf(target) + "を疑った");
      if (person.known.get(target.index) === "human") warm(person, speaker, 0.8, "wrong:" + target.index, "人間の" + nameOf(target) + "を疑った");
    }
    if (target.role === "wolf") speaker.threat += 1;
  }

  /** Everyone else hears speaker stand up for target: it eases them as far as they trust the speaker. */
  function vouched(speaker, target) {
    target.covered.add(speaker.index);
    today()?.covers.push({ by: speaker, target });
    for (const person of people) {
      if (person === speaker || person === target || person.you) continue;
      person.heat[target.index] -= 0.35 * (1 + 0.5 * fond(person, speaker));
      if (person.known.get(target.index) === "wolf") warm(person, speaker, 1, "cover:" + target.index, "人狼の" + nameOf(target) + "をかばった");
    }
  }

  /**
   * What one turned out to be, as the medium m said: whoever pushed for or stood up for them is weighed by it, as far as
   * each one believes m; the traveller's word gains or loses its weight.
   */
  function revealed(target, black, m) {
    const word = (black ? "人狼だった" : "人間だった") + nameOf(target);
    for (const person of people) {
      if (person.you || !person.alive) continue;
      const sure = mediumBelief(person, m);
      for (const other of people) {
        if (other === person) continue;
        if (black) {
          if (target.covered.has(other.index)) warm(person, other, 0.7 * sure, "cover:" + target.index, word + "をかばっていた");
          if (target.named.has(other.index) || other.votedFor.at(-1) === target.index) person.heat[other.index] -= 0.4 * sure;
        } else {
          if (target.named.has(other.index)) warm(person, other, 0.35 * sure, "named:" + target.index, word + "を疑っていた");
          if (other.votedFor.at(-1) === target.index) warm(person, other, 0.15 * sure, "voted:" + target.index, word + "に票を入れていた");
        }
      }
    }
    const self = you();
    if (target.named.has(self.index) || self.votedFor.at(-1) === target.index) credit += black ? 1 : -1;
    if (target.covered.has(self.index)) credit += black ? -1 : 1;
  }

  /** The wolves took someone: whom they suspected is taken more seriously (they may have been onto something). */
  function mourned(victim) {
    if (victim.lastSuspect === null) return;
    const suspect = people[victim.lastSuspect];
    for (const person of people) {
      if (!person.you && person.alive && person !== suspect) warm(person, suspect, 0.45, "mourn:" + victim.index, "襲われた" + victim.name + "が疑っていた");
    }
  }

  // ---- Showing it ---------------------------------------------------------------------------------------------------

  // Lines appear one by one (unless the traveller asked to see them at once); the buttons wait until all are shown.
  let fast = stored(FAST) === "1";
  let shown = [];
  let pumping = false;
  let waiting = [];
  let page = null;

  function pump() {
    if (pumping) return;
    pumping = true;
    const step = () => {
      const item = shown.shift();
      if (!item) {
        pumping = false;
        waiting.splice(0).forEach((resolve) => resolve());
        return;
      }
      item.hidden = false;
      log.scrollTop = log.scrollHeight;
      setTimeout(step, fast ? 0 : item.classList?.contains("vote") ? 90 : 420);
    };
    step();
  }

  const drained = () => (shown.length || pumping ? new Promise((resolve) => waiting.push(resolve)) : Promise.resolve());

  function post(item) {
    if (!page) chapter("");
    item.hidden = !fast;
    page.append(item);
    if (fast) log.scrollTop = log.scrollHeight;
    else {
      shown.push(item);
      pump();
    }
  }

  /** A new part of the log (a day), the earlier ones folded away. */
  function chapter(title) {
    for (const open of log.querySelectorAll?.("details[open]") || []) open.open = false;
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

  function narrate(text, kind = "") {
    const item = document.createElement("li");
    item.className = "wolf-narr" + (kind ? " " + kind : "");
    item.textContent = text;
    post(item);
  }

  /** A line said by person, with the reason they give (why), if any. */
  function speak(person, text, why = null) {
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
    post(item);
  }

  /** A resident's line of this kind about target ("さん" is left off for the traveller), one of its wordings. */
  function say(person, kind, target = null, why = null) {
    let text = pick((person.lines[kind] || "").split("\n"));
    if (target) {
      const name = nameOf(target);
      text = text.replaceAll("{t}さん", target.you ? name : name + "さん").replaceAll("{t}", name);
    }
    speak(person, text, why);
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

  /** The seats as the traveller sees them; counts: the room's mood, to show who is under suspicion. */
  function mark(counts = null) {
    const self = you();
    for (const person of people) {
      person.seat.classList.toggle("dead", !person.alive);
      const known = self.known.get(person.index);
      person.seat.classList.toggle("wolf-mark", person !== self && known === "wolf");
      person.seat.classList.toggle("human-mark", person !== self && known === "human");
      if (counts) {
        const heat = person.alive ? counts.get(person) || 0 : 0;
        person.seat.classList.toggle("hot", heat >= 2);
        person.seat.classList.toggle("warm", heat === 1);
      }
      const notes = [];
      if (person.claim) notes.push("自称" + ROLES[person.claim]);
      if (!person.alive) notes.push(person.diedBy === "vote" ? "追放" : "襲撃");
      tag(person, notes.join("・"));
    }
    status.textContent = (day === 0 ? "最初の夜" : day + "日目") + " ・ あなたは" + ROLES[self.role] + (self.alive ? "" : "（脱落）") + " ・ 残り " + living().length + "人";
    drawBoard();
  }

  /** The claims and what each said, and who left the table how. */
  function drawBoard() {
    if (!board) return;
    board.replaceChildren();
    const claimants = people.filter((person) => person.claim);
    const gone = people.filter((person) => !person.alive);
    if (!claimants.length && !gone.length) return;
    const list = document.createElement("dl");
    for (const person of claimants) {
      const term = document.createElement("dt");
      term.textContent = short(person) + "（" + ROLES[person.claim] + "）";
      const said = document.createElement("dd");
      const results = person.claim === "seer" ? person.results : person.claim === "medium" ? person.spirits : [];
      if (person.claim === "hunter") said.textContent = "夜を守っていると言う";
      else if (!results.length) said.textContent = "まだ結果なし";
      for (const result of results) {
        const chip = document.createElement("span");
        chip.className = "wolf-chip " + (result.black ? "black" : "white");
        chip.textContent = short(people[result.target]) + (result.black ? " ●人狼" : " ○人間");
        said.append(chip);
      }
      const trust = document.createElement("span");
      trust.className = "wolf-believers";
      trust.textContent = "信じる人 " + believers(person) + "人";
      said.append(trust);
      list.append(term, said);
    }
    if (request && request.alive) {
      const term = document.createElement("dt");
      term.textContent = "今夜の占い先";
      const said = document.createElement("dd");
      said.textContent = short(request);
      list.append(term, said);
    }
    if (gone.length) {
      const term = document.createElement("dt");
      term.textContent = "いなくなった人";
      const said = document.createElement("dd");
      said.textContent = gone.map((person) => short(person) + "（" + (person.diedBy === "vote" ? "追放" : "襲撃") + "）").join("、");
      list.append(term, said);
    }
    board.append(list);
  }

  /** How many living residents (other than those claiming the same) believe c over anyone else claiming it. */
  function believers(c) {
    const trust = (self, other) => (other.claim === "seer" ? belief(self, other) : trustIn(self, other, other.claim));
    return living().filter((self) => !self.you && self !== c && self.claim !== c.claim).filter((self) => {
      const mine = trust(self, c);
      return mine >= 0.4 && people.every((other) => other === c || other.claim !== c.claim || trust(self, other) <= mine);
    }).length;
  }

  /** The bonds at the table, which everyone in the guild knows of (not the crushes, nor the numbers). */
  function showTies() {
    if (!tiesNote) return;
    const words = bonds.map(({ a, b, label }) => people[a].name + "と" + people[b].name + "は" + BONDS[label]);
    tiesNote.textContent = words.length ? "卓の顔ぶれ：" + words.join("。") + "。" : "卓の顔ぶれ：とくに深い仲の人どうしはいないようだ。";
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
      if (fast) {
        shown.forEach((item) => { item.hidden = false; });
        shown = [];
        log.scrollTop = log.scrollHeight;
      }
    });
  }

  // ---- The record (this browser only) -----------------------------------------------------------------------------

  function readRecord() {
    try {
      const record = JSON.parse(stored(RECORD));
      if (record && typeof record.games === "number") return record;
    } catch { /* none yet */ }
    return { games: 0, wins: 0, roles: {}, fooled: {} };
  }

  function showRecord(record = readRecord()) {
    if (!recordNote) return;
    if (!record.games) {
      recordNote.textContent = "";
      return;
    }
    const roles = Object.entries(record.roles).map(([role, [games, wins]]) => (ROLES[role] || role) + " " + wins + "/" + games).join("・");
    const fooled = Object.entries(record.fooled).sort((a, b) => b[1] - a[1])[0];
    recordNote.textContent = "戦績（このブラウザ）：" + record.games + "戦" + record.wins + "勝（" + roles + "）"
      + (fooled ? " ・ いちばん騙された相手：" + fooled[0] + "（" + fooled[1] + "回）" : "");
  }

  function keepRecord(won) {
    const record = readRecord();
    const self = you();
    record.games++;
    if (won) record.wins++;
    const [games, wins] = record.roles[self.role] || [0, 0];
    record.roles[self.role] = [games + 1, wins + (won ? 1 : 0)];
    // The wolves who outlasted the traveller's side fooled them.
    if (!won && side(self.role) === "village") for (const wolf of people.filter((person) => person.role === "wolf" && !person.you)) record.fooled[wolf.name] = (record.fooled[wolf.name] || 0) + 1;
    store(RECORD, JSON.stringify(record));
    showRecord(record);
  }

  // ---- The game -----------------------------------------------------------------------------------------------------

  async function play() {
    deal();
    const self = you();
    showTies();
    showRecord();
    mark();
    narrate("酒場の灯りが落とされ、" + people.length + "人が卓を囲んだ。この中に人狼が" + wolves().length + "人まぎれている。", "big");
    const special = DEAL[people.length].filter((role) => role !== "villager" && role !== "wolf").map((role) => ROLES[role]);
    narrate("役目は人狼" + wolves().length + "人・" + special.join("・") + "、あとは村人。住人どうしの仲は、疑い方にも表れる。");
    let role = "あなたの役目は【" + ROLES[self.role] + "】。";
    if (self.role === "wolf") {
      const partner = wolves().find((wolf) => wolf !== self);
      role += partner ? "仲間の人狼は" + partner.name + "だ。" : "仲間はいない。ひとりで村を欺け。";
    }
    if (self.role === "seer") role += "毎晩ひとりを占い、人狼かどうかを知ることができる。";
    if (self.role === "medium") role += "追い出された人が人狼だったかどうかを、その夜に知ることができる。";
    if (self.role === "hunter") role += "毎晩ひとりを人狼の牙から守ることができる（自分は守れない）。";
    if (self.role === "madman") role += "人間だが、人狼の勝ちを願っている。人狼が誰かは知らない。占い師を騙って村を惑わせよう。";
    if (self.role === "villager") role += "話を聞き、投票で人狼を見つけ出そう。";
    narrate(role, "secret");
    await firstNight();
    for (day = 1; ; day++) {
      story.push({ day, claims: [], results: [], named: [], covers: [], executed: null, votes: 0, yourVote: null, killed: null, guarded: null, runoff: false, request: null });
      chapter(day + "日目");
      mark();
      await talk();
      if (await vote()) return;
      if (await night()) return;
    }
  }

  async function firstNight() {
    const self = you();
    // Each seer starts with one reading of someone who is not a wolf.
    for (const seer of people.filter((person) => person.role === "seer")) {
      const target = pick(people.filter((person) => person !== seer && person.role !== "wolf"));
      read(seer, target);
      if (seer === self) narrate("最初の夜、あなたは" + target.name + "を占った。……人間だ。", "secret");
    }
    if (self.role === "wolf" && wolves().length > 1) narrate("灯りが消えた卓の下で、仲間の" + wolves().find((wolf) => wolf !== self).name + "と目を合わせた。", "secret");
    mark();
    await next("夜が明ける");
  }

  /** seer reads target: now they know. */
  function read(seer, target) {
    const black = target.role === "wolf";
    seer.known.set(target.index, black ? "wolf" : "human");
    seer.readings.push({ target: target.index, black });
  }

  /** Someone claims a role for the first time: everyone weighs it by how far they trust them. */
  function claims(person, role) {
    person.claim = role;
    today()?.claims.push({ by: person, role });
    for (const other of people) other.prior[person.index] = clamp(0.35 + Math.random() * 0.3 + 0.25 * fond(other, person), 0.05, 0.95);
  }

  /** A seer claimant's result said aloud. */
  function report(person, result) {
    person.results.push(result);
    today()?.results.push({ by: person, kind: "seer", ...result });
    say(person, result.black ? "black" : "white", people[result.target]);
    if (result.black) heard(person, people[result.target]);
  }

  /**
   * A made-up reading. asked: whom the room asked the seers to read last night, which a fake has to answer for too (a
   * wolf calls a partner human). Otherwise a wolf may call its partner human to vouch for them, and either may call a
   * rival claimant, one the real seer called human (to make them clash) or one they dislike a wolf. A wolf knows whom to
   * spare; a madman does not.
   */
  function lie(liar, asked = null) {
    const told = (person) => liar.results.some((result) => result.target === person.index);
    const wolfish = liar.role === "wolf";
    if (asked && asked.alive && asked !== liar && !told(asked)) {
      return { target: asked.index, black: wolfish ? asked.role !== "wolf" && Math.random() < 0.3 : Math.random() < 0.35 };
    }
    const pool = others(liar).filter((person) => !told(person));
    if (!pool.length) return null;
    const partner = wolfish && pool.find((person) => person.role === "wolf");
    if (partner && Math.random() < 0.2) return { target: partner.index, black: false };
    const humans = wolfish ? pool.filter((person) => person.role !== "wolf") : pool;
    if (humans.length && Math.random() < (liar.role === "madman" ? 0.45 : 0.4)) {
      const whited = new Set(seers().filter((c) => c !== liar).flatMap((c) => c.results.filter((result) => !result.black).map((result) => result.target)));
      return { target: top(humans.map((person) => [person, Math.random() - fond(liar, person) + (person.claim === "seer" ? 0.6 : 0)
        + (whited.has(person.index) ? 0.5 : 0)])).index, black: true };
    }
    return { target: pick(pool).index, black: false };
  }

  /** A medium claimant tells of the executed: the truth from the real one, lies from a fake (whatever suits the wolves). */
  function spiritsOf(medium, told = null) {
    for (const executed of executions.filter((person) => !medium.spirits.some((spirit) => spirit.target === person.index))) {
      const real = medium.role === "medium";
      const black = told ? told.get(executed) : real ? executed.role === "wolf" : executed.role === "wolf" ? false : Math.random() < 0.4;
      const spirit = { target: executed.index, black };
      medium.spirits.push(spirit);
      today()?.results.push({ by: medium, kind: "medium", ...spirit });
      if (medium.you) speak(medium, executed.name + "は" + (black ? "人狼だった。" : "人間だった。"));
      else say(medium, black ? "spirit_black" : "spirit_white", executed);
      revealed(executed, black, medium);
    }
  }

  async function talk() {
    const self = you();
    talked = false;
    asked = request;
    request = null;
    narrate(day + "日目の昼。卓を囲んで話し合いが始まる。", "big");
    const npcs = shuffle(living().filter((person) => !person.you));
    if (day === 1) for (const person of npcs.slice(0, 2)) say(person, "greet");
    const speakers = [];

    // The mediums speak of the executed first, once there is something to tell; a wolf may claim to be one too.
    if (executions.length) {
      const medium = npcs.find((person) => person.role === "medium");
      if (medium && (medium.claim || Math.random() < 0.95)) {
        if (!medium.claim) {
          say(medium, "medium");
          claims(medium, "medium");
        }
        spiritsOf(medium);
        speakers.push(medium);
      }
      let fakeMedium = npcs.find((person) => person.role === "wolf" && person.claim === "medium");
      if (!fakeMedium && wolves().length > 1 && !wolves().some((wolf) => wolf.claim) && Math.random() < (mediums().length ? 0.1 : 0.05)) {
        fakeMedium = npcs.find((person) => person.role === "wolf");
        if (fakeMedium) {
          say(fakeMedium, "medium");
          claims(fakeMedium, "medium");
        }
      }
      if (fakeMedium) {
        spiritsOf(fakeMedium);
        speakers.push(fakeMedium);
      }
      mark();
    }

    // Then the seers. The real one comes out on the first day, and someone always claims against them: the madman if
    // there is one (most of the time), else a wolf; with two wolves, now and then both. So there is always a question
    // of which one to believe.
    const seer = npcs.find((person) => person.role === "seer");
    const seerSpeakers = [];
    if (seer && (seer.claim || day === 1)) seerSpeakers.push(seer);
    seerSpeakers.push(...npcs.filter((person) => person.claim === "seer" && person.role !== "seer"));
    if (day === 1) {
      const madman = npcs.find((person) => person.role === "madman");
      const liar = npcs.find((person) => person.role === "wolf" && !person.claim);
      const fakes = [];
      if (madman && Math.random() < 0.65) fakes.push(madman);
      if (liar && (!fakes.length || wolves().length > 1 && Math.random() < 0.05)) fakes.push(liar);
      if (!fakes.length && madman) fakes.push(madman);
      seerSpeakers.push(...fakes);
    }
    for (const person of shuffle(seerSpeakers)) {
      if (!person.claim) {
        say(person, seers().length ? "counter" : "co");
        claims(person, "seer");
        // A seer's first word gives every reading so far.
        if (person.role === "seer") for (const reading of person.readings) report(person, reading);
        else { const result = lie(person, asked); if (result) report(person, result); }
      } else if (person.role === "seer") {
        const reading = person.readings.at(-1);
        if (reading && !person.results.some((result) => result.target === reading.target)) report(person, reading);
      } else {
        const result = lie(person, asked);
        if (result) report(person, result);
      }
      speakers.push(person);
      mark();
    }

    if (self.alive) await yourTurn();
    else await next("話し合いを見守る");

    // Everyone else names whom they suspect most, and why. A lurking wolf may cut loose a partner the room is set on.
    const named = new Map();
    const counts = mood();
    const favourite = top([...counts]);
    for (const person of npcs.filter((npc) => !speakers.includes(npc))) {
      let target = choice(person);
      if (person.role === "wolf" && favourite && favourite.role === "wolf" && favourite !== person && favourite.alive && Math.random() < 0.3) target = favourite;
      if (!target) continue;
      say(person, "suspect", target, reasonFor(person, target));
      heard(person, target);
      person.lastSuspect = target.index;
      today().named.push({ by: person, target });
      named.set(target, (named.get(target) || 0) + 1);
    }
    // Someone who cares for one who was named stands up for them (at most two a day); a wolf may for its partner.
    let covers = 0;
    for (const [target] of [...named].sort((a, b) => b[1] - a[1])) {
      if (covers >= 2) break;
      const friend = top(npcs.filter((npc) => npc !== target && !target.named.has(npc.index))
        .map((npc) => [npc, fond(npc, target) + (npc.role === "wolf" && target.role === "wolf" ? 0.9 : 0) + Math.random() * 0.3])
        .concat([[null, 0.75]]));
      if (!friend) continue;
      say(friend, "cover", target);
      vouched(friend, target);
      covers++;
    }
    // Whoever is named twice (or by the traveller) answers for themselves, or claims to be the hunter.
    for (const [target, count] of named) {
      if (target.you || !target.alive || count < 2 && !target.namedByYou) continue;
      if (await dodge(target)) continue;
      say(target, "defend");
      for (const person of people) if (person !== target) person.heat[target.index] = Math.max(0, person.heat[target.index] - 0.3);
    }
    for (const person of people) person.namedByYou = false;
    const after = mood();
    // If no one asked yet, someone asks the seers whom to read tonight: the one the room doubts most.
    if (!request && seers().some((c) => c.alive) && Math.random() < 0.7) {
      const asker = pick(npcs.filter((npc) => npc.alive && !npc.claim));
      const target = top([...after].filter(([person]) => person.alive && !person.claim && person !== asker));
      if (asker && target) {
        request = target;
        today().request = { by: asker, target };
        narrate(asker.name + "が「占い師は今夜、" + nameOf(target) + "を占ってほしい」と言った。", "air");
      }
    }
    mark(after);
    air(after);
  }

  /**
   * One about to be sent out may claim to be the hunter: the real one to save themselves, a cornered wolf to steal the
   * name. The real hunter will not let it go (the traveller is asked); true when target claimed.
   */
  async function dodge(target) {
    if (target.claim) return false;
    const taken = people.some((person) => person.claim === "hunter");
    const real = target.role === "hunter" && Math.random() < 0.75;
    const fake = target.role === "wolf" && !taken && Math.random() < 0.35;
    if (!real && !fake) return false;
    say(target, "hunter");
    claims(target, "hunter");
    const hunter = living().find((person) => person.role === "hunter" && !person.claim);
    if (fake && hunter) {
      if (hunter.you) {
        mark();
        if (await ask(target.name + "が狩人を名乗った。本物の狩人はあなただ。名乗り出る？", [{ label: "名乗り出る", value: true }, { label: "黙っている", value: false, quiet: true }])) {
          speak(hunter, "待ってほしい。本物の狩人はわたしだ。");
          claims(hunter, "hunter");
        }
      } else if (Math.random() < 0.8) {
        say(hunter, "hunter");
        claims(hunter, "hunter");
      }
    }
    mark();
    return true;
  }

  /** The room's mood, said aloud: whom the suspicion is gathering on. */
  function air(counts) {
    const ranked = [...counts].sort((a, b) => b[1] - a[1]).slice(0, 2);
    if (!ranked.length) return;
    narrate("卓の空気：" + ranked.map(([target, count]) => short(target) + "に" + count + "人").join("、") + "の疑いが向いている。", "air");
  }

  async function yourTurn() {
    const self = you();
    for (;;) {
      const options = [];
      if (!talked) options.push({ label: "住人に話しかける", value: "talk" });
      if (!request && seers().some((c) => c.alive && c !== self)) options.push({ label: "今夜の占い先を頼む", value: "request" });
      const untold = executions.some((person) => !self.spirits.some((spirit) => spirit.target === person.index));
      if (!self.claim) {
        options.push({ label: "占い師だと名乗る", value: "claim" }, { label: "霊媒師だと名乗る", value: "medium" }, { label: "狩人だと名乗る", value: "hunter" });
      } else if (self.claim === "seer") options.push({ label: "占いの結果を伝える", value: "result" });
      else if (self.claim === "medium" && untold) options.push({ label: "霊媒の結果を伝える", value: "medium" });
      options.push({ label: "怪しい人を名指しする", value: "suspect" }, { label: "誰かをかばう", value: "cover" }, { label: "黙って聞いている", value: "quiet", quiet: true });
      const action = await ask("あなたの番だ。どうする？", options);
      if (action === "talk") {
        await converse();
        continue;
      }
      if (action === "request") {
        const target = await pickPerson("占い師に、今夜誰を占ってほしい？", others(self).filter((person) => person.claim !== "seer"), true);
        if (target) {
          speak(self, "占い師は今夜、" + target.name + "を占ってほしい。");
          request = target;
          today().request = { by: self, target };
          mark();
        }
        continue;
      }
      await act(action);
      return;
    }
  }

  /** Talking to one resident before speaking up: asking whom they will vote for, or telling them what you think. */
  async function converse() {
    const self = you();
    const person = await pickPerson("誰に話しかける？", others(self), true);
    if (!person) return;
    talked = true;
    const how = await ask(person.name + "に何を話す？", [
      { label: "誰に入れるつもりか聞く", value: "ask" },
      { label: "「この人は人狼じゃない」と伝える", value: "white" },
      { label: "「この人が怪しい」と伝える", value: "black" },
    ]);
    if (how === "ask") {
      const target = choice(person);
      if (!target) return;
      say(person, "vote", target, reasonFor(person, target));
      return;
    }
    const target = await pickPerson(how === "white" ? "誰のことを伝える？（人狼じゃない）" : "誰のことを伝える？（怪しい）", others(self).filter((other) => other !== person));
    speak(self, target.name + (how === "white" ? "は人狼じゃないと思う。" : "が怪しいと思う。"));
    const black = how === "black";
    const known = person.known.get(target.index);
    // A resident won't be told otherwise about what they know; a wolf agrees when it suits the wolves.
    if (person.role !== "wolf" && known && (known === "wolf") !== black) {
      narrate(person.name + "はきっぱりと首を振った。");
      return;
    }
    const chance = person.role === "wolf" ? (black === (target.role !== "wolf") ? 0.9 : 0.1)
      : clamp(0.35 + 0.15 * credit + 0.3 * usual[person.index] - 0.2 * fond(person, target) * (black ? 1 : -1), 0.05, 0.9);
    if (Math.random() < chance) {
      narrate(person.name + "は少し考えて、うなずいた。");
      if (black) warm(person, target, 1.1, "told", "旅の人の話を聞いて、気になってきた");
      else person.heat[target.index] -= 1.1;
    } else {
      narrate(person.name + "は首をかしげた。納得はしていないようだ。");
    }
  }

  async function act(action) {
    const self = you();
    if (action === "quiet") {
      narrate("あなたは黙って皆の話に耳を傾けた。");
      return;
    }
    if (action === "suspect") {
      const target = await pickPerson("誰を名指しする？", others(self));
      speak(self, target.name + "が怪しいと思う。");
      heard(self, target);
      today().named.push({ by: self, target });
      target.namedByYou = true;
      return;
    }
    if (action === "cover") {
      const target = await pickPerson("誰をかばう？", others(self));
      speak(self, target.name + "は人狼じゃないと思う。");
      vouched(self, target);
      return;
    }
    if (action === "hunter") {
      speak(self, people.some((person) => person.claim === "hunter") ? "待ってほしい。本物の狩人はわたしだ。" : "わたしは狩人だ。");
      claims(self, "hunter");
      mark();
      return;
    }
    if (action === "medium") {
      if (!self.claim) {
        speak(self, mediums().length ? "待ってほしい。本物の霊媒師はわたしだ。" : "わたしは霊媒師だ。");
        claims(self, "medium");
      }
      const untold = executions.filter((person) => !self.spirits.some((spirit) => spirit.target === person.index));
      if (!untold.length) speak(self, "まだ伝えることはない。");
      else if (self.role === "medium") spiritsOf(self);
      else {
        const told = new Map();
        for (const executed of untold) told.set(executed, await ask(executed.name + "の結果は？", [{ label: "人間だった", value: false }, { label: "人狼だった", value: true }]));
        spiritsOf(self, told);
      }
      mark();
      return;
    }
    if (action === "claim") {
      speak(self, seers().length ? "待ってほしい。本物の占い師はわたしだ。" : "わたしが占い師だ。");
      claims(self, "seer");
    }
    if (self.role === "seer") {
      const untold = self.readings.filter((reading) => !self.results.some((result) => result.target === reading.target));
      for (const reading of untold) {
        self.results.push(reading);
        today().results.push({ by: self, kind: "seer", ...reading });
        speak(self, people[reading.target].name + "を占った。" + (reading.black ? "人狼だ！" : "人間だった。"));
        if (reading.black) heard(self, people[reading.target]);
      }
      if (!untold.length) speak(self, "新しく伝えることはまだない。");
    } else {
      const pool = others(self).filter((person) => !self.results.some((result) => result.target === person.index));
      if (pool.length) {
        const target = await pickPerson("誰を占ったことにする？", pool);
        const black = await ask(target.name + "の結果は？", [{ label: "人間だった", value: false }, { label: "人狼だった", value: true }]);
        self.results.push({ target: target.index, black });
        today().results.push({ by: self, kind: "seer", target: target.index, black });
        speak(self, target.name + "を占った。" + (black ? "人狼だ！" : "人間だった。"));
        if (black) heard(self, target);
      }
    }
    mark();
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
    for (const [voter, target] of ballots) narrate(voter.name + " → " + target.name, "vote");
    return { tally, ballots };
  }

  /** The vote, with a run-off between those tied; true when the game ended with it. */
  async function vote() {
    narrate("投票の時間だ。", "big");
    let { tally, ballots } = await ballot(living(), "誰に投票する？");
    let most = Math.max(...tally.values());
    let tied = [...tally].filter(([, count]) => count === most).map(([target]) => target);
    if (tied.length > 1) {
      today().runoff = true;
      narrate(tied.map((person) => person.name).join("と") + "が" + most + "票で並んだ。決選投票だ。", "big");
      for (const person of tied) if (!person.you) say(person, "defend");
      ({ tally, ballots } = await ballot(tied, "決選投票。誰に入れる？"));
      most = Math.max(...tally.values());
      tied = [...tally].filter(([, count]) => count === most).map(([target]) => target);
      if (tied.length > 1) narrate("それでも決まらず、くじ引きになった。");
    }
    const executed = pick(tied);
    const voiced = [...ballots].find(([voter, target]) => target === executed && !voter.you);
    if (voiced) say(voiced[0], "vote", executed);
    executed.alive = false;
    executed.diedBy = "vote";
    executions.push(executed);
    today().executed = executed;
    today().votes = most;
    narrate(executed.name + "は" + most + "票を集め、酒場から追い出された。", "death");
    for (const medium of living().filter((person) => person.role === "medium")) {
      const black = executed.role === "wolf";
      medium.readings.push({ target: executed.index, black });
      medium.known.set(executed.index, black ? "wolf" : "human");
    }
    mark();
    return finish() || (await next("夜になる"), false);
  }

  /** The night: the traveller's own deed first, then the residents'; true when the game ended with it. */
  async function night() {
    const self = you();
    narrate("灯りの消えた酒場に、" + day + "日目の夜が来た。", "big");
    let guard = null;
    let prey = null;
    if (self.alive && self.role === "seer") {
      const pool = others(self).filter((person) => !self.known.has(person.index));
      if (pool.length) {
        const target = await pickPerson("誰を占う？" + (request && request.alive ? "（頼まれた占い先：" + short(request) + "）" : ""), pool);
        read(self, target);
        narrate(target.name + "を占った。……" + (target.role === "wolf" ? "人狼だ！" : "人間だ。"), "secret");
      }
    }
    if (self.alive && self.role === "medium") {
      const spirit = self.readings.at(-1);
      if (spirit) narrate("あなたは追い出された" + people[spirit.target].name + "の気配に触れた。……" + (spirit.black ? "人狼だった。" : "人間だった。"), "secret");
    }
    if (self.alive && self.role === "hunter") guard = await pickPerson("今夜は誰を守る？", others(self));
    const partner = wolves().find((wolf) => !wolf.you);
    const pickPrey = () => {
      // Whoever claims or has named a wolf is in more danger; one the wolves are fond of, less.
      const pool = living().filter((person) => person.role !== "wolf");
      const weights = pool.map((person) => Math.max(0.2, 1 + person.threat * 1.5 + (person.claim ? 3 : 0)
        - wolves().reduce((total, wolf) => total + 0.4 * fond(wolf, person), 0)));
      let roll = Math.random() * weights.reduce((a, b) => a + b, 0);
      return pool.find((person, i) => (roll -= weights[i]) < 0) || pool.at(-1);
    };
    if (self.alive && self.role === "wolf") {
      // A partner suggests whom to take, and why.
      if (partner) {
        const idea = pickPrey();
        const why = idea.claim ? ROLES[idea.claim] + "を名乗っている" : idea.threat ? "仲間を怪しんでいる" : "生かしておくと厄介そうだ";
        narrate(partner.name + "が目で合図した。「今夜は" + short(idea) + "にしよう。" + why + "」", "secret");
      }
      prey = await pickPerson("今夜は誰を襲う？", others(self).filter((person) => person.role !== "wolf"));
    }

    for (const seer of living().filter((person) => person.role === "seer" && !person.you)) {
      const pool = others(seer).filter((person) => !seer.known.has(person.index));
      // The one the room asked for, unless already known.
      const wanted = request && pool.includes(request) ? request : null;
      if (pool.length) read(seer, wanted || top(pool.map((target) => [target, suspicion(seer, target) + Math.random()])));
    }
    const hunter = living().find((person) => person.role === "hunter" && !person.you);
    if (hunter) {
      // The claimant they believe, else someone dear to them.
      guard = top(others(hunter).map((target) => [target, (target.claim === "seer" ? belief(hunter, target) * 3 : 0)
        + (target.claim === "medium" ? mediumBelief(hunter, target) * 2 : 0) + 0.5 * fond(hunter, target) + Math.random()]));
    }
    if (!prey && wolves().length) prey = pickPrey();
    mark();
    await next("朝を迎える");
    narrate((day + 1) + "日目の朝。", "big");
    if (prey && prey !== guard) {
      prey.alive = false;
      prey.diedBy = "wolf";
      today().killed = prey;
      narrate("灯りがともると、" + prey.name + "の席が空いていた。……人狼に襲われたらしい。", "death");
      mourned(prey);
    } else {
      if (prey) today().guarded = { hunter: people.find((person) => person.role === "hunter"), target: prey };
      narrate("灯りがともると、誰の席も欠けていなかった。静かな朝だ。");
    }
    mark();
    return finish();
  }

  /** Ends the game when one side has won: shows every role, the review and the record, and offers another round. */
  function finish() {
    const left = wolves().length;
    const humans = living().length - left;
    if (left > 0 && left < humans) return false;
    const villageWon = left === 0;
    const self = you();
    const won = villageWon === (side(self.role) === "village");
    narrate(villageWon ? "人狼はすべて追い出された。村人の勝利！" : "人狼が卓を支配した。人狼の勝利……", "big");
    narrate(won ? "あなたの陣営の勝ちだ。" : "あなたの陣営の負けだ。", "secret");
    narrate("役目：" + people.filter((person) => !person.you).map((person) => person.name + "＝" + ROLES[person.role]).join("、") + "。");
    for (const person of people) {
      person.seat.classList.add("revealed");
      person.seat.classList.remove("hot", "warm");
      tag(person, ROLES[person.role] + (person.alive ? "" : person.diedBy === "vote" ? "・追放" : "・襲撃"));
    }
    status.textContent = "決着 ・ あなたは" + ROLES[self.role];
    review();
    keepRecord(won);
    ask(null, [{ label: "もう一度遊ぶ", value: true }]).then(play);
    return true;
  }

  // ---- The review ---------------------------------------------------------------------------------------------------

  const lab = (person) => short(person) + "〔" + ROLES[person.role] + "〕";

  /** Who did what each day, with every role shown, and the moments that decided it. */
  function review() {
    chapter("振り返り");
    const moments = [];
    for (const record of story) {
      narrate(record.day + "日目", "big review");
      for (const claim of record.claims) {
        const lying = claim.by.role !== claim.role;
        narrate(lab(claim.by) + "が" + ROLES[claim.role] + "を名乗った" + (lying ? "（騙り）" : "") + "。", "review");
        if (lying && !claim.by.you) moments.push(lab(claim.by) + "は" + record.day + "日目に" + ROLES[claim.role] + "を騙った。");
      }
      for (const result of record.results) {
        const target = people[result.target];
        const truth = target.role === "wolf";
        narrate((result.kind === "seer" ? "占い" : "霊媒") + "：" + lab(result.by) + "→" + lab(target) + "は" + (result.black ? "人狼" : "人間")
          + (result.black !== truth ? "（うそ）" : ""), "review");
        if (result.kind === "seer" && result.by.role === "wolf" && target.role === "wolf" && !result.black && !result.by.you) {
          moments.push(lab(result.by) + "は" + record.day + "日目、仲間の" + target.name + "を「人間」と言って囲った。");
        }
      }
      if (record.request) narrate("占い先の指定：" + lab(record.request.by) + "→" + lab(record.request.target), "review");
      if (record.named.length) narrate("名指し：" + record.named.map(({ by, target }) => short(by) + "→" + short(target)).join("、"), "review");
      for (const { by, target } of record.covers) {
        const pack = by.role === "wolf" && target.role === "wolf";
        narrate("かばう：" + lab(by) + "→" + lab(target) + (pack ? "（人狼が仲間を）" : ""), "review");
        if (pack && !by.you) moments.push(lab(by) + "は" + record.day + "日目に仲間の" + target.name + "をかばっていた。");
      }
      if (record.executed) {
        narrate("追放：" + lab(record.executed) + "（" + record.votes + "票" + (record.runoff ? "・決選投票" : "") + "）"
          + (record.yourVote ? " ・ あなたの票：" + lab(record.yourVote) : ""), "review");
      }
      if (record.killed) narrate("襲撃：" + lab(record.killed), "review");
      if (record.guarded) {
        narrate("護衛成功：" + (record.guarded.hunter ? lab(record.guarded.hunter) + "が" : "") + lab(record.guarded.target) + "を守った", "review");
        if (record.guarded.hunter && !record.guarded.hunter.you) moments.push(lab(record.guarded.hunter) + "が" + record.day + "日目の夜、" + record.guarded.target.name + "を人狼から守った。");
      }
    }
    const votes = story.map((record) => record.yourVote).filter(Boolean);
    if (votes.length) {
      const hits = votes.filter((target) => target.role === "wolf").length;
      moments.push("あなたの票は" + votes.length + "回中" + hits + "回、人狼に入っていた。");
    }
    const memos = people.filter((person) => !person.you && (person.memo === 1 || person.memo === 2));
    if (memos.length) {
      const right = memos.filter((person) => (person.memo === 2) === (person.role === "wolf")).length;
      moments.push("あなたの白・黒のメモは" + memos.length + "人中" + right + "人が当たっていた。");
    }
    if (moments.length) {
      narrate("見どころ", "big review");
      for (const moment of moments) narrate(moment, "review");
    }
  }

  play();
})();
