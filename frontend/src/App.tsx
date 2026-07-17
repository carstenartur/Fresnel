import { useRef, useState } from 'react';
import { type FresnelJobDocument, type FresnelPluginId, type LoadedFresnelJob } from './jobApi';
import { JobSourceProvider, OpenJobControl } from './jobs/JobFileControls';
import { AssistantPanel } from './modes/AssistantPanel';
import { ComparisonPanel } from './modes/ComparisonPanel';
import { HexMacroCellPanel } from './modes/HexMacroCellPanel';
import { HologramPanel } from './modes/HologramPanel';
import { MultiFocusPanel } from './modes/MultiFocusPanel';
import { RgbPanel } from './modes/RgbPanel';
import { SingleZonePlatePanel } from './modes/SingleZonePlatePanel';
import { WindowFoilPanel } from './modes/WindowFoilPanel';

type DesignModeKey = 'single' | 'hex' | 'foil' | 'multi' | 'rgb' | 'hologram';
type ModeKey = DesignModeKey | 'compare' | 'assistant';

const MODES: ReadonlyArray<{ key: ModeKey; label: string }> = [
  { key: 'single',    label: 'Single ZP' },
  { key: 'hex',       label: 'Hex macro' },
  { key: 'foil',      label: 'Window foil' },
  { key: 'multi',     label: 'Multi-focus' },
  { key: 'rgb',       label: 'RGB' },
  { key: 'hologram',  label: 'Hologram (GS)' },
  { key: 'compare',   label: 'Compare' },
  { key: 'assistant', label: 'Assistant' },
];

const PLUGIN_TO_MODE: Record<FresnelPluginId, DesignModeKey> = {
  'zone-plate': 'single',
  'hex-macro-cell': 'hex',
  'window-foil': 'foil',
  'multi-focus': 'multi',
  'rgb-zone-plate': 'rgb',
  hologram: 'hologram',
};

interface OpenedJobState {
  job: FresnelJobDocument<unknown>;
  revision: number;
}

export function App() {
  const [mode, setMode] = useState<ModeKey>('single');
  const [openedJob, setOpenedJob] = useState<OpenedJobState | null>(null);
  const [jobNotice, setJobNotice] = useState<string | null>(null);
  const revision = useRef(0);

  const openJob = (loaded: LoadedFresnelJob) => {
    const nextMode = PLUGIN_TO_MODE[loaded.job.plugin.id];
    if (!nextMode) {
      throw new Error(`No editor is registered for plugin "${loaded.job.plugin.id}".`);
    }

    revision.current += 1;
    setOpenedJob({ job: loaded.job, revision: revision.current });
    setMode(nextMode);
    setJobNotice(loaded.migratedFromLegacy
      ? `Migrated legacy design "${loaded.sourceName}" to the .fresnel v1 format.`
      : `Opened "${loaded.sourceName}" as ${loaded.job.plugin.id}.`);
  };

  const selectMode = (nextMode: ModeKey) => {
    setMode(nextMode);
    setOpenedJob(null);
    setJobNotice(null);
  };

  const expectedMode = openedJob ? PLUGIN_TO_MODE[openedJob.job.plugin.id] : null;
  const initialJob = expectedMode === mode ? openedJob?.job ?? null : null;
  const panelKey = `${mode}:${openedJob?.revision ?? 0}`;

  let panel: JSX.Element;
  switch (mode) {
    case 'single':
      panel = <SingleZonePlatePanel key={panelKey} initialJob={initialJob} />;
      break;
    case 'hex':
      panel = <HexMacroCellPanel key={panelKey} initialJob={initialJob} />;
      break;
    case 'foil':
      panel = <WindowFoilPanel key={panelKey} initialJob={initialJob} />;
      break;
    case 'multi':
      panel = <MultiFocusPanel key={panelKey} initialJob={initialJob} />;
      break;
    case 'rgb':
      panel = <RgbPanel key={panelKey} initialJob={initialJob} />;
      break;
    case 'hologram':
      panel = <HologramPanel key={panelKey} initialJob={initialJob} />;
      break;
    case 'compare':
      panel = <ComparisonPanel key={panelKey} />;
      break;
    case 'assistant':
      panel = <AssistantPanel key={panelKey} />;
      break;
  }

  return (
    <div className="app">
      <aside className="panel">
        <h1>Fresnel Designer</h1>
        <OpenJobControl onOpenJob={openJob} />
        {jobNotice && (
          <div className="warning info" style={{ marginBottom: 12 }} role="status">
            {jobNotice}
          </div>
        )}
        <div role="tablist" className="mode-tabs">
          {MODES.map((entry) => (
            <button key={entry.key} role="tab" aria-selected={mode === entry.key}
                    className={`mode-tab ${mode === entry.key ? 'active' : ''}`}
                    onClick={() => selectMode(entry.key)}>
              {entry.label}
            </button>
          ))}
        </div>
        <JobSourceProvider job={initialJob}>
          {panel}
        </JobSourceProvider>
      </aside>
      <main className="preview" />
    </div>
  );
}
