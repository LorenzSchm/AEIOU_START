export const THEME_STORAGE_KEY = "forwarder.theme";

/** Runs before paint so the page never flashes the wrong theme. Dark is the default. */
export const THEME_BOOT_SCRIPT = `(function(){try{var p=localStorage.getItem("${THEME_STORAGE_KEY}")||"dark";var t=p==="system"?(matchMedia("(prefers-color-scheme: light)").matches?"light":"dark"):p;document.documentElement.dataset.theme=t;}catch(e){}})();`;
