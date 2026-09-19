package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SparseLongMatrixTest {
    @Test void mutationsTransposeAndWireMatchIndependentDenseValues() {
        var sparse = new SparseLongMatrix(23,19);
        long[][] dense = new long[23][19];
        var random = new Random(20260918);
        for (int step = 0; step < 400; step++) {
            int row = random.nextInt(23), column = random.nextInt(19);
            long value = random.nextInt(5)-2;
            sparse.set(row,column,value); dense[row][column]=value;
            var copy = SparseLongMatrix.fromWire(sparse.wire());
            for (int c = 0; c < 19; c++) {
                var expected = new java.util.ArrayList<Integer>();
                for (int r = 0; r < 23; r++) {
                    assertEquals(dense[r][c],sparse.get(r,c));
                    assertEquals(dense[r][c],copy.get(r,c));
                    if (dense[r][c] != 0) expected.add(r);
                }
                assertArrayEquals(expected.stream().mapToInt(Integer::intValue).toArray(),sparse.columnKeys(c));
            }
        }
    }

    @Test void hundredMillionLogicalCellsOnlyStoreRealArcs() {
        var matrix = new SparseLongMatrix(10_000,10_000);
        for (int r = 0; r < 10_000; r++) matrix.set(r,r,1);
        long[][] wire = matrix.wire();
        assertEquals(10_001,wire.length);
        assertEquals(20_002,java.util.Arrays.stream(wire).mapToInt(r -> r.length).sum());
        assertEquals(0,matrix.get(0,9999));
        assertArrayEquals(new int[]{9999},matrix.columnKeys(9999));
        matrix.set(9999,9999,0);
        assertArrayEquals(new int[0],matrix.columnKeys(9999));
    }

    @Test void malformedWireCannotChangeDimensionsOrDuplicateAnArc() {
        assertThrows(IllegalArgumentException.class, () -> SparseLongMatrix.fromWire(new long[][]{{1,2},{0,1,0,2}}));
        assertThrows(IllegalArgumentException.class, () -> SparseLongMatrix.fromWire(new long[][]{{1,2},{2,1}}));
        assertThrows(IllegalArgumentException.class, () -> SparseLongMatrix.fromWire(new long[][]{{1,2},{0}}));
        assertThrows(IllegalArgumentException.class, () -> SparseLongMatrix.fromWire(new long[][]{{2,2},{0,1}}));
    }
}
