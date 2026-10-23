(function () {
  "use strict";
  if (window.top !== window || location.origin !== "https://ds.163.com") return;
  function install() {
    if (document.getElementById("ow-receipt-login-layout")) return;
    // Keep the official forms and consent controls intact. Only adapt the surrounding layout.
    const style = document.createElement("style");
    style.id = "ow-receipt-login-layout";
    style.textContent = `
      @media (max-width: 600px) {
        .c-login-urs-popup-modal { overflow-x: auto !important; touch-action: pan-x pan-y pinch-zoom !important; }
        .c-login-urs-popup-modal .ant-modal {
          position: fixed !important;
          top: calc(var(--ow-receipt-vp-top, 0px) + var(--ow-receipt-vp-height, 100vh) / 2) !important;
          left: calc(var(--ow-receipt-vp-left, 0px) + var(--ow-receipt-vp-width, 100vw) / 2) !important;
          transform: translate(-50%, -50%) !important; box-sizing: border-box !important;
          width: calc(var(--ow-receipt-vp-width, 100vw) - 24px) !important; min-width: 0 !important;
          max-width: 390px !important; margin: 0 !important; padding: 0 !important;
          max-height: calc(var(--ow-receipt-vp-height, 100vh) - 24px) !important; overflow: auto !important;
        }
        .c-login-urs-popup-modal::before { display: none !important; }
        .c-login-urs-popup {
          box-sizing: border-box !important; min-width: 0 !important;
          width: 100% !important; max-width: 390px !important;
          max-height: none !important; padding: 54px 8px 12px !important;
          overflow-x: auto !important; overflow-y: auto !important;
        }
        .c-login-urs-popup-qrcode { display: none !important; }
        .c-login-urs-popup__close { top: 16px !important; right: 16px !important; }
        .c-login-urs-popup-form {
          flex: 0 0 350px !important; width: 350px !important; padding-right: 0 !important;
          height: auto !important; min-height: 400px !important; zoom: var(--ow-receipt-form-scale, 1);
        }
      }
    `;
    document.head.appendChild(style);
    const viewport = document.querySelector('meta[name="viewport"]');
    if (viewport) viewport.setAttribute("content", "width=device-width,initial-scale=1,minimum-scale=0.4,maximum-scale=5,user-scalable=yes");
    function resize() {
      const viewport = window.visualViewport;
      // After a pinch, retain layout coordinates so users can magnify and pan normally.
      if (viewport && viewport.scale > 1.01 && document.documentElement.style.getPropertyValue("--ow-receipt-vp-width")) return;
      const width = viewport ? viewport.width : document.documentElement.clientWidth;
      const height = viewport ? viewport.height : innerHeight;
      const root = document.documentElement.style;
      root.setProperty("--ow-receipt-vp-width", width + "px");
      root.setProperty("--ow-receipt-vp-height", height + "px");
      root.setProperty("--ow-receipt-vp-top", (viewport ? viewport.offsetTop : 0) + "px");
      root.setProperty("--ow-receipt-vp-left", (viewport ? viewport.offsetLeft : 0) + "px");
      root.setProperty("--ow-receipt-form-scale", String(Math.min(1, Math.max(0.5, (width - 44) / 350))));
    }
    resize();
    window.addEventListener("resize", resize, {passive: true});
    if (window.visualViewport) window.visualViewport.addEventListener("resize", resize, {passive: true});
  }
  if (document.head) install();
  else document.addEventListener("DOMContentLoaded", install, {once: true});
})();
