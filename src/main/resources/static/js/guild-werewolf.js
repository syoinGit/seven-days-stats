// A solo werewolf game with the residents (fragments/guild-windows :: werewolf, GuildWerewolf). No AI and nothing sent
// back: the roles are dealt here, and the residents claim, suspect, stand up for each other, vote and hunt by the rules
// below, each saying it in their own voice (guild/werewolf.yml). The traveller plays one seat.
//
// How a resident thinks: each one keeps a suspicion of everyone else. It starts as a hunch, grows when others name
// someone, and follows the seers' results as far as they believe that seer. How they feel about each other in the
// guild (data-ties, data-bonds: trust, affinity, wariness, a crush, a bond) colours all of it: they are slow to suspect
// someone they are fond of, quick with someone they are wary of, believe a claimant they trust, stand up for a friend
// who is named, and a couple tends to vote together. The medium's word on the executed weighs on whoever pushed for or
// stood up for them, and whom a victim of the wolves suspected weighs more. What they know for certain (themselves, a
// wolf's partner, their own readings) overrides all of it. The wolves vote with the room against whoever is not a wolf,
// may claim to be the seer themselves, stand up for their partner (a tell) and hunt whoever claims or names them.
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
  if (seats.length < 4 || !youSeat || !log || !actions || !status) return;

  const ROLES = { wolf: "人狼", seer: "占い師", medium: "霊媒師", hunter: "狩人", villager: "村人" };
  // By how many sit at the table, the traveller included.
  const DEAL = {
    5: ["wolf", "seer", "villager", "villager", "villager"],
    6: ["wolf", "seer", "hunter", "villager", "villager", "villager"],
    7: ["wolf", "wolf", "seer", "medium", "hunter", "villager", "villager"],
    8: ["wolf", "wolf", "seer", "medium", "hunter", "villager", "villager", "villager"],
  };
  const BONDS = { friend: "友達", close_friend: "親友", companion: "仲間", lover: "恋人", spouse: "夫婦", rival: "宿敵", mentor: "師弟" };
  // How much a bond adds to how fond each of the two is of the other.
  const BOND_WARMTH = { friend: 0.3, companion: 0.3, close_friend: 0.6, mentor: 0.4, lover: 0.9, spouse: 1, rival: -0.8 };
  const YOU = "旅の人";
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
  /** The first of [value, score] pairs with the highest score. */
  const top = (pairs) => pairs.sort((a, b) => b[1] - a[1])[0][0];

  // ---- How the residents stand with each other (from the guild) --------------------------------------------------

  const byId = new Map(seats.map((seat, index) => [seat.dataset.wolfPlayer, index]));
  const fondness = seats.map(() => seats.map(() => 0));
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

  // ---- The table ----------------------------------------------------------------------------------------------------

  function deal() {
    const cast = seats.map((seat) => ({ name: seat.dataset.name, seat, you: false, lines: { ...seat.dataset } }));
    cast.push({ name: "あなた", seat: youSeat, you: true, lines: {} });
    const roles = shuffle(DEAL[cast.length]);
    people = cast.map((person, index) => ({
      ...person, index, role: roles[index], alive: true, diedBy: null, claim: null, results: [], readings: [], spirits: [],
      known: new Map(), hunch: [], heat: [], prior: [], threat: 0, named: new Set(), covered: new Set(), votedFor: [], lastSuspect: null,
    }));
    for (const self of people) {
      self.known.set(self.index, self.role === "wolf" ? "wolf" : "human");
      for (const other of people) {
        self.hunch[other.index] = Math.random();
        self.heat[other.index] = 0;
        self.prior[other.index] = 0;
        if (self.role === "wolf" && other.role === "wolf") self.known.set(other.index, "wolf");
      }
    }
    day = 0;
    executions = [];
    log.replaceChildren();
    for (const person of people) {
      person.seat.classList.remove("dead", "wolf-mark", "human-mark", "revealed", "hot", "warm");
      const tag = person.seat.querySelector("[data-wolf-tag]");
      if (tag) tag.textContent = "";
    }
  }

  const you = () => people.find((person) => person.you);
  const living = () => people.filter((person) => person.alive);
  const others = (self) => living().filter((person) => person !== self);
  const wolves = () => living().filter((person) => person.role === "wolf");
  const seers = () => people.filter((person) => person.claim === "seer");
  const mediums = () => people.filter((person) => person.claim === "medium");
  const nameOf = (person) => (person.you ? YOU : person.name);

  // ---- What a resident thinks ---------------------------------------------------------------------------------------

  /** Whether self knows for certain that one of these results is a lie. */
  const caught = (self, results) => results.some((result) => {
    const known = self.known.get(result.target);
    return known && (known === "wolf") !== result.black;
  });

  /** How far self believes that the medium claimant m speaks the truth (0..1). */
  function mediumBelief(self, m) {
    if (self === m) return self.role === "medium" ? 1 : 0;
    if (self.role === "medium" && !self.you) return 0;
    if (caught(self, m.spirits)) return 0;
    return mediums().filter((other) => other !== self).length > 1 ? 0.5 : 0.9;
  }

  /** How far self believes that claimant c is the real seer (0..1). */
  function belief(self, c) {
    if (self === c) return self.role === "seer" ? 1 : 0;
    if (self.role === "seer" && !self.you) return 0;
    if (caught(self, c.results)) return 0;
    // A reading the medium contradicts gives the claimant away, as far as self believes the medium.
    let doubt = 1;
    for (const m of mediums()) {
      if (m === c) continue;
      const trust = mediumBelief(self, m);
      for (const spirit of m.spirits) {
        if (c.results.some((result) => result.target === spirit.target && result.black !== spirit.black)) doubt *= 1 - 0.85 * trust;
      }
    }
    const all = seers().filter((other) => other !== self);
    if (all.length <= 1) return 0.85 * doubt;
    const eaten = all.find((other) => other.diedBy === "wolf");
    if (eaten) return (eaten === c ? 0.95 : 0.1) * doubt;
    // Between two claimants: the one self trusts more, and whose word holds.
    const weight = (other) => self.prior[other.index] * (other === c ? doubt : 1);
    const sum = all.reduce((total, other) => total + weight(other), 0);
    return sum > 0 ? weight(c) / sum : 0.5;
  }

  /** How much self suspects target: the higher, the more likely a wolf in self's eyes. */
  function suspicion(self, target) {
    const known = self.known.get(target.index);
    if (known) return known === "wolf" ? 100 : -100;
    let score = self.hunch[target.index] + self.heat[target.index] - 0.9 * fond(self, target);
    for (const c of seers()) {
      if (c === self) continue;
      const trust = belief(self, c);
      for (const result of c.results) if (result.target === target.index) score += trust * (result.black ? 4 : -2.5);
    }
    // A claimant they believe is someone they would keep; one they doubt, someone they would hang.
    if (target.claim === "seer") score += self.role === "seer" && !self.you ? 5 : (0.5 - belief(self, target)) * 3;
    if (target.claim === "medium") score += self.role === "medium" && !self.you ? 5 : (0.5 - mediumBelief(self, target)) * 3;
    return score;
  }

  /**
   * Whom self names or votes for among pool: a wolf against the room's favourite non-wolf, anyone else their top
   * suspect, leaning towards whoever already has votes (votes: the tally so far) and whom someone dear voted for
   * (ballots: who voted whom).
   */
  function choice(self, pool = others(self), votes = new Map(), ballots = new Map()) {
    const candidates = pool.filter((person) => person !== self);
    if (self.role === "wolf") {
      const room = living().filter((person) => person.role !== "wolf" && !person.you);
      const score = (target) => room.reduce((total, person) => total + (person === target ? 0 : suspicion(person, target)), 0) / Math.max(1, room.length);
      // A partner the vote is already going against may be dropped, to look clean.
      const doomed = candidates.find((person) => person.role === "wolf" && (votes.get(person) || 0) >= living().length / 2 - 1);
      if (doomed && Math.random() < 0.5) return doomed;
      const prey = candidates.filter((person) => person.role !== "wolf");
      if (!prey.length) return pick(candidates);
      return top(prey.map((target) => [target, score(target) + Math.random() * 0.8 - 0.3 * fond(self, target)]));
    }
    return top(candidates.map((target) => {
      let score = suspicion(self, target) + 0.35 * (votes.get(target) || 0) + Math.random() * 0.6;
      for (const [voter, voted] of ballots) if (voted === target && fond(self, voter) > 0.6) score += 0.5 * fond(self, voter);
      return [target, score];
    }));
  }

  /**
   * Everyone else hears speaker name target. It weighs on them as far as they trust the speaker; one who is fond of the
   * target holds it against the speaker, and so does one who knows the target is human.
   */
  function heard(speaker, target) {
    target.named.add(speaker.index);
    for (const person of people) {
      if (person === speaker || person === target || person.you) continue;
      person.heat[target.index] += 0.5 * (1 + 0.4 * fond(person, speaker));
      if (fond(person, target) > 0.5) person.heat[speaker.index] += 0.25 * fond(person, target);
      if (person.known.get(target.index) === "human") person.heat[speaker.index] += 0.8;
    }
    if (target.role === "wolf") speaker.threat += 1;
  }

  /** Everyone else hears speaker stand up for target: it eases them as far as they trust the speaker. */
  function vouched(speaker, target) {
    target.covered.add(speaker.index);
    for (const person of people) {
      if (person === speaker || person === target || person.you) continue;
      person.heat[target.index] -= 0.35 * (1 + 0.5 * fond(person, speaker));
      if (person.known.get(target.index) === "wolf") person.heat[speaker.index] += 1;
    }
  }

  /** What one turned out to be, as told to all: whoever pushed for or stood up for them is weighed by it. */
  function revealed(target, black, sure) {
    for (const person of people) {
      if (person.you || !person.alive) continue;
      for (const other of people) {
        if (other === person) continue;
        if (black) {
          if (target.covered.has(other.index)) person.heat[other.index] += 0.7 * sure;
          if (target.named.has(other.index) || other.votedFor.at(-1) === target.index) person.heat[other.index] -= 0.4 * sure;
        } else {
          if (target.named.has(other.index)) person.heat[other.index] += 0.35 * sure;
          if (other.votedFor.at(-1) === target.index) person.heat[other.index] += 0.15 * sure;
        }
      }
    }
  }

  /** The wolves took someone: whom they suspected is taken more seriously (they may have been onto something). */
  function mourned(victim) {
    if (victim.lastSuspect === null) return;
    for (const person of people) if (!person.you && person.alive && person.index !== victim.lastSuspect) person.heat[victim.lastSuspect] += 0.45;
  }

  // ---- Showing it ---------------------------------------------------------------------------------------------------

  function narrate(text, kind = "") {
    const item = document.createElement("li");
    item.className = "wolf-narr" + (kind ? " " + kind : "");
    item.textContent = text;
    log.append(item);
    log.scrollTop = log.scrollHeight;
  }

  function speak(person, text) {
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
    log.append(item);
    log.scrollTop = log.scrollHeight;
  }

  /** A resident's line of this kind about target ("さん" is left off for the traveller), one of its wordings. */
  function say(person, kind, target = null) {
    let text = pick((person.lines[kind] || "").split("\n"));
    if (target) {
      const name = nameOf(target);
      text = text.replaceAll("{t}さん", target.you ? name : name + "さん").replaceAll("{t}", name);
    }
    speak(person, text);
  }

  function tag(person, text) {
    const mark = person.seat.querySelector("[data-wolf-tag]");
    if (mark) mark.textContent = text;
  }

  /** How many of the living residents would name each one right now. */
  function mood() {
    const counts = new Map();
    for (const person of living().filter((npc) => !npc.you)) {
      const target = choice(person);
      counts.set(target, (counts.get(target) || 0) + 1);
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
      if (!person.alive) notes.push(person.diedBy === "vote" ? "処刑" : "襲撃");
      tag(person, notes.join("・"));
    }
    status.textContent = (day === 0 ? "最初の夜" : day + "日目") + " ・ あなたは" + ROLES[self.role] + (self.alive ? "" : "（死亡）") + " ・ 生存 " + living().length + "人";
  }

  /** The bonds at the table, which everyone in the guild knows of (not the crushes, nor the numbers). */
  function showTies() {
    if (!tiesNote) return;
    const words = bonds.map(({ a, b, label }) => people[a].name + "と" + people[b].name + "は" + BONDS[label]);
    tiesNote.textContent = words.length ? "卓の顔ぶれ：" + words.join("。") + "。" : "卓の顔ぶれ：とくに深い仲の人どうしはいないようだ。";
  }

  /** Buttons for the traveller; resolves with the value of the one pressed. */
  function ask(prompt, options) {
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
  const pickPerson = (prompt, list) => ask(prompt, list.map((person) => ({ label: person.you ? "あなた" : person.name, value: person })));

  // ---- The game -----------------------------------------------------------------------------------------------------

  async function play() {
    deal();
    const self = you();
    showTies();
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
    if (self.role === "medium") role += "処刑された人が人狼だったかどうかを、その夜に知ることができる。";
    if (self.role === "hunter") role += "毎晩ひとりを人狼の牙から守ることができる（自分は守れない）。";
    if (self.role === "villager") role += "話を聞き、投票で人狼を見つけ出そう。";
    narrate(role, "secret");
    mark();
    await firstNight();
    for (day = 1; ; day++) {
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
    for (const other of people) other.prior[person.index] = clamp(0.35 + Math.random() * 0.3 + 0.25 * fond(other, person), 0.05, 0.95);
  }

  /** A claim's result said aloud: the reading itself for the seer, an invented one for a wolf. */
  function report(person, result) {
    person.results.push(result);
    say(person, result.black ? "black" : "white", people[result.target]);
    if (result.black) heard(person, people[result.target]);
  }

  function lie(wolf) {
    const pool = others(wolf).filter((person) => !wolf.results.some((result) => result.target === person.index));
    if (!pool.length) return null;
    const humans = pool.filter((person) => person.role !== "wolf");
    // A black, sometimes, on someone the wolf has no love for.
    if (humans.length && Math.random() < 0.35) return { target: top(humans.map((person) => [person, Math.random() - fond(wolf, person)])).index, black: true };
    return { target: pick(pool).index, black: false };
  }

  /** The medium tells what they learned of the executed, claiming first if they have not. */
  function spiritsOf(medium) {
    for (const spirit of medium.readings.filter((reading) => !medium.spirits.some((told) => told.target === reading.target))) {
      medium.spirits.push(spirit);
      if (medium.you) speak(medium, people[spirit.target].name + "は" + (spirit.black ? "人狼だった。" : "人間だった。"));
      else say(medium, spirit.black ? "spirit_black" : "spirit_white", people[spirit.target]);
      revealed(people[spirit.target], spirit.black, 0.9);
    }
  }

  async function talk() {
    const self = you();
    narrate(day + "日目の昼。卓を囲んで話し合いが始まる。", "big");
    const npcs = shuffle(living().filter((person) => !person.you));
    if (day === 1) for (const person of npcs.slice(0, 2)) say(person, "greet");

    // The medium speaks of the executed first, once there is something to tell.
    const medium = npcs.find((person) => person.role === "medium");
    const speakers = [];
    if (medium && executions.length && (medium.claim || Math.random() < 0.95)) {
      if (!medium.claim) {
        say(medium, "medium");
        claims(medium, "medium");
      }
      spiritsOf(medium);
      speakers.push(medium);
      mark();
    }

    // Then the seers: the real one comes out on the first day (always once they find a wolf), a wolf may claim too.
    const seer = npcs.find((person) => person.role === "seer");
    if (seer && (seer.claim || day === 1 && Math.random() < 0.85 || seer.readings.some((reading) => reading.black))) speakers.push(seer);
    const fake = npcs.find((person) => person.role === "wolf" && person.claim === "seer");
    if (fake) speakers.push(fake);
    else if (day === 1 && !wolves().some((wolf) => wolf.claim)) {
      const liar = npcs.find((person) => person.role === "wolf");
      if (liar && Math.random() < (wolves().length > 1 ? 0.5 : 0.25)) speakers.push(liar);
    }
    for (const person of shuffle(speakers.filter((speaker) => speaker !== medium))) {
      if (!person.claim) {
        say(person, seers().length ? "counter" : "co");
        claims(person, "seer");
        // A seer's first word gives every reading so far.
        if (person.role === "seer") for (const reading of person.readings) report(person, reading);
        else { const result = lie(person); if (result) report(person, result); }
      } else if (person.role === "seer") {
        const reading = person.readings.at(-1);
        if (reading && !person.results.some((result) => result.target === reading.target)) report(person, reading);
      } else {
        const result = lie(person);
        if (result) report(person, result);
      }
      mark();
    }

    if (self.alive) await yourTurn();
    else await next("話し合いを見守る");

    // Everyone else names whom they suspect most.
    const named = new Map();
    for (const person of npcs.filter((npc) => !speakers.includes(npc))) {
      const target = choice(person);
      say(person, "suspect", target);
      heard(person, target);
      person.lastSuspect = target.index;
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
    // Whoever is named twice (or by the traveller) answers for themselves.
    for (const [target, count] of named) {
      if (target.you || count < 2 && !target.namedByYou) continue;
      say(target, "defend");
      for (const person of people) if (person !== target) person.heat[target.index] = Math.max(0, person.heat[target.index] - 0.3);
    }
    for (const person of people) person.namedByYou = false;
    const counts = mood();
    mark(counts);
    air(counts);
  }

  /** The room's mood, said aloud: whom the suspicion is gathering on. */
  function air(counts) {
    const ranked = [...counts].sort((a, b) => b[1] - a[1]).slice(0, 2);
    if (!ranked.length) return;
    narrate("卓の空気：" + ranked.map(([target, count]) => (target.you ? "あなた" : target.name) + "に" + count + "人").join("、") + "の疑いが向いている。", "air");
  }

  async function yourTurn() {
    const self = you();
    const options = [];
    const untoldSpirits = self.readings.filter((reading) => self.role === "medium" && !self.spirits.some((told) => told.target === reading.target));
    if (self.role === "medium" && (!self.claim || untoldSpirits.length)) options.push({ label: self.claim ? "霊媒の結果を伝える" : "霊媒師だと名乗る", value: "medium" });
    if (self.claim !== "medium") options.push(self.claim === "seer" ? { label: "占いの結果を伝える", value: "result" } : { label: "占い師だと名乗る", value: "claim" });
    options.push({ label: "怪しい人を名指しする", value: "suspect" }, { label: "誰かをかばう", value: "cover" }, { label: "黙って聞いている", value: "quiet", quiet: true });
    const action = await ask("あなたの番だ。どうする？", options);
    if (action === "quiet") {
      narrate("あなたは黙って皆の話に耳を傾けた。");
      return;
    }
    if (action === "suspect") {
      const target = await pickPerson("誰を名指しする？", others(self));
      speak(self, target.name + "が怪しいと思う。");
      heard(self, target);
      target.namedByYou = true;
      return;
    }
    if (action === "cover") {
      const target = await pickPerson("誰をかばう？", others(self));
      speak(self, target.name + "は人狼じゃないと思う。");
      vouched(self, target);
      return;
    }
    if (action === "medium") {
      if (!self.claim) {
        speak(self, mediums().length ? "待ってほしい。本物の霊媒師はわたしだ。" : "わたしは霊媒師だ。");
        claims(self, "medium");
      }
      if (untoldSpirits.length) spiritsOf(self);
      else speak(self, "まだ伝えることはない。");
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
    if (self.alive && candidates(self).length) cast(self, await pickPerson(prompt, candidates(self)));
    for (const person of shuffle(living().filter((npc) => !npc.you))) {
      if (candidates(person).length) cast(person, choice(person, candidates(person), tally, ballots));
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
    narrate(executed.name + "は" + most + "票を集め、処刑された。", "death");
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
    narrate(day + "日目の夜。", "big");
    let guard = null;
    let prey = null;
    if (self.alive && self.role === "seer") {
      const pool = others(self).filter((person) => !self.known.has(person.index));
      if (pool.length) {
        const target = await pickPerson("誰を占う？", pool);
        read(self, target);
        narrate(target.name + "を占った。……" + (target.role === "wolf" ? "人狼だ！" : "人間だ。"), "secret");
      }
    }
    if (self.alive && self.role === "medium") {
      const spirit = self.readings.at(-1);
      if (spirit) narrate("あなたは処刑された" + people[spirit.target].name + "の魂に触れた。……" + (spirit.black ? "人狼だった。" : "人間だった。"), "secret");
    }
    if (self.alive && self.role === "hunter") guard = await pickPerson("今夜は誰を守る？", others(self));
    if (self.alive && self.role === "wolf") prey = await pickPerson("今夜は誰を襲う？", others(self).filter((person) => person.role !== "wolf"));

    for (const seer of living().filter((person) => person.role === "seer" && !person.you)) {
      const pool = others(seer).filter((person) => !seer.known.has(person.index));
      if (pool.length) read(seer, top(pool.map((target) => [target, suspicion(seer, target) + Math.random()])));
    }
    const hunter = living().find((person) => person.role === "hunter" && !person.you);
    if (hunter) {
      // The claimant they believe, else someone dear to them.
      guard = top(others(hunter).map((target) => [target, (target.claim === "seer" ? belief(hunter, target) * 3 : 0)
        + (target.claim === "medium" ? mediumBelief(hunter, target) * 2 : 0) + 0.5 * fond(hunter, target) + Math.random()]));
    }
    if (!prey && wolves().length) {
      // Whoever claims or has named a wolf is in more danger; one the wolves are fond of, less.
      const pool = living().filter((person) => person.role !== "wolf");
      const weights = pool.map((person) => Math.max(0.2, 1 + person.threat * 1.5 + (person.claim ? 3 : 0)
        - wolves().reduce((total, wolf) => total + 0.4 * fond(wolf, person), 0)));
      let roll = Math.random() * weights.reduce((a, b) => a + b, 0);
      prey = pool.find((person, i) => (roll -= weights[i]) < 0) || pool.at(-1);
    }
    mark();
    await next("朝を迎える");
    narrate((day + 1) + "日目の朝。", "big");
    if (prey && prey !== guard) {
      prey.alive = false;
      prey.diedBy = "wolf";
      narrate(prey.name + "が、無残な姿で見つかった。", "death");
      mourned(prey);
    } else {
      narrate("誰も襲われなかった。静かな朝だ。");
    }
    mark();
    return finish();
  }

  /** Ends the game when one side has won: shows every role and offers another round. */
  function finish() {
    const left = wolves().length;
    const humans = living().length - left;
    if (left > 0 && left < humans) return false;
    const villageWon = left === 0;
    const self = you();
    const won = villageWon !== (self.role === "wolf");
    narrate(villageWon ? "人狼はすべて退治された。村人の勝利！" : "人狼が村を食らい尽くした。人狼の勝利……", "big");
    narrate(won ? "あなたの陣営の勝ちだ。" : "あなたの陣営の負けだ。", "secret");
    narrate("役目：" + people.filter((person) => !person.you).map((person) => person.name + "＝" + ROLES[person.role]).join("、") + "。");
    for (const person of people) {
      person.seat.classList.add("revealed");
      person.seat.classList.remove("hot", "warm");
      tag(person, ROLES[person.role] + (person.alive ? "" : person.diedBy === "vote" ? "・処刑" : "・襲撃"));
    }
    status.textContent = "決着 ・ あなたは" + ROLES[self.role];
    ask(null, [{ label: "もう一度遊ぶ", value: true }]).then(play);
    return true;
  }

  play();
})();
