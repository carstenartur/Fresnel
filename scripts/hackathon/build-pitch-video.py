#!/usr/bin/env python3
"""Build the narrated Fresnel pitch video from reproducible project assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

WIDTH = 1920
HEIGHT = 1080
FPS = 30
BACKGROUND = "#07111f"
PANEL = "#102033"
PANEL_ALT = "#162b42"
TEXT = "#f7fafc"
MUTED = "#a9bed1"
ACCENT = "#4fd1c5"
ACCENT_2 = "#7aa2ff"


@dataclass(frozen=True)
class SceneResult:
    scene_id: str
    duration: float
    slide: Path
    audio: Path
    video: Path
    narration: str


def run(*args: str, cwd: Path | None = None) -> None:
    subprocess.run(args, cwd=cwd, check=True)


def output(*args: str) -> str:
    return subprocess.check_output(args, text=True).strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def font(size: int, bold: bool = False, mono: bool = False) -> ImageFont.FreeTypeFont:
    if mono:
        filename = "DejaVuSansMono.ttf"
    elif bold:
        filename = "DejaVuSans-Bold.ttf"
    else:
        filename = "DejaVuSans.ttf"
    candidates = [
        Path("/usr/share/fonts/truetype/dejavu") / filename,
        Path("/usr/share/fonts/dejavu") / filename,
    ]
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    raise FileNotFoundError(f"Could not find {filename}")


def rounded(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], *, fill: str, radius: int = 28,
            outline: str | None = None, width: int = 1) -> None:
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def fit_image(path: Path, size: tuple[int, int], *, contain: bool = True) -> Image.Image:
    image = Image.open(path).convert("RGB")
    if contain:
        image.thumbnail(size, Image.Resampling.LANCZOS)
        canvas = Image.new("RGB", size, "white")
        x = (size[0] - image.width) // 2
        y = (size[1] - image.height) // 2
        canvas.paste(image, (x, y))
        return canvas
    return ImageOps.fit(image, size, method=Image.Resampling.LANCZOS)


def paste_card(canvas: Image.Image, image_path: Path, box: tuple[int, int, int, int], *, contain: bool = True,
               label: str | None = None) -> None:
    x1, y1, x2, y2 = box
    card = Image.new("RGB", (x2 - x1, y2 - y1), PANEL)
    picture = fit_image(image_path, (card.width - 32, card.height - 32 - (44 if label else 0)), contain=contain)
    card.paste(picture, (16, 16))
    if label:
        draw = ImageDraw.Draw(card)
        draw.text((18, card.height - 42), label, font=font(24, bold=True), fill=TEXT)
    mask = Image.new("L", card.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, card.width, card.height), radius=24, fill=255)
    canvas.paste(card, (x1, y1), mask)


def draw_header(canvas: Image.Image, scene: dict[str, Any]) -> None:
    draw = ImageDraw.Draw(canvas)
    draw.text((90, 60), "FRESNEL", font=font(24, bold=True), fill=ACCENT)
    draw.text((90, 108), scene["headline"], font=font(56, bold=True), fill=TEXT)
    draw.text((92, 182), scene.get("subheadline", ""), font=font(29), fill=MUTED)
    draw.rounded_rectangle((90, 235, 300, 243), radius=4, fill=ACCENT)


def hero_slide(scene: dict[str, Any], paths: list[Path]) -> Image.Image:
    base = fit_image(paths[0], (WIDTH, HEIGHT), contain=False).filter(ImageFilter.GaussianBlur(10))
    base = ImageEnhance.Brightness(base).enhance(0.28)
    overlay = Image.new("RGBA", (WIDTH, HEIGHT), (4, 13, 25, 170))
    canvas = Image.alpha_composite(base.convert("RGBA"), overlay).convert("RGB")
    draw = ImageDraw.Draw(canvas)
    draw.text((110, 110), "FRESNEL", font=font(30, bold=True), fill=ACCENT)
    words = scene["headline"].split(" ")
    wrapped: list[str] = []
    current = ""
    headline_font = font(76, bold=True)
    for word in words:
        candidate = f"{current} {word}".strip()
        if draw.textlength(candidate, font=headline_font) > 1120 and current:
            wrapped.append(current)
            current = word
        else:
            current = candidate
    if current:
        wrapped.append(current)
    y = 300
    for line in wrapped:
        draw.text((110, y), line, font=headline_font, fill=TEXT)
        y += 96
    draw.text((114, y + 28), scene.get("subheadline", ""), font=font(36), fill=MUTED)
    rounded(draw, (110, 850, 760, 930), fill="#0d2637", outline="#234a60", width=2)
    draw.text((145, 873), "Versioned jobs · deterministic validation · real exports",
              font=font(25, bold=True), fill=ACCENT)
    return canvas


def gallery_slide(scene: dict[str, Any], paths: list[Path]) -> Image.Image:
    canvas = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw_header(canvas, scene)
    boxes = [
        (90, 300, 500, 790),
        (530, 300, 940, 790),
        (970, 300, 1380, 790),
        (1410, 300, 1820, 790),
    ]
    for path, box in zip(paths, boxes, strict=False):
        paste_card(canvas, path, box)
    draw = ImageDraw.Draw(canvas)
    rounded(draw, (90, 835, 1820, 970), fill=PANEL_ALT)
    labels = ["ZONE PLATES", "MULTI-FOCUS", "PRINTABLE FOILS", "HOLOGRAPHY"]
    xs = [150, 570, 1010, 1460]
    for x, label in zip(xs, labels, strict=True):
        draw.text((x, 876), label, font=font(25, bold=True), fill=ACCENT_2)
    return canvas


def editor_slide(scene: dict[str, Any], paths: list[Path]) -> Image.Image:
    canvas = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw_header(canvas, scene)
    paste_card(canvas, paths[0], (90, 285, 760, 1010), contain=False)
    paste_card(canvas, paths[1], (825, 285, 1820, 1010), contain=True)
    return canvas


def contract_slide(scene: dict[str, Any], paths: list[Path]) -> Image.Image:
    canvas = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw_header(canvas, scene)
    draw = ImageDraw.Draw(canvas)
    rounded(draw, (90, 285, 1260, 1010), fill="#0b1828", outline="#234a60", width=2)
    code_font = font(27, mono=True)
    y = 330
    for line in scene.get("code", "").splitlines():
        fill = ACCENT if any(token in line for token in ('"format"', '"plugin"', '"parameters"')) else TEXT
        draw.text((135, y), line, font=code_font, fill=fill)
        y += 43
    paste_card(canvas, paths[0], (1325, 285, 1820, 780))
    rounded(draw, (1325, 820, 1820, 1010), fill=PANEL_ALT)
    for index, label in enumerate(("portable", "reviewable", "hashable", "re-runnable")):
        draw.text((1370, 850 + index * 36), f"✓  {label}", font=font(24, bold=True), fill=TEXT)
    return canvas


def exports_slide(scene: dict[str, Any], paths: list[Path]) -> Image.Image:
    canvas = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw_header(canvas, scene)
    paste_card(canvas, paths[0], (90, 300, 870, 850))
    paste_card(canvas, paths[1], (920, 300, 1700, 850))
    draw = ImageDraw.Draw(canvas)
    formats = ["PNG", "SVG", "PDF", "DXF", "GERBER", "STL"]
    x = 125
    for label in formats:
        chip_width = int(draw.textlength(label, font=font(24, bold=True))) + 56
        rounded(draw, (x, 890, x + chip_width, 958), fill=PANEL_ALT,
                outline="#2c5571", width=2, radius=18)
        draw.text((x + 28, 910), label, font=font(24, bold=True), fill=ACCENT)
        x += chip_width + 24
    return canvas


def before_after_slide(scene: dict[str, Any], paths: list[Path]) -> Image.Image:
    canvas = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw_header(canvas, scene)
    boxes = [(90, 320, 570, 850), (720, 320, 1200, 850), (1350, 320, 1830, 850)]
    labels = ["TARGET", "PHASE MASK", "RECONSTRUCTION"]
    for path, box, label in zip(paths, boxes, labels, strict=True):
        paste_card(canvas, path, box, label=label)
    draw = ImageDraw.Draw(canvas)
    for x in (615, 1245):
        draw.line((x, 575, x + 55, 575), fill=ACCENT, width=8)
        draw.polygon([(x + 55, 575), (x + 34, 557), (x + 34, 593)], fill=ACCENT)
    return canvas


def pipeline_slide(scene: dict[str, Any], paths: list[Path]) -> Image.Image:
    canvas = Image.new("RGB", (WIDTH, HEIGHT), BACKGROUND)
    draw_header(canvas, scene)
    draw = ImageDraw.Draw(canvas)
    steps = [".fresnel job", "schema validation", "renderer", "artifact", "manifest hash"]
    x = 100
    for index, label in enumerate(steps):
        box = (x, 390, x + 300, 530)
        rounded(draw, box, fill=PANEL_ALT, outline="#2c5571", width=2)
        draw.text((x + 28, 435), label, font=font(25, bold=True), fill=TEXT)
        if index < len(steps) - 1:
            draw.line((x + 310, 460, x + 365, 460), fill=ACCENT, width=7)
            draw.polygon([(x + 365, 460), (x + 344, 443), (x + 344, 477)], fill=ACCENT)
        x += 365
    paste_card(canvas, paths[0], (240, 640, 860, 1000))
    paste_card(canvas, paths[1], (1060, 640, 1680, 1000))
    return canvas


def render_slide(scene: dict[str, Any], repo_root: Path) -> Image.Image:
    paths = [(repo_root / value).resolve() for value in scene.get("images", [])]
    for path in paths:
        if not path.is_file():
            raise FileNotFoundError(f"Missing pitch asset: {path}")
    kind = scene["kind"]
    if kind == "hero":
        return hero_slide(scene, paths)
    if kind == "gallery":
        return gallery_slide(scene, paths)
    if kind == "editor":
        return editor_slide(scene, paths)
    if kind == "contract":
        return contract_slide(scene, paths)
    if kind == "exports":
        return exports_slide(scene, paths)
    if kind == "before-after":
        return before_after_slide(scene, paths)
    if kind == "pipeline":
        return pipeline_slide(scene, paths)
    raise ValueError(f"Unsupported scene kind: {kind}")


def wav_duration(path: Path) -> float:
    return float(output("ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1", str(path)))


def format_srt_time(value: float) -> str:
    milliseconds = max(0, round(value * 1000))
    hours, remainder = divmod(milliseconds, 3_600_000)
    minutes, remainder = divmod(remainder, 60_000)
    seconds, millis = divmod(remainder, 1000)
    return f"{hours:02}:{minutes:02}:{seconds:02},{millis:03}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--scenes", type=Path, default=None)
    parser.add_argument("--output-dir", type=Path, default=None)
    args = parser.parse_args()

    repo_root = args.repo_root.resolve()
    scenes_path = (args.scenes or repo_root / "scripts/hackathon/pitch-scenes.json").resolve()
    output_dir = (args.output_dir or repo_root / "build/hackathon-video").resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    work_dir = output_dir / "work"
    if work_dir.exists():
        shutil.rmtree(work_dir)
    work_dir.mkdir(parents=True)

    tts = shutil.which("espeak-ng") or shutil.which("espeak")
    if not tts:
        raise RuntimeError("espeak-ng or espeak is required")
    for command in ("ffmpeg", "ffprobe"):
        if not shutil.which(command):
            raise RuntimeError(f"{command} is required")

    document = json.loads(scenes_path.read_text(encoding="utf8"))
    scenes = document["scenes"]
    results: list[SceneResult] = []

    for index, scene in enumerate(scenes, start=1):
        stem = f"{index:02d}-{scene['id']}"
        slide = work_dir / f"{stem}.png"
        raw_audio = work_dir / f"{stem}-raw.wav"
        audio = work_dir / f"{stem}.wav"
        video = work_dir / f"{stem}.mp4"

        render_slide(scene, repo_root).save(slide, format="PNG", optimize=True)
        run(tts, "-v", "en-us", "-s", "154", "-p", "46", "-w", str(raw_audio), scene["narration"])
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(raw_audio),
            "-af", "apad=pad_dur=0.65", str(audio))
        duration = wav_duration(audio)
        fade_out = max(0.0, duration - 0.35)
        run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-loop", "1", "-framerate", str(FPS), "-i", str(slide), "-i", str(audio),
            "-t", f"{duration:.3f}",
            "-vf", f"fade=t=in:st=0:d=0.35,fade=t=out:st={fade_out:.3f}:d=0.35,format=yuv420p",
            "-af", f"afade=t=in:st=0:d=0.18,afade=t=out:st={fade_out:.3f}:d=0.35",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "18", "-r", str(FPS),
            "-c:a", "aac", "-b:a", "160k", "-shortest", str(video))
        results.append(SceneResult(scene["id"], duration, slide, audio, video, scene["narration"]))

    concat_file = work_dir / "concat.txt"
    concat_file.write_text("".join(f"file '{item.video.as_posix()}'\n" for item in results), encoding="utf8")
    uncaptioned = work_dir / "fresnel-pitch-uncaptioned.mp4"
    run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0",
        "-i", str(concat_file), "-c", "copy", str(uncaptioned))

    srt_path = output_dir / "fresnel-pitch.en.srt"
    start = 0.0
    cues: list[str] = []
    for cue_index, result in enumerate(results, start=1):
        end = start + result.duration
        cues.append(
            f"{cue_index}\n{format_srt_time(start)} --> {format_srt_time(end)}\n{result.narration}\n\n"
        )
        start = end
    srt_path.write_text("".join(cues), encoding="utf8")

    final_video = output_dir / "fresnel-pitch.mp4"
    subtitle_filter = (
        f"subtitles={srt_path.as_posix()}:"
        "force_style='FontName=DejaVu Sans,FontSize=22,PrimaryColour=&H00FFFFFF&,"
        "OutlineColour=&H88000000&,BorderStyle=3,BackColour=&H88000000&,Outline=1,"
        "Shadow=0,MarginV=28,Alignment=2'"
    )
    run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(uncaptioned),
        "-vf", subtitle_filter, "-c:v", "libx264", "-preset", "medium", "-crf", "18",
        "-c:a", "copy", "-movflags", "+faststart", str(final_video))

    narration_path = output_dir / "fresnel-pitch-narration.txt"
    narration_path.write_text("\n\n".join(item.narration for item in results) + "\n", encoding="utf8")

    inputs = sorted({(repo_root / image).resolve() for scene in scenes for image in scene.get("images", [])})
    inputs.extend([
        (repo_root / "build/hackathon-video/assets/capture-manifest.json").resolve(),
        scenes_path,
    ])
    manifest = {
        "formatVersion": 1,
        "video": {
            "path": str(final_video.relative_to(repo_root)),
            "durationSeconds": round(start, 3),
            "width": WIDTH,
            "height": HEIGHT,
            "fps": FPS,
            "sha256": sha256(final_video),
            "sizeBytes": final_video.stat().st_size,
        },
        "subtitles": {
            "path": str(srt_path.relative_to(repo_root)),
            "sha256": sha256(srt_path),
        },
        "inputs": [
            {
                "path": str(path.relative_to(repo_root)),
                "sha256": sha256(path),
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
        json.dumps(manifest, indent=2) + "\n", encoding="utf8"
    )
    if start >= 180:
        raise RuntimeError(f"Pitch video is too long: {start:.1f} seconds")
    print(json.dumps(manifest["video"], indent=2))


if __name__ == "__main__":
    main()
