# Grounded Experiment Copilot

> **Generated proposals are advisory.**
> Fresnel's schema normalization, deterministic optical validation and manufacturing
> findings remain authoritative. A provider cannot suppress an error or make a design
> fabrication-ready by saying that it is safe.

## Purpose

The Experiment Copilot turns a natural-language optical goal into a reviewable draft of
an existing Fresnel plugin contract. It is not a general chatbot and cannot execute code,
read files, invoke renderers or select arbitrary endpoints.

The first supported vertical slice is:

```text
natural-language Zone Plate goal
  → restricted typed proposal
  → visible assumptions and value origins
  → user review and edits
  → canonical schema normalization
  → deterministic validation and preview
  → versioned .fresnel job
```

Example:

> Create a printable 532 nm zone plate with a 1 m focal distance at 1200 DPI.
> Prefer a robust design that is easy to fabricate.

The review UI distinguishes:

- **User supplied** values explicitly found in the request or edited by the user;
- **Copilot inferred** values, including the deterministically selected printable aperture;
- **Fresnel defaults** taken from the current versioned parameter schema;
- **Deterministic validation results** produced after the proposal boundary.

## Trust boundary

Providers return only an `ExperimentProposal` containing:

```text
selectedPluginId
parameters[] { path, value, source, rationale }
unresolvedQuestions[]
alternatives[]
summary
```

The backend then:

1. rejects unknown providers, plugins, parameter paths and duplicate fields;
2. overlays only known Zone Plate schema defaults and optional current user parameters;
3. asks clarification when wavelength or focal distance is genuinely missing;
4. derives an omitted aperture through the deterministic existing `DesignAssistant`;
5. creates an in-memory candidate job;
6. normalizes it through `FresnelJobService`;
7. validates it through `DesignValidationReports`;
8. returns a canonical `.fresnel` job only after those steps succeed.

The final job stores no API key, hidden prompt or conversation transcript. It remains
renderable and editable without a model provider or network connection.

## Providers

### Deterministic mock provider

Provider ID: `mock`

The mock provider is always available and performs a deliberately bounded extraction for
the Zone Plate MVP. It understands explicit wavelength, focal distance, DPI, aperture,
mask type and polarity. It never performs an external request.

This provider supports:

- ordinary unit and browser tests;
- offline evaluation and demonstrations;
- the reproducible hackathon video;
- operation when paid API quota is unavailable.

It is not presented as a language model. Its status and model identifier make the
implementation explicit in the UI and API.

### OpenAI provider

Provider ID: `openai`

The OpenAI implementation uses the Responses API with strict JSON-schema structured
output. The default model is `gpt-5.6`, but model, endpoint and timeout are configurable.
Only the bounded Zone Plate schema and user request are sent.

Configuration:

| Setting | Environment variable | Default |
|---|---|---|
| enabled | `FRESNEL_COPILOT_OPENAI_ENABLED` | `true` |
| API key | `OPENAI_API_KEY` | none |
| model | `OPENAI_COPILOT_MODEL` | `gpt-5.6` |
| endpoint | `OPENAI_COPILOT_ENDPOINT` | `https://api.openai.com/v1/responses` |
| timeout | `OPENAI_COPILOT_TIMEOUT_SECONDS` | `60` |

The provider reports classified, secret-safe failures for missing configuration,
authentication, quota/rate limits, network errors, refusals and malformed structured
responses. Upstream response bodies and secret values are not exposed to browser clients.

No normal Maven, JUnit, frontend or pull-request test makes a paid external model call.
Provider contract tests use a local HTTP server.

## API

### Provider status

```http
GET /api/assistant/providers
```

Example:

```json
[
  {
    "id": "mock",
    "displayName": "Deterministic Fresnel demo",
    "modelId": "deterministic-zone-plate-parser/1",
    "available": true
  },
  {
    "id": "openai",
    "displayName": "OpenAI structured proposal",
    "modelId": "gpt-5.6",
    "available": false
  }
]
```

Availability reveals only configuration state. It does not expose a secret or quota value.

### Natural-language proposal

```http
POST /api/assistant/propose
Content-Type: application/json
```

```json
{
  "provider": "mock",
  "request": "Create a printable 532 nm zone plate with a 1 m focus at 1200 DPI."
}
```

When the request contains enough optical intent, the response includes:

- field-level value origin and rationale;
- normalized Zone Plate parameters;
- deterministic validation report;
- reviewable alternatives;
- canonical `.fresnel` job.

When core intent is missing, `ready` is `false`, clarification questions are returned and
no job or validation result is fabricated.

## User workflow

Open `/assistant` or select **Assistant** in the application.

1. Choose an available provider.
2. Describe the optical goal.
3. Review every proposed value and its source badge.
4. Edit a value, reset it to the Fresnel default or apply an alternative.
5. Run **Validate & preview**.
6. Save the canonical `.fresnel` job or open it in the trusted Zone Plate editor.
7. Reopen or re-render the file without the provider.

## Existing deterministic Design Assistant

The earlier numeric recommendation endpoint remains available and unchanged:

```http
POST /api/assistant/recommend
Content-Type: application/json
```

It accepts printer resolution, page size, wavelength, target focus and an optional
maximum aperture. The existing `DesignAssistant` generates compact, balanced and
wide-aperture candidates and ranks them deterministically from printability, numerical
aperture, zone count and validation warnings.

This numeric endpoint is also reused inside the new copilot trust boundary when a user
does not specify an aperture: the model does not invent the value; Fresnel chooses it.

## Testing and reproducibility

Automated coverage includes:

- deterministic natural-language extraction;
- missing-intent clarification;
- unknown-path rejection;
- canonical job normalization and parameter hash;
- deterministic validation after the proposal boundary;
- OpenAI request/response contract tests against a local server;
- quota/error classification without secret leakage;
- browser proposal review, user editing, validation, preview, download and editor handoff;
- deterministic Playwright screenshots for the pitch-video pipeline.

The browser demonstration proves:

```text
request → grounded proposal → accepted edit → deterministic validation
        → preview → saved job → trusted editor round trip
```

## Current limitations

The first iteration intentionally supports only the Zone Plate plugin. It does not:

- fabricate or control hardware autonomously;
- execute arbitrary code or shell commands;
- use generated prose as optical validation;
- silently accept missing wavelength or focal intent;
- make paid external calls in normal tests;
- claim that a mock-provider demo came from an LLM.
