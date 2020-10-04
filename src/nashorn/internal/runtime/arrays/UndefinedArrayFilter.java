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

import nashorn.internal.runtime.BitVector;
import nashorn.internal.runtime.UnwarrantedOptimismException;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

/**
 * This filter handles the presence of undefined array elements.
 */
final class UndefinedArrayFilter extends ArrayFilter {

    /** Bit vector tracking undefined slots. */
    private final BitVector undefined;

    UndefinedArrayFilter(ArrayData underlying) {
        super(underlying);
        this.undefined = new BitVector(underlying.length());
    }

    @Override
    public ArrayData copy() {
        var copy = new UndefinedArrayFilter(underlying.copy());
        copy.getUndefined().copy(undefined);
        return copy;
    }

    @Override
    public Object[] asObjectArray() {
        var value = super.asObjectArray();
        for (var i = 0; i < value.length; i++) {
            if (undefined.isSet(i)) {
                value[i] = UNDEFINED;
            }
        }
        return value;
    }

    @Override
    public Object asArrayOfType(Class<?> componentType) {
        var value = super.asArrayOfType(componentType);
        var undefValue = convertUndefinedValue(componentType);
        var l = Array.getLength(value);
        for (var i = 0; i < l; i++) {
            if (undefined.isSet(i)) {
                Array.set(value, i,undefValue);
            }
        }
        return value;
    }

    @Override
    public ArrayData shiftLeft(int by) {
        super.shiftLeft(by);
        undefined.shiftLeft(by, length());
        return this;
    }

    @Override
    public ArrayData shiftRight(int by) {
        super.shiftRight(by);
        undefined.shiftRight(by, length());
        return this;
    }

    @Override
    public ArrayData ensure(long safeIndex) {
        if (safeIndex >= SparseArrayData.MAX_DENSE_LENGTH && safeIndex >= length()) {
            return new SparseArrayData(this, safeIndex + 1);
        }
        super.ensure(safeIndex);
        undefined.resize(length());
        return this;
    }

    @Override
    public ArrayData shrink(long newLength) {
        super.shrink(newLength);
        undefined.resize(length());
        return this;
    }

    @Override
    public ArrayData set(int index, Object value) {
        undefined.clear(index);
        if (value == UNDEFINED) {
            undefined.set(index);
            return this;
        }
        return super.set(index, value);
    }

    @Override
    public ArrayData set(int index, int value) {
        undefined.clear(index);
        return super.set(index, value);
    }

    @Override
    public ArrayData set(int index, double value) {
        undefined.clear(index);
        return super.set(index, value);
    }

    @Override
    public int getInt(int index) {
        if (undefined.isSet(index)) {
            return 0;
        }
        return super.getInt(index);
    }

    @Override
    public int getIntOptimistic(int index, int programPoint) {
        if (undefined.isSet(index)) {
            throw new UnwarrantedOptimismException(UNDEFINED, programPoint);
        }
        return super.getIntOptimistic(index, programPoint);
    }

    @Override
    public double getDouble(int index) {
        if (undefined.isSet(index)) {
            return Double.NaN;
        }
        return super.getDouble(index);
    }

    @Override
    public double getDoubleOptimistic(int index, int programPoint) {
        if (undefined.isSet(index)) {
            throw new UnwarrantedOptimismException(UNDEFINED, programPoint);
        }
        return super.getDoubleOptimistic(index, programPoint);
    }

    @Override
    public Object getObject(int index) {
        if (undefined.isSet(index)) {
            return UNDEFINED;
        }
        return super.getObject(index);
    }

    @Override
    public ArrayData delete(int index) {
        undefined.clear(index);
        return super.delete(index);
    }

    @Override
    public Object pop() {
        var index = length() - 1;
        if (super.has((int)index)) {
            var isUndefined = undefined.isSet(index);
            var value = super.pop();
            return isUndefined ? UNDEFINED : value;
        }
        return super.pop();
    }

    @Override
    public ArrayData slice(long from, long to) {
        var newArray = underlying.slice(from, to);
        var newFilter = new UndefinedArrayFilter(newArray);
        newFilter.getUndefined().copy(undefined);
        newFilter.getUndefined().shiftLeft(from, newFilter.length());
        return newFilter;
    }

    private BitVector getUndefined() {
        return undefined;
    }

}
