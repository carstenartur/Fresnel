# Reproducible hackathon pitch video

This tooling produces a captioned 1080p pitch video from the real Fresnel
application and versioned `.fresnel` production jobs.

The current submission build is intentionally **subtitle-only**. Voice narration
could not be generated because TTS API quota was unavailable during submission
rendering. The video contains no audio stream and says so explicitly in a lower
on-screen notice at the beginning.

Suggested submission note:

> This build is subtitle-only because TTS API quota was unavailable during
> submission rendering.

The wording is factual and avoids implying that the software itself lacks audio
or accessibility support.

## Evidence sources

The build deliberately separates three kinds of evidence:

1. **UI captures** are produced from the packaged application with Playwright.
2. **Optical results** are generated from versioned `.fresnel` jobs through the
   canonical production executor.
3. **Explanatory slides and subtitles** are generated from `pitch-scenes.json`.

The competition-specific hologram scene is not a composited illustration. The
checked-in `openai-hackathon.fresnel` job generates its target image, phase mask
and reconstructed field during the video build.

No image is fetched from an external website and no product UI is invented.

## Generate locally

Prerequisites: Java 21, Maven, Node 20, Chromium for Playwright, Python 3 with
Pillow, and FFmpeg/FFprobe.

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

# Generate the hologram assets, slides, concise captions and silent MP4.
python3 scripts/hackathon/build-pitch-video.py
python3 scripts/hackathon/refine-pitch-captions.py
```

Outputs are written below `build/hackathon-video/`:

- `fresnel-pitch.mp4`
- `fresnel-pitch.en.srt`
- `fresnel-pitch-narration.txt`
- `fresnel-pitch-manifest.json`
- `assets/capture-manifest.json`
- `generated/openai-hackathon/*.png`

The manifest records that audio is deliberately absent and states the quota
reason. The final GitHub workflow is manual-only, so normal Maven/JUnit and pull
request builds do not encode video.

## Updating the final hackathon story

`pitch-scenes.json` is the editorial source of truth. Keep claims limited to
features visible in the current build. Once the GPT-5.6 experiment-copilot
vertical slice exists, add its real UI capture and replace the final
"future natural-language experiment copilot" wording with the demonstrated
interaction.
