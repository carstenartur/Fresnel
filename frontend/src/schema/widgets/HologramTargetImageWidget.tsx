import { useId, useState, type ChangeEvent } from 'react';
import { fileToBase64 } from '../../api';
import type { SchemaCustomWidgetProps } from '../SchemaForm';

/** Trusted local-file widget for the `hologram-target-image` schema identifier. */
export function HologramTargetImageWidget({
  path,
  schema,
  value,
  disabled,
  onChange,
}: SchemaCustomWidgetProps) {
  const inputId = useId();
  const [filename, setFilename] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const base64 = typeof value === 'string' ? value : '';

  const chooseFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    try {
      const encoded = await fileToBase64(file);
      onChange(encoded);
      setFilename(file.name);
      setError(null);
    } catch (readError) {
      setError(readError instanceof Error ? readError.message : String(readError));
    } finally {
      event.target.value = '';
    }
  };

  return (
    <div className="field" data-schema-field={path}>
      <label htmlFor={inputId}>{schema.title ?? 'Target image'}</label>
      <input
        id={inputId}
        type="file"
        accept="image/png,image/jpeg"
        disabled={disabled}
        onChange={chooseFile}
      />
      {base64 ? (
        <div className="warning info" style={{ marginTop: 8, marginBottom: 0 }} role="status">
          {filename ? `Loaded ${filename}` : 'Embedded target image loaded'} · approximately{' '}
          {Math.ceil(base64.length * 0.75 / 1024).toLocaleString()} KiB decoded
          <button
            type="button"
            className="secondary"
            style={{ marginLeft: 8 }}
            disabled={disabled}
            onClick={() => {
              onChange('');
              setFilename(null);
              setError(null);
            }}
          >
            Clear image
          </button>
        </div>
      ) : (
        <small style={{ display: 'block', color: '#6b7280' }}>
          Choose a local PNG or JPEG. No remote URL is loaded from schema data.
        </small>
      )}
      {schema.description && (
        <small style={{ display: 'block', color: '#6b7280', marginTop: 4 }}>
          {schema.description}
        </small>
      )}
      {error && <p className="error-message">{error}</p>}
    </div>
  );
}
