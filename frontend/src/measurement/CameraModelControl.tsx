import type { CameraModel } from './bosCameraModel';

const cameraModels: Record<CameraModel, string> = {
  translation: 'Translation',
  affine: 'Affine (rotation, scale and shear)',
  homography: 'Perspective (homography)',
};
export function restoreCameraModel(value: unknown): CameraModel {
  return value === 'affine' || value === 'homography' ? value : 'translation';
}
export function CameraModelControl({
  value,
  onChange,
}: {
  value: CameraModel;
  onChange: (model: CameraModel) => void;
}) {
  return (
    <label style={{ display: 'block', marginBlock: 8 }}>
      Camera motion model{' '}
      <select value={value} onChange={(e) => onChange(e.currentTarget.value as CameraModel)}>
        {Object.entries(cameraModels).map(([model, label]) => (
          <option key={model} value={model}>
            {label}
          </option>
        ))}
      </select>
    </label>
  );
}
