const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/kakao_map.html'), 'utf8');
const clicked = [];
class Marker {
  constructor(options) { Object.assign(this, options); }
  setMap(map) { this.map = map; }
  setPosition(position) { this.position = position; }
  getPosition() { return this.position; }
}
class MapStub {
  constructor(element, options) { Object.assign(this, options); }
  setCenter(center) { this.center = center; }
  setLevel(level) { this.level = level; }
  setBounds(bounds, ...padding) { this.bounds = bounds; this.padding = padding; }
}
class InfoWindow {
  close() {}
  setContent(content) { this.content = content; }
  open(map, marker) { this.marker = marker; }
}
const sandbox = {
  console,
  document: { getElementById: () => ({}) },
  window: {
    addEventListener() {},
    AndroidBridge: { onMapReady() {}, onLocalMarkerClick(id) { clicked.push(id); } },
  },
  kakao: { maps: {
    load: (callback) => callback(),
    Map: MapStub, Marker, InfoWindow,
    LatLng: class { constructor(lat, lng) { this.lat = lat; this.lng = lng; } },
    Size: class {}, Point: class {}, MarkerImage: class {},
    LatLngBounds: class { constructor() { this.positions = []; } extend(p) { this.positions.push(p); } },
    event: { addListener(target, event, callback) { target[event] = callback; } },
  } },
};
vm.createContext(sandbox);
for (const match of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/g)) vm.runInContext(match[1], sandbox);
sandbox.initMap();
sandbox.addMarker(37.4, 127.1, 1, '2026.10.12 10:00:00.000', '', 'record-A');
sandbox.addMarker(37.5, 127.2, 2, '2026.10.12 10:01:00.000', '', 'record-B');
sandbox.addMarker(37.5, 127.2, 3, '2026.10.12 10:01:00.000', '', 'record-B-overlap');
assert.equal(sandbox.markers.length, 3);
sandbox.markers[1].click();
sandbox.markers[2].click();
assert.deepEqual(clicked, ['record-B', 'record-B-overlap']);
sandbox.fitBounds();
assert.equal(sandbox.map.bounds.positions.length, 3);
sandbox.fitBounds(200, 76, 64, 24);
assert.deepEqual(sandbox.map.padding, [200, 76, 64, 24]);
sandbox.setUserLocation(37.42, -122.08, true);
assert.equal(sandbox.myLocationMarker.position.lng, -122.08);
const userMarker = sandbox.myLocationMarker;
sandbox.clearMarkers();
assert.equal(sandbox.markers.length, 0);
assert.equal(userMarker.map, sandbox.map, 'record refresh must retain the valid user marker');
sandbox.clearUserLocation();
assert.equal(userMarker.map, null);
sandbox.setUserLocation(37.5, 127.2, false);
assert.equal(sandbox.myLocationMarker, userMarker);
assert.equal(userMarker.map, sandbox.map);
console.log('PASS: A/B markers, overlapping UUID selection, bounds, overseas GPS, current-marker retention/removal');
