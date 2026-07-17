import {
  createContext,
  useContext,
  useState,
  type ChangeEvent,
  type ReactNode,
} from 'react';
import {
  FRESNEL_JOB_EXTENSION,
  FRESNEL_JOB_MEDIA_TYPE,
  loadFresnelJobFromFile,
  saveFresnelJob,
  type FresnelJobDocument,
  type FresnelPluginId,
  type LoadedFresnelJob,
} from '../jobApi';

const JobSourceContext = createContext<FresnelJobDocument<unknown> | null>(null);

export interface JobPanelProps {
  initialJob?: FresnelJobDocument<unknown> | null;
}

export type OpenJobHandler = (loaded: LoadedFresnelJob) => void;

export function JobSourceProvider({
  job,
  children,
}: {
  job: FresnelJobDocument<unknown> | null;
  children: ReactNode;
}) {
  return (
    <JobSourceContext.Provider value={job}>
      {children}
    </JobSourceContext.Provider>
  );
}

export function initialJobParameters<T extends object>(
  job: FresnelJobDocument<unknown> | null | undefined,
  expectedPluginId: FresnelPluginId,
  defaults: T,
): T {
  if (!job || job.plugin.id !== expectedPluginId || !isRecord(job.parameters)) {
    return { ...defaults };
  }
  return { ...defaults, ...job.parameters } as T;
}

export function OpenJobControl({ onOpenJob }: { onOpenJob: OpenJobHandler }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const openFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;

    setBusy(true);
    setError(null);
    try {
      onOpenJob(await loadFresnelJobFromFile(file));
    } catch (openError) {
      setError(openError instanceof Error ? openError.message : String(openError));
    } finally {
      setBusy(false);
      event.target.value = '';
    }
  };

  return (
    <div style={{ marginBottom: 12 }}>
      <label className="secondary" style={{ cursor: busy ? 'default' : 'pointer' }}>
        {busy ? 'Opening job…' : 'Open job…'}
        <input
          type="file"
          accept={`${FRESNEL_JOB_MEDIA_TYPE},${FRESNEL_JOB_EXTENSION},application/json,.json`}
          disabled={busy}
          style={{ display: 'none' }}
          onChange={openFile}
        />
      </label>
      {error && <p className="error-message" style={{ marginTop: 8 }}>{error}</p>}
    </div>
  );
}

export function SaveJobControl<T>({
  pluginId,
  parameters,
  disabled = false,
  filename,
}: {
  pluginId: FresnelPluginId;
  parameters: T | null;
  disabled?: boolean;
  filename?: string;
}) {
  const sourceJob = useContext(JobSourceContext);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    if (parameters === null) return;
    setBusy(true);
    setError(null);
    try {
      await saveFresnelJob(pluginId, parameters, filename, sourceJob);
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : String(saveError));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ marginTop: 16 }}>
      <h2>Design job</h2>
      <div className="actions">
        <button
          className="secondary"
          disabled={disabled || busy || parameters === null}
          onClick={() => void save()}
        >
          {busy ? 'Saving…' : 'Save job (.fresnel)'}
        </button>
      </div>
      {error && <p className="error-message" style={{ marginTop: 8 }}>{error}</p>}
    </div>
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
