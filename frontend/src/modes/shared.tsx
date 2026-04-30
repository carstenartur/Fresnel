import { useEffect, useId, useState, type ReactNode } from 'react';

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
