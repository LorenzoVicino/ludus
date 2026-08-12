"""Band-by-band error of the float model, against whichever labels you point it at.

    python bandcheck.py ../build/net.pt ../build/holdout-d10.txt

Exists to separate two things the engine-side gate cannot: whether a network is weak, or whether
quantising it made it weak. This reads the PyTorch model directly, so a disagreement between this and
`bench --predict` is quantisation and nothing else.
"""

import sys

import numpy as np
import torch

from features import active_features
from model import Nnue

BANDS = [50, 150, 400, 1000, 2000]


def band(magnitude: int) -> int:
    for index, limit in enumerate(BANDS):
        if magnitude <= limit:
            return index
    return len(BANDS) - 1


def main() -> int:
    net = Nnue()
    net.load_state_dict(torch.load(sys.argv[1], map_location="cpu"))
    net.eval()

    rows = []
    with open(sys.argv[2], encoding="utf-8") as handle:
        for index, line in enumerate(handle):
            if index % 8:
                continue
            fen, score, _ = line.strip().rsplit("|", 2)
            if abs(int(score)) > 2000:
                continue
            rows.append((fen, int(score)))

    counts = [0] * len(BANDS)
    error = [0.0] * len(BANDS)

    for start in range(0, len(rows), 4096):
        chunk = rows[start:start + 4096]
        own_indices, own_offsets, their_indices, their_offsets = [], [], [], []
        for fen, _ in chunk:
            own, theirs = active_features(fen)
            own_offsets.append(len(own_indices))
            own_indices.extend(own)
            their_offsets.append(len(their_indices))
            their_indices.extend(theirs)
        with torch.no_grad():
            predicted = torch.sigmoid(net(
                torch.tensor(own_indices), torch.tensor(own_offsets),
                torch.tensor(their_indices), torch.tensor(their_offsets))).numpy()
        for (_, score), probability in zip(chunk, predicted):
            index = band(abs(score))
            counts[index] += 1
            error[index] += abs(probability - 1.0 / (1.0 + np.exp(-score / 400.0)))

    print(f"{len(rows):,} positions from {sys.argv[2]}")
    print(f"{'|label|':<12}{'count':>9}{'win% error':>12}")
    for index in range(len(BANDS)):
        if counts[index]:
            low = 0 if index == 0 else BANDS[index - 1]
            print(f"{f'{low}-{BANDS[index]}':<12}{counts[index]:>9}{error[index] / counts[index]:>12.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
