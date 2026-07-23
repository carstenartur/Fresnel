import type { PluginCapability } from '../pluginSchemaApi';

export interface PluginAction {
  label: string;
  run: () => void | Promise<void>;
  disabled?: boolean;
  primary?: boolean;
  title?: string;
}

const ACTION_ORDER: readonly PluginCapability[] = [
  'PREVIEW_PNG',
  'EXPORT_PNG',
  'EXPORT_SVG',
  'EXPORT_PDF',
  'EXPORT_PCL',
  'EXPORT_DXF',
  'EXPORT_GERBER',
  'EXPORT_STL',
  'PROPAGATION_PREVIEW',
  'PRINTABILITY_ANALYSIS',
  'OPTICAL_QUALITY_REPORT',
  'EXPERIMENTAL_VALIDATION',
];

/**
 * Renders only actions explicitly supported by both plugin metadata and the
 * trusted editor implementation. Endpoint selection remains in typed API code;
 * capability names are never converted into URLs dynamically.
 */
export function PluginActionBar({
  capabilities,
  actions,
  busy = false,
}: {
  capabilities: readonly PluginCapability[];
  actions: Readonly<Partial<Record<PluginCapability, PluginAction>>>;
  busy?: boolean;
}) {
  const supported = new Set(capabilities);
  const visible = ACTION_ORDER.flatMap((capability) => {
    const action = actions[capability];
    return supported.has(capability) && action ? [{ capability, action }] : [];
  });

  if (visible.length === 0) return null;

  return (
    <div className="actions" data-plugin-action-bar="true">
      {visible.map(({ capability, action }) => (
        <button
          key={capability}
          className={action.primary ? undefined : 'secondary'}
          disabled={busy || action.disabled}
          title={action.title}
          onClick={() => void action.run()}
        >
          {action.label}
        </button>
      ))}
    </div>
  );
}
