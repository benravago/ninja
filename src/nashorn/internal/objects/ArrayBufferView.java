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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import jdk.dynalink.CallSiteDescriptor;
import jdk.dynalink.linker.GuardedInvocation;
import jdk.dynalink.linker.LinkRequest;

import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Getter;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.arrays.ArrayData;
import nashorn.internal.runtime.arrays.TypedArrayData;
import static nashorn.internal.runtime.ECMAErrors.rangeError;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.UnwarrantedOptimismException.INVALID_PROGRAM_POINT;

/**
 * ArrayBufferView, es6 class or TypedArray implementation
 */
@ScriptClass("ArrayBufferView")
public abstract class ArrayBufferView extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private final NativeArrayBuffer buffer;
    private final int byteOffset;

    private ArrayBufferView(NativeArrayBuffer buffer, int byteOffset, int elementLength, Global global) {
        super($nasgenmap$);

        var bytesPerElement = bytesPerElement();

        checkConstructorArgs(buffer.getByteLength(), bytesPerElement, byteOffset, elementLength);
        setProto(getPrototype(global));

        this.buffer     = buffer;
        this.byteOffset = byteOffset;

        assert byteOffset % bytesPerElement == 0;
        var start = byteOffset / bytesPerElement;
        var newNioBuffer = buffer.getNioBuffer().duplicate().order(ByteOrder.nativeOrder());
        var data = factory().createArrayData(newNioBuffer, start, start + elementLength);

        setArray(data);
    }

    /**
     * Constructor
     */
    protected ArrayBufferView(NativeArrayBuffer buffer, int byteOffset, int elementLength) {
        this(buffer, byteOffset, elementLength, Global.instance());
    }

    private static void checkConstructorArgs(int byteLength, int bytesPerElement, int byteOffset, int elementLength) {
        if (byteOffset < 0 || elementLength < 0) {
            throw new IllegalArgumentException("byteOffset or length must not be negative, byteOffset=" + byteOffset + ", elementLength=" + elementLength + ", bytesPerElement=" + bytesPerElement);
        } else if (byteOffset + elementLength * bytesPerElement > byteLength) {
            throw new IllegalArgumentException("byteOffset + byteLength out of range, byteOffset=" + byteOffset + ", elementLength=" + elementLength + ", bytesPerElement=" + bytesPerElement);
        } else if (byteOffset % bytesPerElement != 0) {
            throw new IllegalArgumentException("byteOffset must be a multiple of the element size, byteOffset=" + byteOffset + " bytesPerElement=" + bytesPerElement);
        }
    }

    private int bytesPerElement() {
        return factory().bytesPerElement;
    }

    /**
     * Buffer getter as per spec
     */
    @Getter(attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_WRITABLE | Attribute.NOT_CONFIGURABLE)
    public static Object buffer(Object self) {
        return ((ArrayBufferView)self).buffer;
    }

    /**
     * Buffer offset getter as per spec
     */
    @Getter(attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_WRITABLE | Attribute.NOT_CONFIGURABLE)
    public static int byteOffset(Object self) {
        return ((ArrayBufferView)self).byteOffset;
    }

    /**
     * Byte length getter as per spec
     */
    @Getter(attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_WRITABLE | Attribute.NOT_CONFIGURABLE)
    public static int byteLength(Object self) {
        final ArrayBufferView view = (ArrayBufferView)self;
        return ((TypedArrayData<?>)view.getArray()).getElementLength() * view.bytesPerElement();
    }

    /**
     * Length getter as per spec
     */
    @Getter(attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_WRITABLE | Attribute.NOT_CONFIGURABLE)
    public static int length(Object self) {
        return ((ArrayBufferView)self).elementLength();
    }

    @Override
    public final Object getLength() {
        return elementLength();
    }

    private int elementLength() {
        return ((TypedArrayData<?>)getArray()).getElementLength();
    }

    /**
     * Factory class for byte ArrayBufferViews
     */
    protected static abstract class Factory {

        final int bytesPerElement; // number of bytes per element for this buffer
        final int maxElementLength;

        /**
         * Constructor
         */
        public Factory(int bytesPerElement) {
            this.bytesPerElement  = bytesPerElement;
            this.maxElementLength = Integer.MAX_VALUE / bytesPerElement;
        }

        /**
         * Factory method
         */
        public final ArrayBufferView construct(int elementLength) {
            if (elementLength > maxElementLength) {
                throw rangeError("inappropriate.array.buffer.length", JSType.toString(elementLength));
            }
            return construct(new NativeArrayBuffer(elementLength * bytesPerElement), 0, elementLength);
        }

        /**
         * Factory method
         */
        public abstract ArrayBufferView construct(NativeArrayBuffer buffer, int byteOffset, int elementLength);

        /**
         * Factory method for array data
         */
        public abstract TypedArrayData<?> createArrayData(ByteBuffer nb, int start, int end);

        /**
         * Get the class name for this type of buffer
         */
        public abstract String getClassName();
    }

    /**
     * Get the factor for this kind of buffer
     */
    protected abstract Factory factory();

    /**
     * Get the prototype for this ArrayBufferView
     */
    protected abstract ScriptObject getPrototype(Global global);

    @Override
    public final String getClassName() {
        return factory().getClassName();
    }

    /**
     * Check if this array contains floats
     */
    protected boolean isFloatArray() {
        return false;
    }

    /**
     * Inheritable constructor implementation
     */
    protected static ArrayBufferView constructorImpl(boolean newObj, Object[] args, Factory factory) {
        var arg0 = args.length != 0 ? args[0] : 0;
        ArrayBufferView dest;
        int length;

        if (!newObj) {
            throw typeError("constructor.requires.new", factory.getClassName());
        }


        if (arg0 instanceof NativeArrayBuffer) {
            // Constructor(ArrayBuffer buffer, optional unsigned long byteOffset, optional unsigned long length)
            var buffer = (NativeArrayBuffer)arg0;
            var byteOffset = args.length > 1 ? JSType.toInt32(args[1]) : 0;

            if (args.length > 2) {
                length = JSType.toInt32(args[2]);
            } else {
                if ((buffer.getByteLength() - byteOffset) % factory.bytesPerElement != 0) {
                    throw new IllegalArgumentException("buffer.byteLength - byteOffset must be a multiple of the element size");
                }
                length = (buffer.getByteLength() - byteOffset) / factory.bytesPerElement;
            }

            return factory.construct(buffer, byteOffset, length);
        } else if (arg0 instanceof ArrayBufferView) {
            // Constructor(TypedArray array)
            length = ((ArrayBufferView)arg0).elementLength();
            dest = factory.construct(length);
        } else if (arg0 instanceof NativeArray) {
            // Constructor(type[] array)
            length = lengthToInt(((NativeArray) arg0).getArray().length());
            dest = factory.construct(length);
        } else {
            // Constructor(unsigned long length). Treating infinity as 0 is a special case for ArrayBufferView.
            var dlen = JSType.toNumber(arg0);
            length = lengthToInt(Double.isInfinite(dlen) ? 0L : JSType.toLong(dlen));
            return factory.construct(length);
        }

        copyElements(dest, length, (ScriptObject)arg0, 0);

        return dest;
    }

    /**
     * Inheritable implementation of set, if no efficient implementation is available
     */
    protected static Object setImpl(Object self, Object array, Object offset0) {
        var dest = (ArrayBufferView)self;
        int length;
        if (array instanceof ArrayBufferView) {
            // void set(TypedArray array, optional unsigned long offset)
            length = ((ArrayBufferView)array).elementLength();
        } else if (array instanceof NativeArray) {
            // void set(type[] array, optional unsigned long offset)
            length = (int) (((NativeArray) array).getArray().length() & 0x7fff_ffff);
        } else {
            throw new IllegalArgumentException("argument is not of array type");
        }

        var source = (ScriptObject)array;
        var offset = JSType.toInt32(offset0); // default=0

        if (dest.elementLength() < length + offset || offset < 0) {
            throw new IllegalArgumentException("offset or array length out of bounds");
        }

        copyElements(dest, length, source, offset);

        return ScriptRuntime.UNDEFINED;
    }

    private static void copyElements(ArrayBufferView dest, int length, ScriptObject source, int offset) {
        if (!dest.isFloatArray()) {
            for (int i = 0, j = offset; i < length; i++, j++) {
                dest.set(j, source.getInt(i, INVALID_PROGRAM_POINT), 0);
            }
        } else {
            for (int i = 0, j = offset; i < length; i++, j++) {
                dest.set(j, source.getDouble(i, INVALID_PROGRAM_POINT), 0);
            }
        }
    }

    private static int lengthToInt(long length) {
        if (length > Integer.MAX_VALUE || length < 0) {
            throw rangeError("inappropriate.array.buffer.length", JSType.toString(length));
        }
        return (int)(length & Integer.MAX_VALUE);
    }

    /**
     * Implementation of subarray if no efficient override exists.
     * <p>
     * Each Native<T>Array subclass has a 'static T subarray(self,begin,end)' implementation.
     * The function returns a new TypedArray view of the ArrayBuffer store for this TypedArray, referencing the elements at begin, inclusive, up to end, exclusive.
     * If either begin or end is negative, it refers to an index from the end of the array, as opposed to from the beginning.
     * <p>
     * If end is unspecified, the subarray contains all elements from begin to the end of the TypedArray.
     * The range specified by the begin and end values is clamped to the valid index range for the current array.
     * If the computed length of the new TypedArray would be negative, it is clamped to zero.
     * <p>
     * The returned TypedArray will be of the same type as the array on which this method is invoked.
     */
    protected static ScriptObject subarrayImpl(Object self, Object begin0, Object end0) {
        var arrayView = (ArrayBufferView)self;
        var byteOffset = arrayView.byteOffset;
        var bytesPerElement = arrayView.bytesPerElement();
        var elementLength = arrayView.elementLength();
        var begin = NativeArrayBuffer.adjustIndex(JSType.toInt32(begin0), elementLength);
        var end = NativeArrayBuffer.adjustIndex(end0 != ScriptRuntime.UNDEFINED ? JSType.toInt32(end0) : elementLength, elementLength);
        var length = Math.max(end - begin, 0);

        assert byteOffset % bytesPerElement == 0;

        // second is byteoffset
        return arrayView.factory().construct(arrayView.buffer, begin * bytesPerElement + byteOffset, length);
    }

    @Override
    protected GuardedInvocation findGetIndexMethod(CallSiteDescriptor desc, LinkRequest request) {
        var inv = getArray().findFastGetIndexMethod(getArray().getClass(), desc, request);
        return inv != null ? inv : super.findGetIndexMethod(desc, request);
    }

    @Override
    protected GuardedInvocation findSetIndexMethod(CallSiteDescriptor desc, LinkRequest request) {
        var inv = getArray().findFastSetIndexMethod(getArray().getClass(), desc, request);
        return inv != null ? inv : super.findSetIndexMethod(desc, request);
    }

}
