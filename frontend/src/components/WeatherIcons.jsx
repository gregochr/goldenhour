/**
 * Small inline weather glyphs shared by the marker popup's detail block and the hourly comfort
 * table. Decorative: every one sits beside the figure it names, so they are hidden from assistive
 * technology rather than read out as an unlabelled image.
 */

/** @returns {React.ReactElement} Thermometer SVG icon. */
export function ThermometerIcon() {
  return (
    <svg className="wf-wx-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0z" />
    </svg>
  );
}

/** @returns {React.ReactElement} Wind SVG icon. */
export function WindIcon() {
  return (
    <svg className="wf-wx-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="#60a5fa" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17.7 7.7a2.5 2.5 0 1 1 1.8 4.3H2" />
      <path d="M9.6 4.6A2 2 0 1 1 11 8H2" />
      <path d="M12.6 19.4A2 2 0 1 0 14 16H2" />
    </svg>
  );
}

/** @returns {React.ReactElement} Rain cloud SVG icon. */
export function RainIcon() {
  return (
    <svg className="wf-wx-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 14.899A7 7 0 1 1 15.71 8h1.79a4.5 4.5 0 0 1 2.5 8.242" />
      <path d="M16 14v6" /><path d="M8 14v6" /><path d="M12 16v6" />
    </svg>
  );
}

/** @returns {React.ReactElement} Droplet SVG icon. */
export function DropletIcon() {
  return (
    <svg className="wf-wx-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z" />
    </svg>
  );
}
