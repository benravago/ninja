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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import java.util.Arrays;

import jdk.dynalink.linker.support.TypeUtilities;

import nashorn.internal.runtime.JSType;
import static nashorn.internal.codegen.CompilerConstants.specialCall;
import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

/**
 * Implementation of {@link ArrayData} as soon as a double has been written to the array
 */
final class NumberArrayData extends ContinuousArrayData implements NumericElements {

    /** The wrapped array */
    private double[] array;

    /**
     * Constructor
     */
    NumberArrayData(double[] array, int length) {
        super(length);
        assert array.length >= length;
        this.array = array;
    }

    @Override
    public final Class<?> getElementType() {
        return double.class;
    }

    @Override
    public final Class<?> getBoxedElementType() {
        return Double.class;
    }

    @Override
    public final int getElementWeight() {
        return 3;
    }

    @Override
    public final ContinuousArrayData widest(ContinuousArrayData otherData) {
        return otherData instanceof IntOrLongElements ? this : otherData;
    }

    @Override
    public NumberArrayData copy() {
        return new NumberArrayData(array.clone(), (int)length());
    }

    @Override
    public Object[] asObjectArray() {
        return toObjectArray(true);
    }

    private Object[] toObjectArray(boolean trim) {
        assert length() <= array.length : "length exceeds internal array size";
        var len = (int)length();
        var oarray = new Object[trim ? len : array.length];

        for (var index = 0; index < len; index++) {
            oarray[index] = array[index];
        }
        return oarray;
    }

    @Override
    public Object asArrayOfType(Class<?> componentType) {
        if (componentType == double.class) {
            var len = (int)length();
            return array.length == len ? array.clone() : Arrays.copyOf(array, len);
        }
        return super.asArrayOfType(componentType);
    }

    private static boolean canWiden(Class<?> type) {
        return TypeUtilities.isWrapperType(type) &&
            type != Boolean.class && type != Character.class;
    }

    @Override
    public ContinuousArrayData convert(Class<?> type) {
        if (! canWiden(type)) {
            var len = (int)length();
            return new ObjectArrayData(toObjectArray(false), len);
        }
        return this;
    }

    @Override
    public ArrayData shiftLeft(int by) {
        if (by >= length()) {
            shrink(0);
        } else {
            System.arraycopy(array, by, array, 0, array.length - by);
        }
        setLength(Math.max(0, length() - by));
        return this;
    }

    @Override
    public ArrayData shiftRight(int by) {
        var newData = ensure(by + length() - 1);
        if (newData != this) {
            newData.shiftRight(by);
            return newData;
        }
        System.arraycopy(array, 0, array, by, array.length - by);
        return this;
    }

    @Override
    public ArrayData ensure(long safeIndex) {
        if (safeIndex >= SparseArrayData.MAX_DENSE_LENGTH) {
            return new SparseArrayData(this, safeIndex + 1);
        }
        var alen = array.length;
        if (safeIndex >= alen) {
            var newLength = ArrayData.nextSize((int)safeIndex);
            array = Arrays.copyOf(array, newLength); //todo fill with nan or never accessed?
        }
        if (safeIndex >= length()) {
            setLength(safeIndex + 1);
        }
        return this;

    }

    @Override
    public ArrayData shrink(long newLength) {
        Arrays.fill(array, (int)newLength, array.length, 0.0);
        return this;
    }

    @Override
    public ArrayData set(int index, Object value) {
        if (value instanceof Double || (value != null && canWiden(value.getClass()))) {
            return set(index, ((Number)value).doubleValue());
        } else if (value == UNDEFINED) {
            return new UndefinedArrayFilter(this).set(index, value);
        }

        var newData = convert(value == null ? Object.class : value.getClass());
        return newData.set(index, value);
    }

    @Override
    public ArrayData set(int index, int value) {
        array[index] = value;
        setLength(Math.max(index + 1, length()));
        return this;
    }

    @Override
    public ArrayData set(int index, double value) {
        array[index] = value;
        setLength(Math.max(index + 1, length()));
        return this;
    }

    private static final MethodHandle HAS_GET_ELEM = specialCall(MethodHandles.lookup(), NumberArrayData.class, "getElem", double.class, int.class).methodHandle();
    private static final MethodHandle SET_ELEM     = specialCall(MethodHandles.lookup(), NumberArrayData.class, "setElem", void.class, int.class, double.class).methodHandle();

    @SuppressWarnings("unused")
    private double getElem(int index) {
        if (has(index)) {
            return array[index];
        }
        throw new ClassCastException();
    }

    @SuppressWarnings("unused")
    private void setElem(int index, double elem) {
        if (hasRoomFor(index)) {
            array[index] = elem;
            return;
        }
        throw new ClassCastException();
    }

    @Override
    public MethodHandle getElementGetter(Class<?> returnType, int programPoint) {
        if (returnType == int.class) {
            return null;
        }
        return getContinuousElementGetter(HAS_GET_ELEM, returnType, programPoint);
    }

    @Override
    public MethodHandle getElementSetter(Class<?> elementType) {
        return elementType.isPrimitive() ? getContinuousElementSetter(MH.asType(SET_ELEM, SET_ELEM.type().changeParameterType(2, elementType)), elementType) : null;
    }

    @Override
    public int getInt(int index) {
        return JSType.toInt32(array[index]);
    }

    @Override
    public double getDouble(int index) {
        return array[index];
    }

    @Override
    public double getDoubleOptimistic(int index, int programPoint) {
        return array[index];
    }

    @Override
    public Object getObject(int index) {
        return array[index];
    }

    @Override
    public boolean has(int index) {
        return 0 <= index && index < length();
    }

    @Override
    public ArrayData delete(int index) {
        return new DeletedRangeArrayFilter(this, index, index);
    }

    @Override
    public ArrayData delete(long fromIndex, long toIndex) {
        return new DeletedRangeArrayFilter(this, fromIndex, toIndex);
    }

    @Override
    public Object pop() {
        var len = (int)length();
        if (len == 0) {
            return UNDEFINED;
        }

        var newLength = len - 1;
        var elem = array[newLength];
        array[newLength] = 0;
        setLength(newLength);
        return elem;
    }

    @Override
    public ArrayData slice(long from, long to) {
        var start = from < 0 ? from + length() : from;
        var newLength = to - start;
        return new NumberArrayData(Arrays.copyOfRange(array, (int)from, (int)to), (int)newLength);
    }

    @Override
    public ArrayData fastSplice(int start, int removed, int added) throws UnsupportedOperationException {
        var oldLength = length();
        var newLength = oldLength - removed + added;
        if (newLength > SparseArrayData.MAX_DENSE_LENGTH && newLength > array.length) {
            throw new UnsupportedOperationException();
        }
        var returnValue = removed == 0 ? EMPTY_ARRAY : new NumberArrayData(Arrays.copyOfRange(array, start, start + removed), removed);

        if (newLength != oldLength) {
            double[] newArray;

            if (newLength > array.length) {
                newArray = new double[ArrayData.nextSize((int)newLength)];
                System.arraycopy(array, 0, newArray, 0, start);
            } else {
                newArray = array;
            }

            System.arraycopy(array, start + removed, newArray, start + added, (int)(oldLength - start - removed));
            array = newArray;
            setLength(newLength);
        }

        return returnValue;
    }

    @Override
    public double fastPush(int arg) {
        return fastPush((double)arg);
    }

    @Override
    public double fastPush(long arg) {
        return fastPush((double)arg);
    }

    @Override
    public double fastPush(double arg) {
        var len = (int)length();
        if (len == array.length) {
           // note that fastpush never creates spares arrays, there is nothing to gain by that - it will just use even more memory
           array = Arrays.copyOf(array, nextSize(len));
        }
        array[len] = arg;
        return increaseLength();
    }

    @Override
    public double fastPopDouble() {
        if (length() == 0) {
            throw new ClassCastException();
        }
        var newLength = (int)decreaseLength();
        var elem = array[newLength];
        array[newLength] = 0;
        return elem;
    }

    @Override
    public Object fastPopObject() {
        return fastPopDouble();
    }

    @Override
    public ContinuousArrayData fastConcat(ContinuousArrayData otherData) {
        var otherLength = (int)otherData.length();
        var thisLength = (int)length();
        assert otherLength > 0 && thisLength > 0;

        var otherArray = ((NumberArrayData)otherData).array;
        var newLength = otherLength + thisLength;
        var newArray = new double[ArrayData.alignUp(newLength)];

        System.arraycopy(array, 0, newArray, 0, thisLength);
        System.arraycopy(otherArray, 0, newArray, thisLength, otherLength);

        return new NumberArrayData(newArray, newLength);
    }

    @Override
    public String toString() {
        assert length() <= array.length : length() + " > " + array.length;
        return getClass().getSimpleName() + ':' + Arrays.toString(Arrays.copyOf(array, (int)length()));
    }

}
