"""The network, in float, shaped so that quantising it is a change of units and nothing else.

Every clamp here has a counterpart in the Java inference. The float model clamps activations to
``[0, 1]``; the integer one clamps to ``[0, QA]``, which is the same interval measured in different
units. Getting that correspondence wrong produces a network that trains beautifully and plays badly,
and the only thing that catches it is comparing the two implementations on real positions — which is
what ``export.py`` writes the fixtures for.
"""

import torch
from torch import nn

from features import HIDDEN, INPUTS, L1, L2


class Nnue(nn.Module):
    """768 -> 256 (twice, shared) -> 512 -> 32 -> 32 -> 1."""

    def __init__(self) -> None:
        super().__init__()
        # An embedding bag rather than a linear layer, because that is literally what the Java
        # accumulator does: look up the column of every active feature and add them together. Thirty
        # or so lookups instead of a 768-wide matrix multiply.
        self.feature_transformer = nn.EmbeddingBag(INPUTS, HIDDEN, mode="sum")
        self.feature_bias = nn.Parameter(torch.zeros(HIDDEN))
        self.layer_one = nn.Linear(HIDDEN * 2, L1)
        self.layer_two = nn.Linear(L1, L2)
        self.output = nn.Linear(L2, 1)

        # Small weights to start: the accumulator sums thirty-odd columns, and a large initialisation
        # saturates every clamp on the first forward pass, which stalls training before it begins.
        nn.init.uniform_(self.feature_transformer.weight, -0.05, 0.05)

    def forward(self, own_indices, own_offsets, their_indices, their_offsets):
        own = self.feature_transformer(own_indices, own_offsets) + self.feature_bias
        theirs = self.feature_transformer(their_indices, their_offsets) + self.feature_bias

        # Mover first. This is what makes one network answer for both colours.
        accumulator = torch.cat([own, theirs], dim=1)

        hidden = torch.clamp(accumulator, 0.0, 1.0)
        hidden = torch.clamp(self.layer_one(hidden), 0.0, 1.0)
        hidden = torch.clamp(self.layer_two(hidden), 0.0, 1.0)
        return self.output(hidden).squeeze(1)


def to_win_probability(centipawns: torch.Tensor) -> torch.Tensor:
    """Centipawns to an expected score, on the same scale the Elo formula uses.

    Training against a probability rather than raw centipawns is what stops a single position
    evaluated at plus two thousand from dominating a batch of ordinary ones.
    """
    return torch.sigmoid(centipawns / 400.0)
