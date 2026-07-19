#!/usr/bin/env python3
"""Replace paragraph-sized captions with short, timed one/two-line cues."""

from __future__ import annotations

import json
import re
import subprocess
import textwrap
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
OUTPUT_DIR = REPO_ROOT / "build/hackathon-video"
WORK_DIR = OUTPUT_DIR / "work"
SCENES_PATH = REPO_ROOT / "scripts/hackathon/pitch-scenes.json"
MANIFEST_PATH = OUTPUT_DIR / "fresnel-pitch-manifest.json"
SRT_PATH = OUTPUT_DIR / "fresnel-pitch.en.srt"
UNCAPTIONED = WORK_DIR / "fresnel-pitch-uncaptioned.mp4"
FINAL = OUTPUT_DIR / "fresnel-pitch.mp4"


def srt_time(value: float) -> str:
    total = max(0, round(value * 1000))
    hours, rest = divmod(total, 3_600_000)
    minutes, rest = divmod(rest, 60_000)
    seconds, millis = divmod(rest, 1000)
    return f"{hours:02}:{minutes:02}:{seconds:02},{millis:03}"


def caption_chunks(text: str) -> list[str]:
    sentences = [part.strip() for part in re.split(r"(?<=[.!?])\s+", text.strip()) if part.strip()]
    chunks: list[str] = []
    for sentence in sentences:
        lines = textwrap.wrap(
            sentence,
            width=52,
            break_long_words=False,
            break_on_hyphens=False,
        )
        for index in range(0, len(lines), 2):
            chunks.append("\n".join(lines[index:index + 2]))
    return chunks or [text.strip()]


def main() -> None:
    if not UNCAPTIONED.is_file():
        raise FileNotFoundError(f"Missing uncaptioned source: {UNCAPTIONED}")

    scenes_document = json.loads(SCENES_PATH.read_text(encoding="utf8"))
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf8"))
    durations = {scene["id"]: float(scene["durationSeconds"]) for scene in manifest["scenes"]}

    cues: list[tuple[float, float, str]] = []
    scene_start = 0.0
    for scene in scenes_document["scenes"]:
        duration = durations[scene["id"]]
        chunks = caption_chunks(scene["narration"])
        weights = [max(1, len(re.sub(r"\s+", " ", chunk))) for chunk in chunks]
        total_weight = sum(weights)
        cursor = scene_start
        for index, (chunk, weight) in enumerate(zip(chunks, weights, strict=True)):
            if index == len(chunks) - 1:
                cue_end = scene_start + duration
            else:
                cue_end = cursor + duration * weight / total_weight
            cues.append((cursor, cue_end, chunk))
            cursor = cue_end
        scene_start += duration

    srt = []
    for index, (start, end, text) in enumerate(cues, start=1):
        # Tiny gaps make transitions between adjacent captions visually calmer.
        visible_end = max(start + 0.35, end - 0.06)
        srt.append(f"{index}\n{srt_time(start)} --> {srt_time(visible_end)}\n{text}\n\n")
    SRT_PATH.write_text("".join(srt), encoding="utf8")

    temp = OUTPUT_DIR / "fresnel-pitch.refined.mp4"
    # libass scales SRT margins from its internal script resolution. A small
    # MarginV therefore places the captions in the actual lower safe area of a
    # 1080p frame; the previous value of 34 lifted them over image labels and
    # editor controls.
    subtitle_filter = (
        f"subtitles={SRT_PATH.as_posix()}:"
        "force_style='FontName=DejaVu Sans,FontSize=11,PrimaryColour=&H00FFFFFF&,"
        "OutlineColour=&H80000000&,BorderStyle=3,BackColour=&H90000000&,Outline=1,"
        "Shadow=0,MarginV=5,Alignment=2'"
    )
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-i", str(UNCAPTIONED),
        "-vf", subtitle_filter,
        "-af", "loudnorm=I=-16:TP=-1.5:LRA=7",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "18",
        "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "1",
        "-movflags", "+faststart", str(temp),
    ], check=True)
    temp.replace(FINAL)

    # The build script wrote the manifest before this refinement pass.
    # Recalculate the final video and subtitle fields while retaining its input inventory.
    import hashlib

    def digest(path: Path) -> str:
        value = hashlib.sha256()
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                value.update(block)
        return value.hexdigest()

    manifest["video"]["sha256"] = digest(FINAL)
    manifest["video"]["sizeBytes"] = FINAL.stat().st_size
    manifest["subtitles"]["sha256"] = digest(SRT_PATH)
    MANIFEST_PATH.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf8")


if __name__ == "__main__":
    main()
