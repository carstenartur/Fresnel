# BOS CI gate

The authoritative branch gate is:

```bash
mvn -B -ntp -Dfresnel.e2e.skip=false verify
```

It compiles every reactor module, validates plugin schemas and jobs, builds the React frontend, starts the backend for browser tests and exercises the target preview/download workflow in Chromium.
