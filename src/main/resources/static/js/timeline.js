document.addEventListener("DOMContentLoaded", () => {
  const timelineRefreshNoticeMs = 60 * 1000;
  const refreshButton = document.querySelector("[data-refresh-page]");
  if (refreshButton) {
    refreshButton.addEventListener("click", () => window.location.reload());
    window.setTimeout(() => {
      refreshButton.classList.add("has-update");
      refreshButton.innerHTML = '<span aria-hidden="true">↑</span> 新しい観測があります';
    }, timelineRefreshNoticeMs);
  }

  // Refresh only while the reader is near the live edge. Never throw someone back
  // to the top while they are browsing older observations.
  window.setInterval(() => {
    const active = document.activeElement;
    const composing = active && (active.tagName === "TEXTAREA" || active.tagName === "INPUT");
    if (!document.hidden && !composing && window.scrollY < 300) window.location.reload();
  }, 60 * 1000);

  const timeline = document.querySelector("#timeline");
  const timelineLoader = document.querySelector("[data-timeline-loader]");
  const timelineProgress = document.querySelector("[data-timeline-progress]");
  let loadingOlder = false;

  if (timeline && timelineLoader && !timelineLoader.hidden) {
    const loadMore = async () => {
      if (loadingOlder || timelineLoader.hidden) return;
      loadingOlder = true;
      timelineLoader.classList.add("is-loading");
      try {
        const offset = timelineLoader.dataset.nextOffset || "0";
        const response = await fetch(`${timeline.dataset.timelineUrl}?offset=${encodeURIComponent(offset)}`, {
          headers: { Accept: "text/html" }, credentials: "same-origin"
        });
        if (!response.ok) throw new Error("Timeline page request failed");
        const documentPage = new DOMParser().parseFromString(await response.text(), "text/html");
        const meta = documentPage.querySelector("[data-timeline-page-meta]");
        const items = documentPage.querySelectorAll("[data-timeline-item]");
        items.forEach((item) => timeline.append(item));
        const hasMore = meta?.dataset.hasMore === "true";
        if (meta?.dataset.nextOffset) timelineLoader.dataset.nextOffset = meta.dataset.nextOffset;
        timelineLoader.hidden = !hasMore || items.length === 0;
        if (timelineProgress) timelineProgress.textContent = hasMore ? "さらに過去の記録" : "これより前の記録はありません";
      } catch (_error) {
        if (timelineProgress) timelineProgress.textContent = "読み込みに失敗しました。再試行してください。";
      } finally {
        loadingOlder = false;
        timelineLoader.classList.remove("is-loading");
      }
    };
    timelineLoader.addEventListener("click", loadMore);
    if ("IntersectionObserver" in window) {
      const observer = new IntersectionObserver((entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          loadMore();
          if (timelineLoader.hidden) observer.disconnect();
        }
      }, { rootMargin: "240px 0px" });
      observer.observe(timelineLoader);
    }
  }

  const copyButton = document.querySelector("[data-copy-generation]");
  if (copyButton) {
    copyButton.addEventListener("click", async () => {
      await navigator.clipboard.writeText(document.querySelector("#generation-data").value);
      copyButton.textContent = "コピー済み";
    });
  }

  document.querySelectorAll(".like-form").forEach((form) => {
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      const button = form.querySelector(".like-button");
      const count = form.querySelector(".like-value");
      if (!button || !count || button.disabled) return;

      button.disabled = true;
      try {
        const response = await fetch(form.dataset.likeUrl, {
          method: "POST",
          body: new FormData(form),
          headers: { Accept: "application/json" },
          credentials: "same-origin"
        });
        if (!response.ok || !response.headers.get("content-type")?.includes("application/json")) {
          throw new Error("Like request failed");
        }
        const result = await response.json();
        if (!result.success) {
          throw new Error(result.message || "Like request failed");
        }
        button.classList.toggle("liked", result.liked);
        button.setAttribute("aria-pressed", String(result.liked));
        count.textContent = String(result.likeCount);
      } catch (_error) {
        form.submit();
      } finally {
        button.disabled = false;
      }
    });
  });

  document.querySelectorAll(".delete-post-form").forEach((form) => {
    form.addEventListener("submit", (event) => {
      if (!window.confirm("このつぶやきを削除しますか？")) event.preventDefault();
    });
  });
});
