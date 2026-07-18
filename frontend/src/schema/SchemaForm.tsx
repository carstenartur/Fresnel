import { useEffect, useId, useMemo, useState } from 'react';
import type {
  ParameterFieldSchema,
  PluginParameterFieldError,
  PluginParameterSchema,
  PluginUiCondition,
  PluginUiSchema,
  PluginUiWidget,
} from '../pluginSchemaApi';

export interface SchemaCustomWidgetProps {
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  disabled: boolean;
  errors: readonly PluginParameterFieldError[];
  onChange: (value: unknown) => void;
}

export type SchemaCustomWidget = (props: SchemaCustomWidgetProps) => JSX.Element;

export function SchemaForm<T extends object>({
  parameterSchema,
  uiSchema,
  value,
  onChange,
  disabled = false,
  customWidgets = {},
  fieldErrors = [],
}: {
  parameterSchema: PluginParameterSchema;
  uiSchema: PluginUiSchema;
  value: T;
  onChange: (next: T) => void;
  disabled?: boolean;
  customWidgets?: Readonly<Record<string, SchemaCustomWidget>>;
  fieldErrors?: readonly PluginParameterFieldError[];
}) {
  const formId = useId().replace(/:/g, '');
  const source = value as Record<string, unknown>;

  const changeField = (path: string, fieldValue: unknown) => {
    const next = setValueAtPath(source, path, fieldValue);
    onChange(next as T);
  };

  return (
    <div data-plugin-schema={uiSchema.pluginId}>
      {uiSchema.groups.map((group) => {
        if (!conditionMatches(group.visibleWhen, source)) return null;

        const visiblePaths = group.fields.filter((path) =>
          conditionMatches(uiSchema.widgets?.[path]?.visibleWhen, source));
        if (visiblePaths.length === 0) return null;

        const controls = visiblePaths.map((path) => {
          const schema = resolveFieldSchema(parameterSchema, path);
          const fieldValue = getValueAtPath(source, path);
          return (
            <SchemaField
              key={path}
              id={`${formId}-${toDomId(path)}`}
              path={path}
              schema={schema}
              widget={uiSchema.widgets?.[path]}
              value={fieldValue}
              required={isRequired(parameterSchema, path)}
              disabled={disabled}
              errors={errorsForPath(fieldErrors, path)}
              customWidgets={customWidgets}
              onChange={(next) => changeField(path, next)}
            />
          );
        });

        const fieldset = (
          <fieldset
            key={group.id}
            data-schema-group={group.id}
            style={{ border: 0, padding: 0, margin: '0 0 12px' }}
          >
            <legend style={{ fontWeight: 600, fontSize: 15, padding: 0, marginBottom: 8 }}>
              {group.title}
            </legend>
            {controls}
          </fieldset>
        );

        if (!group.collapsible) return fieldset;
        return (
          <details key={group.id} open={!group.advanced} style={{ marginBottom: 12 }}>
            <summary style={{ cursor: 'pointer', fontWeight: 600, marginBottom: 8 }}>
              {group.title}
            </summary>
            <fieldset
              data-schema-group={group.id}
              aria-label={group.title}
              style={{ border: 0, padding: 0, margin: 0 }}
            >
              {controls}
            </fieldset>
          </details>
        );
      })}
    </div>
  );
}

function SchemaField({
  id,
  path,
  schema,
  widget,
  value,
  required,
  disabled,
  errors,
  customWidgets,
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  widget?: PluginUiWidget;
  value: unknown;
  required: boolean;
  disabled: boolean;
  errors: readonly PluginParameterFieldError[];
  customWidgets: Readonly<Record<string, SchemaCustomWidget>>;
  onChange: (value: unknown) => void;
}) {
  const widgetType = widget?.type ?? schema['x-fresnel-widget'];
  const readOnly = schema.readOnly === true || widget?.readOnly === true || widgetType === 'read-only';
  if (readOnly) {
    return (
      <SchemaReadOnlyField
        id={id}
        path={path}
        schema={schema}
        value={value}
        errors={errors}
      />
    );
  }

  if (widgetType && !isStandardWidget(widgetType)) {
    const CustomWidget = customWidgets[widgetType];
    if (!CustomWidget) {
      return (
        <div data-schema-field={path}>
          <p className="error-message">
            The trusted widget “{widgetType}” is not registered for {schema.title ?? path}.
          </p>
          <FieldErrors id={`${id}-backend-errors`} errors={errors} />
        </div>
      );
    }
    return (
      <div data-schema-field-wrapper={path}>
        <CustomWidget
          path={path}
          schema={schema}
          value={value}
          disabled={disabled}
          errors={errors}
          onChange={onChange}
        />
        <FieldErrors id={`${id}-backend-errors`} errors={errors} />
      </div>
    );
  }

  if (schema.type === 'number' || schema.type === 'integer') {
    return (
      <SchemaNumberField
        id={id}
        path={path}
        schema={schema}
        value={value}
        required={required}
        disabled={disabled}
        errors={errors}
        presets={widgetType === 'number-with-presets' ? widget?.presets : undefined}
        onChange={onChange}
      />
    );
  }
  if (schema.type === 'string' && schema.enum && widgetType === 'radio') {
    return (
      <SchemaRadioField
        id={id}
        path={path}
        schema={schema}
        value={value}
        required={required}
        disabled={disabled}
        errors={errors}
        onChange={onChange}
      />
    );
  }
  if (schema.type === 'string' && (schema.enum || widgetType === 'select')) {
    return (
      <SchemaSelectField
        id={id}
        path={path}
        schema={schema}
        value={value}
        required={required}
        disabled={disabled}
        errors={errors}
        onChange={onChange}
      />
    );
  }
  if (schema.type === 'boolean') {
    return (
      <SchemaBooleanField
        id={id}
        path={path}
        schema={schema}
        value={value}
        disabled={disabled}
        errors={errors}
        onChange={onChange}
      />
    );
  }
  if (schema.type === 'string') {
    return (
      <SchemaTextField
        id={id}
        path={path}
        schema={schema}
        value={value}
        required={required}
        disabled={disabled}
        errors={errors}
        onChange={onChange}
      />
    );
  }

  return (
    <div data-schema-field={path}>
      <p className="error-message">
        No standard renderer is available for {schema.title ?? path} ({schema.type}).
      </p>
      <FieldErrors id={`${id}-backend-errors`} errors={errors} />
    </div>
  );
}

function SchemaNumberField({
  id,
  path,
  schema,
  value,
  required,
  disabled,
  errors,
  presets,
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  required: boolean;
  disabled: boolean;
  errors: readonly PluginParameterFieldError[];
  presets?: Array<number | string>;
  onChange: (value: unknown) => void;
}) {
  const [text, setText] = useState(() => numericText(value, schema.default));
  const parsed = parseCompleteFiniteNumber(text);
  const localValid = useMemo(
    () => parsed !== null && isNumberWithinSchema(parsed, schema),
    [parsed, schema],
  );
  const descriptionId = schema.description ? `${id}-description` : undefined;
  const localErrorId = !localValid ? `${id}-local-error` : undefined;
  const backendErrorId = errors.length > 0 ? `${id}-backend-errors` : undefined;
  const listId = presets && presets.length > 0 ? `${id}-presets` : undefined;

  useEffect(() => {
    setText(numericText(value, schema.default));
  }, [value, schema.default]);

  const updateText = (next: string) => {
    setText(next);
    const number = parseCompleteFiniteNumber(next);
    // Incomplete text remains local. Complete finite values are submitted even
    // when locally outside range so canonical validation can report the exact
    // public parameter value instead of an old or silently coerced value.
    if (number !== null) onChange(number);
  };

  return (
    <div className="field" data-schema-field={path}>
      <label htmlFor={id}>
        {fieldLabel(schema, path)}{required ? ' *' : ''}
      </label>
      <input
        id={id}
        type="number"
        inputMode="decimal"
        value={text}
        min={schema.minimum ?? schema.exclusiveMinimum}
        max={schema.maximum ?? schema.exclusiveMaximum}
        step={schema['x-fresnel-step'] ?? (schema.type === 'integer' ? 1 : 'any')}
        list={listId}
        required={required}
        disabled={disabled}
        aria-invalid={!localValid || errors.length > 0}
        aria-describedby={describedBy(descriptionId, localErrorId, backendErrorId)}
        onChange={(event) => updateText(event.target.value)}
      />
      {listId && (
        <datalist id={listId}>
          {presets?.map((preset) => <option key={String(preset)} value={preset} />)}
        </datalist>
      )}
      {schema.description && (
        <small id={descriptionId} style={{ display: 'block', color: '#6b7280' }}>
          {schema.description}
        </small>
      )}
      {!localValid && (
        <small id={localErrorId} className="error-message" style={{ display: 'block' }}>
          Enter a finite {schema.type === 'integer' ? 'integer' : 'number'} within the allowed range.
        </small>
      )}
      <FieldErrors id={backendErrorId} errors={errors} />
    </div>
  );
}

function SchemaSelectField({
  id,
  path,
  schema,
  value,
  required,
  disabled,
  errors,
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  required: boolean;
  disabled: boolean;
  errors: readonly PluginParameterFieldError[];
  onChange: (value: unknown) => void;
}) {
  const options = schema.enum ?? [];
  const current = typeof value === 'string'
    ? value
    : typeof schema.default === 'string' ? schema.default : '';
  const labels = schema['x-fresnel-enum-labels'] ?? {};
  const descriptionId = schema.description ? `${id}-description` : undefined;
  const backendErrorId = errors.length > 0 ? `${id}-backend-errors` : undefined;

  return (
    <div className="field" data-schema-field={path}>
      <label htmlFor={id}>
        {fieldLabel(schema, path)}{required ? ' *' : ''}
      </label>
      <select
        id={id}
        value={current}
        required={required}
        disabled={disabled}
        aria-invalid={errors.length > 0}
        aria-describedby={describedBy(descriptionId, backendErrorId)}
        onChange={(event) => onChange(event.target.value)}
      >
        {options.map((option) => (
          <option key={option} value={option}>{labels[option] ?? humanizeEnum(option)}</option>
        ))}
      </select>
      {schema.description && (
        <small id={descriptionId} style={{ display: 'block', color: '#6b7280' }}>
          {schema.description}
        </small>
      )}
      <FieldErrors id={backendErrorId} errors={errors} />
    </div>
  );
}

function SchemaRadioField({
  id,
  path,
  schema,
  value,
  required,
  disabled,
  errors,
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  required: boolean;
  disabled: boolean;
  errors: readonly PluginParameterFieldError[];
  onChange: (value: unknown) => void;
}) {
  const options = schema.enum ?? [];
  const current = typeof value === 'string'
    ? value
    : typeof schema.default === 'string' ? schema.default : '';
  const labels = schema['x-fresnel-enum-labels'] ?? {};
  const descriptionId = schema.description ? `${id}-description` : undefined;
  const backendErrorId = errors.length > 0 ? `${id}-backend-errors` : undefined;

  return (
    <fieldset
      className="field"
      data-schema-field={path}
      disabled={disabled}
      aria-invalid={errors.length > 0}
      aria-describedby={describedBy(descriptionId, backendErrorId)}
      style={{ border: 0, padding: 0, margin: '0 0 10px' }}
    >
      <legend>
        {fieldLabel(schema, path)}{required ? ' *' : ''}
      </legend>
      {options.map((option, index) => {
        const optionId = `${id}-${index}`;
        return (
          <label key={option} htmlFor={optionId} style={{ display: 'block' }}>
            <input
              id={optionId}
              name={id}
              type="radio"
              value={option}
              checked={current === option}
              required={required}
              disabled={disabled}
              onChange={(event) => {
                if (event.target.checked) onChange(option);
              }}
            />{' '}
            {labels[option] ?? humanizeEnum(option)}
          </label>
        );
      })}
      {schema.description && (
        <small id={descriptionId} style={{ display: 'block', color: '#6b7280' }}>
          {schema.description}
        </small>
      )}
      <FieldErrors id={backendErrorId} errors={errors} />
    </fieldset>
  );
}

function SchemaBooleanField({
  id,
  path,
  schema,
  value,
  disabled,
  errors,
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  disabled: boolean;
  errors: readonly PluginParameterFieldError[];
  onChange: (value: unknown) => void;
}) {
  const checked = typeof value === 'boolean'
    ? value
    : typeof schema.default === 'boolean' ? schema.default : false;
  const descriptionId = schema.description ? `${id}-description` : undefined;
  const backendErrorId = errors.length > 0 ? `${id}-backend-errors` : undefined;
  return (
    <div className="field" data-schema-field={path}>
      <label htmlFor={id}>
        <input
          id={id}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          aria-invalid={errors.length > 0}
          aria-describedby={describedBy(descriptionId, backendErrorId)}
          onChange={(event) => onChange(event.target.checked)}
        />{' '}
        {fieldLabel(schema, path)}
      </label>
      {schema.description && (
        <small id={descriptionId} style={{ display: 'block', color: '#6b7280' }}>
          {schema.description}
        </small>
      )}
      <FieldErrors id={backendErrorId} errors={errors} />
    </div>
  );
}

function SchemaTextField({
  id,
  path,
  schema,
  value,
  required,
  disabled,
  errors,
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  required: boolean;
  disabled: boolean;
  errors: readonly PluginParameterFieldError[];
  onChange: (value: unknown) => void;
}) {
  const current = typeof value === 'string'
    ? value
    : typeof schema.default === 'string' ? schema.default : '';
  const descriptionId = schema.description ? `${id}-description` : undefined;
  const backendErrorId = errors.length > 0 ? `${id}-backend-errors` : undefined;
  return (
    <div className="field" data-schema-field={path}>
      <label htmlFor={id}>
        {fieldLabel(schema, path)}{required ? ' *' : ''}
      </label>
      <input
        id={id}
        value={current}
        required={required}
        disabled={disabled}
        aria-invalid={errors.length > 0}
        aria-describedby={describedBy(descriptionId, backendErrorId)}
        onChange={(event) => onChange(event.target.value)}
      />
      {schema.description && (
        <small id={descriptionId} style={{ display: 'block', color: '#6b7280' }}>
          {schema.description}
        </small>
      )}
      <FieldErrors id={backendErrorId} errors={errors} />
    </div>
  );
}

function SchemaReadOnlyField({
  id,
  path,
  schema,
  value,
  errors,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  errors: readonly PluginParameterFieldError[];
}) {
  const descriptionId = schema.description ? `${id}-description` : undefined;
  const backendErrorId = errors.length > 0 ? `${id}-backend-errors` : undefined;
  return (
    <div className="field" data-schema-field={path}>
      <label htmlFor={id}>{fieldLabel(schema, path)}</label>
      <output
        id={id}
        aria-readonly="true"
        aria-invalid={errors.length > 0}
        aria-describedby={describedBy(descriptionId, backendErrorId)}
        style={{ display: 'block' }}
      >
        {formatReadOnlyValue(value ?? schema.default)}
      </output>
      {schema.description && (
        <small id={descriptionId} style={{ display: 'block', color: '#6b7280' }}>
          {schema.description}
        </small>
      )}
      <FieldErrors id={backendErrorId} errors={errors} />
    </div>
  );
}

function FieldErrors({
  id,
  errors,
}: {
  id: string | undefined;
  errors: readonly PluginParameterFieldError[];
}) {
  if (!id || errors.length === 0) return null;
  return (
    <div id={id} className="error-message" style={{ fontSize: 12, marginTop: 4 }}>
      {errors.map((error, index) => (
        <div key={`${error.path}:${error.code}:${index}`}>{error.message}</div>
      ))}
    </div>
  );
}

function errorsForPath(
  errors: readonly PluginParameterFieldError[],
  path: string,
): PluginParameterFieldError[] {
  return errors.filter((error) =>
    error.path === path
    || error.path.startsWith(`${path}.`)
    || error.path.startsWith(`${path}[`));
}

function resolveFieldSchema(root: PluginParameterSchema, path: string): ParameterFieldSchema {
  let properties: Record<string, ParameterFieldSchema> | undefined = root.properties;
  let current: ParameterFieldSchema | undefined;
  for (const segment of path.split('.')) {
    current = properties?.[segment];
    if (!current) throw new Error(`UI schema refers to unknown parameter field: ${path}`);
    properties = current.properties;
  }
  return current as ParameterFieldSchema;
}

function isRequired(root: PluginParameterSchema, path: string): boolean {
  const segments = path.split('.');
  let properties: Record<string, ParameterFieldSchema> | undefined = root.properties;
  let required = root.required ?? [];
  for (let index = 0; index < segments.length; index++) {
    const segment = segments[index];
    const field: ParameterFieldSchema | undefined = properties?.[segment];
    if (!field) return false;
    if (index === segments.length - 1) return required.includes(segment);
    required = field.required ?? [];
    properties = field.properties;
  }
  return false;
}

function conditionMatches(
  condition: PluginUiCondition | undefined,
  source: Record<string, unknown>,
): boolean {
  if (!condition) return true;
  const actual = getValueAtPath(source, condition.path);
  if (Object.prototype.hasOwnProperty.call(condition, 'equals')) {
    return Object.is(actual, condition.equals);
  }
  if (Object.prototype.hasOwnProperty.call(condition, 'notEquals')) {
    return !Object.is(actual, condition.notEquals);
  }
  return condition.oneOf?.some((candidate) => Object.is(actual, candidate)) ?? false;
}

function getValueAtPath(source: Record<string, unknown>, path: string): unknown {
  let current: unknown = source;
  for (const segment of path.split('.')) {
    if (!isRecord(current)) return undefined;
    current = current[segment];
  }
  return current;
}

function setValueAtPath(
  source: Record<string, unknown>,
  path: string,
  value: unknown,
): Record<string, unknown> {
  const [head, ...tail] = path.split('.');
  if (tail.length === 0) return { ...source, [head]: value };
  const child = isRecord(source[head]) ? source[head] : {};
  return { ...source, [head]: setValueAtPath(child, tail.join('.'), value) };
}

function parseCompleteFiniteNumber(text: string): number | null {
  const trimmed = text.trim();
  if (!/^[+-]?(?:\d+(?:\.\d+)?|\.\d+)(?:[eE][+-]?\d+)?$/.test(trimmed)) return null;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : null;
}

function isNumberWithinSchema(value: number, schema: ParameterFieldSchema): boolean {
  if (schema.type === 'integer' && !Number.isInteger(value)) return false;
  if (schema.minimum !== undefined && value < schema.minimum) return false;
  if (schema.maximum !== undefined && value > schema.maximum) return false;
  if (schema.exclusiveMinimum !== undefined && value <= schema.exclusiveMinimum) return false;
  if (schema.exclusiveMaximum !== undefined && value >= schema.exclusiveMaximum) return false;
  return true;
}

function numericText(value: unknown, fallback: unknown): string {
  if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  if (typeof fallback === 'number' && Number.isFinite(fallback)) return String(fallback);
  return '';
}

function fieldLabel(schema: ParameterFieldSchema, path: string): string {
  const segments = path.split('.');
  const base = schema.title ?? segments[segments.length - 1] ?? path;
  const unit = schema['x-fresnel-unit'];
  return unit && !base.toLowerCase().includes(unit.toLowerCase()) ? `${base} (${unit})` : base;
}

function formatReadOnlyValue(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  return JSON.stringify(value);
}

function humanizeEnum(value: string): string {
  const lower = value.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

function describedBy(...ids: Array<string | undefined>): string | undefined {
  const joined = ids.filter(Boolean).join(' ');
  return joined || undefined;
}

function isStandardWidget(widgetType: string): boolean {
  return widgetType === 'select'
    || widgetType === 'number-with-presets'
    || widgetType === 'radio'
    || widgetType === 'read-only';
}

function toDomId(path: string): string {
  return path.replace(/[^a-zA-Z0-9_-]/g, '-');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
