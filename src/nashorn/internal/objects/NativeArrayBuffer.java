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

import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.Getter;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.SpecializedFunction;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import static nashorn.internal.runtime.ECMAErrors.typeError;

/**
 * NativeArrayBuffer - ArrayBuffer as described in the JS typed array spec
 */
@ScriptClass("ArrayBuffer")
public final class NativeArrayBuffer extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private final ByteBuffer nb;

    /**
     * Constructor
     */
    protected NativeArrayBuffer(ByteBuffer nb, Global global) {
        super(global.getArrayBufferPrototype(), $nasgenmap$);
        this.nb = nb;
    }

    /**
     * Constructor
     */
    protected NativeArrayBuffer(ByteBuffer nb) {
        this(nb, Global.instance());
    }

    /**
     * Constructor
     */
    protected NativeArrayBuffer(int byteLength) {
        this(ByteBuffer.allocateDirect(byteLength));
    }

    /**
     * Clone constructor.
     * Used only for slice
     */
    protected NativeArrayBuffer(NativeArrayBuffer other, int begin, int end) {
        this(cloneBuffer(other.getNioBuffer(), begin, end));
    }

    /**
     * Constructor
     */
    @Constructor(arity = 1)
    public static NativeArrayBuffer constructor(boolean newObj, Object self, Object... args) {
        if (!newObj) {
            throw typeError("constructor.requires.new", "ArrayBuffer");
        }

        if (args.length == 0) {
            return new NativeArrayBuffer(0);
        }

        var arg0 = args[0];
        if (arg0 instanceof ByteBuffer) {
            return new NativeArrayBuffer((ByteBuffer)arg0);
        } else {
            return new NativeArrayBuffer(JSType.toInt32(arg0));
        }
    }

    private static ByteBuffer cloneBuffer(ByteBuffer original, int begin, int end) {
        var clone = ByteBuffer.allocateDirect(original.capacity());
        original.rewind(); // copy from the beginning
        clone.put(original);
        original.rewind();
        return clone.flip().position(begin).limit(end).slice();
    }

    ByteBuffer getNioBuffer() {
        return nb;
    }

    @Override
    public String getClassName() {
        return "ArrayBuffer";
    }

    /**
     * Byte length for native array buffer
     */
    @Getter(attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_WRITABLE | Attribute.NOT_CONFIGURABLE)
    public static int byteLength(Object self) {
        return ((NativeArrayBuffer)self).getByteLength();
    }

    /**
     * Returns true if an object is an ArrayBufferView
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static boolean isView(Object self, Object obj) {
        return obj instanceof ArrayBufferView;
    }

    /**
     * Slice function
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static NativeArrayBuffer slice(Object self, Object begin0, Object end0) {
        var arrayBuffer = (NativeArrayBuffer)self;
        var byteLength = arrayBuffer.getByteLength();
        var begin = adjustIndex(JSType.toInt32(begin0), byteLength);
        var end = adjustIndex(end0 != ScriptRuntime.UNDEFINED ? JSType.toInt32(end0) : byteLength, byteLength);
        return new NativeArrayBuffer(arrayBuffer, begin, Math.max(end, begin));
    }

    /**
     * Specialized slice function
     */
    @SpecializedFunction
    public static Object slice(Object self, int begin, int end) {
        var arrayBuffer = (NativeArrayBuffer)self;
        var byteLength = arrayBuffer.getByteLength();
        return new NativeArrayBuffer(arrayBuffer, adjustIndex(begin, byteLength), Math.max(adjustIndex(end, byteLength), begin));
    }

    /**
     * Specialized slice function
     */
    @SpecializedFunction
    public static Object slice(Object self, int begin) {
        return slice(self, begin, ((NativeArrayBuffer)self).getByteLength());
    }

    /**
     * If index is negative, it refers to an index from the end of the array, as opposed to from the beginning.
     * The index is clamped to the valid index range for the array.
     */
    static int adjustIndex(int index, int length) {
        return index < 0 ? clamp(index + length, length) : clamp(index, length);
    }

    /**
     * Clamp index into the range [0, length).
     */
    private static int clamp(int index, int length) {
        if (index < 0) {
            return 0;
        } else if (index > length) {
            return length;
        }
        return index;
    }

    int getByteLength() {
        return nb.limit();
    }

    ByteBuffer getBuffer() {
       return nb;
    }

    ByteBuffer getBuffer(int offset) {
        return nb.duplicate().position(offset);
    }

    ByteBuffer getBuffer(int offset, int length) {
        return getBuffer(offset).limit(length);
    }

}
