import type { CellSpecDto } from '../../api';
import { NumberField } from '../../modes/shared';
import type { SchemaCustomWidgetProps } from '../SchemaForm';

const NEW_CELL: CellSpecDto = {
  focalLengthMm: 1000,
  targetOffsetXmm: 0,
  targetOffsetYmm: 0,
};

/** Trusted custom widget for optional per-cell Window Foil focus specifications. */
export function WindowCellLayoutWidget({
  path,
  schema,
  value,
  disabled,
  onChange,
}: SchemaCustomWidgetProps) {
  const cells = normalizeCells(value);

  const updateCell = (index: number, patch: Partial<CellSpecDto>) => {
    onChange(cells.map((cell, current) => current === index ? { ...cell, ...patch } : cell));
  };
  const addCell = () => onChange([...cells, { ...NEW_CELL }]);
  const removeCell = (index: number) => onChange(cells.filter((_, current) => current !== index));

  return (
    <div className="field" data-schema-field={path}>
      {cells.length === 0 && (
        <p style={{ margin: '0 0 8px', fontSize: 12, color: '#6b7280' }}>
          No per-cell overrides. Fresnel uses the plugin's regular automatic cell layout.
        </p>
      )}
      {cells.map((cell, index) => (
        <fieldset
          key={index}
          style={{
            border: '1px solid #e5e7eb',
            borderRadius: 4,
            padding: 8,
            margin: '0 0 8px',
          }}
        >
          <legend style={{ fontSize: 12, padding: '0 4px' }}>Cell {index + 1}</legend>
          <NumberField
            label={`Cell ${index + 1} focal length (mm)`}
            value={cell.focalLengthMm}
            min={Number.MIN_VALUE}
            step={1}
            onChange={(focalLengthMm) => updateCell(index, { focalLengthMm })}
          />
          <NumberField
            label={`Cell ${index + 1} target X (mm)`}
            value={cell.targetOffsetXmm ?? 0}
            step={1}
            onChange={(targetOffsetXmm) => updateCell(index, { targetOffsetXmm })}
          />
          <NumberField
            label={`Cell ${index + 1} target Y (mm)`}
            value={cell.targetOffsetYmm ?? 0}
            step={1}
            onChange={(targetOffsetYmm) => updateCell(index, { targetOffsetYmm })}
          />
          <button
            type="button"
            className="secondary"
            disabled={disabled}
            onClick={() => removeCell(index)}
            aria-label={`Remove cell specification ${index + 1}`}
          >
            Remove cell specification
          </button>
        </fieldset>
      ))}
      <button type="button" className="secondary" disabled={disabled} onClick={addCell}>
        + Add cell specification
      </button>
      {schema.description && (
        <small style={{ display: 'block', color: '#6b7280', marginTop: 6 }}>
          {schema.description}
        </small>
      )}
    </div>
  );
}

function normalizeCells(value: unknown): CellSpecDto[] {
  if (!Array.isArray(value)) return [];
  return value.filter(isRecord).map((cell) => ({
    focalLengthMm: positiveNumber(cell.focalLengthMm, 1000),
    targetOffsetXmm: finiteNumber(cell.targetOffsetXmm, 0),
    targetOffsetYmm: finiteNumber(cell.targetOffsetYmm, 0),
  }));
}

function finiteNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function positiveNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : fallback;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
