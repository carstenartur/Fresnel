import { useCallback, useEffect, useRef, useState } from 'react';
import { consumeDesktopOpen } from './desktopOpenApi';
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
  const [jobError, setJobError] = useState<string | null>(null);
  const [desktopOpenBusy, setDesktopOpenBusy] = useState(false);
  const revision = useRef(0);
  const desktopTokenStarted = useRef<string | null>(null);

  const applyJob = useCallback((job: FresnelJobDocument<unknown>, notice: string) => {
    const nextMode = PLUGIN_TO_MODE[job.plugin.id];
    if (!nextMode) {
      throw new Error(`No editor is registered for plugin "${job.plugin.id}".`);
    }

    revision.current += 1;
    setOpenedJob({ job, revision: revision.current });
    setMode(nextMode);
    setJobNotice(notice);
    setJobError(null);
  }, []);

  const openJob = useCallback((loaded: LoadedFresnelJob) => {
    applyJob(
      loaded.job,
      loaded.migratedFromLegacy
        ? `Migrated legacy design "${loaded.sourceName}" to the .fresnel v1 format.`
        : `Opened "${loaded.sourceName}" as ${loaded.job.plugin.id}.`,
    );
  }, [applyJob]);

  useEffect(() => {
    const url = new URL(window.location.href);
    const importId = url.searchParams.get('fresnelOpen');
    if (!importId || desktopTokenStarted.current === importId) return;
    desktopTokenStarted.current = importId;

    // Remove the one-time token before any network request so it cannot remain in
    // browser history, copied URLs or screenshots.
    url.searchParams.delete('fresnelOpen');
    window.history.replaceState(window.history.state, '',
      `${url.pathname}${url.search}${url.hash}`);

    setDesktopOpenBusy(true);
    setJobError(null);
    void consumeDesktopOpen(importId)
      .then((result) => {
        if (!result.valid) {
          setJobError(result.errorMessage ?? 'The desktop-opened Fresnel job is invalid.');
          return;
        }
        if (!result.job) {
          setJobError('The desktop open response did not contain a Fresnel job.');
          return;
        }
        applyJob(result.job, `Opened desktop job as ${result.job.plugin.id}.`);
      })
      .catch((error) => {
        setJobError(error instanceof Error ? error.message : String(error));
      })
      .finally(() => setDesktopOpenBusy(false));
  }, [applyJob]);

  const selectMode = (nextMode: ModeKey) => {
    setMode(nextMode);
    setOpenedJob(null);
    setJobNotice(null);
    setJobError(null);
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
        {desktopOpenBusy && (
          <div className="warning info" style={{ marginBottom: 12 }} role="status">
            Opening desktop job…
          </div>
        )}
        {jobNotice && !desktopOpenBusy && (
          <div className="warning info" style={{ marginBottom: 12 }} role="status">
            {jobNotice}
          </div>
        )}
        {jobError && (
          <div className="warning error" style={{ marginBottom: 12 }} role="alert">
            {jobError}
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
