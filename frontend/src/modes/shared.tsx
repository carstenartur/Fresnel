import { useEffect, useId, useMemo, useState, type ReactNode } from 'react';
import type { DesignValidationReport, ValidationLayer } from '../api';

export function NumberField({
  label,
  value,
  min,
  max,
  step,
  disabled = false,
  onChange,
}: {
  label: string;
  value: number;
  min?: number;
  max?: number;
  step?: number;
  disabled?: boolean;
  onChange: (value: number) => void;
}) {
  const inputId = useId();
  const errorId = `${inputId}-error`;
  const [text, setText] = useState(() => finiteText(value));
  const parsed = parseCompleteFiniteNumber(text);
  const valid = useMemo(
    () => parsed !== null
      && (min === undefined || parsed >= min)
      && (max === undefined || parsed <= max),
    [parsed, min, max],
  );

  useEffect(() => {
    setText(finiteText(value));
  }, [value]);

  const updateText = (next: string) => {
    setText(next);
    const number = parseCompleteFiniteNumber(next);
    // Keep incomplete text local. Complete finite values, including values
    // outside the advertised range, remain in the public parameter object so
    // canonical backend validation can explain the actual submitted value.
    if (number !== null) onChange(number);
  };

  return (
    <div className="field">
      <label htmlFor={inputId}>{label}</label>
      <input
        id={inputId}
        type="number"
        inputMode="decimal"
        value={text}
        min={min}
        max={max}
        step={step}
        disabled={disabled}
        aria-invalid={!valid}
        aria-describedby={!valid ? errorId : undefined}
        onChange={(event) => updateText(event.target.value)}
      />
      {!valid && (
        <small id={errorId} className="error-message" style={{ display: 'block' }}>
          Enter a finite number within the allowed range.
        </small>
      )}
    </div>
  );
}

function parseCompleteFiniteNumber(text: string): number | null {
  const trimmed = text.trim();
  if (!/^[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

function finiteText(value: number): string {
  return Number.isFinite(value) ? String(value) : '';
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
export function useBlobUrl(): [string | null, (blob: Blob) => void] {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => () => { if (url) URL.revokeObjectURL(url); }, [url]);
  const set = (blob: Blob) => {
    setUrl((previous) => {
      if (previous) URL.revokeObjectURL(previous);
      return URL.createObjectURL(blob);
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
        {report.metrics.map((metric) => (
          <div key={`${metric.layer}:${metric.key}`} style={{ display: 'contents' }}>
            <dt>
              {metric.label}{' '}
              <span style={{ color: '#9ca3af' }}>({LAYER_LABELS[metric.layer]})</span>
            </dt>
            <dd>{formatMetricValue(metric.value, metric.unit)}</dd>
          </div>
        ))}
      </dl>

      <h4 style={{ margin: '10px 0 4px', fontSize: 13 }}>Findings</h4>
      {report.findings.length === 0 && (
        <div className="warning info">No findings.</div>
      )}
      {report.findings.map((finding) => (
        <div
          key={`${finding.layer}:${finding.code}`}
          className={`warning ${finding.severity === 'ERROR' ? 'error' : finding.severity === 'INFO' ? 'info' : ''}`}
        >
          <strong>{finding.code}</strong> ({LAYER_LABELS[finding.layer]}): {finding.message}
        </div>
      ))}

      <h4 style={{ margin: '10px 0 4px', fontSize: 13 }}>Assumptions / limitations</h4>
      <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13 }}>
        {report.assumptions.map((assumption, index) => (
          <li key={`${assumption.layer}:${index}`}>
            {assumption.statement}
            {assumption.limitation ? ' (limitation)' : ''}
          </li>
        ))}
      </ul>
    </div>
  );
}

function formatMetricValue(value: number, unit: string): string {
  const rendered = Number.isFinite(value) ? value.toFixed(3).replace(/\.?0+$/, '') : String(value);
  return unit ? `${rendered} ${unit}` : rendered;
}
