// Guild windows: links inside the guild open their page as a window over the tavern instead of
// leaving it. The address follows, so Back closes a window and a shared link opens the same one.
(() => {
  const dialog = document.getElementById("guild-window");
  const scrim = document.querySelector("[data-guild-scrim]");
  const tabbar = document.querySelector("[data-guild-tabbar]");
  if (!dialog || !scrim || !tabbar) return;
  const WINDOW = /^\/guild\/(visitors|board|characters(\/\d+)?|log(\/\d+)?|pairs\/\d+-\d+)$/;
  const TAVERN_TITLE = "無人の冒険者ギルド | Rubinco";
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
      history.pushState(null, "", "/guild");
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
    if (url.pathname === "/guild" && !url.search) {
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
    history.replaceState(null, "", "/guild");
    history.pushState({ guildDepth: 1 }, "", here);
    show();
  }
})();
