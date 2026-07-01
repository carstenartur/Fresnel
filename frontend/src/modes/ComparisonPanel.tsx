import { useState } from 'react';
import {
  compareDesigns,
  type ComparisonPluginId,
  type DesignComparisonResult,
  type DesignVariant,
  type OpticalQualityReport,
  type ParameterDifference,
  type RgbZonePlateRequest,
  type SingleZonePlateRequest,
  type ValidationResponse,
  type VariantResult,
} from '../api';
import { NumberField } from './shared';

const SINGLE_DEFAULT: SingleZonePlateRequest = {
  apertureDiameterMm: 10,
  focalLengthMm: 1000,
  wavelengthNm: 550,
  dpi: 1200,
  maskType: 'BINARY_AMPLITUDE',
  polarity: 'POSITIVE',
};

const RGB_DEFAULT: RgbZonePlateRequest = {
  base: { ...SINGLE_DEFAULT },
  redNm: 630,
  greenNm: 532,
  blueNm: 450,
};

interface VariantState {
  id: string;
  label: string;
  pluginId: ComparisonPluginId;
  singleParams: SingleZonePlateRequest;
  rgbParams: RgbZonePlateRequest;
}

let nextVariantId = 1;

function makeDefault(label: string, patch?: Partial<SingleZonePlateRequest>): VariantState {
  return {
    id: `variant-${nextVariantId++}`,
    label,
    pluginId: 'single',
    singleParams: { ...SINGLE_DEFAULT, ...patch },
    rgbParams: { ...RGB_DEFAULT },
  };
}

const INITIAL: VariantState[] = [
  makeDefault('Variant A'),
  makeDefault('Variant B', { focalLengthMm: 5000 }),
];

export function ComparisonPanel() {
  const [variants, setVariants] = useState<VariantState[]>(INITIAL);
  const [rankEnabled, setRankEnabled] = useState(false);
  const [result, setResult] = useState<DesignComparisonResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const updateVariant = (idx: number, updater: (v: VariantState) => VariantState) =>
    setVariants((vs) => vs.map((v, i) => (i === idx ? updater(v) : v)));

  const updateSingle = (idx: number, patch: Partial<SingleZonePlateRequest>) =>
    updateVariant(idx, (v) => ({ ...v, singleParams: { ...v.singleParams, ...patch } }));

  const updateRgbBase = (idx: number, patch: Partial<SingleZonePlateRequest>) =>
    updateVariant(idx, (v) => ({
      ...v,
      rgbParams: { ...v.rgbParams, base: { ...v.rgbParams.base, ...patch } },
    }));

  const updateRgb = (idx: number, patch: Partial<RgbZonePlateRequest>) =>
    updateVariant(idx, (v) => ({ ...v, rgbParams: { ...v.rgbParams, ...patch } }));

  const addVariant = () =>
    setVariants((vs) => [...vs, makeDefault(`Variant ${String.fromCharCode(65 + vs.length)}`)]);

  const removeVariant = (idx: number) =>
    setVariants((vs) => vs.filter((_, i) => i !== idx));

  const runComparison = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const req = {
        variants: variants.map((v): DesignVariant => ({
          label: v.label,
          pluginId: v.pluginId,
          ...(v.pluginId === 'single' ? { singleParams: v.singleParams } : { rgbParams: v.rgbParams }),
        })),
        rank: rankEnabled,
      };
      setResult(await compareDesigns(req));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <h2>Design variants</h2>
      <p style={{ fontSize: 12, color: '#6b7280', marginBottom: 8 }}>
        Configure two or more design variants and click <em>Compare</em> to see them
        side-by-side with identical scale, quality reports and parameter differences.
      </p>

      {variants.map((v, idx) => (
        <VariantEditor
          key={v.id}
          index={idx}
          state={v}
          canRemove={variants.length > 2}
          onLabelChange={(l) => updateVariant(idx, (state) => ({ ...state, label: l }))}
          onPluginChange={(p) => updateVariant(idx, (state) => ({ ...state, pluginId: p }))}
          onSingleChange={(patch) => updateSingle(idx, patch)}
          onRgbBaseChange={(patch) => updateRgbBase(idx, patch)}
          onRgbChange={(patch) => updateRgb(idx, patch)}
          onRemove={() => removeVariant(idx)}
        />
      ))}

      <div className="actions" style={{ marginTop: 8 }}>
        <button className="secondary" onClick={addVariant} disabled={loading}>
          + Add variant
        </button>
      </div>

      <div className="field" style={{ marginTop: 12 }}>
        <label>
          <input type="checkbox" checked={rankEnabled}
                 onChange={(e) => setRankEnabled(e.target.checked)} />
          {' '}Rank variants by optical quality &amp; printability
        </label>
      </div>

      <div className="actions">
        <button onClick={runComparison} disabled={loading}>
          {loading ? 'Comparing…' : 'Compare'}
        </button>
      </div>

      {error && <p className="error-message">{error}</p>}

      {result && <ComparisonResults result={result} />}
    </>
  );
}

// ---------------------------------------------------------------------------
// Variant editor
// ---------------------------------------------------------------------------

interface VariantEditorProps {
  index: number;
  state: VariantState;
  canRemove: boolean;
  onLabelChange: (l: string) => void;
  onPluginChange: (p: ComparisonPluginId) => void;
  onSingleChange: (patch: Partial<SingleZonePlateRequest>) => void;
  onRgbBaseChange: (patch: Partial<SingleZonePlateRequest>) => void;
  onRgbChange: (patch: Partial<RgbZonePlateRequest>) => void;
  onRemove: () => void;
}

function VariantEditor({
  index, state, canRemove,
  onLabelChange, onPluginChange, onSingleChange, onRgbBaseChange, onRgbChange, onRemove,
}: VariantEditorProps) {
  return (
    <div style={{ border: '1px solid #e5e7eb', borderRadius: 6, padding: 12, marginBottom: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
        <strong>Variant {index + 1}</strong>
        {canRemove && (
          <button className="secondary" style={{ fontSize: 12, padding: '2px 8px' }} onClick={onRemove}>
            Remove
          </button>
        )}
      </div>

      <div className="field">
        <label>Label</label>
        <input type="text" value={state.label}
               onChange={(e) => onLabelChange(e.target.value)} />
      </div>

      <div className="field">
        <label>Design type</label>
        <select value={state.pluginId}
                onChange={(e) => onPluginChange(e.target.value as ComparisonPluginId)}>
          <option value="single">Zone Plate</option>
          <option value="rgb">RGB Zone Plate</option>
        </select>
      </div>

      {state.pluginId === 'single' && (
        <SingleParamsEditor params={state.singleParams} onChange={onSingleChange} />
      )}
      {state.pluginId === 'rgb' && (
        <RgbParamsEditor req={state.rgbParams}
                         onBaseChange={onRgbBaseChange}
                         onRgbChange={onRgbChange} />
      )}
    </div>
  );
}

function SingleParamsEditor({
  params, onChange,
}: { params: SingleZonePlateRequest; onChange: (p: Partial<SingleZonePlateRequest>) => void }) {
  return (
    <>
      <NumberField label="Aperture (mm)" value={params.apertureDiameterMm} min={0.1} step={0.1}
        onChange={(v) => onChange({ apertureDiameterMm: v })} />
      <NumberField label="Focal length (mm)" value={params.focalLengthMm} min={1} step={1}
        onChange={(v) => onChange({ focalLengthMm: v })} />
      <NumberField label="Wavelength (nm)" value={params.wavelengthNm} min={100} max={2000} step={1}
        onChange={(v) => onChange({ wavelengthNm: v })} />
      <NumberField label="DPI" value={params.dpi} min={50} step={50}
        onChange={(v) => onChange({ dpi: v })} />
    </>
  );
}

function RgbParamsEditor({
  req, onBaseChange, onRgbChange,
}: {
  req: RgbZonePlateRequest;
  onBaseChange: (p: Partial<SingleZonePlateRequest>) => void;
  onRgbChange: (p: Partial<RgbZonePlateRequest>) => void;
}) {
  return (
    <>
      <NumberField label="Aperture (mm)" value={req.base.apertureDiameterMm} min={0.1} step={0.1}
        onChange={(v) => onBaseChange({ apertureDiameterMm: v })} />
      <NumberField label="Focal length (mm)" value={req.base.focalLengthMm} min={1} step={1}
        onChange={(v) => onBaseChange({ focalLengthMm: v })} />
      <NumberField label="DPI" value={req.base.dpi} min={50} step={50}
        onChange={(v) => onBaseChange({ dpi: v })} />
      <NumberField label="Red (nm)" value={req.redNm} min={100} max={2000} step={1}
        onChange={(v) => onRgbChange({ redNm: v })} />
      <NumberField label="Green (nm)" value={req.greenNm} min={100} max={2000} step={1}
        onChange={(v) => onRgbChange({ greenNm: v })} />
      <NumberField label="Blue (nm)" value={req.blueNm} min={100} max={2000} step={1}
        onChange={(v) => onRgbChange({ blueNm: v })} />
    </>
  );
}

// ---------------------------------------------------------------------------
// Comparison results
// ---------------------------------------------------------------------------

function ComparisonResults({ result }: { result: DesignComparisonResult }) {
  return (
    <div style={{ marginTop: 24 }}>
      {result.parameterDifferences.length > 0 && (
        <ParameterDiffTable diffs={result.parameterDifferences}
                            labels={result.variants.map((v) => v.label)} />
      )}

      <div style={{
        display: 'grid',
        gridTemplateColumns: `repeat(${result.variants.length}, 1fr)`,
        gap: 12,
        marginTop: 16,
      }}>
        {result.variants.map((v, i) => (
          <VariantResultCard key={i} v={v} />
        ))}
      </div>
    </div>
  );
}

function ParameterDiffTable({
  diffs, labels,
}: { diffs: ParameterDifference[]; labels: string[] }) {
  return (
    <div style={{ marginBottom: 16 }}>
      <h3>Parameter differences</h3>
      <table style={{ width: '100%', fontSize: 13, borderCollapse: 'collapse' }}>
        <thead>
          <tr style={{ borderBottom: '1px solid #e5e7eb' }}>
            <th style={{ textAlign: 'left', padding: '4px 6px' }}>Parameter</th>
            {labels.map((l, i) => (
              <th key={i} style={{ textAlign: 'right', padding: '4px 6px' }}>{l}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {diffs.map((d) => (
            <tr key={d.parameter} style={{ borderBottom: '1px solid #f3f4f6' }}>
              <td style={{ padding: '4px 6px', fontFamily: 'monospace', fontSize: 12 }}>
                {d.parameter}
                {d.unit && <span style={{ color: '#9ca3af' }}> ({d.unit})</span>}
              </td>
              {d.values.map((val, i) => (
                <td key={i} style={{ textAlign: 'right', padding: '4px 6px' }}>{val}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function VariantResultCard({ v }: { v: VariantResult }) {
  const hasCritical = v.validation.warnings.some((w) => w.severity === 'ERROR');
  const borderColor = hasCritical ? '#ef4444' : v.validation.valid ? '#22c55e' : '#f59e0b';

  return (
    <div style={{ border: `2px solid ${borderColor}`, borderRadius: 6, padding: 12 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <strong>{v.label}</strong>
          <span style={{ fontSize: 11, color: '#6b7280', marginLeft: 6 }}>
            ({v.pluginId})
          </span>
        </div>
        {v.score && (
          <RankBadge rank={v.score.rank} score={v.score.score} />
        )}
      </div>

      {v.previewBase64 && (
        <div style={{ textAlign: 'center', margin: '8px 0' }}>
          <img
            src={`data:image/png;base64,${v.previewBase64}`}
            alt={`Preview of ${v.label}`}
            width={v.previewWidthPx}
            height={v.previewHeightPx}
            style={{ maxWidth: '100%', imageRendering: 'pixelated' }}
          />
          <div style={{ fontSize: 11, color: '#9ca3af' }}>
            {v.pixelsPerMm.toFixed(2)} px/mm
          </div>
        </div>
      )}

      <VariantWarnings warnings={v.validation.warnings} valid={v.validation.valid} />
      <VariantMetricsSummary validation={v.validation} />
      {v.score && <ScoreBreakdown score={v.score} />}
    </div>
  );
}

function RankBadge({ rank, score }: { rank: number; score: number }) {
  const bg = rank === 1 ? '#fbbf24' : rank === 2 ? '#d1d5db' : '#e5e7eb';
  return (
    <div style={{
      background: bg, borderRadius: 4, padding: '2px 8px',
      fontSize: 12, fontWeight: 'bold', textAlign: 'center',
    }}>
      #{rank} <span style={{ fontWeight: 'normal' }}>({(score * 100).toFixed(0)} %)</span>
    </div>
  );
}

function VariantWarnings({
  warnings, valid,
}: { warnings: ValidationResponse['warnings']; valid: boolean }) {
  if (warnings.length === 0) {
    return (
      <div className="warning info" style={{ fontSize: 12, marginTop: 8 }}>
        {valid ? 'Valid — no warnings.' : 'Invalid design.'}
      </div>
    );
  }
  return (
    <div style={{ marginTop: 8 }}>
      {warnings.map((w) => (
        <div key={w.code}
             className={`warning ${w.severity === 'ERROR' ? 'error' : w.severity === 'INFO' ? 'info' : ''}`}
             style={{ fontSize: 12 }}>
          <strong>{w.code}:</strong> {w.message}
        </div>
      ))}
    </div>
  );
}

function VariantMetricsSummary({ validation }: { validation: ValidationResponse }) {
  const m = validation.metrics;
  const qr: OpticalQualityReport | undefined = validation.qualityReport;
  return (
    <dl style={{ fontSize: 12, marginTop: 8 }}>
      <dt>Outer zone</dt><dd>{m.outerZoneWidthMicrons.toFixed(2)} µm</dd>
      <dt>Px / outer zone</dt><dd>{m.pixelsPerOuterZone.toFixed(2)}</dd>
      <dt>Zones</dt><dd>{m.numberOfZones}</dd>
      <dt>1st-order efficiency</dt>
      <dd>{(m.estimatedFirstOrderEfficiency * 100).toFixed(2)} %</dd>
      {qr && (
        <>
          <dt>NA</dt><dd>{qr.numericalAperture.toFixed(4)}</dd>
          <dt>F#</dt><dd>{qr.fNumber.toFixed(1)}</dd>
          <dt>Airy disk</dt><dd>{qr.airyDiskDiameterMicrons.toFixed(2)} µm</dd>
          <dt>Depth of focus</dt><dd>{qr.depthOfFocusMicrons.toFixed(1)} µm</dd>
          <dt>Chromatic shift</dt><dd>{qr.chromaticFocalShiftMm.toFixed(2)} mm</dd>
        </>
      )}
    </dl>
  );
}

function ScoreBreakdown({ score }: { score: { score: number; explanation: string } }) {
  return (
    <details style={{ marginTop: 8, fontSize: 12 }}>
      <summary style={{ cursor: 'pointer', color: '#6b7280' }}>
        Score breakdown
      </summary>
      <p style={{ margin: '4px 0', color: '#374151' }}>{score.explanation}</p>
    </details>
  );
}
