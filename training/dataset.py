"""Reading the file the collector wrote.

One sample per line, ``fen|score|result``, where the score is centipawns from a shallow search and
the result is 0, 1 or 2 for loss, draw and win — both from the point of view of the side to move.

The result is an integer rather than a fraction on purpose. A draw written as ``0.5`` through a
default formatter on an Italian locale comes out as ``0,5``, and a training file full of commas is a
bug found days later, here.
"""

from __future__ import annotations

import torch
from torch.utils.data import Dataset

from features import active_features


class SelfPlayDataset(Dataset):
    """Positions held as feature indices, which is all the network ever sees of them."""

    def __init__(self, path: str, limit: int | None = None) -> None:
        self.own: list[list[int]] = []
        self.theirs: list[list[int]] = []
        scores: list[float] = []
        results: list[float] = []

        # A limit reads across the file rather than truncating it, so a subset is a sample of the whole
        # dataset and not of however it happens to be ordered.
        #
        # This was written to fix a bias that turned out not to be there. The generator's endgame share
        # self-corrects from zero, so the first lines looked likely to be endgame-heavy — measured, the
        # first 40,000 lines were 49.8% small positions against 49.6% across the file. The correction
        # settles in seconds. The stride stays because nothing guarantees that of the next file, and a
        # limit that means "the first N" is a trap either way, but it is not fixing an observed skew.
        stride = 1
        if limit is not None:
            with open(path, "r", encoding="utf-8") as counting:
                total = sum(1 for _ in counting)
            stride = max(1, total // limit)

        with open(path, "r", encoding="utf-8") as handle:
            for index, line in enumerate(handle):
                if index % stride != 0:
                    continue
                line = line.strip()
                if not line:
                    continue
                fen, score, result = line.rsplit("|", 2)
                own, theirs = active_features(fen)
                self.own.append(own)
                self.theirs.append(theirs)
                scores.append(float(score))
                # 0, 1, 2 becomes 0.0, 0.5, 1.0 — a probability, matching what the model predicts.
                results.append(int(result) / 2.0)
                if limit is not None and len(self.own) >= limit:
                    break

        if not self.own:
            raise ValueError(f"no samples in {path}")

        self.scores = torch.tensor(scores, dtype=torch.float32)
        self.results = torch.tensor(results, dtype=torch.float32)

    def __len__(self) -> int:
        return len(self.own)

    def __getitem__(self, index: int):
        return self.own[index], self.theirs[index], self.scores[index], self.results[index]


def collate(batch):
    """Packs variable-length index lists into the flat form an embedding bag wants."""
    own_indices: list[int] = []
    own_offsets: list[int] = []
    their_indices: list[int] = []
    their_offsets: list[int] = []
    scores = []
    results = []

    for own, theirs, score, result in batch:
        own_offsets.append(len(own_indices))
        own_indices.extend(own)
        their_offsets.append(len(their_indices))
        their_indices.extend(theirs)
        scores.append(score)
        results.append(result)

    return (
        torch.tensor(own_indices, dtype=torch.long),
        torch.tensor(own_offsets, dtype=torch.long),
        torch.tensor(their_indices, dtype=torch.long),
        torch.tensor(their_offsets, dtype=torch.long),
        torch.stack(scores),
        torch.stack(results),
    )
