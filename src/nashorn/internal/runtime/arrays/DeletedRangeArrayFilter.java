/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package nashorn.internal.runtime.arrays;

import java.lang.reflect.Array;

import nashorn.internal.runtime.ScriptRuntime;

/**
 * This filter handles the deletion of array elements.
 */
final class DeletedRangeArrayFilter extends ArrayFilter {

    /** Range (inclusive) tracking deletions */
    private long lo, hi;

    DeletedRangeArrayFilter(ArrayData underlying, long lo, long hi) {
        super(maybeSparse(underlying, hi));
        this.lo = lo;
        this.hi = hi;
    }

    private static ArrayData maybeSparse(ArrayData underlying, long hi) {
        if (hi < SparseArrayData.MAX_DENSE_LENGTH || underlying instanceof SparseArrayData) {
            return underlying;
        }
        return new SparseArrayData(underlying, underlying.length());
    }

    private boolean isEmpty() {
        return lo > hi;
    }

    private boolean isDeleted(int index) {
        var longIndex = ArrayIndex.toLongIndex(index);
        return lo <= longIndex && longIndex <= hi;
    }

    @Override
    public ArrayData copy() {
        return new DeletedRangeArrayFilter(underlying.copy(), lo, hi);
    }

    @Override
    public Object[] asObjectArray() {
        var value = super.asObjectArray();

        if (lo < Integer.MAX_VALUE) {
            var end = (int)Math.min(hi + 1, Integer.MAX_VALUE);
            for (var i = (int)lo; i < end; i++) {
                value[i] = ScriptRuntime.UNDEFINED;
            }
        }

        return value;
    }

    @Override
    public Object asArrayOfType(Class<?> componentType) {
        var value = super.asArrayOfType(componentType);
        var undefValue = convertUndefinedValue(componentType);

        if (lo < Integer.MAX_VALUE) {
            var end = (int)Math.min(hi + 1, Integer.MAX_VALUE);
            for (var i = (int)lo; i < end; i++) {
                Array.set(value, i, undefValue);
            }
        }

        return value;
    }

    @Override
    public ArrayData ensure(long safeIndex) {
        if (safeIndex >= SparseArrayData.MAX_DENSE_LENGTH && safeIndex >= length()) {
            return new SparseArrayData(this, safeIndex + 1);
        }

        return super.ensure(safeIndex);
    }

    @Override
    public ArrayData shiftLeft(int by) {
        super.shiftLeft(by);
        lo = Math.max(0, lo - by);
        hi = Math.max(-1, hi - by);

        return isEmpty() ? getUnderlying() : this;
    }

    @Override
    public ArrayData shiftRight(int by) {
        super.shiftRight(by);
        var len = length();
        lo = Math.min(len, lo + by);
        hi = Math.min(len - 1, hi + by);

        return isEmpty() ? getUnderlying() : this;
    }

    @Override
    public ArrayData shrink(long newLength) {
        super.shrink(newLength);
        lo = Math.min(newLength, lo);
        hi = Math.min(newLength - 1, hi);

        return isEmpty() ? getUnderlying() : this;
    }

    @Override
    public ArrayData set(int index, Object value) {
        var longIndex = ArrayIndex.toLongIndex(index);
        if (longIndex < lo || longIndex > hi) {
            return super.set(index, value);
        } else if (longIndex > lo && longIndex < hi) {
            return getDeletedArrayFilter().set(index, value);
        }
        if (longIndex == lo) {
            lo++;
        } else {
            assert longIndex == hi;
            hi--;
        }

        return isEmpty() ? getUnderlying().set(index, value) : super.set(index, value);
    }

    @Override
    public ArrayData set(int index, int value) {
        var longIndex = ArrayIndex.toLongIndex(index);
        if (longIndex < lo || longIndex > hi) {
            return super.set(index, value);
        } else if (longIndex > lo && longIndex < hi) {
            return getDeletedArrayFilter().set(index, value);
        }
        if (longIndex == lo) {
            lo++;
        } else {
            assert longIndex == hi;
            hi--;
        }

        return isEmpty() ? getUnderlying().set(index, value) : super.set(index, value);
    }

    @Override
    public ArrayData set(int index, double value) {
        var longIndex = ArrayIndex.toLongIndex(index);
        if (longIndex < lo || longIndex > hi) {
            return super.set(index, value);
        } else if (longIndex > lo && longIndex < hi) {
            return getDeletedArrayFilter().set(index, value);
        }
        if (longIndex == lo) {
            lo++;
        } else {
            assert longIndex == hi;
            hi--;
        }

        return isEmpty() ? getUnderlying().set(index, value) : super.set(index, value);
    }

    @Override
    public boolean has(int index) {
        return super.has(index) && !isDeleted(index);
    }

    private ArrayData getDeletedArrayFilter() {
        var deleteFilter = new DeletedArrayFilter(getUnderlying());
        deleteFilter.delete(lo, hi);
        return deleteFilter;
    }

    @Override
    public ArrayData delete(int index) {
        var longIndex = ArrayIndex.toLongIndex(index);
        underlying.setEmpty(index);

        if (longIndex + 1 == lo) {
            lo = longIndex;
        } else if (longIndex - 1 == hi) {
            hi = longIndex;
        } else if (longIndex < lo || hi < longIndex) {
           return getDeletedArrayFilter().delete(index);
        }

        return this;
    }

    @Override
    public ArrayData delete(long fromIndex, long toIndex) {
        if (fromIndex > hi + 1  || toIndex < lo - 1) {
            return getDeletedArrayFilter().delete(fromIndex, toIndex);
        }
        lo = Math.min(fromIndex, lo);
        hi = Math.max(toIndex, hi);
        underlying.setEmpty(lo, hi);
        return this;
    }

    @Override
    public Object pop() {
        var index = (int)length() - 1;
        if (super.has(index)) {
            var isDeleted = isDeleted(index);
            var value = super.pop();

            lo = Math.min(index + 1, lo);
            hi = Math.min(index, hi);
            return isDeleted ? ScriptRuntime.UNDEFINED : value;
        }

        return super.pop();
    }

    @Override
    public ArrayData slice(long from, long to) {
        return new DeletedRangeArrayFilter(underlying.slice(from, to), Math.max(0, lo - from), Math.max(0, hi - from));
    }

}
