import { useEffect, useId, useState, type ReactNode } from 'react';
import type { DesignValidationReport, ValidationLayer } from '../api';

export function NumberField({ label, value, min, max, step, onChange }: {
  label: string; value: number; min?: number; max?: number; step?: number;
  onChange: (v: number) => void;
}) {
  const inputId = useId();
  return (
    <div className="field">
      <label htmlFor={inputId}>{label}</label>
      <input id={inputId} type="number" value={value} min={min} max={max} step={step}
             onChange={(e) => onChange(Number(e.target.value))} />
    </div>
  );
}

export function PreviewPane({ url, alt, children }: { url: string | null; alt: string; children?: ReactNode; }) {
  return (
    <div className="preview-canvas" style={{ marginTop: 16 }}>
      {url
        ? <img src={url} alt={alt} />
        : children ?? <span style={{ color: '#9ca3af' }}>No preview yet</span>}
    </div>
  );
}

/** Manage a single object-URL for a Blob, revoking on update / unmount. */
export function useBlobUrl(): [string | null, (b: Blob) => void] {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  const set = (b: Blob) => {
    setUrl((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return URL.createObjectURL(b);
    });
  };
  return [url, set];
}

const LAYER_LABELS: Record<ValidationLayer, string> = {
  ANALYTICAL_OPTICS: 'Analytical optics',
  NUMERICAL_PROPAGATION: 'Numerical / propagation',
  MANUFACTURING_PRINTABILITY: 'Manufacturing / printability',
  EXPERIMENTAL_HOOKS: 'Experimental hooks',
};

export function ValidationReportView({ report }: { report: DesignValidationReport | null }) {
  if (!report) return null;
  return (
    <div className="metrics" style={{ marginTop: 16 }}>
      <h3>Validation report ({report.pluginId})</h3>
      <p style={{ margin: '0 0 8px', fontSize: 12, color: '#6b7280' }}>
        Hash: <code>{report.parameterHash.slice(0, 12)}</code>
      </p>

      <h4 style={{ margin: '8px 0 4px', fontSize: 13 }}>Metrics</h4>
      <dl>
        {report.metrics.map((m) => (
          <div key={`${m.layer}:${m.key}`} style={{ display: 'contents' }}>
            <dt>{m.label} <span style={{ color: '#9ca3af' }}>({LAYER_LABELS[m.layer]})</span></dt>
            <dd>{Number.isFinite(m.value) ? m.value.toFixed(3).replace(/\.?0+$/, '') : String(m.value)} {m.unit}</dd>
          </div>
        ))}
      </dl>

      <h4 style={{ margin: '10px 0 4px', fontSize: 13 }}>Findings</h4>
      {report.findings.length === 0 && (
        <div className="warning info">No findings.</div>
      )}
      {report.findings.map((f) => (
        <div key={`${f.layer}:${f.code}`}
             className={`warning ${f.severity === 'ERROR' ? 'error' : f.severity === 'INFO' ? 'info' : ''}`}>
          <strong>{f.code}</strong> ({LAYER_LABELS[f.layer]}): {f.message}
        </div>
      ))}

      <h4 style={{ margin: '10px 0 4px', fontSize: 13 }}>Assumptions / limitations</h4>
      <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13 }}>
        {report.assumptions.map((a, idx) => (
          <li key={`${a.layer}:${idx}`}>
            {a.statement}
            {a.limitation ? ' (limitation)' : ''}
          </li>
        ))}
      </ul>
    </div>
  );
}
