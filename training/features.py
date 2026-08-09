"""Turning a FEN into the sparse feature indices the network reads.

This file is the twin of ``NnueNetwork.featureIndex`` on the Java side, and the two have to agree
exactly. They are the only place where the two languages describe the same thing, so they are also
the only place a silent disagreement can hide: a network trained on one indexing and evaluated with
another produces plausible nonsense rather than an error.

``export.py`` writes a set of test positions with their activations so the Java side can check the
agreement rather than assume it.
"""

WHITE, BLACK = 0, 1
PAWN, KNIGHT, BISHOP, ROOK, QUEEN, KING = range(6)

INPUTS = 768
HIDDEN = 256
L1 = 32
L2 = 32

# Mirrors NnueNetwork.SCALE. The quantisation scales are no longer constants: the exporter measures
# the trained weights and writes the scales into the network file, because a fixed scale chosen for
# one training run silently mis-serves the next.
SCALE = 400

_PIECE_CHARS = {
    "P": (WHITE, PAWN), "N": (WHITE, KNIGHT), "B": (WHITE, BISHOP),
    "R": (WHITE, ROOK), "Q": (WHITE, QUEEN), "K": (WHITE, KING),
    "p": (BLACK, PAWN), "n": (BLACK, KNIGHT), "b": (BLACK, BISHOP),
    "r": (BLACK, ROOK), "q": (BLACK, QUEEN), "k": (BLACK, KING),
}


def feature_index(perspective: int, color: int, piece_type: int, square: int) -> int:
    """The index of a piece's feature, seen from ``perspective``.

    Two things happen for the black perspective and both are needed: the colours swap, so the first
    half of the inputs always means "my pieces", and the square is mirrored vertically, so a pawn on
    the seventh rank looks to black exactly as a pawn on the second looks to white.
    """
    own = color == perspective
    oriented = square if perspective == WHITE else square ^ 56
    return (0 if own else 1) * 384 + piece_type * 64 + oriented


def parse_fen(fen: str):
    """Returns ``(pieces, side_to_move)`` where pieces is a list of ``(color, type, square)``.

    Square numbering matches the Java side: a1 is 0, h8 is 63.
    """
    fields = fen.split()
    placement, side = fields[0], fields[1]

    pieces = []
    for row_index, row in enumerate(placement.split("/")):
        rank = 7 - row_index
        file = 0
        for char in row:
            if char.isdigit():
                file += int(char)
                continue
            color, piece_type = _PIECE_CHARS[char]
            pieces.append((color, piece_type, rank * 8 + file))
            file += 1
        if file != 8:
            raise ValueError(f"rank {rank + 1} describes {file} squares: {fen}")

    return pieces, WHITE if side == "w" else BLACK


def active_features(fen: str):
    """The indices active for each perspective, mover first.

    Mover first is what lets one network serve both colours: it answers "how is the side to move
    doing", which is the question the search asks at every node.
    """
    pieces, side_to_move = parse_fen(fen)
    own, theirs = [], []
    for color, piece_type, square in pieces:
        own.append(feature_index(side_to_move, color, piece_type, square))
        theirs.append(feature_index(1 - side_to_move, color, piece_type, square))
    return own, theirs
