import { useState } from 'react';
import { HexMacroCellPanel } from './modes/HexMacroCellPanel';
import { HologramPanel } from './modes/HologramPanel';
import { MultiFocusPanel } from './modes/MultiFocusPanel';
import { RgbPanel } from './modes/RgbPanel';
import { SingleZonePlatePanel } from './modes/SingleZonePlatePanel';
import { WindowFoilPanel } from './modes/WindowFoilPanel';

type ModeKey = 'single' | 'hex' | 'foil' | 'multi' | 'rgb' | 'hologram';

const MODES: ReadonlyArray<{ key: ModeKey; label: string; component: () => JSX.Element }> = [
  { key: 'single',   label: 'Single ZP',     component: SingleZonePlatePanel },
  { key: 'hex',      label: 'Hex macro',     component: HexMacroCellPanel },
  { key: 'foil',     label: 'Window foil',   component: WindowFoilPanel },
  { key: 'multi',    label: 'Multi-focus',   component: MultiFocusPanel },
  { key: 'rgb',      label: 'RGB',           component: RgbPanel },
  { key: 'hologram', label: 'Hologram (GS)', component: HologramPanel },
];

export function App() {
  const [mode, setMode] = useState<ModeKey>('single');
  // Recreate the panel when the mode changes so each panel gets fresh state.
  const Panel = MODES.find((m) => m.key === mode)!.component;
  return (
    <div className="app">
      <aside className="panel">
        <h1>Fresnel Designer</h1>
        <div role="tablist" className="mode-tabs">
          {MODES.map((m) => (
            <button key={m.key} role="tab" aria-selected={mode === m.key}
                    className={`mode-tab ${mode === m.key ? 'active' : ''}`}
                    onClick={() => setMode(m.key)}>
              {m.label}
            </button>
          ))}
        </div>
        <Panel key={mode} />
      </aside>
      <main className="preview" />
    </div>
  );
}
