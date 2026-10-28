// A solo werewolf game with the residents (fragments/guild-windows :: werewolf, GuildWerewolf). No AI and nothing sent
// back: the roles are dealt here, and the residents claim, suspect, vote and hunt by the rules below, each saying it in
// their own voice (guild/werewolf.yml). The traveller plays one seat.
//
// How a resident thinks: each one keeps a suspicion of everyone else. It starts as a hunch, grows when others name
// someone, and follows the seers' results as far as they believe that seer. With two seers each picks one to believe,
// and a seer the wolves killed is taken to have been the real one. What they know for certain (themselves, a wolf's
// partner, their own readings) overrides all of it. The wolves vote with the room against whoever is not a wolf, may
// claim to be the seer themselves, and hunt whoever claims or names them.
(() => {
  const box = document.querySelector("[data-wolf]");
  if (!box) return;
  const $ = (selector) => box.querySelector(selector);
  const seats = [...box.querySelectorAll("[data-wolf-player]")];
  const youSeat = $("[data-wolf-you]");
  const log = $("[data-wolf-log]");
  const actions = $("[data-wolf-actions]");
  const status = $("[data-wolf-status]");
  if (seats.length < 4 || !youSeat || !log || !actions || !status) return;

  const ROLES = { wolf: "人狼", seer: "占い師", hunter: "狩人", villager: "村人" };
  // By how many sit at the table, the traveller included.
  const DEAL = {
    5: ["wolf", "seer", "villager", "villager", "villager"],
    6: ["wolf", "seer", "hunter", "villager", "villager", "villager"],
    7: ["wolf", "wolf", "seer", "hunter", "villager", "villager", "villager"],
    8: ["wolf", "wolf", "seer", "hunter", "villager", "villager", "villager", "villager"],
  };
  const YOU = "旅の人";
  const random = (n) => Math.floor(Math.random() * n);
  const pick = (list) => list[random(list.length)];
  const shuffle = (list) => {
    const out = [...list];
    for (let i = out.length - 1; i > 0; i--) {
      const j = random(i + 1);
      [out[i], out[j]] = [out[j], out[i]];
    }
    return out;
  };

  let people = [];
  let day = 0;

  // ---- The table ----------------------------------------------------------------------------------------------------

  function deal() {
    const cast = seats.map((seat) => ({ name: seat.dataset.name, seat, you: false, lines: { ...seat.dataset } }));
    cast.push({ name: "あなた", seat: youSeat, you: true, lines: {} });
    const roles = shuffle(DEAL[cast.length]);
    people = cast.map((person, index) => ({
      ...person, index, role: roles[index], alive: true, diedBy: null, claimed: false, results: [], readings: [],
      known: new Map(), hunch: [], heat: [], prior: [], threat: 0,
    }));
    for (const self of people) {
      self.known.set(self.index, self.role === "wolf" ? "wolf" : "human");
      for (const other of people) {
        self.hunch[other.index] = Math.random();
        self.heat[other.index] = 0;
        self.prior[other.index] = 0.35 + Math.random() * 0.3;
        if (self.role === "wolf" && other.role === "wolf") self.known.set(other.index, "wolf");
      }
    }
    day = 0;
    log.replaceChildren();
    for (const person of people) {
      person.seat.classList.remove("dead", "wolf-mark", "human-mark", "revealed");
      const tag = person.seat.querySelector("[data-wolf-tag]");
      if (tag) tag.textContent = "";
    }
  }

  const you = () => people.find((person) => person.you);
  const living = () => people.filter((person) => person.alive);
  const others = (self) => living().filter((person) => person !== self);
  const wolves = () => living().filter((person) => person.role === "wolf");
  const claimants = () => people.filter((person) => person.claimed);
  const nameOf = (person) => (person.you ? YOU : person.name);

  // ---- What a resident thinks ---------------------------------------------------------------------------------------

  /** How far self believes that claimant c is the real seer (0..1). */
  function belief(self, c) {
    if (self === c) return self.role === "seer" ? 1 : 0;
    if (self.role === "seer" && !self.you) return 0;
    // A reading that contradicts what self knows for certain gives the claimant away.
    for (const result of c.results) {
      const known = self.known.get(result.target);
      if (known && (known === "wolf") !== result.black) return 0;
    }
    const all = claimants().filter((other) => other !== self);
    if (all.length <= 1) return 0.85;
    const eaten = all.find((other) => other.diedBy === "wolf");
    if (eaten) return eaten === c ? 0.95 : 0.1;
    const sum = all.reduce((total, other) => total + self.prior[other.index], 0);
    return self.prior[c.index] / sum;
  }

  /** How much self suspects target: the higher, the more likely a wolf in self's eyes. */
  function suspicion(self, target) {
    const known = self.known.get(target.index);
    if (known) return known === "wolf" ? 100 : -100;
    let score = self.hunch[target.index] + self.heat[target.index];
    for (const c of claimants()) {
      if (c === self) continue;
      const trust = belief(self, c);
      for (const result of c.results) if (result.target === target.index) score += trust * (result.black ? 4 : -2.5);
    }
    // A seer they believe is someone they would keep; one they doubt, someone they would hang.
    if (target.claimed) score += self.role === "seer" && !self.you ? 5 : (0.5 - belief(self, target)) * 3;
    return score;
  }

  /**
   * Whom self names or votes for: a wolf against the room's favourite non-wolf, anyone else their top suspect, leaning
   * towards whoever already has votes (votes: the count so far, when voting).
   */
  function choice(self, votes = new Map()) {
    const candidates = others(self);
    if (self.role === "wolf") {
      const room = living().filter((person) => person.role !== "wolf" && !person.you);
      const prey = candidates.filter((person) => person.role !== "wolf");
      const score = (target) => room.reduce((total, person) => total + (person === target ? 0 : suspicion(person, target)), 0) / Math.max(1, room.length);
      return prey.map((target) => [target, score(target) + Math.random() * 0.8]).sort((a, b) => b[1] - a[1])[0][0];
    }
    return candidates.map((target) => [target, suspicion(self, target) + 0.35 * (votes.get(target) || 0) + Math.random() * 0.6])
      .sort((a, b) => b[1] - a[1])[0][0];
  }

  /** Everyone else hears speaker name target: it weighs on them, and on the speaker when they know target is human. */
  function heard(speaker, target) {
    for (const person of people) {
      if (person === speaker || person === target || person.you) continue;
      person.heat[target.index] += 0.5;
      if (person.known.get(target.index) === "human") person.heat[speaker.index] += 0.8;
    }
    if (target.role === "wolf") speaker.threat += 1;
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

  /** A resident's line of this kind about target ("さん" is left off for the traveller). */
  function say(person, kind, target = null) {
    const wordings = (person.lines[kind] || "").split("\n");
    let text = wordings[Math.floor(Math.random() * wordings.length)];
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

  function mark() {
    const self = you();
    for (const person of people) {
      person.seat.classList.toggle("dead", !person.alive);
      const known = self.known.get(person.index);
      person.seat.classList.toggle("wolf-mark", person !== self && known === "wolf");
      person.seat.classList.toggle("human-mark", person !== self && known === "human");
      const notes = [];
      if (person.claimed) notes.push("自称占い師");
      if (!person.alive) notes.push(person.diedBy === "vote" ? "処刑" : "襲撃");
      tag(person, notes.join("・"));
    }
    const role = ROLES[self.role];
    status.textContent = (day === 0 ? "最初の夜" : day + "日目") + " ・ あなたは" + role + (self.alive ? "" : "（死亡）") + " ・ 生存 " + living().length + "人";
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
  const pickPerson = (prompt, list, cancel = null) =>
    ask(prompt, [...list.map((person) => ({ label: nameOf(person) === YOU ? "あなた" : person.name, value: person })),
      ...(cancel ? [{ label: cancel, value: null, quiet: true }] : [])]);

  // ---- The game -----------------------------------------------------------------------------------------------------

  async function play() {
    deal();
    const self = you();
    mark();
    narrate("酒場の灯りが落とされ、" + people.length + "人が卓を囲んだ。この中に人狼が" + wolves().length + "人まぎれている。", "big");
    let role = "あなたの役目は【" + ROLES[self.role] + "】。";
    if (self.role === "wolf") {
      const partner = wolves().find((wolf) => wolf !== self);
      role += partner ? "仲間の人狼は" + partner.name + "だ。" : "仲間はいない。ひとりで村を欺け。";
    }
    if (self.role === "seer") role += "毎晩ひとりを占い、人狼かどうかを知ることができる。";
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
    if (humans.length && Math.random() < 0.35) return { target: pick(humans).index, black: true };
    return { target: pick(pool).index, black: false };
  }

  async function talk() {
    const self = you();
    narrate(day + "日目の昼。卓を囲んで話し合いが始まる。", "big");
    const npcs = shuffle(living().filter((person) => !person.you));
    if (day === 1) for (const person of npcs.slice(0, 2)) say(person, "greet");

    // The seers speak first: the real one comes out on the first day (always once they find a wolf), a wolf may claim too.
    const seer = npcs.find((person) => person.role === "seer");
    const speakers = [];
    if (seer && (seer.claimed || day === 1 && Math.random() < 0.85 || seer.readings.some((reading) => reading.black))) speakers.push(seer);
    const fake = npcs.find((person) => person.role === "wolf" && person.claimed);
    if (fake) speakers.push(fake);
    else if (day === 1 && !wolves().some((wolf) => wolf.claimed)) {
      const liar = npcs.find((person) => person.role === "wolf");
      if (liar && Math.random() < (wolves().length > 1 ? 0.5 : 0.25)) speakers.push(liar);
    }
    for (const person of shuffle(speakers)) {
      if (!person.claimed) {
        say(person, claimants().length ? "counter" : "co");
        person.claimed = true;
        for (const other of people) other.prior[person.index] = 0.35 + Math.random() * 0.3;
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

    // Everyone else names whom they suspect most; whoever is named twice (or by the traveller) answers for themselves.
    const named = new Map();
    for (const person of npcs.filter((npc) => !speakers.includes(npc))) {
      const target = choice(person);
      say(person, "suspect", target);
      heard(person, target);
      named.set(target, (named.get(target) || 0) + 1);
    }
    for (const [target, count] of named) {
      if (target.you || !target.alive || count < 2 && !target.namedByYou) continue;
      say(target, "defend");
      for (const person of people) if (person !== target) person.heat[target.index] = Math.max(0, person.heat[target.index] - 0.3);
    }
    for (const person of people) person.namedByYou = false;
    mark();
  }

  async function yourTurn() {
    const self = you();
    const options = [];
    if (!self.claimed) options.push({ label: "占い師だと名乗る", value: "claim" });
    else options.push({ label: "占いの結果を伝える", value: "result" });
    options.push({ label: "怪しい人を名指しする", value: "suspect" }, { label: "黙って聞いている", value: "quiet", quiet: true });
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
    if (action === "claim") {
      speak(self, claimants().length ? "待ってほしい。本物の占い師はわたしだ。" : "わたしが占い師だ。");
      self.claimed = true;
      for (const other of people) other.prior[self.index] = 0.35 + Math.random() * 0.3;
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

  /** The vote; true when the game ended with it. */
  async function vote() {
    const self = you();
    narrate("投票の時間だ。", "big");
    const votes = new Map();
    const tally = new Map();
    const cast = (voter, target) => {
      votes.set(voter, target);
      tally.set(target, (tally.get(target) || 0) + 1);
    };
    if (self.alive) cast(self, await pickPerson("誰に投票する？", others(self)));
    for (const person of shuffle(living().filter((npc) => !npc.you))) cast(person, choice(person, tally));
    for (const [voter, target] of votes) narrate(voter.name + " → " + target.name, "vote");
    const top = Math.max(...tally.values());
    const executed = pick([...tally].filter(([, count]) => count === top).map(([target]) => target));
    const voiced = [...votes].find(([voter, target]) => target === executed && !voter.you);
    if (voiced) say(voiced[0], "vote", executed);
    executed.alive = false;
    executed.diedBy = "vote";
    narrate(executed.name + "は" + top + "票を集め、処刑された。", "death");
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
    if (self.alive && self.role === "hunter") guard = await pickPerson("今夜は誰を守る？", others(self));
    if (self.alive && self.role === "wolf") prey = await pickPerson("今夜は誰を襲う？", others(self).filter((person) => person.role !== "wolf"));

    for (const seer of living().filter((person) => person.role === "seer" && !person.you)) {
      const pool = others(seer).filter((person) => !seer.known.has(person.index));
      if (pool.length) read(seer, pool.map((target) => [target, suspicion(seer, target) + Math.random()]).sort((a, b) => b[1] - a[1])[0][0]);
    }
    const hunter = living().find((person) => person.role === "hunter" && !person.you);
    if (hunter) {
      const pool = others(hunter);
      guard = pool.map((target) => [target, (target.claimed ? belief(hunter, target) * 3 : 0) + Math.random()]).sort((a, b) => b[1] - a[1])[0][0];
    }
    if (!prey && wolves().length) {
      // Whoever claims to be the seer or has named a wolf is in more danger.
      const pool = living().filter((person) => person.role !== "wolf");
      const weights = pool.map((person) => 1 + person.threat * 1.5 + (person.claimed ? 3 : 0));
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
    for (const person of people) {
      person.seat.classList.add("revealed");
      tag(person, ROLES[person.role] + (person.alive ? "" : person.diedBy === "vote" ? "・処刑" : "・襲撃"));
    }
    status.textContent = "決着 ・ あなたは" + ROLES[self.role];
    ask(null, [{ label: "もう一度遊ぶ", value: true }]).then(play);
    return true;
  }

  play();
})();
