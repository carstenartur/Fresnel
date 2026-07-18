import { type ComponentType, useEffect, useRef, useState } from 'react';
import { type FresnelJobDocument, type FresnelPluginId, type LoadedFresnelJob } from './jobApi';
import { JobSourceProvider, OpenJobControl, type JobPanelProps } from './jobs/JobFileControls';
import { AssistantPanel } from './modes/AssistantPanel';
import { ComparisonPanel } from './modes/ComparisonPanel';
import { HexMacroCellPanel } from './modes/HexMacroCellPanel';
import { HologramPanel } from './modes/HologramPanel';
import { MultiFocusPanel } from './modes/MultiFocusPanel';
import { RgbPanel } from './modes/RgbPanel';
import { WindowFoilPanel } from './modes/WindowFoilPanel';
import { ZonePlatePanel } from './modes/ZonePlatePanel';
import { fetchPluginMetadata } from './pluginSchemaApi';

type AuxiliaryMode = 'compare' | 'assistant';
type ModeKey = FresnelPluginId | AuxiliaryMode;

interface EditorRegistration {
  label: string;
  component: ComponentType<JobPanelProps>;
}

/** Trusted compile-time component registry. Schema data never supplies module names. */
const EDITOR_REGISTRY: Record<FresnelPluginId, EditorRegistration> = {
  'zone-plate': { label: 'Single ZP', component: ZonePlatePanel },
  'hex-macro-cell': { label: 'Hex macro', component: HexMacroCellPanel },
  'window-foil': { label: 'Window foil', component: WindowFoilPanel },
  'multi-focus': { label: 'Multi-focus', component: MultiFocusPanel },
  'rgb-zone-plate': { label: 'RGB', component: RgbPanel },
  hologram: { label: 'Hologram (GS)', component: HologramPanel },
};

const FALLBACK_PLUGIN_ORDER: readonly FresnelPluginId[] = [
  'zone-plate',
  'hex-macro-cell',
  'window-foil',
  'multi-focus',
  'rgb-zone-plate',
  'hologram',
];

const AUXILIARY_MODES: ReadonlyArray<{ key: AuxiliaryMode; label: string }> = [
  { key: 'compare', label: 'Compare' },
  { key: 'assistant', label: 'Assistant' },
];

interface OpenedJobState {
  job: FresnelJobDocument<unknown>;
  revision: number;
}

export function App() {
  const [mode, setMode] = useState<ModeKey>(() => modeFromPath(window.location.pathname));
  const [pluginOrder, setPluginOrder] = useState<readonly FresnelPluginId[]>(FALLBACK_PLUGIN_ORDER);
  const [pluginRegistryError, setPluginRegistryError] = useState<string | null>(null);
  const [openedJob, setOpenedJob] = useState<OpenedJobState | null>(null);
  const [jobNotice, setJobNotice] = useState<string | null>(null);
  const revision = useRef(0);

  useEffect(() => {
    const applyRoute = () => {
      setMode(modeFromPath(window.location.pathname));
      setOpenedJob(null);
      setJobNotice(null);
    };
    window.addEventListener('popstate', applyRoute);
    return () => window.removeEventListener('popstate', applyRoute);
  }, []);

  useEffect(() => {
    let active = true;
    fetchPluginMetadata()
      .then((plugins) => {
        if (!active) return;

        const unknownIds = plugins
          .map((plugin) => plugin.id)
          .filter((pluginId) => !isPluginId(pluginId));
        setPluginRegistryError(unknownIds.length === 0
          ? null
          : `No trusted editor is registered for backend plugin${unknownIds.length === 1 ? '' : 's'}: ${unknownIds.join(', ')}.`);

        const seen = new Set<FresnelPluginId>();
        const registered = plugins
          .map((plugin) => plugin.id)
          .filter((pluginId): pluginId is FresnelPluginId => isPluginId(pluginId))
          .filter((pluginId) => {
            if (seen.has(pluginId)) return false;
            seen.add(pluginId);
            return true;
          });
        if (registered.length > 0) setPluginOrder(registered);
      })
      .catch(() => {
        // Keep the deterministic compile-time order when metadata is temporarily unavailable.
      });
    return () => { active = false; };
  }, []);

  const openJob = (loaded: LoadedFresnelJob) => {
    const pluginId = loaded.job.plugin.id;
    if (!isPluginId(pluginId)) {
      throw new Error(`No trusted editor is registered for plugin "${pluginId}".`);
    }

    revision.current += 1;
    setOpenedJob({ job: loaded.job, revision: revision.current });
    setMode(pluginId);
    pushModeRoute(pluginId);
    setJobNotice(loaded.migratedFromLegacy
      ? `Migrated legacy design "${loaded.sourceName}" to the .fresnel v1 format.`
      : `Opened "${loaded.sourceName}" as ${pluginId}.`);
  };

  const selectMode = (nextMode: ModeKey) => {
    setMode(nextMode);
    setOpenedJob(null);
    setJobNotice(null);
    pushModeRoute(nextMode);
  };

  const expectedPlugin = openedJob?.job.plugin.id ?? null;
  const initialJob = expectedPlugin === mode ? openedJob?.job ?? null : null;
  const panelKey = `${mode}:${openedJob?.revision ?? 0}`;

  let panel: JSX.Element;
  if (isPluginId(mode)) {
    const Editor = EDITOR_REGISTRY[mode].component;
    panel = <Editor key={panelKey} initialJob={initialJob} />;
  } else if (mode === 'compare') {
    panel = <ComparisonPanel key={panelKey} />;
  } else {
    panel = <AssistantPanel key={panelKey} />;
  }

  return (
    <div className="app">
      <aside className="panel">
        <h1>Fresnel Designer</h1>
        <OpenJobControl onOpenJob={openJob} />
        {pluginRegistryError && (
          <div className="warning error" style={{ marginBottom: 12 }} role="alert">
            {pluginRegistryError}
          </div>
        )}
        {jobNotice && (
          <div className="warning info" style={{ marginBottom: 12 }} role="status">
            {jobNotice}
          </div>
        )}
        <div role="tablist" className="mode-tabs">
          {pluginOrder.map((pluginId) => (
            <button
              key={pluginId}
              role="tab"
              aria-selected={mode === pluginId}
              className={`mode-tab ${mode === pluginId ? 'active' : ''}`}
              onClick={() => selectMode(pluginId)}
            >
              {EDITOR_REGISTRY[pluginId].label}
            </button>
          ))}
          {AUXILIARY_MODES.map((entry) => (
            <button
              key={entry.key}
              role="tab"
              aria-selected={mode === entry.key}
              className={`mode-tab ${mode === entry.key ? 'active' : ''}`}
              onClick={() => selectMode(entry.key)}
            >
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

function modeFromPath(pathname: string): ModeKey {
  const match = /^\/plugins\/([^/]+)\/?$/.exec(pathname);
  if (match && isPluginId(match[1])) return match[1];
  if (/^\/compare\/?$/.test(pathname)) return 'compare';
  if (/^\/assistant\/?$/.test(pathname)) return 'assistant';
  return 'zone-plate';
}

function pushModeRoute(mode: ModeKey): void {
  const pathname = isPluginId(mode) ? `/plugins/${mode}` : `/${mode}`;
  if (window.location.pathname !== pathname) {
    window.history.pushState(null, '', pathname);
  }
}

function isPluginId(value: string): value is FresnelPluginId {
  return Object.prototype.hasOwnProperty.call(EDITOR_REGISTRY, value);
}
