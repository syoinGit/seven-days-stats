const loginDialog = document.querySelector("#portal-login");
if (loginDialog && typeof loginDialog.showModal === "function") {
  loginDialog.classList.add("modal-ready");
  const openLogin = () => {
    if (!loginDialog.open) loginDialog.showModal();
  };
  document.querySelectorAll("[data-open-login]").forEach((link) => {
    link.addEventListener("click", (event) => {
      event.preventDefault();
      openLogin();
    });
  });
  // Server-rendered open also keeps the login form usable without JavaScript.
  if (loginDialog.open) {
    loginDialog.close();
    openLogin();
  }
}

const serverControls = document.querySelector(".server-controls");
if (serverControls) {
  const forms = [...serverControls.querySelectorAll("[data-start-server]")];
  const stopForms = [...serverControls.querySelectorAll("[data-stop-server]")];
  const chips = [...document.querySelectorAll("[data-server-status]")];
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN";
  const labels = { offline: "停止中", starting: "起動中", online: "稼働中", stopping: "停止処理中",
    maintenance: "メンテナンス中", error: "エラー", unknown: "未確認" };
  const reservations = new Map([...serverControls.querySelectorAll("[data-server]")]
    .map((server) => [server.dataset.server, {
      status: server.dataset.status, memory: Number(server.dataset.memoryMib)
    }]));
  const budget = Number(serverControls.dataset.memoryBudgetMib);
  const refreshMemory = () => {
    const known = reservations.size > 0 && Number.isFinite(budget) && budget > 0
      && [...reservations.values()].every((slot) =>
        ["offline", "starting", "online", "stopping", "maintenance"].includes(slot.status)
        && Number.isFinite(slot.memory) && slot.memory > 0);
    const reserved = [...reservations.values()]
      .reduce((sum, slot) => sum + (slot.status === "offline" ? 0 : slot.memory), 0);
    const summary = serverControls.querySelector("[data-memory-reserved]");
    if (summary) summary.textContent = known ? `${(reserved / 1024).toFixed(1)} GiB` : "未確認";
    forms.forEach((form) => {
      const slot = reservations.get(form.dataset.startServer);
      let reason = "";
      if (!known || !slot) reason = "サーバー状態を確認できないため起動できません。";
      else if (slot.memory > budget) reason = "必要なメモリ枠がゲーム用上限を超えています。";
      else if (reserved + slot.memory > budget) reason = "メモリ枠が不足しています。他のゲームの停止が必要です。";
      const allowed = known && slot?.status === "offline" && reason === "";
      form.dataset.memoryAllowed = String(allowed);
      form.querySelector("button").disabled = form.dataset.pending === "true" || !allowed;
      const note = form.closest(".server-control").querySelector("[data-memory-reason]");
      if (note) note.textContent = slot?.status === "offline" ? reason : "";
    });
    stopForms.forEach((form) => {
      form.querySelector("button").disabled = form.dataset.pending === "true"
        || !stoppable(form.dataset.status);
    });
  };
  const updateStatus = (gameId, status) => {
    const code = Object.hasOwn(labels, status) ? status : "unknown";
    if (reservations.has(gameId)) reservations.get(gameId).status = code;
    chips.filter((chip) => chip.dataset.serverStatus === gameId).forEach((chip) => {
      Object.keys(labels).forEach((value) => chip.classList.remove(`status-${value}`));
      chip.classList.add(`status-${code}`);
      chip.textContent = labels[code];
    });
    forms.filter((form) => form.dataset.startServer === gameId).forEach((form) => {
      form.dataset.status = code;
    });
    stopForms.filter((form) => form.dataset.stopServer === gameId).forEach((form) => {
      form.dataset.status = code;
    });
    refreshMemory();
  };
  const stoppable = (status) => status === "online" || status === "starting";
  const send = async (form, gameId, text) => {
    const message = form.closest(".server-control").querySelector(".server-action-message");
    form.dataset.pending = "true";
    form.querySelector("button").disabled = true;
    message.textContent = text.pending;
    try {
      if (!csrfToken) throw new Error("Missing CSRF token");
      const response = await fetch(form.action, {
        method: "POST", credentials: "same-origin", headers: {
          [csrfHeader]: csrfToken, Accept: "application/json"
        }, signal: AbortSignal.timeout(30000)
      });
      if (!response.ok) throw new Error("Server request failed");
      const result = await response.json();
      message.textContent = result.message || text.done;
      updateStatus(gameId, result.status);
    } catch (_error) {
      message.textContent = text.failed;
      updateStatus(gameId, "unknown");
    } finally {
      form.dataset.pending = "false";
      refreshMemory();
    }
  };
  refreshMemory();
  forms.forEach((form) => {
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (form.dataset.pending === "true" || form.dataset.status !== "offline"
          || form.dataset.memoryAllowed !== "true") return;
      await send(form, form.dataset.startServer, { pending: "起動を要求しています…",
        done: "起動要求を処理しました。",
        failed: "起動結果を確認できませんでした。再読み込みして状態を確認してください。" });
    });
  });
  stopForms.forEach((form) => {
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (form.dataset.pending === "true" || !stoppable(form.dataset.status)) return;
      const name = form.dataset.serverName || "サーバー";
      // Stopping disconnects everyone playing, so require an explicit confirmation.
      if (typeof confirm !== "function"
          || !confirm(`${name}を停止します。接続中のプレイヤーは切断されます。よろしいですか？`)) return;
      await send(form, form.dataset.stopServer, { pending: "停止を要求しています…",
        done: "停止要求を処理しました。",
        failed: "停止結果を確認できませんでした。再読み込みして状態を確認してください。" });
    });
  });
  if (serverControls.dataset.liveServerStatus === "true") {
    const refresh = async () => {
      try {
        if (document.visibilityState === "hidden") return;
        const response = await fetch("/portal/actions/games/status", {
          credentials: "same-origin", headers: { Accept: "application/json" },
          signal: AbortSignal.timeout(30000)
        });
        if (!response.ok) throw new Error("Status request failed");
        const statuses = await response.json();
        chips.forEach((chip) => updateStatus(chip.dataset.serverStatus,
          statuses[chip.dataset.serverStatus]?.code));
      } catch (_error) {
        chips.forEach((chip) => updateStatus(chip.dataset.serverStatus, "unknown"));
      } finally {
        setTimeout(refresh, 15000);
      }
    };
    setTimeout(refresh, 15000);
  }
}
