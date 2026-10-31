import L from 'leaflet';
import { useEffect, useMemo, useRef } from 'react';
import { category, isOpen } from './labels.js';

// Where the map opens before any SOS has a location: Bengaluru, where the team tests.
const HOME = { center: [12.97, 77.59], zoom: 11 };

/** The W2 map: one pin per located SOS in its category colour; new ones pulse. */
export default function SosMap({ sos, selected, onSelect }) {
  const box = useRef(null);
  const map = useRef(null);
  const pins = useRef(null);
  const accuracy = useRef(null);
  const fitted = useRef(false);
  const onSelectRef = useRef(onSelect);
  onSelectRef.current = onSelect;

  useEffect(() => {
    const m = L.map(box.current, { zoomControl: true, attributionControl: true }).setView(HOME.center, HOME.zoom);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(m);
    pins.current = L.layerGroup().addTo(m);
    map.current = m;
    // The panel next to the map can change size; keep the tiles filling the box.
    const observer = new ResizeObserver(() => m.invalidateSize());
    observer.observe(box.current);
    return () => {
      observer.disconnect();
      m.remove();
      map.current = null;
    };
  }, []);

  const located = useMemo(() => sos.filter((s) => s.lat != null && s.lon != null), [sos]);

  useEffect(() => {
    const layer = pins.current;
    if (!layer) return;
    layer.clearLayers();
    // Draw open SOS last so they sit on top of closed ones.
    const ordered = [...located].sort((a, b) => Number(isOpen(a)) - Number(isOpen(b)));
    for (const s of ordered) {
      const c = category(s.category);
      const classes = ['pin', s.status === 'NEW' ? 'pin-new' : '', isOpen(s) ? '' : 'pin-closed', selected?.id === s.id ? 'pin-selected' : ''].join(' ');
      const icon = L.divIcon({
        className: '',
        html: `<div class="${classes}" style="--cat:${c.color}"><span class="material-symbols-outlined" aria-hidden="true">${c.icon}</span></div>`,
        iconSize: [34, 34],
        iconAnchor: [17, 17],
      });
      L.marker([s.lat, s.lon], { icon, title: `${c.label}, ${s.people} people`, keyboard: true, riseOnHover: true })
        .on('click', () => onSelectRef.current(s.id))
        .addTo(layer);
    }
    if (!fitted.current && located.length > 0 && map.current) {
      fitted.current = true;
      map.current.fitBounds(L.latLngBounds(located.map((s) => [s.lat, s.lon])).pad(0.3), { maxZoom: 15 });
    }
  }, [located, selected?.id]);

  // The selected SOS: centre on it and show how precise its location is.
  useEffect(() => {
    const m = map.current;
    accuracy.current?.remove();
    accuracy.current = null;
    if (!m || selected?.lat == null) return;
    if (selected.accuracyM) {
      accuracy.current = L.circle([selected.lat, selected.lon], {
        radius: selected.accuracyM,
        color: category(selected.category).color,
        weight: 1,
        fillOpacity: 0.12,
        interactive: false,
      }).addTo(m);
    }
    m.flyTo([selected.lat, selected.lon], Math.max(m.getZoom(), 16), { duration: 0.6 });
  }, [selected?.id, selected?.lat, selected?.lon, selected?.accuracyM, selected?.category]);

  const unlocated = sos.length - located.length;
  return (
    <div className="map-wrap">
      <div ref={box} className="map" />
      <div className="map-legend" aria-hidden="true">
        <span>
          <span className="legend-dot pulse" /> New, not answered yet
        </span>
        {unlocated > 0 && <span>{unlocated} without a location (list only)</span>}
      </div>
    </div>
  );
}
