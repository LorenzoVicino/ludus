"""Quantises a trained network and writes the file the engine loads.

    python export.py --net net.pt --out ../build/ludus.nnue --fixtures ../build/nnue-fixtures.txt

Quantisation is a change of units, and every constant here has a counterpart in ``NnueNetwork``.
Getting one wrong does not raise anything: it produces a network that plays worse than the one that
was trained, for no visible reason. So this also writes fixtures — positions with the float model's
own answer — and the Java side asserts it agrees within the tolerance quantisation costs.
"""

from __future__ import annotations

import argparse
import struct

import torch

from dataset import SelfPlayDataset
from features import HIDDEN, INPUTS, L1, L2, SCALE, active_features
from model import Nnue

MAGIC = 0x4C55_444E  # "LUDN"
FORMAT_VERSION = 3

# The accumulator is int16 and holds the biases plus one column per piece on the board, so the scale
# has to leave room for a full board. Thirty-three is thirty-two pieces and a margin.
MAX_ACTIVE_FEATURES = 34
INT16_LIMIT = 32767
INT8_LIMIT = 127
# Beyond this the extra resolution buys nothing and the products start crowding int32.
MAX_QA = 2048


def choose_scales(model: Nnue) -> tuple[int, int]:
    """Picks the largest scales that saturate nothing.

    Fixed scales were the first version's mistake and it was invisible: a trained feature weight
    averaging 0.039 became the integer 5 at a scale of 127, and a first-layer weight of 0.037 became
    the integer 2 at a scale of 64. The exported network was a coarse caricature of the trained one,
    off by up to 29 centipawns, and nothing raised so much as a warning.

    Measuring instead removes that class of problem, and keeps removing it every time the weight
    distribution shifts under retraining.

    **Rounding those measured scales down to a power of two was the second version's mistake**, and it
    threw away up to half the resolution for nothing: a dense peak of 2.01 permits a scale of 63, and
    the rounding made it 32. Powers of two would earn their keep if the engine shifted, and it does
    not — ``NnueEvaluator`` calls ``divideRounding(sum, qb)``, an honest division. The constraint was
    protecting an optimisation nobody had written.
    """
    feature_max = max(
        float(model.feature_transformer.weight.detach().abs().max()),
        float(model.feature_bias.detach().abs().max()),
    )
    qa = max(1, min(MAX_QA, int(INT16_LIMIT / (MAX_ACTIVE_FEATURES * feature_max))))
    print(f"feature weights peak at {feature_max:.4f} -> QA {qa} "
          f"(a typical weight becomes "
          f"{round(float(model.feature_transformer.weight.detach().abs().mean()) * qa)})")

    # A scale per dense layer, because one shared scale has to serve the largest weight in any layer
    # that shares it and these layers do not resemble each other: layer_two peaks at 2.01 while
    # layer_one's median weight is 0.032. The peak set the resolution and the median paid for it.
    #
    # Measured on ten fixtures: fixtures outside the 12-centipawn tolerance went from 9 to 4, and the
    # mean gap from 37.8 to 33.0. The *worst* gap did not move, 58 to 59 — and that nearly cost the
    # change, because the worst gap was the one statistic I compared first. A single summary number
    # decided nothing here; the count did.
    #
    # What the unchanged worst case says about where the rest of the error lives: it is the start
    # position, the fullest board there is, and the gaps shrink as pieces come off. That points at the
    # feature accumulator, where thirty-two rounded weights are summed, rather than at the dense layers,
    # where the count is fixed. The fix for that one is to clamp feature weights during training so a
    # larger QA fits in int16: QA is capped at 32767 / (34 * peak), so a peak of 0.77 permits 1258 while
    # a peak of 0.25 would permit 3855. It needs a training change and a retrain, and is not done.
    scales = []
    for name, layer in (("layer_one", model.layer_one),
                        ("layer_two", model.layer_two),
                        ("output", model.output)):
        weights = layer.weight.detach().abs()
        peak = float(weights.max())
        scale = max(1, int(INT8_LIMIT / peak))
        scales.append(scale)
        print(f"{name:12s} peaks at {peak:.4f} -> {scale:4d} "
              f"(a typical weight becomes {round(float(weights.mean()) * scale)})")
    return (qa, *scales)

# A handful of positions with nothing in common, so a mistake that only shows up with, say, a rook on
# the seventh rank has somewhere to show up.
FIXTURE_POSITIONS = [
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
    "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
    "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
    "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
    "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
    "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
    "8/5k2/3p4/1p1Pp2p/pP2Pp1P/P4P1K/8/8 b - - 0 1",
    "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
    "6k1/5ppp/8/8/8/8/5PPP/R5K1 b - - 0 1",
    "8/8/8/3k4/8/3K4/8/7R b - - 0 1",
]


def clamp(value: int, low: int, high: int, what: str, overflows: list[str]) -> int:
    if value < low or value > high:
        overflows.append(f"{what}: {value}")
        return max(low, min(high, value))
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--net", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--fixtures", default=None)
    arguments = parser.parse_args()

    model = Nnue()
    model.load_state_dict(torch.load(arguments.net, map_location="cpu"))
    model.eval()

    overflows: list[str] = []
    qa, qb1, qb2, qb3 = choose_scales(model)

    with open(arguments.out, "wb") as handle:
        handle.write(struct.pack(">iiiiiiiiii", MAGIC, FORMAT_VERSION,
                                 INPUTS, HIDDEN, L1, L2, qa, qb1, qb2, qb3))

        # Feature transformer: weights and the accumulator live in activation units.
        weights = model.feature_transformer.weight.detach()
        for feature in range(INPUTS):
            for hidden in range(HIDDEN):
                value = clamp(round(float(weights[feature][hidden]) * qa), -32768, 32767,
                              "feature weight", overflows)
                handle.write(struct.pack(">h", value))

        bias = model.feature_bias.detach()
        for hidden in range(HIDDEN):
            handle.write(struct.pack(">h", clamp(round(float(bias[hidden]) * qa), -32768, 32767,
                                                 "feature bias", overflows)))

        write_dense(handle, model.layer_one, HIDDEN * 2, L1, qa, qb1, overflows)
        write_dense(handle, model.layer_two, L1, L2, qa, qb2, overflows)

        # The output layer has one neuron, so its weights are written flat and its bias alone.
        output_weights = model.output.weight.detach()[0]
        for i in range(L2):
            handle.write(struct.pack(">b", clamp(round(float(output_weights[i]) * qb3), -128, 127,
                                                 "output weight", overflows)))
        handle.write(struct.pack(">i", round(float(model.output.bias.detach()[0]) * qa * qb3)))

    print(f"wrote {arguments.out}")
    if overflows:
        # Saturating silently would hand the engine a network quietly different from the trained one.
        print(f"WARNING: {len(overflows)} value(s) saturated during quantisation, "
              f"first few: {overflows[:5]}")

    if arguments.fixtures:
        write_fixtures(model, arguments.fixtures)

    return 0


def write_dense(handle, layer, inputs: int, outputs: int, qa: int, qb: int,
                overflows: list[str]) -> None:
    weights = layer.weight.detach()
    for neuron in range(outputs):
        for i in range(inputs):
            handle.write(struct.pack(">b", clamp(round(float(weights[neuron][i]) * qb), -128, 127,
                                                 "dense weight", overflows)))
    biases = layer.bias.detach()
    for neuron in range(outputs):
        # A dense bias is added to products already scaled by both factors, so it carries both.
        handle.write(struct.pack(">i", round(float(biases[neuron]) * qa * qb)))


@torch.no_grad()
def write_fixtures(model: Nnue, path: str) -> None:
    """Positions with the float model's own answer, in centipawns, for the Java side to check."""
    with open(path, "w", encoding="utf-8") as handle:
        for fen in FIXTURE_POSITIONS:
            own, theirs = active_features(fen)
            own_indices = torch.tensor(own, dtype=torch.long)
            their_indices = torch.tensor(theirs, dtype=torch.long)
            offsets = torch.tensor([0], dtype=torch.long)

            value = model(own_indices, offsets, their_indices, offsets)
            centipawns = round(float(value[0]) * SCALE)
            handle.write(f"{fen}|{centipawns}\n")
    print(f"wrote {path} with {len(FIXTURE_POSITIONS)} fixtures")


if __name__ == "__main__":
    raise SystemExit(main())
