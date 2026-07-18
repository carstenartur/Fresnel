import {
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import type { FresnelPluginId } from '../jobApi';
import {
  fetchPluginSchema,
  validatePluginParameters,
  type PluginParameterValidation,
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
  onStructuralValidation?: (validation: PluginParameterValidation<T> | null) => void;
  children?: (
    schema: PluginSchemaDocument<T>,
    validation: PluginParameterValidation<T> | null,
  ) => ReactNode;
}

interface ValidationSnapshot<T extends object> {
  fingerprint: string;
  result: PluginParameterValidation<T>;
}

/**
 * Common lifecycle for every schema-backed plugin editor.
 *
 * <p>The shell owns schema retrieval, loading/error presentation, optional
 * default initialization, the standard form and debounced canonical structural
 * validation. Plugin panels retain only renderer-specific state and trusted
 * extension components.</p>
 */
export function PluginEditorShell<T extends object>({
  pluginId,
  value,
  onChange,
  disabled = false,
  customWidgets = {},
  applyDefaultsOnLoad = false,
  onStructuralValidation,
  children,
}: PluginEditorShellProps<T>) {
  const [schema, setSchema] = useState<PluginSchemaDocument<T> | null>(null);
  const [schemaError, setSchemaError] = useState<string | null>(null);
  const [validationSnapshot, setValidationSnapshot] =
    useState<ValidationSnapshot<T> | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [visibleFormValid, setVisibleFormValid] = useState(false);
  const shellRef = useRef<HTMLDivElement>(null);
  const defaultsApplied = useRef(false);
  const onChangeRef = useRef(onChange);
  const validationCallbackRef = useRef(onStructuralValidation);
  const validationRequestId = useRef(0);
  const validityTimer = useRef<number | null>(null);
  const valueFingerprint = JSON.stringify(value);
  const validation = validationSnapshot?.fingerprint === valueFingerprint
    ? validationSnapshot.result
    : null;
  const effectiveValidation = visibleFormValid ? validation : null;

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    validationCallbackRef.current = onStructuralValidation;
  }, [onStructuralValidation]);

  useEffect(() => {
    validationCallbackRef.current?.(effectiveValidation);
  }, [effectiveValidation]);

  useEffect(() => {
    let active = true;
    defaultsApplied.current = false;
    setSchema(null);
    setSchemaError(null);
    setValidationSnapshot(null);
    setValidationError(null);
    setVisibleFormValid(false);

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

  useEffect(() => {
    if (!schema) return;
    const requestId = ++validationRequestId.current;
    const fingerprint = valueFingerprint;

    // The previous result belongs to a different public parameter object. Make
    // actions invalid immediately instead of leaving a short stale-valid window
    // during the debounce.
    setValidationError(null);

    const timer = window.setTimeout(async () => {
      try {
        const result = await validatePluginParameters(pluginId, value);
        if (requestId !== validationRequestId.current) return;
        setValidationSnapshot({ fingerprint, result });
        setValidationError(null);
      } catch (requestError) {
        if (requestId !== validationRequestId.current) return;
        setValidationSnapshot(null);
        setValidationError(requestError instanceof Error
          ? requestError.message
          : String(requestError));
      }
    }, 200);

    return () => window.clearTimeout(timer);
  }, [pluginId, schema, value, valueFingerprint]);

  const checkVisibleFormValidity = () => {
    const schemaForm = shellRef.current?.querySelector('[data-plugin-schema]');
    const visiblyInvalid = schemaForm?.querySelector('[aria-invalid="true"]');
    setVisibleFormValid(Boolean(schemaForm) && !visiblyInvalid);
  };

  const scheduleVisibleValidityCheck = () => {
    if (validityTimer.current !== null) window.clearTimeout(validityTimer.current);
    // Input/change capture fires before a controlled field has committed its new
    // aria-invalid state. A zero-delay task observes the committed DOM reliably;
    // unlike requestAnimationFrame it is not throttled in headless/background use.
    validityTimer.current = window.setTimeout(() => {
      validityTimer.current = null;
      checkVisibleFormValidity();
    }, 0);
  };

  useLayoutEffect(() => {
    checkVisibleFormValidity();
    return () => {
      if (validityTimer.current !== null) {
        window.clearTimeout(validityTimer.current);
        validityTimer.current = null;
      }
    };
    // Validation errors can add aria-invalid to a control without changing the
    // public parameter object, so both states participate in the visible check.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema, valueFingerprint, validation]);

  return (
    <div
      ref={shellRef}
      data-plugin-editor-shell={pluginId}
      onInputCapture={scheduleVisibleValidityCheck}
      onChangeCapture={scheduleVisibleValidityCheck}
    >
      {schema ? (
        <SchemaForm
          parameterSchema={schema.parameterSchema}
          uiSchema={schema.uiSchema}
          value={value}
          onChange={onChange}
          disabled={disabled}
          customWidgets={customWidgets}
          fieldErrors={validation?.errors ?? []}
        />
      ) : !schemaError ? (
        <p role="status" style={{ fontSize: 12, color: '#6b7280' }}>
          Loading plugin schema…
        </p>
      ) : null}

      {schemaError && (
        <p className="error-message">Could not load editor schema: {schemaError}</p>
      )}

      {validation && !validation.valid && (
        <div
          className="warning error"
          role="alert"
          data-parameter-validation="invalid"
          style={{ marginBottom: 12 }}
        >
          <strong>Parameter validation failed</strong>
          <ul style={{ margin: '6px 0 0', paddingLeft: 18 }}>
            {validation.errors.map((fieldError, index) => (
              <li key={`${fieldError.path}:${fieldError.code}:${index}`}>
                <code>{fieldError.path}</code>: {fieldError.message}
              </li>
            ))}
          </ul>
        </div>
      )}

      {validationError && (
        <p className="error-message" role="alert">
          Could not validate plugin parameters: {validationError}
        </p>
      )}

      {schema && children?.(schema, effectiveValidation)}
    </div>
  );
}
