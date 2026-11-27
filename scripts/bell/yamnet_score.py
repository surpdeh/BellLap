#!/usr/bin/env python3
"""Scores bell candidate windows with the real YAMNet model, for the JVM replay harness (F14).

LiteRT has no desktop JVM runtime, so `:testing:harness:replay -Pbell.dump=DIR` writes each window stage 0 would
hand to YAMNet as DIR/<key>.f32 (15600 little-endian float32 samples, 16 kHz, [-1, 1]). This script runs YAMNet on
each and writes {"labels": [...521 names...], "scores": {key: [...521 scores...]}}. The harness then applies the
bell-family rule itself (`YamnetClassMap`), so the rule lives in one place.

Usage: yamnet_score.py MODEL.tflite DIR OUT.json
"""
import json
import pathlib
import sys
import zipfile

import numpy as np
from ai_edge_litert.interpreter import Interpreter

WINDOW = 15600


def main(model: str, window_dir: str, out: str) -> None:
    with zipfile.ZipFile(model) as z:  # the label list is packed inside the .tflite as model metadata
        labels = z.read("yamnet_label_list.txt").decode().strip().splitlines()
    interpreter = Interpreter(model_path=model)
    interpreter.allocate_tensors()
    inp = interpreter.get_input_details()[0]["index"]
    outp = interpreter.get_output_details()[0]["index"]
    scores = {}
    for path in sorted(pathlib.Path(window_dir).glob("*.f32")):
        x = np.fromfile(path, dtype="<f4")
        if x.size != WINDOW:
            raise SystemExit(f"{path}: expected {WINDOW} samples, got {x.size}")
        interpreter.set_tensor(inp, x)
        interpreter.invoke()
        s = interpreter.get_tensor(outp).reshape(-1)
        scores[path.stem] = [round(float(v), 5) for v in s]
    pathlib.Path(out).write_text(json.dumps({"labels": labels, "scores": scores}))
    print(f"scored {len(scores)} windows -> {out}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
