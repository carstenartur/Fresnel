# Reproducible hackathon pitch video

This tooling produces a narrated, captioned 1080p pitch video from the real
Fresnel application and the checked-in documentation jobs.

It deliberately separates three kinds of evidence:

1. **UI captures** are produced from the packaged application with Playwright.
2. **Optical results** come from `docs/assets/plugins/`, whose source of truth is
   the versioned `.fresnel` job set and `docs/generated/example-manifest.json`.
3. **Explanatory slides, narration and subtitles** are generated from
   `pitch-scenes.json`.

No image is fetched from an external website and no product UI is invented.

## Generate locally

Prerequisites: Java 21, Maven, Node 20, Chromium for Playwright, Python 3 with
Pillow, FFmpeg/FFprobe, and `espeak-ng` or `espeak`.

```bash
# Build the packaged application, including the React frontend.
mvn -B -ntp -DskipTests package

# Start it in a separate terminal.
java -jar backend/target/backend-*.jar

# Capture the real UI.
cd frontend
npm ci --legacy-peer-deps
npx playwright install chromium
node scripts/capture-hackathon-assets.mjs
cd ..

# Compose slides, narration, subtitles and MP4.
python3 scripts/hackathon/build-pitch-video.py
```

Outputs are written below `build/hackathon-video/`:

- `fresnel-pitch.mp4`
- `fresnel-pitch.en.srt`
- `fresnel-pitch-narration.txt`
- `fresnel-pitch-manifest.json`
- `assets/capture-manifest.json`

The GitHub workflow uploads these as one reviewable artifact. It is opt-in for
normal development: the workflow is triggered only when pitch tooling or its
source assets change, and none of the normal Maven/JUnit workflows perform the
video encoding.

## Updating the final hackathon story

`pitch-scenes.json` is the editorial source of truth. Keep claims limited to
features visible in the current build. Once the GPT-5.6 experiment-copilot
vertical slice exists, add its real UI capture and replace the final
"next hackathon step" wording with the demonstrated interaction.
