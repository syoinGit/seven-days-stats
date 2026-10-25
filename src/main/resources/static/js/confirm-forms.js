// Forms with data-confirm ask before submitting (inline handlers are blocked by the CSP).
document.addEventListener("submit", (event) => {
  const form = event.target.closest("form[data-confirm]");
  if (form && !window.confirm(form.dataset.confirm)) event.preventDefault();
});
