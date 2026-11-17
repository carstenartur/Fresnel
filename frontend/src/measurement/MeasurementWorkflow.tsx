import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import './MeasurementWorkflow.css';

export interface SourceArtifact {
  role: string;
  file?: File;
  sha256?: string;
  byteLength?: number;
}

export interface MeasurementProgress {
  artifacts: SourceArtifact[];
  planned: number;
  ready?: boolean;
  calibrating?: boolean;
  capturing?: boolean;
  captureReady?: boolean;
  analyzing?: boolean;
  completed?: boolean;
  cancelled?: boolean;
  error?: string;
  camera?: string;
  session?: string;
  target?: string | null;
}

type Tracks = Record<string, MeasurementProgress | undefined>;
const WorkflowContext = createContext<{
  tracks: Tracks;
  report: (key: string, progress?: MeasurementProgress) => void;
  active: Record<string, string>;
  activate: (key: string) => void;
} | null>(null);

function family(key: string) { return key.split('/')[0]; }

export function MeasurementWorkflowProvider({ children }: { children: ReactNode }) {
  const [tracks, setTracks] = useState<Tracks>({});
  const [active, setActive] = useState<Record<string,string>>({});
  const report = useCallback((key: string, progress?: MeasurementProgress) => {
    setTracks(previous => ({ ...previous, [key]: progress }));
  }, []);
  const activate = useCallback((key: string) => {
    setActive(previous => previous[family(key)] === key ? previous : { ...previous, [family(key)]: key });
  }, []);
  return <WorkflowContext.Provider value={{tracks, report, active, activate}}>{children}</WorkflowContext.Provider>;
}

/** A panel reports measured inputs and outcomes. The shell never dispatches a camera or analysis command. */
export function useMeasurementTrack(key: string, snapshot: MeasurementProgress) {
  const context = useContext(WorkflowContext);
  const report = context?.report;
  useEffect(() => { report?.(key, snapshot); }, [key, report, snapshot]);
  useEffect(() => () => report?.(key), [key, report]);
  return () => context?.activate(key);
}

export function workflowStage(p: MeasurementProgress, target: boolean, selected: boolean) {
  if (p.error) return 'FAILED';
  if (p.cancelled) return 'CANCELLED';
  if (p.analyzing) return 'ANALYZING';
  if (p.completed) return 'COMPLETED';
  if (p.ready) return 'READY_TO_ANALYZE';
  if (p.capturing) return 'CAPTURING';
  if (p.calibrating) return 'CALIBRATING';
  if (p.artifacts.length) return 'CAPTURING';
  if (selected && p.captureReady !== false) return 'READY_TO_CAPTURE';
  return target ? 'TARGET_READY' : 'DRAFT';
}

const stageNames: Record<ReturnType<typeof workflowStage>,string> = {
  DRAFT:'Draft', TARGET_READY:'Target ready', CALIBRATING:'Checking setup', READY_TO_CAPTURE:'Ready to capture',
  CAPTURING:'Collecting sources', READY_TO_ANALYZE:'Ready to analyze', ANALYZING:'Analyzing', COMPLETED:'Analysis complete',
  FAILED:'Needs attention', CANCELLED:'Cancelled',
};

export interface WorkflowSource {
  key: string;
  label: string;
  captureId: string;
  analysisId: string;
  archiveId: string;
  planned: number;
  requiresTarget?: boolean;
  requiresSession?: boolean;
}

/** Open nested details before focusing the real control, without changing the plugin route. */
function reveal(id: string) {
  const element = document.getElementById(id);
  if (!element) return;
  for (let parent: HTMLElement | null = element; parent; parent = parent.parentElement) {
    if (parent instanceof HTMLDetailsElement) parent.open = true;
  }
  element.focus({ preventScroll: true });
  element.scrollIntoView({ block: 'center' });
}

function ArtifactPreview({ artifact }: { artifact: SourceArtifact }) {
  const [url, setUrl] = useState('');
  useEffect(() => {
    if (!artifact.file) return;
    const next = URL.createObjectURL(artifact.file);
    setUrl(next);
    return () => URL.revokeObjectURL(next);
  }, [artifact.file]);
  return url ? <img src={url} alt={artifact.role + ' source'} /> : null;
}

function SourceGallery({ artifacts }: { artifacts: SourceArtifact[] }) {
  const [preview, setPreview] = useState<string>();
  return <details className="workflow-artifacts">
    <summary>Source files and provenance ({artifacts.length})</summary>
    <ul>{artifacts.map(a => <li key={a.role}>
      <strong>{a.role}</strong>{a.file && ' · ' + a.file.name}
      {(a.file || a.byteLength !== undefined) && ' · ' + (a.file?.size ?? a.byteLength)?.toLocaleString() + ' bytes'}
      {a.sha256 && <code>SHA-256 {a.sha256}</code>}
      {a.file && ['image/png', 'image/jpeg'].includes(a.file.type) &&
        <button type="button" aria-expanded={preview === a.role}
          onClick={() => setPreview(preview === a.role ? undefined : a.role)}>
          {preview === a.role ? 'Hide' : 'Preview'} {a.role}
        </button>}
      {a.file && preview === a.role && <ArtifactPreview artifact={a}/>}
    </li>)}</ul>
  </details>;
}

export function MeasurementWorkflow({ sources, target, targetId, targetError }: {
  sources: readonly WorkflowSource[];
  target?: string;
  targetId: string;
  targetError?: string | null;
}) {
  const ctx = useContext(WorkflowContext);
  if (!ctx || !sources.length) return null;
  const selected = ctx.active[family(sources[0].key)];
  const source = sources.find(s => s.key === selected) ?? sources[0];
  const p = ctx.tracks[source.key] ?? { artifacts: [], planned: ctx.tracks[sources[0].key]?.planned ?? source.planned, captureReady: !source.requiresSession };
  const canCapture = !source.requiresTarget || Boolean(target);
  const progress = { ...p, error: p.error || targetError || undefined };
  const state = workflowStage(progress, Boolean(target), canCapture && selected === source.key);
  const current = targetError || state === 'DRAFT' ? 0 :
    state === 'COMPLETED' ? 3 : p.ready || p.analyzing ? 2 : 1;
  const targetIdentity = p.target === undefined ? target : p.target;
  const steps = [
    { label:'Set up target', id:targetId, available:true },
    { label:'Collect sources', id:source.captureId, available:canCapture },
    { label:'Analyze sources', id:source.analysisId, available:p.ready || p.analyzing || p.completed },
    { label:'Save or reopen', id:source.archiveId, available:true },
  ];
  return <section className="measurement-workflow" aria-label="Measurement workflow" data-state={state}>
    <h3>Measurement workflow</h3>
    <p aria-live="polite"><strong>{stageNames[state]}</strong> · {p.artifacts.length} / {p.planned} sources</p>
    <ol>{steps.map((s, i) => <li key={s.id + i} aria-current={i === current ? 'step' : undefined}>
      <button type="button" disabled={!s.available} onClick={() => {
        if (i === 1 && canCapture) ctx.activate(source.key);
        reveal(s.id);
      }}>{s.label}</button>
    </li>)}</ol>
    <label>Capture source <select value={source.key} onChange={event => {
      const next = sources.find(s => s.key === event.target.value);
      if (next) { ctx.activate(next.key); if (!next.requiresTarget || target) reveal(next.captureId); }
    }}>{sources.map(s => <option key={s.key} value={s.key}>{s.label}</option>)}</select></label>
    <button type="button" onClick={() => reveal('measurement-camera-status')}>Camera service status</button>
    {p.camera && <p>Selected camera · {p.camera}</p>}
    {p.session && <p>Capture session · <code>{p.session}</code></p>}
    {targetIdentity && <p>Target · <code>{targetIdentity}</code></p>}
    {p.target === null && p.artifacts.length > 0 && <p>Target identity was not recorded for these sources.</p>}
    {progress.error && <p>{progress.error.slice(0, 240)}</p>}
    {(state === 'FAILED' || state === 'CANCELLED') &&
      <button type="button" onClick={() => reveal(steps[current].id)}>Review and retry</button>}
    {p.artifacts.length > 0 && <SourceGallery key={source.key} artifacts={p.artifacts}/>}
  </section>;
}
