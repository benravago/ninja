/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.Property;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.SpecializedFunction;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import static nashorn.internal.runtime.ECMAErrors.rangeError;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;


/**
 * DataView builtin constructor.
 *
 * Based on the specification here: http://www.khronos.org/registry/typedarray/specs/latest/#8
 * <p>
 * An ArrayBuffer is a useful object for representing an arbitrary chunk of data.
 * In many cases, such data will be read from disk or from the network, and will not follow the alignment restrictions that are imposed on the typed array views described earlier.
 * In addition, the data will often be heterogeneous in nature and have a defined byte order. The DataView view provides a low-level interface for reading such data from and writing it to an ArrayBuffer.
 * <p>
 * Regardless of the host computer's endianness, DataView reads or writes values to or from main memory with a specified endianness: big or little.
 */
@ScriptClass("DataView")
public class NativeDataView extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    // inherited ArrayBufferView properties

    /**
     * Underlying ArrayBuffer storage object
     */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT)
    public final Object buffer;

    /**
     * The offset in bytes from the start of the ArrayBuffer
     */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT)
    public final int byteOffset;

    /**
     * The number of bytes from the offset that this DataView will reference
     */
    @Property(attributes = Attribute.NON_ENUMERABLE_CONSTANT)
    public final int byteLength;

    // underlying ByteBuffer
    private final ByteBuffer buf;

    private NativeDataView(NativeArrayBuffer arrBuf) {
        this(arrBuf, arrBuf.getBuffer(), 0);
    }

    private NativeDataView(NativeArrayBuffer arrBuf, int offset) {
        this(arrBuf, bufferFrom(arrBuf, offset), offset);
    }

    private NativeDataView(NativeArrayBuffer arrBuf, int offset, int length) {
        this(arrBuf, bufferFrom(arrBuf, offset, length), offset, length);
    }

    private NativeDataView(NativeArrayBuffer arrBuf, ByteBuffer buf, int offset) {
       this(arrBuf, buf, offset, buf.capacity() - offset);
    }

    private NativeDataView(NativeArrayBuffer arrBuf, ByteBuffer buf, int offset, int length) {
        super(Global.instance().getDataViewPrototype(), $nasgenmap$);
        this.buffer = arrBuf;
        this.byteOffset = offset;
        this.byteLength = length;
        this.buf = buf;
    }

    /**
     * Create a new DataView object using the passed ArrayBuffer for its storage.
     * Optional byteOffset and byteLength can be used to limit the section of the buffer referenced.
     * The byteOffset indicates the offset in bytes from the start of the ArrayBuffer, and the byteLength is the number of bytes from the offset that this DataView will reference.
     * If both byteOffset and byteLength are omitted, the DataView spans the entire ArrayBuffer range.
     * If the byteLength is omitted, the DataView extends from the given byteOffset until the end of the ArrayBuffer.
     * If the given byteOffset and byteLength references an area beyond the end of the ArrayBuffer an exception is raised.
     */
    @Constructor(arity = 1)
    public static NativeDataView constructor(boolean newObj, Object self, Object... args) {
        if (args.length == 0 || !(args[0] instanceof NativeArrayBuffer)) {
            throw typeError("not.an.arraybuffer.in.dataview");
        }

        var arrBuf = (NativeArrayBuffer)args[0];
        return switch (args.length) {
            case 1 -> new NativeDataView(arrBuf);
            case 2 -> new NativeDataView(arrBuf, JSType.toInt32(args[1]));
            default -> new NativeDataView(arrBuf, JSType.toInt32(args[1]), JSType.toInt32(args[2]));
        };
    }

    /**
     * Specialized version of DataView constructor
     */
    @SpecializedFunction(isConstructor=true)
    public static NativeDataView constructor(boolean newObj, Object self, Object arrBuf, int offset) {
        if (!(arrBuf instanceof NativeArrayBuffer)) {
            throw typeError("not.an.arraybuffer.in.dataview");
        }
        return new NativeDataView((NativeArrayBuffer) arrBuf, offset);
    }

    /**
     * Specialized version of DataView constructor
     */
    @SpecializedFunction(isConstructor=true)
    public static NativeDataView constructor(boolean newObj, Object self, Object arrBuf, int offset, int length) {
        if (!(arrBuf instanceof NativeArrayBuffer)) {
            throw typeError("not.an.arraybuffer.in.dataview");
        }
        return new NativeDataView((NativeArrayBuffer) arrBuf, offset, length);
    }

    // Gets the value of the given type at the specified byte offset from the start of the view.
    // There is no alignment constraint; multi-byte values may be fetched from any offset.
    // For multi-byte values, the optional littleEndian argument indicates whether a big-endian or little-endian value should be read.
    // If false or undefined, a big-endian value is read.
    // These methods raise an exception if they would read beyond the end of the view.

    /**
     * Get 8-bit signed int from given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static int getInt8(Object self, Object byteOffset) {
        try {
            return getBuffer(self).get(JSType.toInt32(byteOffset));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 8-bit signed int from given byteOffset
     */
    @SpecializedFunction
    public static int getInt8(Object self, int byteOffset) {
        try {
            return getBuffer(self).get(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 8-bit unsigned int from given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static int getUint8(Object self, Object byteOffset) {
        try {
            return 0xFF & getBuffer(self).get(JSType.toInt32(byteOffset));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 8-bit unsigned int from given byteOffset
     */
    @SpecializedFunction
    public static int getUint8(Object self, int byteOffset) {
        try {
            return 0xFF & getBuffer(self).get(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 16-bit signed int from given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static int getInt16(Object self, Object byteOffset, Object littleEndian) {
        try {
            return getBuffer(self, littleEndian).getShort(JSType.toInt32(byteOffset));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 16-bit signed int from given byteOffset
     */
    @SpecializedFunction
    public static int getInt16(Object self, int byteOffset) {
        try {
            return getBuffer(self, false).getShort(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 16-bit signed int from given byteOffset
     */
    @SpecializedFunction
    public static int getInt16(Object self, int byteOffset, boolean littleEndian) {
        try {
            return getBuffer(self, littleEndian).getShort(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 16-bit unsigned int from given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static int getUint16(Object self, Object byteOffset, Object littleEndian) {
        try {
            return 0xFFFF & getBuffer(self, littleEndian).getShort(JSType.toInt32(byteOffset));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 16-bit unsigned int from given byteOffset
     */
    @SpecializedFunction
    public static int getUint16(Object self, int byteOffset) {
        try {
            return 0xFFFF & getBuffer(self, false).getShort(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 16-bit unsigned int from given byteOffset
     */
    @SpecializedFunction
    public static int getUint16(Object self, int byteOffset, boolean littleEndian) {
        try {
            return 0xFFFF & getBuffer(self, littleEndian).getShort(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 32-bit signed int from given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static int getInt32(Object self, Object byteOffset, Object littleEndian) {
        try {
            return getBuffer(self, littleEndian).getInt(JSType.toInt32(byteOffset));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 32-bit signed int from given byteOffset
     */
    @SpecializedFunction
    public static int getInt32(Object self, int byteOffset) {
        try {
            return getBuffer(self, false).getInt(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 32-bit signed int from given byteOffset
     */
    @SpecializedFunction
    public static int getInt32(Object self, int byteOffset, boolean littleEndian) {
        try {
            return getBuffer(self, littleEndian).getInt(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 32-bit unsigned int from given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static double getUint32(Object self, Object byteOffset, Object littleEndian) {
        try {
            return 0xFFFFFFFFL & getBuffer(self, littleEndian).getInt(JSType.toInt32(byteOffset));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 32-bit unsigned int from given byteOffset
     */
    @SpecializedFunction
    public static double getUint32(Object self, int byteOffset) {
        try {
            return JSType.toUint32(getBuffer(self, false).getInt(JSType.toInt32(byteOffset)));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 32-bit unsigned int from given byteOffset
     */
    @SpecializedFunction
    public static double getUint32(Object self, int byteOffset, boolean littleEndian) {
        try {
            return JSType.toUint32(getBuffer(self, littleEndian).getInt(JSType.toInt32(byteOffset)));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 32-bit float value from given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static double getFloat32(Object self, Object byteOffset, Object littleEndian) {
        try {
            return getBuffer(self, littleEndian).getFloat(JSType.toInt32(byteOffset));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 32-bit float value from given byteOffset
     */
    @SpecializedFunction
    public static double getFloat32(Object self, int byteOffset) {
        try {
            return getBuffer(self, false).getFloat(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 32-bit float value from given byteOffset
     */
    @SpecializedFunction
    public static double getFloat32(Object self, int byteOffset, boolean littleEndian) {
        try {
            return getBuffer(self, littleEndian).getFloat(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 64-bit float value from given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static double getFloat64(Object self, Object byteOffset, Object littleEndian) {
        try {
            return getBuffer(self, littleEndian).getDouble(JSType.toInt32(byteOffset));
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 64-bit float value from given byteOffset
     */
    @SpecializedFunction
    public static double getFloat64(Object self, int byteOffset) {
        try {
            return getBuffer(self, false).getDouble(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Get 64-bit float value from given byteOffset
     */
    @SpecializedFunction
    public static double getFloat64(Object self, int byteOffset, boolean littleEndian) {
        try {
            return getBuffer(self, littleEndian).getDouble(byteOffset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    // Stores a value of the given type at the specified byte offset from the start of the view.
    // There is no alignment constraint; multi-byte values may be stored at any offset.
    // For multi-byte values, the optional littleEndian argument indicates whether the value should be stored in big-endian or little-endian byte order.
    // If false or undefined, the value is stored in big-endian byte order.
    // These methods raise an exception if they would write beyond the end of the view.

    /**
     * Set 8-bit signed int at the given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 2)
    public static Object setInt8(Object self, Object byteOffset, Object value) {
        try {
            getBuffer(self).put(JSType.toInt32(byteOffset), (byte)JSType.toInt32(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 8-bit signed int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setInt8(Object self, int byteOffset, int value) {
        try {
            getBuffer(self).put(byteOffset, (byte)value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 8-bit unsigned int at the given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 2)
    public static Object setUint8(Object self, Object byteOffset, Object value) {
        try {
            getBuffer(self).put(JSType.toInt32(byteOffset), (byte)JSType.toInt32(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 8-bit unsigned int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setUint8(Object self, int byteOffset, int value) {
        try {
            getBuffer(self).put(byteOffset, (byte)value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 16-bit signed int at the given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 2)
    public static Object setInt16(Object self, Object byteOffset, Object value, Object littleEndian) {
        try {
            getBuffer(self, littleEndian).putShort(JSType.toInt32(byteOffset), (short)JSType.toInt32(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 16-bit signed int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setInt16(Object self, int byteOffset, int value) {
        try {
            getBuffer(self, false).putShort(byteOffset, (short)value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 16-bit signed int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setInt16(Object self, int byteOffset, int value, boolean littleEndian) {
        try {
            getBuffer(self, littleEndian).putShort(byteOffset, (short)value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 16-bit unsigned int at the given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 2)
    public static Object setUint16(Object self, Object byteOffset, Object value, Object littleEndian) {
        try {
            getBuffer(self, littleEndian).putShort(JSType.toInt32(byteOffset), (short)JSType.toInt32(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 16-bit unsigned int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setUint16(Object self, int byteOffset, int value) {
        try {
            getBuffer(self, false).putShort(byteOffset, (short)value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 16-bit unsigned int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setUint16(Object self, int byteOffset, int value, boolean littleEndian) {
        try {
            getBuffer(self, littleEndian).putShort(byteOffset, (short)value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 32-bit signed int at the given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 2)
    public static Object setInt32(Object self, Object byteOffset, Object value, Object littleEndian) {
        try {
            getBuffer(self, littleEndian).putInt(JSType.toInt32(byteOffset), JSType.toInt32(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 32-bit signed int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setInt32(Object self, int byteOffset, int value) {
        try {
            getBuffer(self, false).putInt(byteOffset, value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 32-bit signed int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setInt32(Object self, int byteOffset, int value, boolean littleEndian) {
        try {
            getBuffer(self, littleEndian).putInt(byteOffset, value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 32-bit unsigned int at the given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 2)
    public static Object setUint32(Object self, Object byteOffset, Object value, Object littleEndian) {
        try {
            getBuffer(self, littleEndian).putInt(JSType.toInt32(byteOffset), (int)JSType.toUint32(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 32-bit unsigned int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setUint32(Object self, int byteOffset, double value) {
        try {
            getBuffer(self, false).putInt(byteOffset, (int) JSType.toUint32(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 32-bit unsigned int at the given byteOffset
     */
    @SpecializedFunction
    public static Object setUint32(Object self, int byteOffset, double value, boolean littleEndian) {
        try {
            getBuffer(self, littleEndian).putInt(byteOffset, (int) JSType.toUint32(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 32-bit float at the given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 2)
    public static Object setFloat32(Object self, Object byteOffset, Object value, Object littleEndian) {
        try {
            getBuffer(self, littleEndian).putFloat((int)JSType.toUint32(byteOffset), (float)JSType.toNumber(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 32-bit float at the given byteOffset
     */
    @SpecializedFunction
    public static Object setFloat32(Object self, int byteOffset, double value) {
        try {
            getBuffer(self, false).putFloat(byteOffset, (float)value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 32-bit float at the given byteOffset
     */
    @SpecializedFunction
    public static Object setFloat32(Object self, int byteOffset, double value, boolean littleEndian) {
        try {
            getBuffer(self, littleEndian).putFloat(byteOffset, (float)value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 64-bit float at the given byteOffset
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 2)
    public static Object setFloat64(Object self, Object byteOffset, Object value, Object littleEndian) {
        try {
            getBuffer(self, littleEndian).putDouble((int)JSType.toUint32(byteOffset), JSType.toNumber(value));
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 64-bit float at the given byteOffset
     */
    @SpecializedFunction
    public static Object setFloat64(Object self, int byteOffset, double value) {
        try {
            getBuffer(self, false).putDouble(byteOffset, value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    /**
     * Set 64-bit float at the given byteOffset
     */
    @SpecializedFunction
    public static Object setFloat64(Object self, int byteOffset, double value, boolean littleEndian) {
        try {
            getBuffer(self, littleEndian).putDouble(byteOffset, value);
            return UNDEFINED;
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.offset");
        }
    }

    // internals only below this point

    private static ByteBuffer bufferFrom(NativeArrayBuffer nab, int offset) {
        try {
            return nab.getBuffer(offset);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.constructor.offset");
        }
    }

    private static ByteBuffer bufferFrom(NativeArrayBuffer nab, int offset, int length) {
        try {
            return nab.getBuffer(offset, length);
        } catch (IllegalArgumentException iae) {
            throw rangeError(iae, "dataview.constructor.offset");
        }
    }

    private static NativeDataView checkSelf(Object self) {
        if (!(self instanceof NativeDataView)) {
            throw typeError("not.an.arraybuffer.in.dataview", ScriptRuntime.safeToString(self));
        }
        return (NativeDataView)self;
    }

    private static ByteBuffer getBuffer(Object self) {
        return checkSelf(self).buf;
    }

    private static ByteBuffer getBuffer(Object self, Object littleEndian) {
        return getBuffer(self, JSType.toBoolean(littleEndian));
    }

    private static ByteBuffer getBuffer(Object self, boolean littleEndian) {
        return getBuffer(self).order(littleEndian? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    }

}
