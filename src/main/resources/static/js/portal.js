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
  const chips = [...document.querySelectorAll("[data-server-status]")];
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content || "X-CSRF-TOKEN";
  const labels = { offline: "停止中", starting: "起動中", online: "稼働中",
    maintenance: "メンテナンス中", error: "エラー", unknown: "未確認" };
  const updateStatus = (gameId, status) => {
    const code = Object.hasOwn(labels, status) ? status : "unknown";
    chips.filter((chip) => chip.dataset.serverStatus === gameId).forEach((chip) => {
      Object.keys(labels).forEach((value) => chip.classList.remove(`status-${value}`));
      chip.classList.add(`status-${code}`);
      chip.textContent = labels[code];
    });
    forms.filter((form) => form.dataset.startServer === gameId).forEach((form) => {
      form.dataset.status = code;
      form.querySelector("button").disabled = form.dataset.pending === "true" || code !== "offline";
    });
  };
  forms.forEach((form) => {
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      if (form.dataset.pending === "true" || form.dataset.status !== "offline") return;
      const message = form.closest(".server-control").querySelector(".server-action-message");
      const button = form.querySelector("button");
      form.dataset.pending = "true";
      button.disabled = true;
      message.textContent = "起動を要求しています…";
      try {
        if (!csrfToken) throw new Error("Missing CSRF token");
        const response = await fetch(form.action, {
          method: "POST", credentials: "same-origin", headers: {
            [csrfHeader]: csrfToken, Accept: "application/json"
          }, signal: AbortSignal.timeout(30000)
        });
        if (!response.ok) throw new Error("Start request failed");
        const result = await response.json();
        message.textContent = result.message || "起動要求を処理しました。";
        updateStatus(form.dataset.startServer, result.status);
      } catch (_error) {
        message.textContent = "起動結果を確認できませんでした。再読み込みして状態を確認してください。";
        updateStatus(form.dataset.startServer, "unknown");
      } finally {
        form.dataset.pending = "false";
        button.disabled = form.dataset.status !== "offline";
      }
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
