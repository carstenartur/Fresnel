import type { FocusPointDto } from '../../api';
import { NumberField } from '../../modes/shared';
import type { SchemaCustomWidgetProps } from '../SchemaForm';

const NEW_POINT: FocusPointDto = { xMm: 0, yMm: 0, zMm: 1000 };

/** Trusted custom widget for the `focus-point-list` UI-schema identifier. */
export function FocusPointListWidget({
  path,
  schema,
  value,
  disabled,
  onChange,
}: SchemaCustomWidgetProps) {
  const points = normalizePoints(value, schema.default);

  const updatePoint = (index: number, patch: Partial<FocusPointDto>) => {
    onChange(points.map((point, current) => current === index ? { ...point, ...patch } : point));
  };
  const addPoint = () => onChange([...points, { ...NEW_POINT }]);
  const removePoint = (index: number) => {
    if (points.length <= 1) return;
    onChange(points.filter((_, current) => current !== index));
  };

  return (
    <div className="field" data-schema-field={path}>
      {schema.description && (
        <p style={{ margin: '0 0 8px', fontSize: 12, color: '#6b7280' }}>
          {schema.description}
        </p>
      )}
      {points.map((point, index) => (
        <fieldset
          key={index}
          disabled={disabled}
          style={{
            border: '1px solid #e5e7eb',
            borderRadius: 4,
            padding: 8,
            margin: '0 0 8px',
          }}
        >
          <legend style={{ fontSize: 12, padding: '0 4px' }}>Focus {index + 1}</legend>
          <div style={{
            display: 'grid',
            gridTemplateColumns: '1fr 1fr 1fr auto',
            gap: 4,
            alignItems: 'end',
          }}>
            <NumberField
              label={`x${index + 1} (mm)`}
              value={point.xMm}
              step={0.5}
              onChange={(xMm) => updatePoint(index, { xMm })}
            />
            <NumberField
              label={`y${index + 1} (mm)`}
              value={point.yMm}
              step={0.5}
              onChange={(yMm) => updatePoint(index, { yMm })}
            />
            <NumberField
              label={`z${index + 1} (mm)`}
              value={point.zMm}
              min={Number.MIN_VALUE}
              step={10}
              onChange={(zMm) => updatePoint(index, { zMm })}
            />
            <button
              type="button"
              className="secondary"
              disabled={disabled || points.length <= 1}
              onClick={() => removePoint(index)}
              title={`Remove focus ${index + 1}`}
              aria-label={`Remove focus ${index + 1}`}
            >
              ×
            </button>
          </div>
        </fieldset>
      ))}
      <button type="button" className="secondary" disabled={disabled} onClick={addPoint}>
        + Add focus point
      </button>
    </div>
  );
}

function normalizePoints(value: unknown, fallback: unknown): FocusPointDto[] {
  const source = Array.isArray(value) && value.length > 0
    ? value
    : Array.isArray(fallback) && fallback.length > 0 ? fallback : [NEW_POINT];
  const points = source
    .filter(isRecord)
    .map((point) => ({
      xMm: finiteNumber(point.xMm, 0),
      yMm: finiteNumber(point.yMm, 0),
      // Keep finite invalid values visible. The canonical backend validator owns
      // the positivity rule and reports the indexed path to the user.
      zMm: finiteNumber(point.zMm, 1000),
    }));
  return points.length > 0 ? points : [{ ...NEW_POINT }];
}

function finiteNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
