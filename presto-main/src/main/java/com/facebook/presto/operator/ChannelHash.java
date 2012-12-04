/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.operator;

import com.facebook.presto.block.BlockCursor;
import com.facebook.presto.slice.Slice;
import com.facebook.presto.tuple.TupleInfo;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenCustomHashMap;
import it.unimi.dsi.fastutil.longs.LongHash.Strategy;

import java.util.Arrays;

import static com.facebook.presto.operator.SyntheticAddress.encodeSyntheticAddress;
import static com.facebook.presto.operator.SyntheticAddress.decodeSliceOffset;
import static com.facebook.presto.operator.SyntheticAddress.decodeSliceIndex;

public class ChannelHash
{
    //
    // This class is effectively a Multimap<KeyAddress,Position>.
    //
    // The key address is a SyntheticAddress and the position is the position of the key withing the
    // channel index.
    //
    // The multimap itself is formed out of a regular map and position chaining array.  To perform a
    // lookup, the "lookup" slice is set in the hash, and a synthetic address within the "lookup" slice
    // is created. The "lookup" slice is given index -1 as to not conflict with any slices
    // in the channel index.  Then first position is retrieved from the main address to position map.
    // If a position was found, the remaining value positions are located using the position links array.
    //

    private static final int LOOKUP_SLICE_INDEX = 0xFF_FF_FF_FF;

    private final SliceHashStrategy hashStrategy;
    private final Long2IntOpenCustomHashMap addressToPositionMap;
    private final IntArrayList positionLinks;

    public ChannelHash(ChannelIndex channelIndex)
    {
        hashStrategy = new SliceHashStrategy(channelIndex.getTupleInfo(), channelIndex.getSlices().elements());
        addressToPositionMap = new Long2IntOpenCustomHashMap(channelIndex.getPositionCount(), hashStrategy);
        addressToPositionMap.defaultReturnValue(-1);
        positionLinks = new IntArrayList(new int[channelIndex.getValueAddresses().size()]);
        Arrays.fill(positionLinks.elements(), -1);
        for (int position = 0; position < channelIndex.getValueAddresses().size(); position++) {
            long sliceAddress = channelIndex.getValueAddresses().elements()[position];
            int oldPosition = addressToPositionMap.put(sliceAddress, position);
            if (oldPosition >= 0) {
                // link the new position to the old position
                positionLinks.set(position, oldPosition);
            }
        }
    }

    public ChannelHash(ChannelHash hash)
    {
        // hash strategy can not be shared across threads, but everything else can
        this.hashStrategy = new SliceHashStrategy(hash.hashStrategy.tupleInfo, hash.hashStrategy.slices);
        this.addressToPositionMap = new Long2IntOpenCustomHashMap(hash.addressToPositionMap, hashStrategy);
        addressToPositionMap.defaultReturnValue(-1);
        this.positionLinks = hash.positionLinks;
    }

    public void setLookupSlice(Slice lookupSlice)
    {
        hashStrategy.setLookupSlice(lookupSlice);
    }

    public int get(BlockCursor cursor)
    {
        int position = addressToPositionMap.get(encodeSyntheticAddress(LOOKUP_SLICE_INDEX, cursor.getRawOffset()));
        return position;
    }

    public int getNextPosition(int currentPosition)
    {
        return positionLinks.getInt(currentPosition);
    }

    public static class SliceHashStrategy
            implements Strategy
    {
        private final TupleInfo tupleInfo;
        private final Slice[] slices;
        private Slice lookupSlice;

        public SliceHashStrategy(TupleInfo tupleInfo, Slice[] slices)
        {
            this.tupleInfo = tupleInfo;
            this.slices = slices;
        }

        public void setLookupSlice(Slice lookupSlice)
        {
            this.lookupSlice = lookupSlice;
        }

        @Override
        public int hashCode(long sliceAddress)
        {
            Slice slice = getSliceForSyntheticAddress(sliceAddress);
            int offset = (int) sliceAddress;
            int length = tupleInfo.size(slice, offset);
            int hashCode = slice.hashCode(offset, length);
            return hashCode;
        }

        @Override
        public boolean equals(long leftSliceAddress, long rightSliceAddress)
        {
            Slice leftSlice = getSliceForSyntheticAddress(leftSliceAddress);
            int leftOffset = decodeSliceOffset(leftSliceAddress);
            int leftLength = tupleInfo.size(leftSlice, leftOffset);

            Slice rightSlice = getSliceForSyntheticAddress(rightSliceAddress);
            int rightOffset = decodeSliceOffset(rightSliceAddress);
            int rightLength = tupleInfo.size(rightSlice, rightOffset);

            return leftSlice.equals(leftOffset, leftLength, rightSlice, rightOffset, rightLength);

        }

        private Slice getSliceForSyntheticAddress(long sliceAddress)
        {
            int sliceIndex = decodeSliceIndex(sliceAddress);
            Slice slice;
            if (sliceIndex == LOOKUP_SLICE_INDEX) {
                slice = lookupSlice;
            }
            else {
                slice = slices[sliceIndex];
            }
            return slice;
        }
    }
}