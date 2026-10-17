document.addEventListener("DOMContentLoaded", () => {
  const refreshButton = document.querySelector("[data-refresh-page]");
  const composer = document.querySelector(".timeline-compose textarea");
  const hasDraft = () => Boolean(composer?.value.trim());
  let submitting = false;

  if (refreshButton) {
    refreshButton.addEventListener("click", () => {
      if (!hasDraft() || window.confirm("入力中のつぶやきを破棄して更新しますか？")) window.location.reload();
    });
    window.setInterval(() => {
      const active = document.activeElement;
      const composing = active && (active.tagName === "TEXTAREA" || active.tagName === "INPUT");
      if (!document.hidden && !composing && !hasDraft() && !submitting
          && !document.querySelector(".reaction-menu[open]") && window.scrollY < 300) {
        window.location.reload();
      } else {
        refreshButton.classList.add("has-update");
        refreshButton.textContent = "最新の観測を確認";
      }
    }, 60 * 1000);
  }

  const timeline = document.querySelector("#timeline");
  const timelineLoader = document.querySelector("[data-timeline-loader]");
  const timelineProgress = document.querySelector("[data-timeline-progress]");
  let loadingOlder = false;

  if (timeline && timelineLoader && !timelineLoader.hidden) {
    let observer;
    const loadMore = async () => {
      if (loadingOlder || timelineLoader.hidden) return;
      loadingOlder = true;
      timelineLoader.classList.add("is-loading");
      timelineLoader.setAttribute("aria-busy", "true");
      const controller = new AbortController();
      const timeout = window.setTimeout(() => controller.abort(), 15000);
      try {
        const offset = Number(timelineLoader.dataset.nextOffset || "0");
        const url = new URL(timeline.dataset.timelineUrl, window.location.href);
        url.searchParams.set("offset", String(offset));
        const response = await fetch(url, {
          headers: { Accept: "text/html" }, credentials: "same-origin", signal: controller.signal
        });
        if (!response.ok || response.redirected) throw new Error("Timeline page request failed");
        const documentPage = new DOMParser().parseFromString(await response.text(), "text/html");
        const meta = documentPage.querySelector("[data-timeline-page-meta]");
        const items = documentPage.querySelectorAll("[data-timeline-item]");
        const nextOffset = Number(meta?.dataset.nextOffset);
        const hasMore = meta?.dataset.hasMore === "true";
        if (!meta || !["true", "false"].includes(meta.dataset.hasMore)
            || !Number.isSafeInteger(nextOffset) || nextOffset < 0
            || (hasMore && (nextOffset <= offset || items.length === 0))) {
          throw new Error("Invalid timeline page");
        }
        items.forEach((item) => timeline.append(item));
        timelineLoader.dataset.nextOffset = String(nextOffset);
        timelineLoader.hidden = !hasMore;
        if (!hasMore) observer?.disconnect();
        if (timelineProgress) timelineProgress.textContent = hasMore ? "さらに過去の記録" : "これより前の記録はありません";
      } catch (_error) {
        if (timelineProgress) timelineProgress.textContent = "読み込みに失敗しました。押すと再試行できます。";
      } finally {
        window.clearTimeout(timeout);
        loadingOlder = false;
        timelineLoader.classList.remove("is-loading");
        timelineLoader.removeAttribute("aria-busy");
      }
    };
    timelineLoader.addEventListener("click", loadMore);
    if ("IntersectionObserver" in window) {
      observer = new IntersectionObserver((entries) => {
        if (entries.some((entry) => entry.isIntersecting)) loadMore();
      }, { rootMargin: "240px 0px" });
      observer.observe(timelineLoader);
    }
  }

  const copyButton = document.querySelector("[data-copy-generation]");
  if (copyButton) {
    copyButton.addEventListener("click", async () => {
      const data = document.querySelector("#generation-data");
      if (!data) return;
      try {
        await navigator.clipboard.writeText(data.value);
        copyButton.textContent = "コピー済み";
      } catch (_error) {
        data.focus();
        data.select();
        copyButton.textContent = "選択したテキストをコピーしてください";
      }
    });
  }

  // Delegate so forms inserted by pagination receive the same confirmation and
  // duplicate-submit protection as the initial page.
  document.addEventListener("submit", (event) => {
    const form = event.target;
    if (!form.matches(".delete-post-form, .reaction-form, .timeline-compose form")) return;
    if (submitting || (form.matches(".delete-post-form")
        && !window.confirm("このつぶやきを削除しますか？"))) {
      event.preventDefault();
      return;
    }
    submitting = true;
  });
  window.addEventListener("pageshow", () => { submitting = false; });
});
