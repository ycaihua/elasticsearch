/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.support;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefHash;
import org.apache.lucene.util.InPlaceMergeSorter;
import org.elasticsearch.common.lucene.ReaderContextAware;
import org.elasticsearch.index.fielddata.*;
import org.elasticsearch.index.fielddata.AtomicFieldData.Order;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.aggregations.support.FieldDataSource.Bytes.SortedAndUnique.SortedUniqueBytesValues;
import org.elasticsearch.search.aggregations.support.bytes.ScriptBytesValues;
import org.elasticsearch.search.aggregations.support.numeric.ScriptDoubleValues;
import org.elasticsearch.search.aggregations.support.numeric.ScriptLongValues;

/**
 *
 */
public abstract class FieldDataSource {

    /** Whether values are unique or not per document. */
    public enum Uniqueness {
        UNIQUE,
        NOT_UNIQUE,
        UNKNOWN;
    }

    /** Return whether values are unique. */
    public Uniqueness getUniqueness() {
        return Uniqueness.UNKNOWN;
    }

    /** Get the current {@link BytesValues}. */
    public abstract BytesValues bytesValues();

    /** Ask the underlying data source to provide pre-computed hashes, optional operation. */
    public void setNeedsHashes(boolean needsHashes) {}

    public static abstract class Bytes extends FieldDataSource {

        public static class FieldData extends Bytes implements ReaderContextAware {

            protected boolean needsHashes;
            protected final IndexFieldData<?> indexFieldData;
            protected AtomicFieldData<?> atomicFieldData;
            private BytesValues bytesValues;

            public FieldData(IndexFieldData<?> indexFieldData) {
                this.indexFieldData = indexFieldData;
                needsHashes = false;
            }

            @Override
            public Uniqueness getUniqueness() {
                return Uniqueness.UNIQUE;
            }

            public final void setNeedsHashes(boolean needsHashes) {
                this.needsHashes = needsHashes;
            }

            @Override
            public void setNextReader(AtomicReaderContext reader) {
                atomicFieldData = indexFieldData.load(reader);
                if (bytesValues != null) {
                    bytesValues = atomicFieldData.getBytesValues(needsHashes);
                }
            }

            @Override
            public org.elasticsearch.index.fielddata.BytesValues bytesValues() {
                if (bytesValues == null) {
                    bytesValues = atomicFieldData.getBytesValues(needsHashes);
                }
                return bytesValues;
            }
        }

        public static class Script extends Bytes {

            private final ScriptBytesValues values;

            public Script(SearchScript script) {
                values = new ScriptBytesValues(script);
            }

            @Override
            public org.elasticsearch.index.fielddata.BytesValues bytesValues() {
                return values;
            }

        }

        public static class SortedAndUnique extends Bytes implements ReaderContextAware {

            private final FieldDataSource delegate;
            private BytesValues bytesValues;

            public SortedAndUnique(FieldDataSource delegate) {
                this.delegate = delegate;
            }

            @Override
            public Uniqueness getUniqueness() {
                return Uniqueness.UNIQUE;
            }

            @Override
            public void setNextReader(AtomicReaderContext reader) {
                bytesValues = null; // order may change per-segment -> reset
            }

            @Override
            public org.elasticsearch.index.fielddata.BytesValues bytesValues() {
                if (bytesValues == null) {
                    bytesValues = delegate.bytesValues();
                    if (bytesValues.isMultiValued() &&
                            (delegate.getUniqueness() != Uniqueness.UNIQUE || bytesValues.getOrder() != Order.BYTES)) {
                        bytesValues = new SortedUniqueBytesValues(bytesValues);
                    }
                }
                return bytesValues;
            }

            static class SortedUniqueBytesValues extends FilterBytesValues {

                final BytesRef spare;
                int[] sortedIds;
                final BytesRefHash bytes;
                int numUniqueValues;
                int pos = Integer.MAX_VALUE;

                public SortedUniqueBytesValues(BytesValues delegate) {
                    super(delegate);
                    bytes = new BytesRefHash();
                    spare = new BytesRef();
                }

                @Override
                public int setDocument(int docId) {
                    final int numValues = super.setDocument(docId);
                    if (numValues == 0) {
                        sortedIds = null;
                        return 0;
                    }
                    bytes.clear();
                    bytes.reinit();
                    for (int i = 0; i < numValues; ++i) {
                        bytes.add(super.nextValue(), super.hashCode());
                    }
                    numUniqueValues = bytes.size();
                    sortedIds = bytes.sort(BytesRef.getUTF8SortedAsUnicodeComparator());
                    pos = 0;
                    return numUniqueValues;
                }

                @Override
                public BytesRef nextValue() {
                    bytes.get(sortedIds[pos++], spare);
                    return spare;
                }

                @Override
                public Order getOrder() {
                    return Order.BYTES;
                }

            }

        }

    }

    public static abstract class Numeric extends FieldDataSource {

        /** Whether the underlying data is floating-point or not. */
        public abstract boolean isFloatingPoint();

        /** Get the current {@link LongValues}. */
        public abstract LongValues longValues();

        /** Get the current {@link DoubleValues}. */
        public abstract DoubleValues doubleValues();

        public static class WithScript extends Numeric {

            private final Numeric delegate;
            private final LongValues longValues;
            private final DoubleValues doubleValues;
            private final FieldDataSource.WithScript.BytesValues bytesValues;

            public WithScript(Numeric delegate, SearchScript script) {
                this.delegate = delegate;
                this.longValues = new LongValues(delegate, script);
                this.doubleValues = new DoubleValues(delegate, script);
                this.bytesValues = new FieldDataSource.WithScript.BytesValues(delegate, script);
            }

            @Override
            public boolean isFloatingPoint() {
                return true; // even if the underlying source produces longs, scripts can change them to doubles
            }

            @Override
            public BytesValues bytesValues() {
                return bytesValues;
            }

            @Override
            public LongValues longValues() {
                return longValues;
            }

            @Override
            public DoubleValues doubleValues() {
                return doubleValues;
            }

            static class LongValues extends org.elasticsearch.index.fielddata.LongValues {

                private final Numeric source;
                private final SearchScript script;

                public LongValues(Numeric source, SearchScript script) {
                    super(true);
                    this.source = source;
                    this.script = script;
                }

                @Override
                public int setDocument(int docId) {
                    return source.longValues().setDocument(docId);
                }

                @Override
                public long nextValue() {
                    script.setNextVar("_value", source.longValues().nextValue());
                    return script.runAsLong();
                }
            }

            static class DoubleValues extends org.elasticsearch.index.fielddata.DoubleValues {

                private final Numeric source;
                private final SearchScript script;

                public DoubleValues(Numeric source, SearchScript script) {
                    super(true);
                    this.source = source;
                    this.script = script;
                }

                @Override
                public int setDocument(int docId) {
                    return source.doubleValues().setDocument(docId);
                }

                @Override
                public double nextValue() {
                    script.setNextVar("_value", source.doubleValues().nextValue());
                    return script.runAsDouble();
                }
            }
        }

        public static class FieldData extends Numeric implements ReaderContextAware {

            protected boolean needsHashes;
            protected final IndexNumericFieldData<?> indexFieldData;
            protected AtomicNumericFieldData atomicFieldData;
            private BytesValues bytesValues;
            private LongValues longValues;
            private DoubleValues doubleValues;

            public FieldData(IndexNumericFieldData<?> indexFieldData) {
                this.indexFieldData = indexFieldData;
                needsHashes = false;
            }

            @Override
            public Uniqueness getUniqueness() {
                return Uniqueness.UNIQUE;
            }

            @Override
            public boolean isFloatingPoint() {
                return indexFieldData.getNumericType().isFloatingPoint();
            }

            @Override
            public final void setNeedsHashes(boolean needsHashes) {
                this.needsHashes = needsHashes;
            }

            @Override
            public void setNextReader(AtomicReaderContext reader) {
                atomicFieldData = indexFieldData.load(reader);
                if (bytesValues != null) {
                    bytesValues = atomicFieldData.getBytesValues(needsHashes);
                }
                if (longValues != null) {
                    longValues = atomicFieldData.getLongValues();
                }
                if (doubleValues != null) {
                    doubleValues = atomicFieldData.getDoubleValues();
                }
            }

            @Override
            public org.elasticsearch.index.fielddata.BytesValues bytesValues() {
                if (bytesValues == null) {
                    bytesValues = atomicFieldData.getBytesValues(needsHashes);
                }
                return bytesValues;
            }

            @Override
            public org.elasticsearch.index.fielddata.LongValues longValues() {
                if (longValues == null) {
                    longValues = atomicFieldData.getLongValues();
                }
                assert longValues.getOrder() == Order.NUMERIC;
                return longValues;
            }

            @Override
            public org.elasticsearch.index.fielddata.DoubleValues doubleValues() {
                if (doubleValues == null) {
                    doubleValues = atomicFieldData.getDoubleValues();
                }
                assert doubleValues.getOrder() == Order.NUMERIC;
                return doubleValues;
            }
        }

        public static class Script extends Numeric {
            private final ScriptValueType scriptValueType;

            private final ScriptDoubleValues doubleValues;
            private final ScriptLongValues longValues;
            private final ScriptBytesValues bytesValues;

            public Script(SearchScript script, ScriptValueType scriptValueType) {
                this.scriptValueType = scriptValueType;
                longValues = new ScriptLongValues(script);
                doubleValues = new ScriptDoubleValues(script);
                bytesValues = new ScriptBytesValues(script);
            }

            @Override
            public boolean isFloatingPoint() {
                return scriptValueType != null ? scriptValueType.isFloatingPoint() : true;
            }

            @Override
            public LongValues longValues() {
                return longValues;
            }

            @Override
            public DoubleValues doubleValues() {
                return doubleValues;
            }

            @Override
            public BytesValues bytesValues() {
                return bytesValues;
            }

        }

        public static class SortedAndUnique extends Numeric implements ReaderContextAware {

            private final Numeric delegate;
            private LongValues longValues;
            private DoubleValues doubleValues;
            private BytesValues bytesValues;

            public SortedAndUnique(Numeric delegate) {
                this.delegate = delegate;
            }

            @Override
            public Uniqueness getUniqueness() {
                return Uniqueness.UNIQUE;
            }

            @Override
            public boolean isFloatingPoint() {
                return delegate.isFloatingPoint();
            }

            @Override
            public void setNextReader(AtomicReaderContext reader) {
                longValues = null; // order may change per-segment -> reset
                doubleValues = null;
                bytesValues = null;
            }

            @Override
            public org.elasticsearch.index.fielddata.LongValues longValues() {
                if (longValues == null) {
                    longValues = delegate.longValues();
                    if (longValues.isMultiValued() &&
                            (delegate.getUniqueness() != Uniqueness.UNIQUE || longValues.getOrder() != Order.NUMERIC)) {
                        longValues = new SortedUniqueLongValues(longValues);
                    }
                }
                return longValues;
            }

            @Override
            public org.elasticsearch.index.fielddata.DoubleValues doubleValues() {
                if (doubleValues == null) {
                    doubleValues = delegate.doubleValues();
                    if (doubleValues.isMultiValued() &&
                            (delegate.getUniqueness() != Uniqueness.UNIQUE || doubleValues.getOrder() != Order.NUMERIC)) {
                        doubleValues = new SortedUniqueDoubleValues(doubleValues);
                    }
                }
                return doubleValues;
            }

            @Override
            public org.elasticsearch.index.fielddata.BytesValues bytesValues() {
                if (bytesValues == null) {
                    bytesValues = delegate.bytesValues();
                    if (bytesValues.isMultiValued() &&
                            (delegate.getUniqueness() != Uniqueness.UNIQUE || bytesValues.getOrder() != Order.BYTES)) {
                        bytesValues = new SortedUniqueBytesValues(bytesValues);
                    }
                }
                return bytesValues;
            }

            private static class SortedUniqueLongValues extends FilterLongValues {

                int numUniqueValues;
                long[] array = new long[2];
                int pos = Integer.MAX_VALUE;

                final InPlaceMergeSorter sorter = new InPlaceMergeSorter() {
                    @Override
                    protected void swap(int i, int j) {
                        final long tmp = array[i];
                        array[i] = array[j];
                        array[j] = tmp;
                    }
                    @Override
                    protected int compare(int i, int j) {
                        final long l1 = array[i];
                        final long l2 = array[j];
                        return l1 < l2 ? -1 : l1 == l2 ? 0 : 1;
                    }
                };

                protected SortedUniqueLongValues(LongValues delegate) {
                    super(delegate);
                }

                @Override
                public int setDocument(int docId) {
                    final int numValues = super.setDocument(docId);
                    if (numValues == 0) {
                        return numUniqueValues = 0;
                    }
                    array = ArrayUtil.grow(array, numValues);
                    for (int i = 0; i < numValues; ++i) {
                        array[i] = super.nextValue();
                    }
                    sorter.sort(0, numValues);
                    numUniqueValues = 1;
                    for (int i = 1; i < numValues; ++i) {
                        if (array[i] != array[i-1]) {
                            array[numUniqueValues++] = array[i];
                        }
                    }
                    pos = 0;
                    return numUniqueValues;
                }

                @Override
                public long nextValue() {
                    assert pos < numUniqueValues;
                    return array[pos++];
                }

                @Override
                public Order getOrder() {
                    return Order.NUMERIC;
                }

            }

            private static class SortedUniqueDoubleValues extends FilterDoubleValues {

                int numUniqueValues;
                double[] array = new double[2];
                int pos = Integer.MAX_VALUE;

                final InPlaceMergeSorter sorter = new InPlaceMergeSorter() {
                    @Override
                    protected void swap(int i, int j) {
                        final double tmp = array[i];
                        array[i] = array[j];
                        array[j] = tmp;
                    }
                    @Override
                    protected int compare(int i, int j) {
                        return Double.compare(array[i], array[j]);
                    }
                };

                SortedUniqueDoubleValues(DoubleValues delegate) {
                    super(delegate);
                }

                @Override
                public int setDocument(int docId) {
                    final int numValues = super.setDocument(docId);
                    if (numValues == 0) {
                        return numUniqueValues = 0;
                    }
                    array = ArrayUtil.grow(array, numValues);
                    for (int i = 0; i < numValues; ++i) {
                        array[i] = super.nextValue();
                    }
                    sorter.sort(0, numValues);
                    numUniqueValues = 1;
                    for (int i = 1; i < numValues; ++i) {
                        if (array[i] != array[i-1]) {
                            array[numUniqueValues++] = array[i];
                        }
                    }
                    pos = 0;
                    return numUniqueValues;
                }

                @Override
                public double nextValue() {
                    assert pos < numUniqueValues;
                    return array[pos++];
                }

                @Override
                public Order getOrder() {
                    return Order.NUMERIC;
                }

            }

        }

    }

    // No need to implement ReaderContextAware here, the delegate already takes care of updating data structures
    public static class WithScript extends Bytes {

        private final BytesValues bytesValues;

        public WithScript(FieldDataSource delegate, SearchScript script) {
            this.bytesValues = new BytesValues(delegate, script);
        }

        @Override
        public BytesValues bytesValues() {
            return bytesValues;
        }

        static class BytesValues extends org.elasticsearch.index.fielddata.BytesValues {

            private final FieldDataSource source;
            private final SearchScript script;
            private final BytesRef scratch;

            public BytesValues(FieldDataSource source, SearchScript script) {
                super(true);
                this.source = source;
                this.script = script;
                scratch = new BytesRef();
            }

            @Override
            public int setDocument(int docId) {
                return source.bytesValues().setDocument(docId);
            }

            @Override
            public BytesRef nextValue() {
                BytesRef value = source.bytesValues().nextValue();
                script.setNextVar("_value", value.utf8ToString());
                scratch.copyChars(script.run().toString());
                return scratch;
            }
        }
    }

    public static class GeoPoint extends FieldDataSource implements ReaderContextAware {

        protected boolean needsHashes;
        protected final IndexGeoPointFieldData<?> indexFieldData;
        protected AtomicGeoPointFieldData<?> atomicFieldData;
        private BytesValues bytesValues;
        private GeoPointValues geoPointValues;

        public GeoPoint(IndexGeoPointFieldData<?> indexFieldData) {
            this.indexFieldData = indexFieldData;
            needsHashes = false;
        }

        @Override
        public Uniqueness getUniqueness() {
            return Uniqueness.UNIQUE;
        }

        @Override
        public final void setNeedsHashes(boolean needsHashes) {
            this.needsHashes = needsHashes;
        }

        @Override
        public void setNextReader(AtomicReaderContext reader) {
            atomicFieldData = indexFieldData.load(reader);
            if (bytesValues != null) {
                bytesValues = atomicFieldData.getBytesValues(needsHashes);
            }
            if (geoPointValues != null) {
                geoPointValues = atomicFieldData.getGeoPointValues();
            }
        }

        @Override
        public org.elasticsearch.index.fielddata.BytesValues bytesValues() {
            if (bytesValues == null) {
                bytesValues = atomicFieldData.getBytesValues(needsHashes);
            }
            return bytesValues;
        }

        public org.elasticsearch.index.fielddata.GeoPointValues geoPointValues() {
            if (geoPointValues == null) {
                geoPointValues = atomicFieldData.getGeoPointValues();
            }
            return geoPointValues;
        }
    }

}