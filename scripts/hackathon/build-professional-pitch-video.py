#!/usr/bin/env python3
"""Build the Fresnel pitch with professional narration.

Visual rendering remains delegated to ``build-pitch-video.py`` so the existing,
reviewed slide composition stays the source of truth. Narration is either:

* supplied as reviewed per-scene WAV files through
  ``FRESNEL_PITCH_NARRATION_DIR``; or
* generated with OpenAI text-to-speech.

There is deliberately no low-quality local speech-synthesis fallback.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from PIL import ImageDraw

REPO_ROOT = Path(__file__).resolve().parents[2]
LEGACY_SCRIPT = Path(__file__).with_name("build-pitch-video.py")


def load_visual_module():
    spec = importlib.util.spec_from_file_location("fresnel_pitch_visuals", LEGACY_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Cannot load visual renderer from {LEGACY_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


visuals = load_visual_module()

MODEL = os.environ.get("OPENAI_TTS_MODEL", "gpt-4o-mini-tts-2025-12-15")
VOICE = os.environ.get("OPENAI_TTS_VOICE", "marin")
TTS_ENDPOINT = os.environ.get(
    "OPENAI_TTS_ENDPOINT",
    "https://api.openai.com/v1/audio/speech",
)
TTS_INSTRUCTIONS = os.environ.get(
    "OPENAI_TTS_INSTRUCTIONS",
    (
        "Speak in a natural, confident English presentation voice for a technical "
        "hackathon jury. Sound warm, intelligent and human, with a measured medium "
        "pace, varied sentence rhythm and short natural pauses. Avoid robotic cadence, "
        "sing-song delivery, exaggerated enthusiasm, an advertising tone, or dramatic "
        "announcer phrasing. Pronounce Fresnel as 'freh-NELL', Gerchberg-Saxton as "
        "'GURK-berg SAKS-ton', and '.fresnel' as 'dot freh-NELL'."
    ),
)


def sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def api_error_message(error: urllib.error.HTTPError) -> str:
    try:
        body = error.read(4096).decode("utf-8", errors="replace")
    except Exception:
        body = ""
    return f"OpenAI TTS returned HTTP {error.code}: {body[:1000]}"


def synthesize_openai(text: str, destination: Path) -> dict[str, Any]:
    api_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not api_key:
        raise RuntimeError(
            "OPENAI_API_KEY is required for professional narration. "
            "Set FRESNEL_PITCH_NARRATION_DIR to use reviewed human recordings instead."
        )

    payload = json.dumps({
        "model": MODEL,
        "voice": VOICE,
        "input": text,
        "instructions": TTS_INSTRUCTIONS,
        "response_format": "wav",
        "speed": 1.0,
    }).encode("utf-8")

    request = urllib.request.Request(
        TTS_ENDPOINT,
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "audio/wav",
            "User-Agent": "Fresnel-pitch-video/1",
        },
    )

    last_error: Exception | None = None
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                content = response.read()
                content_type = response.headers.get_content_type()
            if len(content) < 1024:
                raise RuntimeError(
                    f"OpenAI TTS returned only {len(content)} bytes ({content_type})"
                )
            if content.lstrip().startswith((b"{", b"<")):
                raise RuntimeError(
                    "OpenAI TTS returned structured text instead of WAV audio"
                )
            destination.write_bytes(content)
            return {
                "provider": "openai",
                "model": MODEL,
                "voice": VOICE,
                "instructionsSha256": sha256_bytes(TTS_INSTRUCTIONS.encode("utf-8")),
                "sourceSha256": sha256_bytes(content),
            }
        except urllib.error.HTTPError as error:
            last_error = RuntimeError(api_error_message(error))
            if error.code not in {408, 409, 429, 500, 502, 503, 504}:
                raise last_error
        except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
            last_error = error

        if attempt < 3:
            time.sleep(2 ** attempt)

    raise RuntimeError(f"OpenAI TTS failed after 3 attempts: {last_error}")


def narration_source(index: int, scene: dict[str, Any], destination: Path) -> dict[str, Any]:
    directory = os.environ.get("FRESNEL_PITCH_NARRATION_DIR", "").strip()
    if directory:
        source = Path(directory).expanduser().resolve() / f"{index:02d}-{scene['id']}.wav"
        if not source.is_file():
            raise FileNotFoundError(f"Missing reviewed narration file: {source}")
        shutil.copyfile(source, destination)
        return {
            "provider": "recorded",
            "sourceFile": source.name,
            "sourceSha256": visuals.sha256(destination),
        }
    return synthesize_openai(scene["narration"], destination)


def add_ai_disclosure(slide: Path) -> None:
    image = visuals.Image.open(slide).convert("RGB")
    draw = ImageDraw.Draw(image)
    text = "Narration generated with OpenAI text-to-speech."
    disclosure_font = visuals.font(20)
    width = draw.textlength(text, font=disclosure_font)
    draw.rounded_rectangle(
        (visuals.WIDTH - width - 76, visuals.HEIGHT - 58,
         visuals.WIDTH - 28, visuals.HEIGHT - 18),
        radius=14,
        fill="#08131f",
    )
    draw.text(
        (visuals.WIDTH - width - 52, visuals.HEIGHT - 50),
        text,
        font=disclosure_font,
        fill=visuals.MUTED,
    )
    image.save(slide, format="PNG", optimize=True)


def main() -> None:
    scenes_path = REPO_ROOT / "scripts/hackathon/pitch-scenes.json"
    output_dir = REPO_ROOT / "build/hackathon-video"
    work_dir = output_dir / "work"

    output_dir.mkdir(parents=True, exist_ok=True)
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True)

    for command in ("ffmpeg", "ffprobe"):
        if not shutil.which(command):
            raise RuntimeError(f"{command} is required")

    document = json.loads(scenes_path.read_text(encoding="utf-8"))
    scenes = document["scenes"]
    results = []
    narration_metadata = []

    for index, scene in enumerate(scenes, start=1):
        stem = f"{index:02d}-{scene['id']}"
        slide = work_dir / f"{stem}.png"
        source_audio = work_dir / f"{stem}-source.wav"
        audio = work_dir / f"{stem}.wav"
        video = work_dir / f"{stem}.mp4"

        visuals.render_slide(scene, REPO_ROOT).save(slide, format="PNG", optimize=True)
        if index == len(scenes) and not os.environ.get("FRESNEL_PITCH_NARRATION_DIR", "").strip():
            add_ai_disclosure(slide)

        metadata = narration_source(index, scene, source_audio)
        narration_metadata.append({"scene": scene["id"], **metadata})

        visuals.run(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-i", str(source_audio),
            "-ar", "48000", "-ac", "1",
            "-af", "apad=pad_dur=0.65",
            str(audio),
        )
        duration = visuals.wav_duration(audio)
        fade_out = max(0.0, duration - 0.35)
        visuals.run(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-loop", "1", "-framerate", str(visuals.FPS),
            "-i", str(slide), "-i", str(audio),
            "-t", f"{duration:.3f}",
            "-vf",
            f"fade=t=in:st=0:d=0.35,fade=t=out:st={fade_out:.3f}:d=0.35,format=yuv420p",
            "-af",
            f"afade=t=in:st=0:d=0.18,afade=t=out:st={fade_out:.3f}:d=0.35",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
            "-r", str(visuals.FPS),
            "-c:a", "aac", "-b:a", "192k", "-shortest",
            str(video),
        )
        results.append(
            visuals.SceneResult(
                scene["id"], duration, slide, audio, video, scene["narration"]
            )
        )

    concat_file = work_dir / "concat.txt"
    concat_file.write_text(
        "".join(f"file '{item.video.as_posix()}'\n" for item in results),
        encoding="utf-8",
    )
    uncaptioned = work_dir / "fresnel-pitch-uncaptioned.mp4"
    visuals.run(
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "concat", "-safe", "0",
        "-i", str(concat_file), "-c", "copy", str(uncaptioned),
    )

    srt_path = output_dir / "fresnel-pitch.en.srt"
    start = 0.0
    cues = []
    for cue_index, result in enumerate(results, start=1):
        end = start + result.duration
        cues.append(
            f"{cue_index}\n"
            f"{visuals.format_srt_time(start)} --> {visuals.format_srt_time(end)}\n"
            f"{result.narration}\n\n"
        )
        start = end
    srt_path.write_text("".join(cues), encoding="utf-8")

    final_video = output_dir / "fresnel-pitch.mp4"
    subtitle_filter = (
        f"subtitles={srt_path.as_posix()}:"
        "force_style='FontName=DejaVu Sans,FontSize=11,PrimaryColour=&H00FFFFFF&,"
        "OutlineColour=&H80000000&,BorderStyle=3,BackColour=&H90000000&,Outline=1,"
        "Shadow=0,MarginV=5,Alignment=2'"
    )
    visuals.run(
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(uncaptioned),
        "-vf", subtitle_filter,
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
        "-c:a", "copy", "-movflags", "+faststart",
        str(final_video),
    )

    narration_path = output_dir / "fresnel-pitch-narration.txt"
    narration_path.write_text(
        "\n\n".join(item.narration for item in results) + "\n",
        encoding="utf-8",
    )

    inputs = sorted({
        (REPO_ROOT / image).resolve()
        for scene in scenes
        for image in scene.get("images", [])
    })
    inputs.extend([
        (REPO_ROOT / "build/hackathon-video/assets/capture-manifest.json").resolve(),
        scenes_path,
    ])
    manifest = {
        "formatVersion": 2,
        "video": {
            "path": str(final_video.relative_to(REPO_ROOT)),
            "durationSeconds": round(start, 3),
            "width": visuals.WIDTH,
            "height": visuals.HEIGHT,
            "fps": visuals.FPS,
            "sha256": visuals.sha256(final_video),
            "sizeBytes": final_video.stat().st_size,
        },
        "narration": {
            "aiGenerated": not bool(
                os.environ.get("FRESNEL_PITCH_NARRATION_DIR", "").strip()
            ),
            "scenes": narration_metadata,
        },
        "subtitles": {
            "path": str(srt_path.relative_to(REPO_ROOT)),
            "sha256": visuals.sha256(srt_path),
        },
        "inputs": [
            {
                "path": str(path.relative_to(REPO_ROOT)),
                "sha256": visuals.sha256(path),
                "sizeBytes": path.stat().st_size,
            }
            for path in inputs
            if path.is_file()
        ],
        "scenes": [
            {"id": result.scene_id, "durationSeconds": round(result.duration, 3)}
            for result in results
        ],
    }
    (output_dir / "fresnel-pitch-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )

    if start >= 180:
        raise RuntimeError(f"Pitch video is too long: {start:.1f} seconds")
    print(json.dumps(manifest["video"], indent=2))


if __name__ == "__main__":
    main()
