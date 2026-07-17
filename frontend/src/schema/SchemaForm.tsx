import { useEffect, useId, useMemo, useState } from 'react';
import type {
  ParameterFieldSchema,
  PluginParameterSchema,
  PluginUiSchema,
  PluginUiWidget,
} from '../pluginSchemaApi';

export interface SchemaCustomWidgetProps {
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  disabled: boolean;
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
}: {
  parameterSchema: PluginParameterSchema;
  uiSchema: PluginUiSchema;
  value: T;
  onChange: (next: T) => void;
  disabled?: boolean;
  customWidgets?: Readonly<Record<string, SchemaCustomWidget>>;
}) {
  const formId = useId().replace(/:/g, '');

  const changeField = (path: string, fieldValue: unknown) => {
    const next = setValueAtPath(value as Record<string, unknown>, path, fieldValue);
    onChange(next as T);
  };

  return (
    <div data-plugin-schema={uiSchema.pluginId}>
      {uiSchema.groups.map((group) => {
        const controls = group.fields.map((path) => {
          const schema = resolveFieldSchema(parameterSchema, path);
          const fieldValue = getValueAtPath(value as Record<string, unknown>, path);
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
  customWidgets: Readonly<Record<string, SchemaCustomWidget>>;
  onChange: (value: unknown) => void;
}) {
  const widgetType = widget?.type ?? schema['x-fresnel-widget'];
  if (widgetType && widgetType !== 'select' && widgetType !== 'number-with-presets') {
    const CustomWidget = customWidgets[widgetType];
    if (!CustomWidget) {
      return (
        <p key={path} className="error-message" data-schema-field={path}>
          The trusted widget “{widgetType}” is not registered for {schema.title ?? path}.
        </p>
      );
    }
    return (
      <CustomWidget
        path={path}
        schema={schema}
        value={value}
        disabled={disabled}
        onChange={onChange}
      />
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
        presets={widget?.type === 'number-with-presets' ? widget.presets : undefined}
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
        onChange={onChange}
      />
    );
  }

  return (
    <p className="error-message" data-schema-field={path}>
      No standard renderer is available for {schema.title ?? path} ({schema.type}).
    </p>
  );
}

function SchemaNumberField({
  id,
  path,
  schema,
  value,
  required,
  disabled,
  presets,
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  required: boolean;
  disabled: boolean;
  presets?: Array<number | string>;
  onChange: (value: unknown) => void;
}) {
  const initial = numericText(value, schema.default);
  const [text, setText] = useState(initial);
  const parsed = Number(text);
  const valid = useMemo(() => isValidNumber(text, parsed, schema), [text, parsed, schema]);
  const descriptionId = schema.description ? `${id}-description` : undefined;
  const errorId = !valid ? `${id}-error` : undefined;
  const listId = presets && presets.length > 0 ? `${id}-presets` : undefined;

  useEffect(() => {
    setText(numericText(value, schema.default));
  }, [value, schema.default]);

  const updateText = (next: string) => {
    setText(next);
    const number = Number(next);
    if (isValidNumber(next, number, schema)) onChange(number);
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
        aria-invalid={!valid}
        aria-describedby={[descriptionId, errorId].filter(Boolean).join(' ') || undefined}
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
      {!valid && (
        <small id={errorId} className="error-message" style={{ display: 'block' }}>
          Enter a finite {schema.type === 'integer' ? 'integer' : 'number'} within the allowed range.
        </small>
      )}
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
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  required: boolean;
  disabled: boolean;
  onChange: (value: unknown) => void;
}) {
  const options = schema.enum ?? [];
  const current = typeof value === 'string'
    ? value
    : typeof schema.default === 'string' ? schema.default : '';
  const labels = schema['x-fresnel-enum-labels'] ?? {};
  const descriptionId = schema.description ? `${id}-description` : undefined;

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
        aria-describedby={descriptionId}
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
    </div>
  );
}

function SchemaBooleanField({
  id,
  path,
  schema,
  value,
  disabled,
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  disabled: boolean;
  onChange: (value: unknown) => void;
}) {
  const checked = typeof value === 'boolean'
    ? value
    : typeof schema.default === 'boolean' ? schema.default : false;
  return (
    <div className="field" data-schema-field={path}>
      <label htmlFor={id}>
        <input
          id={id}
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked)}
        />{' '}
        {fieldLabel(schema, path)}
      </label>
      {schema.description && (
        <small style={{ display: 'block', color: '#6b7280' }}>{schema.description}</small>
      )}
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
  onChange,
}: {
  id: string;
  path: string;
  schema: ParameterFieldSchema;
  value: unknown;
  required: boolean;
  disabled: boolean;
  onChange: (value: unknown) => void;
}) {
  const current = typeof value === 'string'
    ? value
    : typeof schema.default === 'string' ? schema.default : '';
  const descriptionId = schema.description ? `${id}-description` : undefined;
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
        aria-describedby={descriptionId}
        onChange={(event) => onChange(event.target.value)}
      />
      {schema.description && (
        <small id={descriptionId} style={{ display: 'block', color: '#6b7280' }}>
          {schema.description}
        </small>
      )}
    </div>
  );
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

function isValidNumber(text: string, value: number, schema: ParameterFieldSchema): boolean {
  if (text.trim() === '' || !Number.isFinite(value)) return false;
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

function humanizeEnum(value: string): string {
  const lower = value.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

function toDomId(path: string): string {
  return path.replace(/[^a-zA-Z0-9_-]/g, '-');
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
