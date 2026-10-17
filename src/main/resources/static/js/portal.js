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
