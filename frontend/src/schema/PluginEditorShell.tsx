import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { FresnelPluginId } from '../jobApi';
import {
  fetchPluginSchema,
  type PluginSchemaDocument,
} from '../pluginSchemaApi';
import {
  SchemaForm,
  type SchemaCustomWidget,
} from './SchemaForm';

export interface PluginEditorShellProps<T extends object> {
  pluginId: FresnelPluginId;
  value: T;
  onChange: (next: T) => void;
  disabled?: boolean;
  customWidgets?: Readonly<Record<string, SchemaCustomWidget>>;
  applyDefaultsOnLoad?: boolean;
  children?: (schema: PluginSchemaDocument<T>) => ReactNode;
}

/**
 * Common lifecycle for every schema-backed plugin editor.
 *
 * <p>The shell owns schema retrieval, loading/error presentation, optional
 * default initialization and the standard form. Plugin panels retain only
 * renderer-specific state and trusted extension components.</p>
 */
export function PluginEditorShell<T extends object>({
  pluginId,
  value,
  onChange,
  disabled = false,
  customWidgets = {},
  applyDefaultsOnLoad = false,
  children,
}: PluginEditorShellProps<T>) {
  const [schema, setSchema] = useState<PluginSchemaDocument<T> | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const defaultsApplied = useRef(false);
  const onChangeRef = useRef(onChange);

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    let active = true;
    defaultsApplied.current = false;
    setSchema(null);
    setSchemaError(null);

    fetchPluginSchema<T>(pluginId)
      .then((loaded) => {
        if (!active) return;
        setSchema(loaded);
        if (applyDefaultsOnLoad && !defaultsApplied.current) {
          defaultsApplied.current = true;
          onChangeRef.current(loaded.defaults);
        }
      })
      .catch((loadError: unknown) => {
        if (!active) return;
        setSchema(null);
        setSchemaError(loadError instanceof Error ? loadError.message : String(loadError));
      });

    return () => { active = false; };
  }, [pluginId, applyDefaultsOnLoad]);

  return (
    <div data-plugin-editor-shell={pluginId}>
      {schema ? (
        <SchemaForm
          parameterSchema={schema.parameterSchema}
          uiSchema={schema.uiSchema}
          value={value}
          onChange={onChange}
          disabled={disabled}
          customWidgets={customWidgets}
        />
      ) : !schemaError ? (
        <p role="status" style={{ fontSize: 12, color: '#6b7280' }}>
          Loading plugin schema…
        </p>
      ) : null}

      {schemaError && (
        <p className="error-message">Could not load editor schema: {schemaError}</p>
      )}

      {schema && children?.(schema)}
    </div>
  );
}
