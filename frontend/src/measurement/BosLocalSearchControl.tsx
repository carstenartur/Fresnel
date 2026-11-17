import type { LocalSearch } from './bosDisplacement';

export function savedLocalSearch(value: unknown): LocalSearch {
  // Experiments saved before the pyramid estimator used a single-resolution search.
  return value === 'pyramid' ? 'pyramid' : 'single-scale';
}

export function BosLocalSearchControl({
  value,
  onChange,
}: {
  value: LocalSearch;
  onChange: (next: LocalSearch) => void;
}) {
  return (
    <details>
      <summary>Correlation settings</summary>
      <label>
        Local displacement search{' '}
        <select value={value} onChange={(e) => onChange(e.currentTarget.value as LocalSearch)}>
          <option value="pyramid">Coarse to fine (up to 10 px per axis)</option>
          <option value="single-scale">Single scale (original 5 px search radius)</option>
        </select>
      </label>
    </details>
  );
}
