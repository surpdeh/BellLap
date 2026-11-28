#!/usr/bin/env python3
"""Cuts a frame range out of a local video and lays it out as a real fixture (F03b layout, labels.json v1) under
testing/fixtures/real/<name>/, which is gitignored. Never commit the output: see docs/FIXTURES.md.

Usage: clip-video.py INPUT NAME [--start N] [--end N] [--fps F] [--stabilise X0,Y0,X1,Y1]
                     [--track-line X1,Y1,X2,Y2] [--spec SPEC.json]

--start/--end   source frame range [start, end) (default: the whole clip).
--fps           frame rate written to labels.json (default: ffprobe's r_frame_rate). PTS = index / fps.
--stabilise     removes a pan or tilt: each frame is shifted so the band X0,Y0,X1,Y1 (source pixels, pick
                background with no riders in it) stays where it is on the first frame, then every frame is cropped
                to the area all frames cover. The slit-scan crossing detector needs a still line. Offsets are written
                to stabilise.csv. Only translation is removed, so keep segments short (about a second).
--track-line    with --stabilise, for a painted (yellow or white) finish line at X1,Y1,X2,Y2 on the first frame:
                the line is refitted on every frame and each frame is warped (rotation and shift, full size, no
                crop) so it lands back on X1,Y1,X2,Y2. The background band only supplies the shift along the line.
                Use it when the camera moves as well as turns: the near road then moves differently from the far
                background (parallax) and band stabilisation alone lets the line wander.
--spec          labels in output pixels and output frame indices (0 = first frame of the segment), converted to
                labels.json units (ns, frame fractions). Keys, all optional:
                  "flagFrames": [[start, end), ...], "flagBoxes": [[frame, x, y, w, h], ...],
                  "finishLine": [x1, y1, x2, y2], "crossings": [[frame, group], ...]
                Without a spec the frames are written with empty labels: look at them, write the spec, rerun.

Frames are 8-bit luma PNGs at the source resolution, like the analysis stream the detectors see on a phone.
Needs ffmpeg/ffprobe, numpy and Pillow.
"""
import argparse
import csv
import json
import pathlib
import shutil
import subprocess
import tempfile

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
NS = 1_000_000_000


def probe_fps(src: str) -> float:
    rate = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=r_frame_rate",
         "-of", "csv=p=0", src], check=True, capture_output=True, text=True).stdout.strip()
    num, den = rate.split("/")
    return float(num) / float(den)


def shift(a: np.ndarray, b: np.ndarray) -> tuple[int, int]:
    """Integer (dx, dy) by which the content of b moved relative to a (phase correlation)."""
    win = np.outer(np.hanning(a.shape[0]), np.hanning(a.shape[1]))
    fa, fb = np.fft.fft2((a - a.mean()) * win), np.fft.fft2((b - b.mean()) * win)
    cross = fa * np.conj(fb)
    r = np.fft.ifft2(cross / (np.abs(cross) + 1e-9)).real
    y, x = np.unravel_index(np.argmax(r), r.shape)
    y = y - r.shape[0] if y > r.shape[0] // 2 else y
    x = x - r.shape[1] if x > r.shape[1] // 2 else x
    return -int(x), -int(y)


def stabilise(frames: list[np.ndarray], band: tuple[int, int, int, int]) -> tuple[list[np.ndarray], list]:
    x0, y0, x1, y1 = band
    offsets = [(0, 0)]
    for prev, cur in zip(frames, frames[1:]):
        dx, dy = shift(prev[y0:y1, x0:x1].astype(np.float64), cur[y0:y1, x0:x1].astype(np.float64))
        offsets.append((offsets[-1][0] + dx, offsets[-1][1] + dy))
    h, w = frames[0].shape
    # Frame i shows reference pixel (x, y) at (x + dx_i, y + dy_i); keep the reference area every frame covers.
    left = max(0, -min(o[0] for o in offsets))
    top = max(0, -min(o[1] for o in offsets))
    right = min(w, w - max(o[0] for o in offsets))
    bottom = min(h, h - max(o[1] for o in offsets))
    if right - left < w // 2 or bottom - top < h // 2:
        raise SystemExit(f"pan too large to stabilise: common area {right - left}x{bottom - top}; use a shorter segment")
    out = [f[top + dy:bottom + dy, left + dx:right + dx] for f, (dx, dy) in zip(frames, offsets)]
    return out, [(i, dx, dy, left, top) for i, (dx, dy) in enumerate(offsets)]


def yellowness(rgb: np.ndarray) -> np.ndarray:
    """R + G - 2B: a yellow line (and a white one, weakly) stands out from grey asphalt."""
    c = rgb.astype(np.float32)
    return np.clip(c[..., 0] + c[..., 1] - 2 * c[..., 2], 0, 255)


def fit_line(y: np.ndarray, m: float, c: float, x_lo: int, x_hi: int) -> tuple[float, float]:
    """Refits y = m x + c to the strongest yellow pixel per column near the previous fit (weighted least squares)."""
    h, w = y.shape
    xs = np.arange(max(0, x_lo), min(w, x_hi))
    for band in (14, 6, 4):
        pts = []
        for x in xs:
            yc = m * x + c
            y0, y1 = int(max(0, yc - band)), int(min(h - 1, yc + band))
            col = y[y0:y1 + 1, x]
            if col.size and col.max() > 40:
                pts.append((x, y0 + int(col.argmax()), float(col.max())))
        if len(pts) < 0.3 * len(xs):
            break  # mostly occluded: keep the previous fit
        p = np.array(pts)
        m, c = np.polyfit(p[:, 0], p[:, 1], 1, w=p[:, 2])
    return float(m), float(c)


def track_line(rgb: list[np.ndarray], band: tuple[int, int, int, int], line: tuple[float, ...]):
    """Warps every frame so the fitted line maps onto [line]; returns luma frames and per-frame rows."""
    x1, y1, x2, y2 = line
    m, c = (y2 - y1) / (x2 - x1), y1 - (y2 - y1) / (x2 - x1) * x1
    bx0, by0, bx1, by1 = band
    luma = [np.asarray(Image.fromarray(f).convert("L"), dtype=np.float64) for f in rgb]
    dx = 0
    out, rows = [], []
    for i, f in enumerate(rgb):
        if i:
            step, _ = shift(luma[i - 1][by0:by1, bx0:bx1], luma[i][by0:by1, bx0:bx1])
            dx += step
        m, c = fit_line(yellowness(f), m, c, int(min(x1, x2) + dx - 40), int(max(x1, x2) + dx + 40))
        # Output point (x1, y1) / (x2, y2) comes from the fitted line at the background-shifted x.
        sx1, sx2 = x1 + dx, x2 + dx
        src = np.array([[sx1, m * sx1 + c], [sx2, m * sx2 + c]])
        dst = np.array([[x1, y1], [x2, y2]])
        a, b, tx, ty = similarity(dst, src)  # output -> source, as PIL wants
        img = Image.fromarray(f).convert("L").transform(
            (f.shape[1], f.shape[0]), Image.AFFINE, (a, -b, tx, b, a, ty), resample=Image.BILINEAR)
        out.append(np.asarray(img))
        rows.append((i, dx, round(m, 5), round(c, 2)))
    return out, rows


def similarity(p: np.ndarray, q: np.ndarray) -> tuple[float, float, float, float]:
    """(a, b, tx, ty) of the similarity q = [[a, -b], [b, a]] p + t through two point pairs."""
    zp = complex(*(p[1] - p[0]))
    zq = complex(*(q[1] - q[0]))
    r = zq / zp
    t = complex(*q[0]) - r * complex(*p[0])
    return r.real, r.imag, t.real, t.imag


def labels(name: str, fps: float, size: tuple[int, int], spec: dict) -> dict:
    w, h = size
    pts = lambda i: round(i * NS / fps)  # noqa: E731 - same rounding as ImageSequenceFrameSource.ptsOf
    out = {"schemaVersion": 1, "name": name, "framesDir": "frames", "fps": fps}
    out["flagIntervals"] = [{"startNs": pts(a), "endNs": pts(b)} for a, b in spec.get("flagFrames", [])]
    out["flagBoxes"] = [
        {"frame": f, "x": x / w, "y": y / h, "width": bw / w, "height": bh / h}
        for f, x, y, bw, bh in spec.get("flagBoxes", [])
    ]
    if "finishLine" in spec:
        x1, y1, x2, y2 = spec["finishLine"]
        out["finishLine"] = {"x1": x1 / w, "y1": y1 / h, "x2": x2 / w, "y2": y2 / h}
    out["crossings"] = [{"frame": f, "ptsNs": pts(f), "group": g} for f, g in spec.get("crossings", [])]
    return out


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("input")
    ap.add_argument("name")
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--end", type=int)
    ap.add_argument("--fps", type=float)
    ap.add_argument("--stabilise")
    ap.add_argument("--track-line")
    ap.add_argument("--spec")
    a = ap.parse_args()
    fps = a.fps or probe_fps(a.input)
    end = f"*lt(n\\,{a.end})" if a.end is not None else ""
    with tempfile.TemporaryDirectory() as tmp:
        fmt = "rgb24" if a.track_line else "gray"
        subprocess.run(
            ["ffmpeg", "-loglevel", "error", "-i", a.input, "-vf", f"select='gte(n\\,{a.start}){end}',format={fmt}",
             "-vsync", "0", f"{tmp}/%05d.png"], check=True)
        frames = [np.asarray(Image.open(p)) for p in sorted(pathlib.Path(tmp).glob("*.png"))]
    if not frames:
        raise SystemExit("no frames in that range")
    out_dir = ROOT / "testing/fixtures/real" / a.name
    if out_dir.exists():
        shutil.rmtree(out_dir)
    (out_dir / "frames").mkdir(parents=True)
    if a.track_line:
        if not a.stabilise:
            raise SystemExit("--track-line needs --stabilise for the shift along the line")
        frames, rows = track_line(frames, tuple(int(v) for v in a.stabilise.split(",")),
                                  tuple(float(v) for v in a.track_line.split(",")))
        with open(out_dir / "stabilise.csv", "w", newline="") as f:
            csv.writer(f).writerows([("frame", "dx_along", "slope", "intercept"), *rows])
    elif a.stabilise:
        frames, rows = stabilise(frames, tuple(int(v) for v in a.stabilise.split(",")))
        with open(out_dir / "stabilise.csv", "w", newline="") as f:
            csv.writer(f).writerows([("frame", "dx", "dy", "crop_left", "crop_top"), *rows])
    for i, f in enumerate(frames):
        Image.fromarray(f).save(out_dir / "frames" / f"frame_{i:05d}.png")
    spec = json.loads(pathlib.Path(a.spec).read_text()) if a.spec else {}
    size = (frames[0].shape[1], frames[0].shape[0])
    (out_dir / "labels.json").write_text(json.dumps(labels(a.name, fps, size, spec), indent=2) + "\n")
    print(f"Wrote {len(frames)} frames ({size[0]}x{size[1]}) and labels.json to {out_dir}. "
          "Run: ./gradlew :testing:harness:replayReal")


if __name__ == "__main__":
    main()
