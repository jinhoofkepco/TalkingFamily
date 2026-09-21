"use strict";

(() => {
  const status = document.getElementById("status");
  const statusText = document.getElementById("status-text");
  const retry = document.getElementById("retry");
  const recenter = document.getElementById("recenter");
  const map = L.map("map", {
    attributionControl: true, zoomControl: true, minZoom: 3, maxZoom: 19,
    zoomAnimation: false, fadeAnimation: false, markerZoomAnimation: false,
    maxBounds: [[-85.05112878, -180], [85.05112878, 180]], maxBoundsViscosity: 1
  });
  map.attributionControl.setPrefix(false);
  map.zoomControl.setPosition("topleft");
  map.zoomControl.options.zoomInTitle = "지도 확대";
  map.zoomControl.options.zoomOutTitle = "지도 축소";
  document.querySelector(".leaflet-control-zoom-in").setAttribute("aria-label", "지도 확대");
  document.querySelector(".leaflet-control-zoom-out").setAttribute("aria-label", "지도 축소");
  document.querySelector(".leaflet-control-zoom-in").setAttribute("title", "지도 확대");
  document.querySelector(".leaflet-control-zoom-out").setAttribute("title", "지도 축소");

  // Only visible tiles are requested. Chromium keeps its normal HTTP cache.
  const tiles = L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    minZoom: 3, maxZoom: 19, maxNativeZoom: 19, noWrap: true,
    updateWhenIdle: true, updateWhenZooming: false, keepBuffer: 0,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>'
  });
  let latest = null;
  let marker = null;
  let accuracyCircle = null;
  let failed = false;
  let loadingTimer = null;

  function showStatus(text, canRetry) {
    status.hidden = false;
    statusText.textContent = text;
    retry.hidden = !canRetry;
  }
  function stopLoadingTimer() { clearTimeout(loadingTimer); loadingTimer = null; }
  tiles.on("loading", () => {
    failed = false;
    stopLoadingTimer();
    showStatus("지도를 불러오고 있어요…", false);
    loadingTimer = setTimeout(() => showStatus("지도 연결이 늦어지고 있어요.", true), 12000);
  });
  tiles.on("tileerror", () => {
    failed = true;
    showStatus("지도를 불러오지 못했어요. 인터넷 연결을 확인해 주세요.", true);
  });
  tiles.on("load", () => {
    stopLoadingTimer();
    if (!failed) status.hidden = true;
  });

  function focusLocation() {
    if (latest) map.setView(latest, Math.max(map.getZoom(), 16), { animate: false });
  }
  recenter.addEventListener("click", focusLocation);
  retry.addEventListener("click", () => {
    failed = false;
    showStatus("지도를 다시 불러오고 있어요…", false);
    // Recreate only this viewport's tiles, without bypassing the HTTP cache.
    tiles.redraw();
  });
  window.addEventListener("resize", () => map.invalidateSize({ pan: false }));

  window.FamilyMap = Object.freeze({
    setLocation(latitude, longitude, accuracy) {
      if (!Number.isFinite(latitude) || !Number.isFinite(longitude) ||
          Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return;
      latest = [Math.max(-85.05112878, Math.min(85.05112878, latitude)), longitude];
      const radius = Number.isFinite(accuracy) && accuracy > 0 ? Math.min(accuracy, 100000) : 0;
      if (!marker) {
        map.setView(latest, 16, { animate: false });
        tiles.addTo(map);
        accuracyCircle = L.circle(latest, { radius, color: "#245b46", weight: 1.5, fillOpacity: .12 }).addTo(map);
        marker = L.circleMarker(latest, { radius: 9, color: "#fff", weight: 3, fillColor: "#245b46", fillOpacity: 1 })
          .addTo(map).bindTooltip("기록된 위치", { permanent: true, direction: "top", offset: [0, -11] });
        recenter.disabled = false;
      } else {
        marker.setLatLng(latest);
        accuracyCircle.setLatLng(latest).setRadius(radius);
        // New points update the marker, while preserving the view the parent chose.
      }
    },
    recenter: focusLocation
  });
})();
