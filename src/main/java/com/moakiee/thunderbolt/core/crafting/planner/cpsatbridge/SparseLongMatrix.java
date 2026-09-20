package com.moakiee.thunderbolt.core.crafting.planner.cpsatbridge;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Zero-default material arcs. The bootstrap-only wire preserves dimensions without dense rows. */
public final class SparseLongMatrix {
    private final int columns;
    private final Map<Integer, Long>[] data;
    private final int[][] rowKeys;
    private int[][] columnKeys;

    @SuppressWarnings("unchecked")
    public SparseLongMatrix(int rows, int columns) {
        if (rows < 0 || columns < 0) throw new IllegalArgumentException("negative shape");
        this.columns = columns;
        data = (Map<Integer, Long>[]) new Map<?, ?>[rows];
        rowKeys = new int[rows][];
    }

    public int rows() { return data.length; }
    public int columns() { return columns; }
    public long get(int row, int column) {
        return data[row] == null ? 0 : data[row].getOrDefault(column, 0L);
    }
    public void set(int row, int column, long value) {
        if (column < 0 || column >= columns) throw new IndexOutOfBoundsException(column);
        if (value == 0) {
            if (data[row] == null || data[row].remove(column) == null) return;
        } else {
            if (data[row] == null) data[row] = new TreeMap<>();
            data[row].put(column, value);
        }
        rowKeys[row] = null;
        columnKeys = null;
    }
    /** Read-only index views, in the same stable order as the old dense traversal. */
    public int[] rowKeys(int row) {
        if (rowKeys[row] == null) rowKeys[row] = data[row] == null ? new int[0]
                : data[row].keySet().stream().mapToInt(Integer::intValue).toArray();
        return rowKeys[row];
    }
    public int[] columnKeys(int column) {
        if (columnKeys == null) {
            int[] sizes = new int[columns];
            for (int r = 0; r < rows(); r++) for (int c : rowKeys(r)) sizes[c]++;
            columnKeys = new int[columns][];
            for (int c = 0; c < columns; c++) columnKeys[c] = new int[sizes[c]];
            Arrays.fill(sizes, 0);
            for (int r = 0; r < rows(); r++) for (int c : rowKeys(r)) columnKeys[c][sizes[c]++] = r;
        }
        return columnKeys[column];
    }
    public static int[] unionRow(int row, SparseLongMatrix... matrices) {
        var keys = new TreeSet<Integer>();
        for (var matrix : matrices) for (int key : matrix.rowKeys(row)) keys.add(key);
        return keys.stream().mapToInt(Integer::intValue).toArray();
    }
    public static int[] unionColumn(int column, SparseLongMatrix... matrices) {
        var keys = new TreeSet<Integer>();
        for (var matrix : matrices) for (int key : matrix.columnKeys(column)) keys.add(key);
        return keys.stream().mapToInt(Integer::intValue).toArray();
    }
    public long[][] wire() {
        long[][] wire = new long[rows()+1][];
        wire[0] = new long[] {rows(), columns};
        for (int r = 0; r < rows(); r++) {
            int[] keys = rowKeys(r);
            wire[r+1] = new long[keys.length*2];
            for (int i = 0; i < keys.length; i++) {
                wire[r+1][2*i] = keys[i]; wire[r+1][2*i+1] = get(r, keys[i]);
            }
        }
        return wire;
    }
    public static SparseLongMatrix fromWire(long[][] wire) {
        if (wire.length == 0 || wire[0].length != 2 || wire[0][0] != wire.length-1
                || wire[0][1] < 0 || wire[0][1] > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid sparse shape");
        var matrix = new SparseLongMatrix(wire.length-1, (int) wire[0][1]);
        for (int r = 0; r < matrix.rows(); r++) {
            long[] row = wire[r+1];
            if ((row.length & 1) != 0) throw new IllegalArgumentException("invalid sparse row");
            long previous = -1;
            for (int i = 0; i < row.length; i += 2) {
                if (row[i] <= previous || row[i] >= matrix.columns || row[i+1] == 0)
                    throw new IllegalArgumentException("invalid sparse entry");
                matrix.set(r, (int) row[i], row[i+1]); previous = row[i];
            }
        }
        return matrix;
    }
    public static SparseLongMatrix fromDense(long[][] values) {
        int columns = values.length == 0 ? 0 : values[0].length;
        var matrix = new SparseLongMatrix(values.length, columns);
        for (int r = 0; r < values.length; r++) {
            if (values[r].length != columns) throw new IllegalArgumentException("ragged matrix");
            for (int c = 0; c < columns; c++) if (values[r][c] != 0) matrix.set(r,c,values[r][c]);
        }
        return matrix;
    }
}
