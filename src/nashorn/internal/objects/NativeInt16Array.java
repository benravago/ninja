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

package nashorn.internal.objects;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.Property;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.arrays.ArrayData;
import nashorn.internal.runtime.arrays.TypedArrayData;
import static nashorn.internal.codegen.CompilerConstants.specialCall;

/**
 * Int16 array for the TypedArray extension
 */
@ScriptClass("Int16Array")
public final class NativeInt16Array extends ArrayBufferView {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    // The size in bytes of each element in the array.
    @Property(attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_WRITABLE | Attribute.NOT_CONFIGURABLE, where = Where.CONSTRUCTOR)
    public static final int BYTES_PER_ELEMENT = 2;

    private static final Factory FACTORY = new Factory(BYTES_PER_ELEMENT) {
        @Override
        public ArrayBufferView construct(NativeArrayBuffer buffer, int byteOffset, int length) {
            return new NativeInt16Array(buffer, byteOffset, length);
        }
        @Override
        public Int16ArrayData createArrayData(ByteBuffer nb, int start, int end) {
            return new Int16ArrayData(nb.asShortBuffer(), start, end);
        }
        @Override
        public String getClassName() {
            return "Int16Array";
        }
    };

    private static final class Int16ArrayData extends TypedArrayData<ShortBuffer> {

        private static final MethodHandle GET_ELEM = specialCall(MethodHandles.lookup(), Int16ArrayData.class, "getElem", int.class, int.class).methodHandle();
        private static final MethodHandle SET_ELEM = specialCall(MethodHandles.lookup(), Int16ArrayData.class, "setElem", void.class, int.class, int.class).methodHandle();

        private Int16ArrayData(ShortBuffer nb, int start, int end) {
            super((nb.position(start).limit(end)).slice(), end - start);
        }

        @Override
        protected MethodHandle getGetElem() {
            return GET_ELEM;
        }

        @Override
        protected MethodHandle getSetElem() {
            return SET_ELEM;
        }

        @Override
        public Class<?> getElementType() {
            return int.class;
        }

        @Override
        public Class<?> getBoxedElementType() {
            return Integer.class;
        }

        private int getElem(int index) {
            try {
                return nb.get(index);
            } catch (IndexOutOfBoundsException e) {
                throw new ClassCastException(); //force relink - this works for unoptimistic too
            }
        }

        private void setElem(int index, int elem) {
            try {
                if (index < nb.limit()) {
                    nb.put(index, (short) elem);
                }
            } catch (IndexOutOfBoundsException e) {
                throw new ClassCastException();
            }
        }

        @Override
        public int getInt(int index) {
            return getElem(index);
        }

        @Override
        public int getIntOptimistic(int index, int programPoint) {
            return getElem(index);
        }

        @Override
        public double getDouble(int index) {
            return getInt(index);
        }

        @Override
        public double getDoubleOptimistic(int index, int programPoint) {
            return getElem(index);
        }

        @Override
        public Object getObject(int index) {
            return getInt(index);
        }

        @Override
        public ArrayData set(int index, Object value) {
            return set(index, JSType.toInt32(value));
        }

        @Override
        public ArrayData set(int index, int value) {
            setElem(index, value);
            return this;
        }

        @Override
        public ArrayData set(int index, double value) {
            return set(index, (int)value);
        }
    }

    @Constructor(arity = 1)
    public static NativeInt16Array constructor(boolean newObj, Object self, Object... args) {
        return (NativeInt16Array)constructorImpl(newObj, args, FACTORY);
    }

    NativeInt16Array(NativeArrayBuffer buffer, int byteOffset, int byteLength) {
        super(buffer, byteOffset, byteLength);
    }

    @Override
    protected Factory factory() {
        return FACTORY;
    }

    @Function(attributes = Attribute.NOT_ENUMERABLE)
    protected static Object set(Object self, Object array, Object offset) {
        return ArrayBufferView.setImpl(self, array, offset);
    }

    @Function(attributes = Attribute.NOT_ENUMERABLE)
    protected static NativeInt16Array subarray(Object self, Object begin, Object end) {
        return (NativeInt16Array)ArrayBufferView.subarrayImpl(self, begin, end);
    }

    /**
     * ECMA 6 22.2.3.30 %TypedArray%.prototype [ @@iterator ] ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, name = "@@iterator")
    public static Object getIterator(Object self) {
        return ArrayIterator.newArrayValueIterator(self);
    }

    @Override
    protected ScriptObject getPrototype(Global global) {
        return global.getInt16ArrayPrototype();
    }

}
