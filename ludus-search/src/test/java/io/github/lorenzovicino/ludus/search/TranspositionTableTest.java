package io.github.lorenzovicino.ludus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.Squares;
import org.junit.jupiter.api.Test;

class TranspositionTableTest {

    private static final int SOME_MOVE = Move.of(Squares.E1, Squares.G1, Move.CASTLE_KING);

    @Test
    void storesAndReturnsAnEntry() {
        TranspositionTable table = new TranspositionTable(1);
        table.store(0xDEADBEEFL, SOME_MOVE, 123, 7, TranspositionTable.BOUND_EXACT, 0);

        long entry = table.probe(0xDEADBEEFL);
        assertNotEquals(0, entry, "The entry just written should be found");
        assertEquals(SOME_MOVE, TranspositionTable.moveOf(entry));
        assertEquals(123, TranspositionTable.scoreOf(entry, 0));
        assertEquals(7, TranspositionTable.depthOf(entry));
        assertEquals(TranspositionTable.BOUND_EXACT, TranspositionTable.boundOf(entry));
    }

    @Test
    void reportsAMissAsZero() {
        TranspositionTable table = new TranspositionTable(1);
        table.store(0xDEADBEEFL, SOME_MOVE, 123, 7, TranspositionTable.BOUND_EXACT, 0);
        assertEquals(0, table.probe(0x1234L), "An unrelated key must not match");
    }

    @Test
    void negativeScoresSurviveThePacking() {
        // The score occupies sixteen bits inside a long, so it has to be sign-extended on the way
        // out. Without that a losing score reads as a large positive one.
        TranspositionTable table = new TranspositionTable(1);
        table.store(1L, SOME_MOVE, -456, 3, TranspositionTable.BOUND_UPPER, 0);
        assertEquals(-456, TranspositionTable.scoreOf(table.probe(1L), 0));
    }

    @Test
    void ordinaryScoresIgnorePly() {
        assertEquals(50, TranspositionTable.toTableScore(50, 9));
        assertEquals(-50, TranspositionTable.toTableScore(-50, 9));
        assertEquals(50, TranspositionTable.fromTableScore(50, 9));
    }

    @Test
    void mateScoresAreConvertedToBeRelativeToTheNode() {
        // A mate five plies from the root, seen at ply three, is two plies from that node. Storing
        // the root-relative number would make the same entry claim a different mate when it is read
        // at another depth — which is how an engine ends up announcing mates that do not exist.
        int fiveFromRoot = Search.MATE - 5;
        int stored = TranspositionTable.toTableScore(fiveFromRoot, 3);
        assertEquals(Search.MATE - 2, stored, "Two plies from the node that wrote it");

        assertEquals(Search.MATE - 8, TranspositionTable.fromTableScore(stored, 6),
                "Read at ply six, the same two-ply mate is eight plies from the root");
    }

    @Test
    void mateScoresRoundTripAtTheSamePly() {
        for (int ply = 0; ply < 20; ply++) {
            int win = Search.MATE - 4;
            int loss = -Search.MATE + 4;
            assertEquals(win, TranspositionTable.fromTableScore(
                    TranspositionTable.toTableScore(win, ply), ply));
            assertEquals(loss, TranspositionTable.fromTableScore(
                    TranspositionTable.toTableScore(loss, ply), ply));
        }
    }

    @Test
    void deeperEntriesAreNotOverwrittenByShallowerOnesInTheSameSearch() {
        TranspositionTable table = new TranspositionTable(1);
        long key = 99L;
        table.store(key, SOME_MOVE, 500, 10, TranspositionTable.BOUND_EXACT, 0);
        table.store(key, Move.NONE, -500, 2, TranspositionTable.BOUND_UPPER, 0);

        long entry = table.probe(key);
        assertEquals(10, TranspositionTable.depthOf(entry), "The depth-10 result is the better one");
        assertEquals(500, TranspositionTable.scoreOf(entry, 0));
    }

    @Test
    void entriesFromAnEarlierSearchGiveWayToShallowerNewOnes() {
        // An old deep entry describes a position the game has probably moved past, so a fresh
        // shallow one is more useful than clinging to it.
        TranspositionTable table = new TranspositionTable(1);
        long key = 99L;
        table.store(key, SOME_MOVE, 500, 10, TranspositionTable.BOUND_EXACT, 0);

        table.newSearch();
        table.store(key, Move.NONE, -500, 2, TranspositionTable.BOUND_UPPER, 0);

        assertEquals(2, TranspositionTable.depthOf(table.probe(key)));
    }

    @Test
    void clearForgetsEverything() {
        TranspositionTable table = new TranspositionTable(1);
        table.store(7L, SOME_MOVE, 1, 1, TranspositionTable.BOUND_EXACT, 0);
        table.clear();
        assertEquals(0, table.probe(7L));
    }

    @Test
    void capacityIsAPowerOfTwoAndScalesWithTheRequest() {
        TranspositionTable small = new TranspositionTable(1);
        TranspositionTable large = new TranspositionTable(64);

        assertEquals(0, small.capacity() & (small.capacity() - 1), "Indexing masks, so it must be 2^n");
        assertEquals(0, large.capacity() & (large.capacity() - 1));
        assertTrue(large.capacity() > small.capacity());
    }
}
