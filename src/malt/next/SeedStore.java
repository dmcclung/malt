package malt.next;

import jloda.util.ProgressPercentage;
import malt.sequence.ISeedExtractor;

/**
 * maintains seeds and their locations
 * <p/>
 * Created by huson on 8/6/14.
 */
public class SeedStore {
    private final static int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
    private final static long SEQUENCE_ID_SHIFT = 33;
    private final static long FRAME_RANK_SHIFT = 30;
    private final static long FRAME_BITS_MASK = ((1l << FRAME_RANK_SHIFT) + (1l << FRAME_RANK_SHIFT + 1l) + (1l << FRAME_RANK_SHIFT + 2l));
    private final static long SEQUENCE_POS_MASK = (1 << FRAME_RANK_SHIFT) - 1;

    private long[] dataArray;
    private int dataPos = 0;

    private final int seedWeight;
    private final int bitsPerLetter;
    private long letterMask;


    /**
     * constructor
     *
     * @param extractor
     * @param seedWeight
     * @param approximateNumberOfWords
     */
    public SeedStore(ISeedExtractor extractor, int seedWeight, long approximateNumberOfWords) {
        this.bitsPerLetter = extractor.getBitsPerLetter();
        this.seedWeight = seedWeight;

        approximateNumberOfWords = (long) (1.2 * approximateNumberOfWords); // add 20% to account for differences between jobs
        if (2l * approximateNumberOfWords >= MAX_ARRAY_SIZE)
            throw new RuntimeException("numberOfWords=" + approximateNumberOfWords + ": too big, use more chunks or batches");
        this.dataArray = new long[(int) (2 * approximateNumberOfWords)]; // one number for word and one for location
        if (seedWeight * bitsPerLetter > 64)
            throw new RuntimeException("wordLengthXbitsPerLetter exceeds 64");
    }

    /**
     * reset
     */
    public void reset() {
        dataPos = 0;
    }

    /**
     * clears but does not free memory.
     */
    public void clear() {
        dataPos = 0;
    }

    /**
     * add a value
     *
     * @param value
     * @param sequenceId
     */
    public void add(long value, int sequenceId, int sequencePosition) {
        if (dataPos == dataArray.length) {
            dataArray = Utilities.growEven(dataArray);
        }
        dataArray[dataPos++] = value;
        // use bits 64-34 for sequence id
        // use bits 33-31 for frameRank
        // use bits 30-1 for sequencePos
        dataArray[dataPos++] = ((long) sequenceId << SEQUENCE_ID_SHIFT) + (sequencePosition & SEQUENCE_POS_MASK);
    }

    /**
     * add a value
     *
     * @param value
     * @param sequenceId
     * @param sequencePosition
     * @param frameRank
     */
    public void add(long value, int sequenceId, int sequencePosition, byte frameRank) {
        if (dataPos == dataArray.length) {
            dataArray = Utilities.growEven(dataArray);
        }
        dataArray[dataPos++] = value;
        // use bits 64-34 for sequence id
        // use bits 33-31 for frameRank
        // use bits 30-1 for sequencePos
        dataArray[dataPos++] = ((long) sequenceId << SEQUENCE_ID_SHIFT) + ((long) frameRank << FRAME_RANK_SHIFT) + (sequencePosition & SEQUENCE_POS_MASK);
    }

    /**
     * get number of seeds (word+location) stored
     *
     * @return number of seeds stored
     */
    public long size() {
        return dataPos >>> 1;
    }

    /**
     * sort all entries
     */
    public void sort(final ProgressPercentage progress) {
        dataArray = radixSort2(dataArray, dataPos, bitsPerLetter * seedWeight, bitsPerLetter, progress);
    }

    /**
     * radix sort list of longs, using entries with even index as keys and entries with odd indices as associated values
     *
     * @param array
     * @param w     number of bits to use (64 to sort full numbers)
     * @param d     number of bits to consider at a time - in the case of 4-bit encoded letters: 4
     * @return sorted array
     */
    private long[] radixSort2(long[] array, int length, int w, int d, final ProgressPercentage progress) {
        if (length % 2 != 0)
            throw new RuntimeException("radixSort2(length=" + length + "): length must be even");

        final int steps = w / d;
        long[] a = array;
        long[] b = new long[length];

        if (progress != null) {
            progress.setMaximum(steps);
            progress.setProgress(0);
        }

        for (int p = 0; p < steps; p++) {
            final int c[] = new int[1 << d];
            // the next three for loops implement counting-sort
            for (int i = 0; i < length; i += 2) {
                c[(int) ((a[i] >> d * p) & ((1 << d) - 1))]++;
            }
            for (int i = 1; i < 1 << d; i++)
                c[i] += c[i - 1];
            for (int i = length - 2; i >= 0; i -= 2) {
                final int index = (--c[(int) ((a[i] >> d * p) & ((1 << d) - 1))]) << 1;
                b[index] = a[i];
                b[index + 1] = a[i + 1];
            }
            // swap arrays
            final long[] tmp = b;
            b = a;
            a = tmp;
            if (progress != null)
                progress.setProgress(p);
        }
        return a;
    }

    /**
     * gets the dataArray array, even entries are seed, od dentries contain corresponding seqId, seqPos and frameRank
     *
     * @return
     */
    public long[] getDataArray() {
        return dataArray;
    }

    /**
     * gets the length of the dataArray array
     *
     * @return length
     */
    public int getLength() {
        return dataPos;
    }

    /**
     * gets the sequence id for a given data array index.
     *
     * @param index must be odd, but this is not checked
     * @return sequence Id
     */
    public int getSequenceIdForIndex(int index) {
        return (int) (dataArray[index] >>> SEQUENCE_ID_SHIFT);
    }

    /**
     * gets the sequence pos for a given data array index
     *
     * @param index must be odd, but this is not checked
     * @return sequence position
     */
    public int getSequencePosForIndex(int index) {
        return (int) (dataArray[index] & SEQUENCE_POS_MASK);
    }

    /**
     * gets the frame rank for a given data array index
     *
     * @param index must be odd, but this is not checked
     * @return frame rank
     */
    public byte getFrameRankForIndex(int index) {
        return (byte) (((dataArray[index] & FRAME_BITS_MASK) >> FRAME_RANK_SHIFT));
    }
}
