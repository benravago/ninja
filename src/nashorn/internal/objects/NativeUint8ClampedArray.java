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
import static nashorn.internal.codegen.CompilerConstants.staticCall;
import static nashorn.internal.lookup.Lookup.MH;

/**
 * Uint8 clamped array for TypedArray extension
 */
@ScriptClass("Uint8ClampedArray")
public final class NativeUint8ClampedArray extends ArrayBufferView {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    // The size in bytes of each element in the array.
    @Property(attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_WRITABLE | Attribute.NOT_CONFIGURABLE, where = Where.CONSTRUCTOR)
    public static final int BYTES_PER_ELEMENT = 1;

    private static final Factory FACTORY = new Factory(BYTES_PER_ELEMENT) {
        @Override
        public ArrayBufferView construct(NativeArrayBuffer buffer, int byteOffset, int length) {
            return new NativeUint8ClampedArray(buffer, byteOffset, length);
        }
        @Override
        public Uint8ClampedArrayData createArrayData(ByteBuffer nb, int start, int end) {
            return new Uint8ClampedArrayData(nb, start, end);
        }
        @Override
        public String getClassName() {
            return "Uint8ClampedArray";
        }
    };

    private static final class Uint8ClampedArrayData extends TypedArrayData<ByteBuffer> {

        private static final MethodHandle GET_ELEM = specialCall(MethodHandles.lookup(), Uint8ClampedArrayData.class, "getElem", int.class, int.class).methodHandle();
        private static final MethodHandle SET_ELEM = specialCall(MethodHandles.lookup(), Uint8ClampedArrayData.class, "setElem", void.class, int.class, int.class).methodHandle();
        private static final MethodHandle RINT_D   = staticCall(MethodHandles.lookup(), Uint8ClampedArrayData.class, "rint", double.class, double.class).methodHandle();
        private static final MethodHandle RINT_O   = staticCall(MethodHandles.lookup(), Uint8ClampedArrayData.class, "rint", Object.class, Object.class).methodHandle();

        private Uint8ClampedArrayData(ByteBuffer nb, int start, int end) {
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
            return int.class;
        }

        private int getElem(int index) {
            try {
                return nb.get(index) & 0xff;
            } catch (IndexOutOfBoundsException e) {
                throw new ClassCastException(); //force relink - this works for unoptimistic too
            }
        }

        @Override
        public MethodHandle getElementSetter(Class<?> elementType) {
            var setter = super.getElementSetter(elementType); //getContinuousElementSetter(getClass(), setElem(), elementType);
            if (setter != null) {
                if (elementType == Object.class) {
                    return MH.filterArguments(setter, 2, RINT_O);
                } else if (elementType == double.class) {
                    return MH.filterArguments(setter, 2, RINT_D);
                }
            }
            return setter;
        }

        private void setElem(int index, int elem) {
            try {
                if (index < nb.limit()) {
                    byte clamped;
                    if ((elem & 0xffff_ff00) == 0) {
                        clamped = (byte) elem;
                    } else {
                        clamped = elem < 0 ? 0 : (byte) 0xff;
                    }
                    nb.put(index, clamped);
                }
            } catch (IndexOutOfBoundsException e) {
                throw new ClassCastException();
            }
        }

        @Override
        public boolean isClamped() {
            return true;
        }

        @Override
        public boolean isUnsigned() {
            return true;
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
            return set(index, JSType.toNumber(value));
        }

        @Override
        public ArrayData set(int index, int value) {
            setElem(index, value);
            return this;
        }

        @Override
        public ArrayData set(int index, double value) {
            return set(index, (int) rint(value));
        }

        private static double rint(double rint) {
            return (int)Math.rint(rint);
        }

        @SuppressWarnings("unused")
        private static Object rint(Object rint) {
            return rint(JSType.toNumber(rint));
        }

    }

    @Constructor(arity = 1)
    public static NativeUint8ClampedArray constructor(boolean newObj, Object self, Object... args) {
        return (NativeUint8ClampedArray)constructorImpl(newObj, args, FACTORY);
    }

    NativeUint8ClampedArray(NativeArrayBuffer buffer, int byteOffset, int length) {
        super(buffer, byteOffset, length);
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
    protected static NativeUint8ClampedArray subarray(Object self, Object begin, Object end) {
        return (NativeUint8ClampedArray)ArrayBufferView.subarrayImpl(self, begin, end);
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
        return global.getUint8ClampedArrayPrototype();
    }

}
