"""Turns a directory of PNG frames into the animation the README shows.

    python tools/assemble_gif.py build/frames docs/play.gif 1400

Called by tools/capture-readme-gif.ps1, which produces the frames. Kept separate because assembling an
animation is the one part of that job a shell is bad at.

The size of the result is the whole problem. A README is often opened on a phone, and an image measured in
megabytes is a cost paid by every visitor whether they look at it or not — so the frames are scaled down,
quantised to a small palette, and the last frame is held rather than duplicated.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

# Wide enough to read the board and the numbers beside it, narrow enough that a GIF of it is small. GitHub
# renders README images at about 900 pixels of column width anyway, so more is paid for and not seen.
TARGET_WIDTH = 900

# A screenshot of a flat design uses few colours: the board has two, the panel one, the text one. Sixty-four
# is generous for that and costs a fraction of a full palette.
PALETTE_COLOURS = 64


def main() -> int:
    frames_dir = Path(sys.argv[1])
    out = Path(sys.argv[2])
    frame_ms = int(sys.argv[3]) if len(sys.argv) > 3 else 1400

    paths = sorted(frames_dir.glob("*.png"))
    if not paths:
        print(f"no frames in {frames_dir}", file=sys.stderr)
        return 1

    frames = []
    for path in paths:
        image = Image.open(path).convert("RGB")
        if image.width > TARGET_WIDTH:
            height = round(image.height * TARGET_WIDTH / image.width)
            image = image.resize((TARGET_WIDTH, height), Image.LANCZOS)
        # Quantised per frame with a shared method rather than left to save() to decide, which otherwise
        # picks a web-safe palette and makes the board look dithered.
        frames.append(image.quantize(colors=PALETTE_COLOURS, method=Image.MEDIANCUT))

    # The last frame is held longer: the animation ends on the finished position, and a viewer needs a beat
    # to read it before it loops back to an empty board.
    durations = [frame_ms] * len(frames)
    durations[0] = frame_ms + 600
    durations[-1] = frame_ms + 1800

    out.parent.mkdir(parents=True, exist_ok=True)
    frames[0].save(
        out,
        save_all=True,
        append_images=frames[1:],
        duration=durations,
        loop=0,
        optimize=True,
        disposal=1,
    )

    print(f"{out}: {len(frames)} frames, {out.stat().st_size / 1024:,.0f} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
