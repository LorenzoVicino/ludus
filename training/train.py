"""Trains the evaluation network.

    python train.py --data ../build/selfplay.txt --epochs 8 --out net.pt

The target blends the two labels a sample carries. The search score teaches tactics — it knows why a
position is good — and the game result teaches what actually mattered, which the score at a shallow
depth often gets wrong. Training on either alone produces a network that is confidently wrong in a
different way.
"""

from __future__ import annotations

import argparse
import time

import torch
from torch import nn
from torch.utils.data import DataLoader, random_split

from dataset import SelfPlayDataset, collate
from model import Nnue, to_win_probability


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True)
    parser.add_argument("--out", default="net.pt")
    parser.add_argument("--epochs", type=int, default=8)
    parser.add_argument("--batch-size", type=int, default=4096)
    parser.add_argument("--lr", type=float, default=1e-3)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument(
        "--lambda-score",
        type=float,
        default=0.7,
        help="how much of the target comes from the search score rather than the game result",
    )
    arguments = parser.parse_args()

    torch.manual_seed(20260809)

    dataset = SelfPlayDataset(arguments.data, arguments.limit)
    validation_size = max(1, len(dataset) // 10)
    train_set, validation_set = random_split(
        dataset, [len(dataset) - validation_size, validation_size],
        generator=torch.Generator().manual_seed(20260809))

    print(f"{len(dataset):,} samples: {len(train_set):,} train, {len(validation_set):,} validation")

    train_loader = DataLoader(train_set, batch_size=arguments.batch_size, shuffle=True,
                              collate_fn=collate)
    validation_loader = DataLoader(validation_set, batch_size=arguments.batch_size,
                                   collate_fn=collate)

    model = Nnue()
    optimiser = torch.optim.Adam(model.parameters(), lr=arguments.lr)
    loss_function = nn.MSELoss()

    for epoch in range(1, arguments.epochs + 1):
        started = time.time()
        model.train()
        total = 0.0
        batches = 0

        for own_indices, own_offsets, their_indices, their_offsets, scores, results in train_loader:
            # The model predicts in the same units the engine will read, so a sigmoid over its raw
            # output is directly comparable with a sigmoid over the search score.
            predicted = torch.sigmoid(model(own_indices, own_offsets, their_indices, their_offsets))
            target = (arguments.lambda_score * to_win_probability(scores)
                      + (1.0 - arguments.lambda_score) * results)

            loss = loss_function(predicted, target)
            optimiser.zero_grad()
            loss.backward()
            optimiser.step()

            total += loss.item()
            batches += 1

        validation_loss = evaluate(model, validation_loader, loss_function, arguments.lambda_score)
        print(f"epoch {epoch:2d}  train {total / max(1, batches):.5f}  "
              f"validation {validation_loss:.5f}  {time.time() - started:.1f}s")

    torch.save(model.state_dict(), arguments.out)
    print(f"saved {arguments.out}")
    return 0


@torch.no_grad()
def evaluate(model: Nnue, loader: DataLoader, loss_function, lambda_score: float) -> float:
    model.eval()
    total = 0.0
    batches = 0
    for own_indices, own_offsets, their_indices, their_offsets, scores, results in loader:
        predicted = torch.sigmoid(model(own_indices, own_offsets, their_indices, their_offsets))
        target = lambda_score * to_win_probability(scores) + (1.0 - lambda_score) * results
        total += loss_function(predicted, target).item()
        batches += 1
    return total / max(1, batches)


if __name__ == "__main__":
    raise SystemExit(main())
