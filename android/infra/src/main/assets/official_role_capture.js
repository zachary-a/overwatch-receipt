(function () {
  "use strict";
  if (window.top !== window || window.__owReceiptCaptureInstalled) return;
  window.__owReceiptCaptureInstalled = true;
  const site = location.origin === "https://ds.163.com";
  const pay = location.origin === "https://pay.ds.163.com";
  if (!site && !pay) return;
  const rolesUrl = "https://payms.ds.163.com/v1/plutusuinfo/blizzard/accounts?game_code=ld5";
  const infoUrl = "https://inf.ds.163.com/v1/web/base/mine/userInfo";
  let siteReady = false, busy = false, timer = null, attempts = 0, openedLogin = false;
  function post(message) {
    try { window.ReceiptAuthBridge.postMessage(JSON.stringify(message)); } catch (_) {}
  }
  function endpoint(url) {
    try {
      const u = new URL(url, location.href);
      if (u.origin === "https://payms.ds.163.com" && u.pathname === "/v1/plutusuinfo/blizzard/accounts" && u.searchParams.get("game_code") === "ld5") return "roles";
      if (site && u.origin === "https://inf.ds.163.com" && ["/v1/web/base/mine/userInfo", "/v1/web/base/login"].includes(u.pathname)) return "site";
    } catch (_) {}
    return null;
  }
  function deliver(url, text, http) {
    if (window.__owReceiptRolesDone || typeof text !== "string" || text.length > 524288) return;
    const kind = endpoint(url);
    if (kind === "roles") {
      // Preserve original JSON: account IDs can exceed JavaScript's safe integer range.
      post({kind: "roles", endpoint: "blizzardAccounts", http: http, body: text});
    } else if (kind === "site") {
      try {
        const data = JSON.parse(text);
        siteReady = http >= 200 && http < 300 && data.code === 200;
        post({kind: "siteStatus", ready: siteReady, code: Number.isInteger(data.code) ? data.code : null});
        if (siteReady && !busy) schedule(0);
      } catch (_) { post({kind: "discoveryError", stage: "site", reason: "INVALID"}); }
    }
  }
  const open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__owReceiptUrl = String(url);
    return open.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function () {
    if (endpoint(this.__owReceiptUrl)) this.addEventListener("load", function () {
      if (this.responseType === "" || this.responseType === "text") deliver(this.__owReceiptUrl, this.responseText, this.status);
    });
    return send.apply(this, arguments);
  };
  if (window.fetch) {
    const originalFetch = window.fetch;
    window.fetch = function (request) {
      const url = typeof request === "string" ? request : request && request.url;
      const result = originalFetch.apply(this, arguments);
      if (endpoint(url)) result.then(response => response.clone().text().then(text => deliver(url, text, response.status)).catch(() => {})).catch(() => {});
      return result;
    };
  }
  function cookie(name) {
    const raw = document.cookie.split(/;\s*/).find(row => row.startsWith(name + "="))?.slice(name.length + 1) || "";
    try { return decodeURIComponent(raw); } catch (_) { return raw; }
  }
  function openLogin() {
    if (openedLogin || siteReady || !site) return;
    // Open the official form; never touch an input, consent checkbox or submit button.
    const button = document.querySelector(".c-header-user__login") ||
      [...document.querySelectorAll("button")].find(e => ["登录", "登录/注册"].includes(e.textContent.trim()));
    if (button) { openedLogin = true; button.click(); }
  }
  function schedule(delay) {
    clearTimeout(timer);
    if (!window.__owReceiptRolesDone) timer = setTimeout(discover, delay);
  }
  async function discover() {
    if (window.__owReceiptRolesDone || busy || ++attempts > 240) return;
    if (document.visibilityState !== "visible") { schedule(5000); return; }
    busy = true;
    let delay = 5000;
    const controller = new AbortController(), timeout = setTimeout(() => controller.abort(), 12000);
    try {
      if (site && !siteReady) {
        // The website completes /base/login itself. This GET only checks its session.
        const response = await fetch(infoUrl, {credentials: "include", signal: controller.signal,
          headers: {"GL-ClientType": "61", "GL-Uid": cookie("GOD_UUID"), "GL-X-XSRF-TOKEN": cookie("GL-XSRF-TOKEN"),
            "GL-DeviceId": localStorage.getItem("ns-client-id") || localStorage.getItem("ds-website-uuid") || ""}});
        if (response.status === 429) delay = 30000;
        deliver(infoUrl, await response.text(), response.status);
        if (!siteReady) openLogin();
      }
      if (pay || siteReady) {
        const response = await fetch(rolesUrl, {credentials: "include", signal: controller.signal,
          headers: {"Content-Type": "application/json;charset=UTF-8"}});
        if (response.status === 429) delay = Math.max(30000, Math.min(300000, Number(response.headers.get("Retry-After")) * 1000 || 30000));
        deliver(rolesUrl, await response.text(), response.status);
        // Native code handles this actionable response; do not silently poll errors forever.
        window.__owReceiptRoleReadFinished = true;
      }
    } catch (_) {
      post({kind: "discoveryError", stage: site && !siteReady ? "site" : "roles", reason: "NETWORK"});
      window.__owReceiptRoleReadFinished = true;
    } finally {
      clearTimeout(timeout); busy = false;
      if (!window.__owReceiptRoleReadFinished) schedule(delay);
    }
  }
  window.__owReceiptReadRoles = function () {
    window.__owReceiptRolesDone = false; window.__owReceiptRoleReadFinished = false;
    if (site) siteReady = false;
    attempts = 0; schedule(0);
  };
  schedule(1200);
})();

