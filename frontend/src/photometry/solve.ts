export interface Light { x: number; y: number; z: number; relativeIntensity: number }
export interface Grid {
  normals: Float32Array; albedo: Float32Array; confidence: Float32Array;
  residual: Float32Array; valid: Uint8Array; lightCount: Uint8Array;
}

export const limits = { shadow: .008, saturation: .98, minFlatSignal: .025,
  minLights: 4, outlierThreshold: .035, maxRelativeResidual: .12, maxPixels: 512 * 512 } as const;

type Row = readonly [number, number, number];

// Solve the symmetric normal equations by cofactors. The trace bound on condition rejects
// an unstable local solution even when the full screen geometry passed its global check.
function fit(rows: Row[], signal: Float64Array, used: number, skip: number, g: Float64Array) {
  let xx = 0, xy = 0, xz = 0, yy = 0, yz = 0, zz = 0, bx = 0, by = 0, bz = 0;
  for (let i = 0; i < rows.length; i++) {
    if (!(used & (1 << i)) || i === skip) continue;
    const [x, y, z] = rows[i], value = signal[i];
    xx += x*x; xy += x*y; xz += x*z; yy += y*y; yz += y*z; zz += z*z;
    bx += x*value; by += y*value; bz += z*value;
  }
  const a = yy*zz - yz*yz, b = xz*yz - xy*zz, c = xy*yz - xz*yy;
  const d = xx*zz - xz*xz, e = xy*xz - xx*yz, f = xx*yy - xy*xy;
  const det = xx*a + xy*b + xz*c;
  if (det <= 1e-12 || (xx+yy+zz)*(a+d+f) / det > 1600) return false;
  g[0] = (a*bx + b*by + c*bz) / det;
  g[1] = (b*bx + d*by + e*bz) / det;
  g[2] = (c*bx + e*by + f*bz) / det;
  return g[2] > 0;
}

function sumSquaredError(rows: Row[], signal: Float64Array, used: number, skip: number, g: Float64Array) {
  let square = 0;
  for (let i = 0; i < rows.length; i++) {
    if (!(used & (1 << i)) || i === skip) continue;
    const [x, y, z] = rows[i];
    const error = signal[i] - (x*g[0] + y*g[1] + z*g[2]);
    square += error*error;
  }
  return square;
}

/** Linear luminance in [0,1]; rows correspond to explicitly mapped directional pattern IDs. */
export function reconstruct(lights: Light[], dark: Float32Array, flat: Float32Array, frames: Float32Array[]): Grid {
  if (!dark.length || dark.length > limits.maxPixels || dark.length !== flat.length ||
      lights.length !== frames.length || lights.length < 4 || lights.length > 16 ||
      frames.some(frame => frame.length !== dark.length)) throw new Error('Inconsistent or oversized photometry dataset');
  const rows = lights.map<Row>(l => [l.x*l.relativeIntensity, l.y*l.relativeIntensity, l.z*l.relativeIntensity]);
  const n = dark.length;
  const grid: Grid = { normals: new Float32Array(3*n), albedo: new Float32Array(n),
    confidence: new Float32Array(n), residual: new Float32Array(n), valid: new Uint8Array(n), lightCount: new Uint8Array(n) };
  const signal = new Float64Array(lights.length), g = new Float64Array(3), candidate = new Float64Array(3);
  for (let p = 0; p < n; p++) {
    // The flat frame supplies an illuminated-object mask. Dividing by local flat brightness
    // here would erase the spatial albedo we want to measure.
    if (!(flat[p] - dark[p] > limits.minFlatSignal) || dark[p] >= limits.saturation) continue;
    let mask = 0, count = 0, total = 0;
    for (let i = 0; i < frames.length; i++) {
      const raw = frames[i][p];
      signal[i] = raw - dark[p];
      if (!(signal[i] > limits.shadow) || raw >= limits.saturation) continue;
      mask |= 1 << i;
      total += signal[i];
      count++;
    }
    grid.lightCount[p] = count;
    if (count < limits.minLights || !fit(rows, signal, mask, -1, g)) continue;
    let square = sumSquaredError(rows, signal, mask, -1, g);
    if (count >= limits.minLights + 1 && square > limits.outlierThreshold**2) {
      // Accept removal of one gross reflection only when the other observations agree.
      let best = square, rejected = -1;
      for (let i = 0; i < lights.length; i++) {
        if (!(mask & (1 << i)) || !fit(rows, signal, mask, i, candidate)) continue;
        const error = sumSquaredError(rows, signal, mask, i, candidate);
        if (error < best) { best = error; rejected = i; }
      }
      if (rejected >= 0 && best < .16*square) {
        fit(rows, signal, mask, rejected, g);
        square = best;
        total -= signal[rejected];
        count--;
      }
    }
    const residual = Math.sqrt(square / count);
    const relative = residual / (total/count);
    grid.lightCount[p] = count;
    grid.residual[p] = residual;
    if (!(relative <= limits.maxRelativeResidual)) continue;
    const albedo = Math.hypot(g[0], g[1], g[2]);
    if (!(albedo > 1e-8)) continue;
    grid.valid[p] = 1;
    grid.albedo[p] = albedo;
    grid.confidence[p] = (count/lights.length) * (1 - relative/limits.maxRelativeResidual);
    for (let j = 0; j < 3; j++) grid.normals[3*p+j] = g[j] / albedo;
  }
  return grid;
}
