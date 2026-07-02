import { useState } from 'react';
import {
  getRecommendation,
  type AssistantWarning,
  type CandidateDesign,
  type DesignGoalRequest,
  type DesignRecommendation,
  type OpticalQualityReport,
  type RecommendationReason,
  type ValidationResponse,
} from '../api';
import { NumberField } from './shared';

/** Preset for the first vertical slice: 600 dpi, A4, green laser, 2 m focus. */
const DEFAULT_GOAL: DesignGoalRequest = {
  dpi: 600,
  pageSizeWidthMm: 210,
  pageSizeHeightMm: 297,
  wavelengthNm: 532,
  targetFocusMm: 2000,
};

export function AssistantPanel() {
  const [goal, setGoal] = useState<DesignGoalRequest>(DEFAULT_GOAL);
  const [maxAperture, setMaxAperture] = useState<string>('');
  const [result, setResult] = useState<DesignRecommendation | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const patch = (p: Partial<DesignGoalRequest>) => setGoal((g) => ({ ...g, ...p }));

  const run = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const req: DesignGoalRequest = {
        ...goal,
        maxApertureMm: maxAperture !== '' ? Number(maxAperture) : undefined,
      };
      setResult(await getRecommendation(req));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <h2>Design Assistant</h2>
      <p style={{ fontSize: 12, color: '#6b7280', marginBottom: 12 }}>
        Provide your printer and optical constraints. The assistant generates Zone Plate
        candidates, evaluates them and recommends the best option with an explanation.
      </p>
      <div
        className="warning info"
        style={{ fontSize: 12, marginBottom: 12 }}
      >
        <strong>Advisory:</strong> Recommendations are based on the paraxial thin-lens
        approximation and stated assumptions. Verify designs experimentally before use.
      </div>

      <fieldset style={{ border: '1px solid #e5e7eb', borderRadius: 6, padding: 12, marginBottom: 8 }}>
        <legend style={{ fontSize: 13, fontWeight: 600, padding: '0 4px' }}>Printer &amp; page</legend>
        <NumberField label="DPI" value={goal.dpi} min={50} step={50}
          onChange={(v) => patch({ dpi: v })} />
        <NumberField label="Page width (mm)" value={goal.pageSizeWidthMm} min={10} step={1}
          onChange={(v) => patch({ pageSizeWidthMm: v })} />
        <NumberField label="Page height (mm)" value={goal.pageSizeHeightMm} min={10} step={1}
          onChange={(v) => patch({ pageSizeHeightMm: v })} />
      </fieldset>

      <fieldset style={{ border: '1px solid #e5e7eb', borderRadius: 6, padding: 12, marginBottom: 8 }}>
        <legend style={{ fontSize: 13, fontWeight: 600, padding: '0 4px' }}>Light source &amp; optics</legend>
        <NumberField label="Wavelength (nm)" value={goal.wavelengthNm} min={100} max={2000} step={1}
          onChange={(v) => patch({ wavelengthNm: v })} />
        <NumberField label="Target focal distance (mm)" value={goal.targetFocusMm} min={10} step={10}
          onChange={(v) => patch({ targetFocusMm: v })} />
      </fieldset>

      <fieldset style={{ border: '1px solid #e5e7eb', borderRadius: 6, padding: 12, marginBottom: 12 }}>
        <legend style={{ fontSize: 13, fontWeight: 600, padding: '0 4px' }}>Optional constraints</legend>
        <div className="field">
          <label>Max aperture (mm) — leave blank for no limit</label>
          <input
            type="number"
            value={maxAperture}
            min={0.1}
            step={0.1}
            placeholder="(none)"
            onChange={(e) => setMaxAperture(e.target.value)}
          />
        </div>
      </fieldset>

      <div className="actions">
        <button onClick={run} disabled={loading}>
          {loading ? 'Generating…' : 'Get Recommendation'}
        </button>
      </div>

      {error && <p className="error-message">{error}</p>}

      {result && <RecommendationResults result={result} />}
    </>
  );
}

// ---------------------------------------------------------------------------
// Results display
// ---------------------------------------------------------------------------

function RecommendationResults({ result }: { result: DesignRecommendation }) {
  return (
    <div style={{ marginTop: 24 }}>
      {result.globalWarnings.length > 0 && (
        <GlobalWarningsPanel warnings={result.globalWarnings} />
      )}

      <h3 style={{ marginBottom: 8 }}>Recommended design</h3>
      <CandidateCard candidate={result.recommended} isRecommended />

      {result.alternatives.length > 0 && (
        <>
          <h3 style={{ marginTop: 20, marginBottom: 8 }}>Alternatives</h3>
          {result.alternatives.map((c) => (
            <CandidateCard key={c.rank} candidate={c} />
          ))}
        </>
      )}
    </div>
  );
}

function GlobalWarningsPanel({ warnings }: { warnings: AssistantWarning[] }) {
  // Filter out the ADVISORY disclaimer — it is shown in the panel header already
  const nonAdvisory = warnings.filter((w) => w.code !== 'ADVISORY');
  if (nonAdvisory.length === 0) return null;
  return (
    <div style={{ marginBottom: 16 }}>
      {nonAdvisory.map((w) => (
        <div key={w.code} className="warning">
          <strong>{w.code}:</strong> {w.message}
        </div>
      ))}
    </div>
  );
}

function CandidateCard({
  candidate,
  isRecommended = false,
}: {
  candidate: CandidateDesign;
  isRecommended?: boolean;
}) {
  const hasError = candidate.validation.warnings.some((w) => w.severity === 'ERROR');
  const hasWarning = candidate.validation.warnings.some((w) => w.severity === 'WARNING');
  const borderColor = isRecommended
    ? '#2563eb'
    : hasError
      ? '#ef4444'
      : hasWarning
        ? '#f59e0b'
        : '#22c55e';

  const bg = isRecommended ? '#eff6ff' : 'white';

  return (
    <div
      style={{
        border: `2px solid ${borderColor}`,
        borderRadius: 6,
        padding: 12,
        marginBottom: 12,
        background: bg,
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <strong>{candidate.label}</strong>
          {isRecommended && (
            <span
              style={{
                marginLeft: 8,
                fontSize: 11,
                background: '#2563eb',
                color: 'white',
                borderRadius: 4,
                padding: '1px 6px',
              }}
            >
              Recommended
            </span>
          )}
        </div>
        <ScoreBadge rank={candidate.rank} score={candidate.compositeScore} />
      </div>

      <ParametersSummary p={candidate.validation} />
      <ReasonsList reasons={candidate.reasons} />
      {candidate.warnings.length > 0 && <CandidateWarnings warnings={candidate.warnings} />}
    </div>
  );
}

function ScoreBadge({ rank, score }: { rank: number; score: number }) {
  const bg = rank === 1 ? '#2563eb' : rank === 2 ? '#6b7280' : '#9ca3af';
  return (
    <div
      style={{
        background: bg,
        color: 'white',
        borderRadius: 4,
        padding: '2px 8px',
        fontSize: 12,
        fontWeight: 'bold',
        textAlign: 'center',
        minWidth: 56,
      }}
    >
      #{rank} <span style={{ fontWeight: 'normal' }}>({(score * 100).toFixed(0)} %)</span>
    </div>
  );
}

function ParametersSummary({ p }: { p: ValidationResponse }) {
  const m = p.metrics;
  const qr: OpticalQualityReport | undefined = p.qualityReport;
  return (
    <dl style={{ fontSize: 12, marginTop: 8 }}>
      <dt>Outer zone</dt>
      <dd>{m.outerZoneWidthMicrons.toFixed(1)} µm</dd>
      <dt>Px / outer zone</dt>
      <dd>{m.pixelsPerOuterZone.toFixed(2)}</dd>
      <dt>Zones</dt>
      <dd>{m.numberOfZones}</dd>
      <dt>1st-order efficiency</dt>
      <dd>{(m.estimatedFirstOrderEfficiency * 100).toFixed(2)} %</dd>
      {qr && (
        <>
          <dt>NA</dt>
          <dd>{qr.numericalAperture.toFixed(5)}</dd>
          <dt>F#</dt>
          <dd>{qr.fNumber.toFixed(1)}</dd>
          <dt>Airy disk</dt>
          <dd>{qr.airyDiskDiameterMicrons.toFixed(0)} µm</dd>
          <dt>Depth of focus</dt>
          <dd>{qr.depthOfFocusMicrons.toFixed(0)} µm</dd>
        </>
      )}
    </dl>
  );
}

function ReasonsList({ reasons }: { reasons: RecommendationReason[] }) {
  if (reasons.length === 0) return null;
  return (
    <details style={{ marginTop: 8, fontSize: 12 }}>
      <summary style={{ cursor: 'pointer', color: '#6b7280' }}>Scoring breakdown</summary>
      <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
        {reasons.map((r) => (
          <li key={r.dimension} style={{ marginBottom: 2 }}>
            <strong>{r.dimension}:</strong> {r.description}
          </li>
        ))}
      </ul>
    </details>
  );
}

function CandidateWarnings({ warnings }: { warnings: AssistantWarning[] }) {
  return (
    <div style={{ marginTop: 8 }}>
      {warnings.map((w) => (
        <div
          key={w.code}
          className={`warning ${w.code === 'OUTER_ZONE_TOO_SMALL' ? 'error' : ''}`}
          style={{ fontSize: 12 }}
        >
          <strong>{w.code}:</strong> {w.message}
        </div>
      ))}
    </div>
  );
}
