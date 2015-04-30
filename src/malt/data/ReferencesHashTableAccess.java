package malt.data;

import jloda.map.ILongGetter;
import jloda.map.IntFileGetter;
import jloda.map.LongFileGetter;
import jloda.util.Basic;
import jloda.util.CanceledException;
import jloda.util.ProgressPercentage;
import malt.util.MurmurHash3;
import malt.util.Utilities;

import java.io.*;

/**
 * hash table used for mapping k-mers to sequences and offsets (given by a pair of integers)
 * Daniel Huson, 8.2014
 */

public class ReferencesHashTableAccess {
    public static int BUFFER_SIZE = 8192;  // benchmarking suggested that choosing a large size doesn't make a difference
    private final ILongGetter tableIndexGetter; // each entry points to a row of integers that is contained in the data table

    private final int tableSize;
    private final int hashMask;

    private final int randomNumberSeed;

    private long theSize = 0; // counts items

    private final IAlphabet seedAlphabet;  // alphabet used by seeds
    private final SeedShape seedShape; //  seed shape that is saved and loaded from index

    private IntFileGetter tableDataGetter; // used for memory mapping

    /**
     * construct the table from the given directory
     *
     * @param indexDirectory
     */
    public ReferencesHashTableAccess(String indexDirectory, int tableNumber) throws IOException, CanceledException {
        final File indexFile = new File(indexDirectory, "index" + tableNumber + ".idx");
        final File tableIndexFile = new File(indexDirectory, "table" + tableNumber + ".idx");
        final File tableDataFile = new File(indexDirectory, "table" + tableNumber + ".db");

        try (DataInputStream ins = new DataInputStream(new BufferedInputStream(new FileInputStream(indexFile), BUFFER_SIZE))) {
            ProgressPercentage progress = new ProgressPercentage("Reading file: " + indexFile);
            Basic.readAndVerifyMagicNumber(ins, ReferencesHashTableBuilder.MAGIC_NUMBER);

            SequenceType referenceSequenceType = SequenceType.valueOf(ins.readInt());
            System.err.println("Reference sequence type: " + referenceSequenceType.toString());
            if (referenceSequenceType == SequenceType.Protein) {
                int length = ins.readInt();
                byte[] reductionBytes = new byte[length];
                if (ins.read(reductionBytes, 0, length) != length)
                    throw new IOException("Read failed");
                seedAlphabet = new ReducedAlphabet(Basic.toString(reductionBytes));
                System.err.println("Protein reduction: " + seedAlphabet);
            } else
                seedAlphabet = DNA5.getInstance();

            // get all sizes:
            tableSize = ins.readInt();
            // get mask used in hashing
            hashMask = ins.readInt();

            randomNumberSeed = ins.readInt();
            theSize = ins.readLong();
            final int stepSize = ins.readInt();
            if (stepSize > 1)
                System.err.println("Index was built using stepSize=" + stepSize);

            {
                int length = ins.readInt();
                byte[] shapeBytes = new byte[length];
                if (ins.read(shapeBytes, 0, length) != length)
                    throw new IOException("Read failed");
                seedShape = new SeedShape(seedAlphabet, shapeBytes);
            }

            progress.reportTaskCompleted();
        }

        tableIndexGetter = loadTableIndex(tableIndexFile);
        tableDataGetter = loadTableData(tableDataFile);
    }

    /**
     * load the table index as a memory mapped file
     *
     * @param tableIndexFile
     * @return long buffer to access file
     * @throws IOException
     */
    private LongFileGetter loadTableIndex(final File tableIndexFile) throws IOException {
        System.err.println("Opening file: " + tableIndexFile);
        return new LongFileGetter(tableIndexFile);
    }

    /**
     * load the table data as a memory mapped file
     *
     * @param tableDataFile
     * @return input reader to access file
     * @throws IOException
     */
    private IntFileGetter loadTableData(final File tableDataFile) throws IOException {
        System.err.println("Opening file: " + tableDataFile);
        return new IntFileGetter(tableDataFile);
    }

    /**
     * lookup all entries for a given key and put them in the given row object. If none found, row is set to empty
     * todo: re-implement this
     *
     * @param key
     * @param row
     */
    public int lookup(byte[] key, Row row) {
        int hashValue = getHash(key);
        if (hashValue >= 0 && hashValue < tableIndexGetter.limit() && setRow(tableIndexGetter.get(hashValue), row))
            return row.size();
        row.setEmpty();
        return 0;
    }

    /**
     * get the hash value
     *
     * @param key
     * @return hash value
     */
    public int getHash(byte[] key) {
        int value = MurmurHash3.murmurhash3x8632(key, 0, key.length, randomNumberSeed) & hashMask;
        if (value >= Basic.MAX_ARRAY_SIZE) // only use modulo if we are on or above table size
            value %= Basic.MAX_ARRAY_SIZE;
        return value;
    }

    /**
     * get the number of entries
     *
     * @return number of entries
     */
    public long size() {
        return theSize;
    }

    /**
     * get the seed shape associated with this table
     *
     * @return seed shape
     */
    public SeedShape getSeedShape() {
        return seedShape;
    }

    /**
     * show the whole hash table in human readable form
     *
     * @throws java.io.IOException
     */
    public void show() throws IOException {
        System.err.println("Table (" + tableSize + "):");

        Row row = new Row();

        for (int z = 0; z < tableIndexGetter.limit(); z++) {
            if (z > 50)
                continue;
            System.err.print("hash " + z + " -> ");
            if (setRow(tableIndexGetter.get(z), row)) {
                System.err.print("(" + row.size() / 2 + ")");
                for (int i = 0; i < row.size(); i += 2) {
                    if (i > 100) {
                        System.err.print(" ...");
                        break;
                    }
                    System.err.print(" " + row.get(i) + "/" + row.get(i + 1));
                }
            }
            System.err.println();
        }
    }

    /**
     * set the row for the given location
     *
     * @param location
     * @param row
     * @return false, if location invalid
     */
    private boolean setRow(long location, Row row) {
        if (location == 0)
            return false;
        if (location < 0) {
            location = -location;
            row.setPair((int) (location >> 32), (int) location); // is a singleton entry
        } else {
            int length = tableDataGetter.get(location); // length is number int's that follow this first int that tells us the length
            if (row.tmpArray.length <= length)
                row.tmpArray = new int[length + 1];
            row.tmpArray[0] = length;
            for (int i = 1; i <= length; i++)
                row.tmpArray[i] = tableDataGetter.get(location + i);
            row.set(row.tmpArray, 0);
        }
        return true;
    }

    /**
     * get alphabet used for seeds. Note that the seed alphabet may differ from the query alphabet i.e. when using a protein reduction alphabet for seeding
     *
     * @return seed alphabet
     */
    public IAlphabet getSeedAlphabet() {
        return seedAlphabet;
    }

    /**
     * make sure that we can reads the files
     *
     * @param indexDirectory
     * @throws IOException
     */
    public static void checkFilesExist(String indexDirectory, int tableNumber) throws IOException {
        Utilities.checkFileExists(new File(indexDirectory));
        Utilities.checkFileExists(new File(indexDirectory, "index" + tableNumber + ".idx"));
        Utilities.checkFileExists(new File(indexDirectory, "table" + tableNumber + ".idx"));
            Utilities.checkFileExists(new File(indexDirectory, "table" + tableNumber + ".db"));
    }

    /**
     * determines the number of tables existing in the index
     *
     * @param indexDirectory
     * @return number of tables
     */
    public static int determineNumberOfTables(String indexDirectory) {
        int tableNumber = 0;
        while ((new File(indexDirectory, "index" + tableNumber + ".idx")).exists()) {
            tableNumber++;
        }
        return tableNumber;
    }

    /**
     * show part of the hash table in human readable form
     *
     * @throws java.io.IOException
     */
    public void showAPart() throws IOException {
        final Row row = new Row();

        System.err.println("Seed table (" + tableIndexGetter.limit() + "):");
        for (int z = 0; z < tableIndexGetter.limit(); z++) {
            if (z > 10)
                continue;
            System.err.print("hash " + z + " -> ");
            if (setRow(tableIndexGetter.get(z), row)) {
                System.err.print("(" + row.size() / 2 + ")");
                for (int i = 0; i < row.size(); i += 2) {
                    if (i > 100) {
                        System.err.print(" ...");
                        break;
                    }
                    System.err.print(" " + row.get(i) + "/" + row.get(i + 1));
                }
            }
            System.err.println();
        }

    }

    /**
     * construct the table from the given directory
     *
     * @param indexDirectory
     */
    public static SequenceType getIndexSequenceType(String indexDirectory) throws IOException, CanceledException {
        File indexFile = new File(indexDirectory, "index0.idx");
        try (DataInputStream ins = new DataInputStream(new BufferedInputStream(new FileInputStream(indexFile), 8192))) {
            Basic.readAndVerifyMagicNumber(ins, ReferencesHashTableBuilder.MAGIC_NUMBER);
            return SequenceType.valueOf(ins.readInt());
        }
    }
}

