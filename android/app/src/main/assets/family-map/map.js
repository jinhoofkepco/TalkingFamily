"use strict";

(() => {
  const status = document.getElementById("status");
  const statusText = document.getElementById("status-text");
  const retry = document.getElementById("retry");
  const recenter = document.getElementById("recenter");
  const overview = document.getElementById("overview");
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
  let history = [];
  let timelineInset = 0;
  const route = L.polyline([], { color: "#245b46", weight: 3, opacity: .65, dashArray: "6 7", interactive: false });
  const timePoints = L.layerGroup();
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
    if (latest) {
      map.setView(latest, Math.max(map.getZoom(), 16), { animate: false });
      if (timelineInset) map.panBy([0, timelineInset / 2], { animate: false });
    }
  }
  function fitHistory() {
    if (!history.length) return;
    map.fitBounds(history.map(point => [point[0], point[1]]), {
      paddingTopLeft: [35, 70], paddingBottomRight: [35, timelineInset + 40], maxZoom: 16, animate: false
    });
  }
  function timeLabel(epoch) {
    return new Date(epoch).toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit", hour12: false });
  }
  function showLocation(latitude, longitude, accuracy, measuredAtMillis) {
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude) ||
        Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return;
    latest = [Math.max(-85.05112878, Math.min(85.05112878, latitude)), longitude];
    const radius = Number.isFinite(accuracy) && accuracy > 0 ? Math.min(accuracy, 100000) : 0;
    const label = Number.isSafeInteger(measuredAtMillis) && measuredAtMillis >= 0 && measuredAtMillis <= 253402300799999
      ? timeLabel(measuredAtMillis) + " 기록" : "기록된 위치";
    if (!marker) {
      map.setView(latest, 16, { animate: false });
      tiles.addTo(map);
      route.addTo(map);
      timePoints.addTo(map);
      accuracyCircle = L.circle(latest, { radius, color: "#245b46", weight: 1.5, fillOpacity: .12 }).addTo(map);
      marker = L.circleMarker(latest, { radius: 9, color: "#fff", weight: 3, fillColor: "#245b46", fillOpacity: 1 })
        .addTo(map).bindTooltip(label, { permanent: true, direction: "top", offset: [0, -11] });
      recenter.disabled = false;
    } else {
      marker.setLatLng(latest).setTooltipContent(label);
      accuracyCircle.setLatLng(latest).setRadius(radius);
    }
    marker.bringToFront();
  }
  recenter.addEventListener("click", focusLocation);
  overview.addEventListener("click", fitHistory);
  retry.addEventListener("click", () => {
    failed = false;
    showStatus("지도를 다시 불러오고 있어요…", false);
    // Recreate only this viewport's tiles, without bypassing the HTTP cache.
    tiles.redraw();
  });
  window.addEventListener("resize", () => map.invalidateSize({ pan: false }));

  window.FamilyMap = Object.freeze({
    setLocation: showLocation,
    setTimelineInset(inset) {
      timelineInset = Number.isInteger(inset) ? Math.max(0, Math.min(inset, 300)) : 0;
      document.documentElement.style.setProperty("--timeline-inset", timelineInset + "px");
    },
    setHistory(points) {
      if (!Array.isArray(points) || points.some(point => !Array.isArray(point) || point.length !== 4 ||
          !Number.isFinite(point[0]) || Math.abs(point[0]) > 90 || !Number.isFinite(point[1]) || Math.abs(point[1]) > 180 ||
          !(point[2] === null || (Number.isFinite(point[2]) && point[2] >= 0)) ||
          !Number.isSafeInteger(point[3]) || point[3] < 0 || point[3] > 253402300799999)) return false;
      const firstHistory = history.length === 0;
      history = points.map(point => [Math.max(-85.05112878, Math.min(85.05112878, point[0])), point[1], point[2], point[3]]);
      overview.disabled = !history.length;
      overview.hidden = !history.length;
      timePoints.clearLayers();
      if (!history.length) { route.setLatLngs([]); return true; }
      if (!marker) showLocation(...history[history.length - 1]);
      // Keep every record selectable. Only the drawn connecting line is simplified.
      const vertexCount = Math.min(history.length, 2000);
      const drawn = Array.from({ length: vertexCount }, (_, index) => history[Math.round(index * (history.length - 1) / Math.max(1, vertexCount - 1))]);
      route.setLatLngs(drawn.map(point => [point[0], point[1]]));
      const labelCount = Math.min(history.length, 8);
      for (let index = 0; index < labelCount; index++) {
        const point = history[Math.round(index * (history.length - 1) / Math.max(1, labelCount - 1))];
        L.circleMarker([point[0], point[1]], { radius: 4, color: "#fff", weight: 1, fillColor: "#698675", fillOpacity: 1 })
          .addTo(timePoints).bindTooltip(timeLabel(point[3]), { direction: "top" });
      }
      marker.bringToFront();
      if (firstHistory) fitHistory();
      return true;
    },
    selectHistory(index) {
      if (!Number.isInteger(index) || index < 0 || index >= history.length) return false;
      showLocation(...history[index]);
      return true;
    },
    fitHistory,
    recenter: focusLocation
  });
})();
