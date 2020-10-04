/*
 * Copyright (c) 2010, 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import jdk.dynalink.CallSiteDescriptor;
import jdk.dynalink.linker.GuardedInvocation;
import jdk.dynalink.linker.LinkRequest;

import nashorn.api.scripting.JSObject;
import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.Getter;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.Setter;
import nashorn.internal.objects.annotations.SpecializedFunction;
import nashorn.internal.objects.annotations.SpecializedFunction.LinkLogic;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.Context;
import nashorn.internal.Util;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.OptimisticBuiltins;
import nashorn.internal.runtime.PropertyDescriptor;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.Undefined;
import nashorn.internal.runtime.arrays.ArrayData;
import nashorn.internal.runtime.arrays.ArrayIndex;
import nashorn.internal.runtime.arrays.ArrayLikeIterator;
import nashorn.internal.runtime.arrays.ContinuousArrayData;
import nashorn.internal.runtime.arrays.IntElements;
import nashorn.internal.runtime.arrays.IteratorAction;
import nashorn.internal.runtime.arrays.NumericElements;
import nashorn.internal.runtime.linker.Bootstrap;
import nashorn.internal.runtime.linker.InvokeByName;
import static nashorn.internal.runtime.ECMAErrors.rangeError;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.PropertyDescriptor.VALUE;
import static nashorn.internal.runtime.PropertyDescriptor.WRITABLE;
import static nashorn.internal.runtime.arrays.ArrayIndex.isValidArrayIndex;
import static nashorn.internal.runtime.arrays.ArrayLikeIterator.arrayLikeIterator;
import static nashorn.internal.runtime.arrays.ArrayLikeIterator.reverseArrayLikeIterator;

/**
 * Runtime representation of a JavaScript array.
 *
 * NativeArray only holds numeric keyed values.
 * All other values are stored in spill.
 */
@ScriptClass("Array")
public final class NativeArray extends ScriptObject implements OptimisticBuiltins {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private static final Object JOIN                     = new Object();
    private static final Object EVERY_CALLBACK_INVOKER   = new Object();
    private static final Object SOME_CALLBACK_INVOKER    = new Object();
    private static final Object FOREACH_CALLBACK_INVOKER = new Object();
    private static final Object MAP_CALLBACK_INVOKER     = new Object();
    private static final Object FILTER_CALLBACK_INVOKER  = new Object();
    private static final Object REDUCE_CALLBACK_INVOKER  = new Object();
    private static final Object CALL_CMP                 = new Object();
    private static final Object TO_LOCALE_STRING         = new Object();

    /*
     * Constructors.
     */
    NativeArray() {
        this(ArrayData.initialArray());
    }

    NativeArray(long length) {
        this(ArrayData.allocate(length));
    }

    NativeArray(int[] array) {
        this(ArrayData.allocate(array));
    }

    NativeArray(double[] array) {
        this(ArrayData.allocate(array));
    }

    NativeArray(long[] array) {
        this(ArrayData.allocate(array.length));

        var arrayData = this.getArray();
        Class<?> widest = int.class;

        for (var index = 0; index < array.length; index++) {
            var value = array[index];

            if (widest == int.class && JSType.isRepresentableAsInt(value)) {
                arrayData = arrayData.set(index, (int) value); // false
            } else if (widest != Object.class && JSType.isRepresentableAsDouble(value)) {
                arrayData = arrayData.set(index, (double) value); // false
                widest = double.class;
            } else {
                arrayData = arrayData.set(index, (Object) value); // false
                widest = Object.class;
            }
        }

        this.setArray(arrayData);
    }

    NativeArray(Object[] array) {
        this(ArrayData.allocate(array.length));

        var arrayData = this.getArray();

        for (var index = 0; index < array.length; index++) {
            var value = array[index];

            if (value == ScriptRuntime.EMPTY) {
                arrayData = arrayData.delete(index);
            } else {
                arrayData = arrayData.set(index, value); // false
            }
        }

        this.setArray(arrayData);
    }

    NativeArray(ArrayData arrayData) {
        this(arrayData, Global.instance());
    }

    NativeArray(ArrayData arrayData, Global global) {
        super(global.getArrayPrototype(), $nasgenmap$);
        setArray(arrayData);
        setIsArray();
    }

    @Override
    protected GuardedInvocation findGetIndexMethod(CallSiteDescriptor desc, LinkRequest request) {
        var inv = getArray().findFastGetIndexMethod(getArray().getClass(), desc, request);
        if (inv != null) {
            return inv;
        }
        return super.findGetIndexMethod(desc, request);
    }

    @Override
    protected GuardedInvocation findSetIndexMethod(CallSiteDescriptor desc, LinkRequest request) {
        var inv = getArray().findFastSetIndexMethod(getArray().getClass(), desc, request);
        if (inv != null) {
            return inv;
        }
        return super.findSetIndexMethod(desc, request);
    }

    private static InvokeByName getJOIN() {
        return Global.instance().getInvokeByName(JOIN,
            new Callable<InvokeByName>() {
                @Override
                public InvokeByName call() {
                    return new InvokeByName("join", ScriptObject.class);
                }
            });
    }

    private static MethodHandle createIteratorCallbackInvoker(Object key, Class<?> rtype) {
        return Global.instance().getDynamicInvoker(key,
            new Callable<MethodHandle>() {
                @Override
                public MethodHandle call() {
                    return Bootstrap.createDynamicCallInvoker(rtype, Object.class, Object.class, Object.class, double.class, Object.class);
                }
            });
    }

    private static MethodHandle getEVERY_CALLBACK_INVOKER() {
        return createIteratorCallbackInvoker(EVERY_CALLBACK_INVOKER, boolean.class);
    }

    private static MethodHandle getSOME_CALLBACK_INVOKER() {
        return createIteratorCallbackInvoker(SOME_CALLBACK_INVOKER, boolean.class);
    }

    private static MethodHandle getFOREACH_CALLBACK_INVOKER() {
        return createIteratorCallbackInvoker(FOREACH_CALLBACK_INVOKER, void.class);
    }

    private static MethodHandle getMAP_CALLBACK_INVOKER() {
        return createIteratorCallbackInvoker(MAP_CALLBACK_INVOKER, Object.class);
    }

    private static MethodHandle getFILTER_CALLBACK_INVOKER() {
        return createIteratorCallbackInvoker(FILTER_CALLBACK_INVOKER, boolean.class);
    }

    private static MethodHandle getREDUCE_CALLBACK_INVOKER() {
        return Global.instance().getDynamicInvoker(REDUCE_CALLBACK_INVOKER,
            new Callable<MethodHandle>() {
                @Override
                public MethodHandle call() {
                    return Bootstrap.createDynamicCallInvoker(Object.class, Object.class, Undefined.class, Object.class, Object.class, double.class, Object.class);
                }
            });
    }

    private static MethodHandle getCALL_CMP() {
        return Global.instance().getDynamicInvoker(CALL_CMP,
            new Callable<MethodHandle>() {
                @Override
                public MethodHandle call() {
                    return Bootstrap.createDynamicCallInvoker(double.class, Object.class, Object.class, Object.class, Object.class);
                }
            });
    }

    private static InvokeByName getTO_LOCALE_STRING() {
        return Global.instance().getInvokeByName(TO_LOCALE_STRING,
            new Callable<InvokeByName>() {
                @Override
                public InvokeByName call() {
                    return new InvokeByName("toLocaleString", ScriptObject.class, String.class);
                }
            });
    }

    @Override
    public String getClassName() {
        return "Array";
    }

    @Override
    public Object getLength() {
        var length = getArray().length();
        assert length >= 0L;
        if (length <= Integer.MAX_VALUE) {
            return (int)length;
        }
        return length;
    }

    private boolean defineLength(long oldLen, PropertyDescriptor oldLenDesc, PropertyDescriptor desc, boolean reject) {

        // Step 3a
        if (!desc.has(VALUE)) {
            return super.defineOwnProperty("length", desc, reject);
        }

        // Step 3b
        var newLenDesc = desc;

        // Step 3c and 3d - get new length and convert to long
        var newLen = NativeArray.validLength(newLenDesc.getValue());

        // Step 3e - note that we need to convert to int or double as long is not considered a JS number type anymore
        newLenDesc.setValue(JSType.toNarrowestNumber(newLen));

        // Step 3f - increasing array length - just need to set new length value (and attributes if any) and return
        if (newLen >= oldLen) {
            return super.defineOwnProperty("length", newLenDesc, reject);
        }

        // Step 3g
        if (!oldLenDesc.isWritable()) {
            if (reject) {
                throw typeError("property.not.writable", "length", ScriptRuntime.safeToString(this));
            }
            return false;
        }

        // Step 3h and 3i
        var newWritable = !newLenDesc.has(WRITABLE) || newLenDesc.isWritable();
        if (!newWritable) {
            newLenDesc.setWritable(true);
        }

        // Step 3j and 3k
        var succeeded = super.defineOwnProperty("length", newLenDesc, reject);
        if (!succeeded) {
            return false;
        }

        // Step 3l - make sure that length is set till the point we can delete the old elements
        var o = oldLen;
        while (newLen < o) {
            o--;
            var deleteSucceeded = delete(o); // false
            if (!deleteSucceeded) {
                newLenDesc.setValue(o + 1);
                if (!newWritable) {
                    newLenDesc.setWritable(false);
                }
                super.defineOwnProperty("length", newLenDesc, false);
                if (reject) {
                    throw typeError("property.not.writable", "length", ScriptRuntime.safeToString(this));
                }
                return false;
            }
        }

        // Step 3m
        if (!newWritable) {
            // make 'length' property not writable
            var newDesc = Global.newEmptyInstance();
            newDesc.set(WRITABLE, false, 0);
            return super.defineOwnProperty("length", newDesc, false);
        }

        return true;
    }

    /**
     * ECMA 15.4.5.1 [[DefineOwnProperty]] ( P, Desc, Throw )
     */
    @Override
    public boolean defineOwnProperty(Object key, Object propertyDesc, boolean reject) {
        var desc = toPropertyDescriptor(Global.instance(), propertyDesc);

        // never be undefined as "length" is always defined and can't be deleted for arrays
        // Step 1
        var oldLenDesc = (PropertyDescriptor) super.getOwnPropertyDescriptor("length");

        // Step 2
        // get old length and convert to long. Always a Long/Uint32 but we take the safe road.
        var oldLen = JSType.toUint32(oldLenDesc.getValue());

        // Step 3
        if ("length".equals(key)) {
            // check for length being made non-writable
            var result = defineLength(oldLen, oldLenDesc, desc, reject);
            if (desc.has(WRITABLE) && !desc.isWritable()) {
                setIsLengthNotWritable();
            }
            return result;
        }

        // Step 4a
        var index = ArrayIndex.getArrayIndex(key);
        if (ArrayIndex.isValidArrayIndex(index)) {
            var longIndex = ArrayIndex.toLongIndex(index);
            // Step 4b
            // setting an element beyond current length, but 'length' is not writable
            if (longIndex >= oldLen && !oldLenDesc.isWritable()) {
                if (reject) {
                    throw typeError("property.not.writable", Long.toString(longIndex), ScriptRuntime.safeToString(this));
                }
                return false;
            }

            // Step 4c
            // set the new array element
            var succeeded = super.defineOwnProperty(key, desc, false);

            // Step 4d
            if (!succeeded) {
                if (reject) {
                    throw typeError("cant.redefine.property", key.toString(), ScriptRuntime.safeToString(this));
                }
                return false;
            }

            // Step 4e -- adjust new length based on new element index that is set
            if (longIndex >= oldLen) {
                oldLenDesc.setValue(longIndex + 1);
                super.defineOwnProperty("length", oldLenDesc, false);
            }

            // Step 4f
            return true;
        }

        // not an index property
        return super.defineOwnProperty(key, desc, reject);
    }

    /**
     * Spec mentions use of [[DefineOwnProperty]] for indexed properties in certain places (eg. Array.prototype.map, filter).
     * We can not use ScriptObject.set method in such cases.
     * This is because set method uses inherited setters (if any) from any object in proto chain such as Array.prototype, Object.prototype.
     * This method directly sets a particular element value in the current object.
     */
    @Override
    public final void defineOwnProperty(int index, Object value) {
        assert isValidArrayIndex(index) : "invalid array index";
        var longIndex = ArrayIndex.toLongIndex(index);
        if (longIndex >= getArray().length()) {
            // make array big enough to hold..
            setArray(getArray().ensure(longIndex));
        }
        setArray(getArray().set(index, value)); // false
    }

    /**
     * Return the array contents upcasted as an ObjectArray, regardless of representation
     */
    public Object[] asObjectArray() {
        return getArray().asObjectArray();
    }

    @Override
    public void setIsLengthNotWritable() {
        super.setIsLengthNotWritable();
        setArray(ArrayData.setIsLengthNotWritable(getArray()));
    }

    /**
     * ECMA 15.4.3.2 Array.isArray ( arg )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static boolean isArray(Object self, Object arg) {
        return isArray(arg) || (arg instanceof JSObject && ((JSObject)arg).isArray());
    }

    /**
     * Length getter
     */
    @Getter(attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_CONFIGURABLE)
    public static Object length(Object self) {
        if (isArray(self)) {
            var length = ((ScriptObject) self).getArray().length();
            assert length >= 0L;
            // Cast to the narrowest supported numeric type to help optimistic type calculator
            if (length <= Integer.MAX_VALUE) {
                return (int) length;
            }
            return (double) length;
        }

        return 0;
    }

    /**
     * Length setter
     */
    @Setter(attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_CONFIGURABLE)
    public static void length(Object self, Object length) {
        if (isArray(self)) {
            ((ScriptObject)self).setLength(validLength(length));
        }
    }

    /**
     * Prototype length getter
     */
    @Getter(name = "length", where = Where.PROTOTYPE, attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_CONFIGURABLE)
    public static Object getProtoLength(Object self) {
        return length(self);  // Same as instance getter but we can't make nasgen use the same method for prototype
    }

    /**
     * Prototype length setter
     */
    @Setter(name = "length", where = Where.PROTOTYPE, attributes = Attribute.NOT_ENUMERABLE | Attribute.NOT_CONFIGURABLE)
    public static void setProtoLength(Object self, Object length) {
        length(self, length);  // Same as instance setter but we can't make nasgen use the same method for prototype
    }

    static long validLength(Object length) {
        // ES5 15.4.5.1, steps 3.c and 3.d require two ToNumber conversions here
        var doubleLength = JSType.toNumber(length);
        if (doubleLength != JSType.toUint32(length)) {
            throw rangeError("inappropriate.array.length", ScriptRuntime.safeToString(length));
        }
        return (long) doubleLength;
    }

    /**
     * ECMA 15.4.4.2 Array.prototype.toString ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object toString(Object self) {
        var obj = Global.toObject(self);
        if (obj instanceof ScriptObject) {
            var joinInvoker = getJOIN();
            var sobj = (ScriptObject)obj;
            try {
                var join = joinInvoker.getGetter().invokeExact(sobj);
                if (Bootstrap.isCallable(join)) {
                    return joinInvoker.getInvoker().invokeExact(join, sobj);
                }
            } catch (Throwable t) {
                Util.uncheck(t);
            }
        }

        // FIXME: should lookup Object.prototype.toString and call that?
        return ScriptRuntime.builtinObjectToString(self);
    }

    /**
     * Assert that an array is numeric, if not throw type error
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object assertNumeric(Object self) {
        if (!(self instanceof NativeArray && ((NativeArray)self).getArray().getOptimisticType().isNumeric())) {
            throw typeError("not.a.numeric.array", ScriptRuntime.safeToString(self));
        }
        return Boolean.TRUE;
    }

    /**
     * ECMA 15.4.4.3 Array.prototype.toLocaleString ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static String toLocaleString(Object self) {
        var sb = new StringBuilder();
        var iter = arrayLikeIterator(self, true);

        while (iter.hasNext()) {
            var obj = iter.next();

            if (obj != null && obj != ScriptRuntime.UNDEFINED) {
                var val = JSType.toScriptObject(obj);

                try {
                    if (val instanceof ScriptObject) {
                        var localeInvoker = getTO_LOCALE_STRING();
                        var sobj = (ScriptObject)val;
                        var toLocaleString = localeInvoker.getGetter().invokeExact(sobj);

                        if (Bootstrap.isCallable(toLocaleString)) {
                            sb.append((String)localeInvoker.getInvoker().invokeExact(toLocaleString, sobj));
                        } else {
                            throw typeError("not.a.function", "toLocaleString");
                        }
                    }
                } catch (Throwable t) {
                    Util.uncheck(t);
                }
            }

            if (iter.hasNext()) {
                sb.append(",");
            }
        }

        return sb.toString();
    }

    /**
     * ECMA 15.4.2.2 new Array (len)
     */
    @Constructor(arity = 1)
    public static NativeArray construct(boolean newObj, Object self, Object... args) {
        switch (args.length) {
            case 0 -> {
                return new NativeArray(0);
            }
            case 1 -> {
                var len = args[0];
                if (len instanceof Number) {
                    long length;
                    if (len instanceof Integer || len instanceof Long) {
                        length = ((Number) len).longValue();
                        if (length >= 0 && length < JSType.MAX_UINT) {
                            return new NativeArray(length);
                        }
                    }

                    length = JSType.toUint32(len);

                    // If the argument len is a Number and ToUint32(len) is equal to len, then the length property of the newly constructed object is set to ToUint32(len).
                    // If the argument len is a Number and ToUint32(len) is not equal to len, a RangeError exception is thrown.
                    var numberLength = ((Number) len).doubleValue();
                    if (length != numberLength) {
                        throw rangeError("inappropriate.array.length", JSType.toString(numberLength));
                    }

                    return new NativeArray(length);
                }
                // If the argument len is not a Number, then the length property of the newly constructed object is set to 1 and the 0 property of the newly constructed object is set to len
                return new NativeArray(new Object[]{args[0]});
            }
            default -> {
                return new NativeArray(args);
            }
        }
    }

    /**
     * ECMA 15.4.2.2 new Array (len).
     * Specialized constructor for zero arguments - empty array
     */
    @SpecializedFunction(isConstructor=true)
    public static NativeArray construct(boolean newObj, Object self) {
        return new NativeArray(0);
    }

    /**
     * ECMA 15.4.2.2 new Array (len).
     * Specialized constructor for zero arguments - empty array
     */
    @SpecializedFunction(isConstructor=true)
    public static Object construct(boolean newObj, Object self, boolean element) {
        return new NativeArray(new Object[] { element });
    }

    /**
     * ECMA 15.4.2.2 new Array (len).
     * Specialized constructor for one integer argument (length)
     */
    @SpecializedFunction(isConstructor=true)
    public static NativeArray construct(boolean newObj, Object self, int length) {
        if (length >= 0) {
            return new NativeArray(length);
        }
        return construct(newObj, self, new Object[]{length});
    }

    /**
     * ECMA 15.4.2.2 new Array (len).
     * Specialized constructor for one long argument (length)
     */
    @SpecializedFunction(isConstructor=true)
    public static NativeArray construct(boolean newObj, Object self, long length) {
        if (length >= 0L && length <= JSType.MAX_UINT) {
            return new NativeArray(length);
        }
        return construct(newObj, self, new Object[]{length});
    }

    /**
     * ECMA 15.4.2.2 new Array (len).
     * Specialized constructor for one double argument (length)
     */
    @SpecializedFunction(isConstructor=true)
    public static NativeArray construct(boolean newObj, Object self, double length) {
        var uint32length = JSType.toUint32(length);

        if (uint32length == length) {
            return new NativeArray(uint32length);
        }

        return construct(newObj, self, new Object[]{length});
    }

    /**
     * ECMA 15.4.4.4 Array.prototype.concat ( [ item1 [ , item2 [ , ... ] ] ] )
     */
    @SpecializedFunction(linkLogic=ConcatLinkLogic.class, convertsNumericArgs = false)
    public static NativeArray concat(Object self, int arg) {
        var newData = getContinuousArrayDataCCE(self, Integer.class).copy(); //get at least an integer data copy of this data
        newData.fastPush(arg); //add an integer to its end
        return new NativeArray(newData);
    }

    /**
     * ECMA 15.4.4.4 Array.prototype.concat ( [ item1 [ , item2 [ , ... ] ] ] )
     */
    @SpecializedFunction(linkLogic=ConcatLinkLogic.class, convertsNumericArgs = false)
    public static NativeArray concat(Object self, double arg) {
        var newData = getContinuousArrayDataCCE(self, Double.class).copy(); //get at least a number array data copy of this data
        newData.fastPush(arg); //add a double at the end
        return new NativeArray(newData);
    }

    /**
     * ECMA 15.4.4.4 Array.prototype.concat ( [ item1 [ , item2 [ , ... ] ] ] )
     */
    @SpecializedFunction(linkLogic=ConcatLinkLogic.class)
    public static NativeArray concat(Object self, Object arg) {
        // arg is [NativeArray] of same type.
        var selfData = getContinuousArrayDataCCE(self);
        ContinuousArrayData newData;

        if (arg instanceof NativeArray) {
            var argData = (ContinuousArrayData)((NativeArray)arg).getArray();
            if (argData.isEmpty()) {
                newData = selfData.copy();
            } else if (selfData.isEmpty()) {
                newData = argData.copy();
            } else {
                var widestElementType = selfData.widest(argData).getBoxedElementType();
                newData = ((ContinuousArrayData)selfData.convert(widestElementType)).fastConcat((ContinuousArrayData)argData.convert(widestElementType));
            }
        } else {
            newData = getContinuousArrayDataCCE(self, Object.class).copy();
            newData.fastPush(arg);
        }

        return new NativeArray(newData);
    }

    /**
     * ECMA 15.4.4.4 Array.prototype.concat ( [ item1 [ , item2 [ , ... ] ] ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static NativeArray concat(Object self, Object... args) {
        var list = new ArrayList<Object>();

        concatToList(list, Global.toObject(self));

        for (var obj : args) {
            concatToList(list, obj);
        }

        return new NativeArray(list.toArray());
    }

    private static void concatToList(ArrayList<Object> list, Object obj) {
        var isScriptArray = isArray(obj);
        var isScriptObject = isScriptArray || obj instanceof ScriptObject;
        if (isScriptArray || obj instanceof Iterable || obj instanceof JSObject || (obj != null && obj.getClass().isArray())) {
            var iter = arrayLikeIterator(obj, true);
            if (iter.hasNext()) {
                for (var i = 0; iter.hasNext(); ++i) {
                    var value = iter.next();
                    if (value == ScriptRuntime.UNDEFINED && isScriptObject && !((ScriptObject)obj).has(i)) {
                        // TODO: eventually rewrite arrayLikeIterator to use a three-state enum for handling UNDEFINED instead of an "includeUndefined" boolean with states SKIP, INCLUDE, RETURN_EMPTY.
                        // Until then, this is how we'll make sure that empty elements don't make it into the concatenated array.
                        list.add(ScriptRuntime.EMPTY);
                    } else {
                        list.add(value);
                    }
                }
            } else if (!isScriptArray) {
                list.add(obj); // add empty object, but not an empty array
            }
        } else {
            // single element, add it
            list.add(obj);
        }
    }

    /**
     * ECMA 15.4.4.5 Array.prototype.join (separator)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static String join(Object self, Object separator) {
        var sb = new StringBuilder();
        var iter = arrayLikeIterator(self, true);
        var sep = separator == ScriptRuntime.UNDEFINED ? "," : JSType.toString(separator);

        while (iter.hasNext()) {
            var obj = iter.next();

            if (obj != null && obj != ScriptRuntime.UNDEFINED) {
                sb.append(JSType.toString(obj));
            }

            if (iter.hasNext()) {
                sb.append(sep);
            }
        }

        return sb.toString();
    }

    /**
     * Specialization of pop for ContinuousArrayData.
     * The link guard checks that the array is continuous AND not empty.
     * The runtime guard checks that the guard is continuous (CCE otherwise)
     */
    @SpecializedFunction(name="pop", linkLogic=PopLinkLogic.class)
    public static int popInt(Object self) {
        // must be non empty IntArrayData
        return getContinuousNonEmptyArrayDataCCE(self, IntElements.class).fastPopInt();
    }

    /**
     * Specialization of pop for ContinuousArrayData
     */
    @SpecializedFunction(name="pop", linkLogic=PopLinkLogic.class)
    public static double popDouble(Object self) {
        // must be non empty int long or double array data
        return getContinuousNonEmptyArrayDataCCE(self, NumericElements.class).fastPopDouble();
    }

    /**
     * Specialization of pop for ContinuousArrayData
     */
    @SpecializedFunction(name="pop", linkLogic=PopLinkLogic.class)
    public static Object popObject(Object self) {
        // can be any data, because the numeric ones will throw cce and force relink
        return getContinuousArrayDataCCE(self, null).fastPopObject();
    }

    /**
     * ECMA 15.4.4.6 Array.prototype.pop ()
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object pop(Object self) {
        try {
            var sobj = (ScriptObject)self;

            if (bulkable(sobj)) {
                return sobj.getArray().pop();
            }

            var len = JSType.toUint32(sobj.getLength());

            if (len == 0) {
                sobj.set("length", 0, 0);
                return ScriptRuntime.UNDEFINED;
            }

            var index = len - 1;
            var element = sobj.get(index);

            sobj.delete(index); // true
            sobj.set("length", index, 0);

            return element;
        } catch (ClassCastException | NullPointerException e) {
            throw typeError("not.an.object", ScriptRuntime.safeToString(self));
        }
    }

    /**
     * ECMA 15.4.4.7 Array.prototype.push (args...)
     */
    @SpecializedFunction(linkLogic=PushLinkLogic.class, convertsNumericArgs = false)
    public static double push(Object self, int arg) {
        return getContinuousArrayDataCCE(self, Integer.class).fastPush(arg);
    }

    /**
     * ECMA 15.4.4.7 Array.prototype.push (args...)
     */
    @SpecializedFunction(linkLogic=PushLinkLogic.class, convertsNumericArgs = false)
    public static double push(Object self, double arg) {
        return getContinuousArrayDataCCE(self, Double.class).fastPush(arg);
    }

    /**
     * ECMA 15.4.4.7 Array.prototype.push (args...)
     */
    @SpecializedFunction(name="push", linkLogic=PushLinkLogic.class)
    public static double pushObject(Object self, Object arg) {
        return getContinuousArrayDataCCE(self, Object.class).fastPush(arg);
    }

    /**
     * ECMA 15.4.4.7 Array.prototype.push (args...)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static Object push(Object self, Object... args) {
        try {
            var sobj   = (ScriptObject)self;

            if (bulkable(sobj) && sobj.getArray().length() + args.length <= JSType.MAX_UINT) {
                var newData = sobj.getArray().push(args);
                sobj.setArray(newData);
                return JSType.toNarrowestNumber(newData.length());
            }

            var len = JSType.toUint32(sobj.getLength());
            for (var element : args) {
                sobj.set(len++, element, 0);
            }
            sobj.set("length", len, 0);

            return JSType.toNarrowestNumber(len);
        } catch (ClassCastException | NullPointerException e) {
            throw typeError(Context.getGlobal(), e, "not.an.object", ScriptRuntime.safeToString(self));
        }
    }

    /**
     * ECMA 15.4.4.7 Array.prototype.push (args...) specialized for single object argument
     */
    @SpecializedFunction
    public static double push(Object self, Object arg) {
        try {
            var sobj = (ScriptObject)self;
            var arrayData = sobj.getArray();
            var length = arrayData.length();
            if (bulkable(sobj) && length < JSType.MAX_UINT) {
                sobj.setArray(arrayData.push(arg));
                return length + 1;
            }

            var len = JSType.toUint32(sobj.getLength());
            sobj.set(len++, arg, 0);
            sobj.set("length", len, 0);
            return len;
        } catch (ClassCastException | NullPointerException e) {
            throw typeError("not.an.object", ScriptRuntime.safeToString(self));
        }
    }

    /**
     * ECMA 15.4.4.8 Array.prototype.reverse ()
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object reverse(Object self) {
        try {
            var sobj = (ScriptObject)self;
            var len = JSType.toUint32(sobj.getLength());
            var middle = len / 2;

            for (var lower = 0; lower != middle; lower++) {
                var upper = len - lower - 1;
                var lowerValue = sobj.get(lower);
                var upperValue = sobj.get(upper);
                var lowerExists = sobj.has(lower);
                var upperExists = sobj.has(upper);

                if (lowerExists && upperExists) {
                    sobj.set(lower, upperValue, 0);
                    sobj.set(upper, lowerValue, 0);
                } else if (!lowerExists && upperExists) {
                    sobj.set(lower, upperValue, 0);
                    sobj.delete(upper); // true
                } else if (lowerExists && !upperExists) {
                    sobj.delete(lower); // true
                    sobj.set(upper, lowerValue, 0);
                }
            }
            return sobj;
        } catch (ClassCastException | NullPointerException e) {
            throw typeError("not.an.object", ScriptRuntime.safeToString(self));
        }
    }

    /**
     * ECMA 15.4.4.9 Array.prototype.shift ()
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object shift(Object self) {
        var obj = Global.toObject(self);

        Object first = ScriptRuntime.UNDEFINED;

        if (!(obj instanceof ScriptObject)) {
            return first;
        }

        var sobj = (ScriptObject) obj;

        var len = JSType.toUint32(sobj.getLength());

        if (len > 0) {
            first = sobj.get(0);

            if (bulkable(sobj)) {
                sobj.getArray().shiftLeft(1);
            } else {
                var hasPrevious = true;
                for (long k = 1; k < len; k++) {
                    var hasCurrent = sobj.has(k);
                    if (hasCurrent) {
                        sobj.set(k - 1, sobj.get(k), 0);
                    } else if (hasPrevious) {
                        sobj.delete(k - 1); // true
                    }
                    hasPrevious = hasCurrent;
                }
            }
            sobj.delete(--len); // true
        } else {
            len = 0;
        }

        sobj.set("length", len, 0);

        return first;
    }

    /**
     * ECMA 15.4.4.10 Array.prototype.slice ( start [ , end ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object slice(Object self, Object start, Object end) {
        var obj = Global.toObject(self);
        if (!(obj instanceof ScriptObject)) {
            return ScriptRuntime.UNDEFINED;
        }

        var sobj = (ScriptObject)obj;
        var len = JSType.toUint32(sobj.getLength());
        var relativeStart = JSType.toLong(start);
        var relativeEnd = end == ScriptRuntime.UNDEFINED ? len : JSType.toLong(end);

        var k = relativeStart < 0 ? Math.max(len + relativeStart, 0) : Math.min(relativeStart, len);
        var finale = relativeEnd < 0 ? Math.max(len + relativeEnd, 0) : Math.min(relativeEnd, len);

        if (k >= finale) {
            return new NativeArray(0);
        }

        if (bulkable(sobj)) {
            return new NativeArray(sobj.getArray().slice(k, finale));
        }

        // Construct array with proper length to have a deleted filter on undefined elements
        var copy = new NativeArray(finale - k);
        for (long n = 0; k < finale; n++, k++) {
            if (sobj.has(k)) {
                copy.defineOwnProperty(ArrayIndex.getArrayIndex(n), sobj.get(k));
            }
        }

        return copy;
    }

    private static Object compareFunction(Object comparefn) {
        if (comparefn == ScriptRuntime.UNDEFINED) {
            return null;
        }

        if (!Bootstrap.isCallable(comparefn)) {
            throw typeError("not.a.function", ScriptRuntime.safeToString(comparefn));
        }

        return comparefn;
    }

    private static Object[] sort(Object[] array, Object comparefn) {
        var cmp = compareFunction(comparefn);

        var list = Arrays.asList(array);
        Object cmpThis = cmp == null || Bootstrap.isCallable(cmp) ? ScriptRuntime.UNDEFINED : Global.instance();

        try {
            Collections.sort(list, new Comparator<Object>() {
                private final MethodHandle call_cmp = getCALL_CMP();

                @Override
                public int compare(Object x, Object y) {
                    if (x == ScriptRuntime.UNDEFINED && y == ScriptRuntime.UNDEFINED) {
                        return 0;
                    } else if (x == ScriptRuntime.UNDEFINED) {
                        return 1;
                    } else if (y == ScriptRuntime.UNDEFINED) {
                        return -1;
                    }

                    if (cmp != null) {
                        try {
                            return (int)Math.signum((double)call_cmp.invokeExact(cmp, cmpThis, x, y));
                        } catch (Throwable t) {
                            Util.uncheck(t);
                        }
                    }

                    return JSType.toString(x).compareTo(JSType.toString(y));
                }
            });
        } catch (IllegalArgumentException iae) {
            // Collections.sort throws IllegalArgumentException when Comparison method violates its general contract.
            // See ECMA spec 15.4.4.11 Array.prototype.sort (comparefn).
            // If "comparefn" is not undefined and is not a consistent comparison function for the elements of this array, the behaviour of sort is implementation-defined.
        }

        return list.toArray(new Object[0]);
    }

    /**
     * ECMA 15.4.4.11 Array.prototype.sort ( comparefn )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static ScriptObject sort(Object self, Object comparefn) {
        try {
            var sobj = (ScriptObject) self;
            var len = JSType.toUint32(sobj.getLength());
            var array = sobj.getArray();

            if (len > 1) {
                // Get only non-missing elements.
                // Missing elements go at the end of the sorted array. So, just don't copy these to sort input.
                var src = new ArrayList<Object>();

                for (var iter = array.indexIterator(); iter.hasNext(); ) {
                    long index = iter.next();
                    if (index >= len) {
                        break;
                    }
                    src.add(array.getObject((int)index));
                }

                var sorted = sort(src.toArray(), comparefn);

                for (var i = 0; i < sorted.length; i++) {
                    array = array.set(i, sorted[i]); // true
                }

                // delete missing elements - which are at the end of sorted array
                if (sorted.length != len) {
                    array = array.delete(sorted.length, len - 1);
                }

                sobj.setArray(array);
            }

            return sobj;
        } catch (ClassCastException | NullPointerException e) {
            throw typeError("not.an.object", ScriptRuntime.safeToString(self));
        }
    }

    /**
     * ECMA 15.4.4.12 Array.prototype.splice ( start, deleteCount [ item1 [ , item2 [ , ... ] ] ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 2)
    public static Object splice(Object self, Object... args) {
        var obj = Global.toObject(self);

        if (!(obj instanceof ScriptObject)) {
            return ScriptRuntime.UNDEFINED;
        }

        var sobj = (ScriptObject)obj;
        var len = JSType.toUint32(sobj.getLength());
        var relativeStart = JSType.toLong(args.length > 0 ? args[0] : ScriptRuntime.UNDEFINED);

        var actualStart = relativeStart < 0 ? Math.max(len + relativeStart, 0) : Math.min(relativeStart, len);
        long actualDeleteCount;
        Object[] items = ScriptRuntime.EMPTY_ARRAY;

        if (args.length == 0) {
            actualDeleteCount = 0;
        } else if (args.length == 1) {
            actualDeleteCount = len - actualStart;
        } else {
            actualDeleteCount = Math.min(Math.max(JSType.toLong(args[1]), 0), len - actualStart);
            if (args.length > 2) {
                items = new Object[args.length - 2];
                System.arraycopy(args, 2, items, 0, items.length);
            }
        }

        NativeArray returnValue;

        if (actualStart <= Integer.MAX_VALUE && actualDeleteCount <= Integer.MAX_VALUE && bulkable(sobj)) {
            try {
                returnValue = new NativeArray(sobj.getArray().fastSplice((int)actualStart, (int)actualDeleteCount, items.length));

                // Since this is a dense bulkable array we can use faster defineOwnProperty to copy new elements
                var k = (int) actualStart;
                for (var i = 0; i < items.length; i++, k++) {
                    sobj.defineOwnProperty(k, items[i]);
                }
            } catch (UnsupportedOperationException uoe) {
                returnValue = slowSplice(sobj, actualStart, actualDeleteCount, items, len);
            }
        } else {
            returnValue = slowSplice(sobj, actualStart, actualDeleteCount, items, len);
        }

        return returnValue;
    }

    private static NativeArray slowSplice(ScriptObject sobj, long start, long deleteCount, Object[] items, long len) {

        var array = new NativeArray(deleteCount);

        for (long k = 0; k < deleteCount; k++) {
            var from = start + k;

            if (sobj.has(from)) {
                array.defineOwnProperty(ArrayIndex.getArrayIndex(k), sobj.get(from));
            }
        }

        if (items.length < deleteCount) {
            for (long k = start; k < len - deleteCount; k++) {
                var from = k + deleteCount;
                var to   = k + items.length;

                if (sobj.has(from)) {
                    sobj.set(to, sobj.get(from), 0);
                } else {
                    sobj.delete(to); // true
                }
            }

            for (long k = len; k > len - deleteCount + items.length; k--) {
                sobj.delete(k - 1); // true
            }
        } else if (items.length > deleteCount) {
            for (long k = len - deleteCount; k > start; k--) {
                var from = k + deleteCount - 1;
                var to = k + items.length - 1;

                if (sobj.has(from)) {
                    var fromValue = sobj.get(from);
                    sobj.set(to, fromValue, 0);
                } else {
                    sobj.delete(to); // true
                }
            }
        }

        long k = start;
        for (var i = 0; i < items.length; i++, k++) {
            sobj.set(k, items[i], 0);
        }

        var newLength = len - deleteCount + items.length;
        sobj.set("length", newLength, 0);

        return array;
    }

    /**
     * ECMA 15.4.4.13 Array.prototype.unshift ( [ item1 [ , item2 [ , ... ] ] ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static Object unshift(Object self, Object... items) {
        var obj = Global.toObject(self);

        if (!(obj instanceof ScriptObject)) {
            return ScriptRuntime.UNDEFINED;
        }

        var sobj = (ScriptObject)obj;
        var len = JSType.toUint32(sobj.getLength());

        if (items == null) {
            return ScriptRuntime.UNDEFINED;
        }

        if (bulkable(sobj)) {
            sobj.getArray().shiftRight(items.length);

            for (var j = 0; j < items.length; j++) {
                sobj.setArray(sobj.getArray().set(j, items[j])); // true
            }
        } else {
            for (long k = len; k > 0; k--) {
                var from = k - 1;
                var to = k + items.length - 1;

                if (sobj.has(from)) {
                    var fromValue = sobj.get(from);
                    sobj.set(to, fromValue, 0);
                } else {
                    sobj.delete(to); // true
                }
            }

            for (var j = 0; j < items.length; j++) {
                sobj.set(j, items[j], 0);
            }
        }

        var newLength = len + items.length;
        sobj.set("length", newLength, 0);

        return JSType.toNarrowestNumber(newLength);
    }

    /**
     * ECMA 15.4.4.14 Array.prototype.indexOf ( searchElement [ , fromIndex ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static double indexOf(Object self, Object searchElement, Object fromIndex) {
        try {
            var sobj = (ScriptObject)Global.toObject(self);
            var len = JSType.toUint32(sobj.getLength());
            if (len == 0) {
                return -1;
            }

            var n = JSType.toLong(fromIndex);
            if (n >= len) {
                return -1;
            }


            for (long k = Math.max(0, n < 0 ? len - Math.abs(n) : n); k < len; k++) {
                if (sobj.has(k)) {
                    if (ScriptRuntime.EQ_STRICT(sobj.get(k), searchElement)) {
                        return k;
                    }
                }
            }
        } catch (ClassCastException | NullPointerException e) {
            // FALL-THRU
        }

        return -1;
    }

    /**
     * ECMA 15.4.4.15 Array.prototype.lastIndexOf ( searchElement [ , fromIndex ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static double lastIndexOf(Object self, Object... args) {
        try {
            var sobj = (ScriptObject)Global.toObject(self);
            var len = JSType.toUint32(sobj.getLength());

            if (len == 0) {
                return -1;
            }

            var searchElement = args.length > 0 ? args[0] : ScriptRuntime.UNDEFINED;
            var n = args.length > 1 ? JSType.toLong(args[1]) : len - 1;

            for (long k = n < 0 ? len - Math.abs(n) : Math.min(n, len - 1); k >= 0; k--) {
                if (sobj.has(k)) {
                    if (ScriptRuntime.EQ_STRICT(sobj.get(k), searchElement)) {
                        return k;
                    }
                }
            }
        } catch (ClassCastException | NullPointerException e) {
            throw typeError("not.an.object", ScriptRuntime.safeToString(self));
        }

        return -1;
    }

    /**
     * ECMA 15.4.4.16 Array.prototype.every ( callbackfn [ , thisArg ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static boolean every(Object self, Object callbackfn, Object thisArg) {
        return applyEvery(Global.toObject(self), callbackfn, thisArg);
    }

    private static boolean applyEvery(Object self, Object callbackfn, Object thisArg) {
        return new IteratorAction<Boolean>(Global.toObject(self), callbackfn, thisArg, true) {
            private final MethodHandle everyInvoker = getEVERY_CALLBACK_INVOKER();

            @Override
            protected boolean forEach(Object val, double i) throws Throwable {
                return result = (boolean)everyInvoker.invokeExact(callbackfn, thisArg, val, i, self);
            }
        }.apply();
    }

    /**
     * ECMA 15.4.4.17 Array.prototype.some ( callbackfn [ , thisArg ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static boolean some(Object self, Object callbackfn, Object thisArg) {
        return new IteratorAction<Boolean>(Global.toObject(self), callbackfn, thisArg, false) {
            private final MethodHandle someInvoker = getSOME_CALLBACK_INVOKER();

            @Override
            protected boolean forEach(Object val, double i) throws Throwable {
                return !(result = (boolean)someInvoker.invokeExact(callbackfn, thisArg, val, i, self));
            }
        }.apply();
    }

    /**
     * ECMA 15.4.4.18 Array.prototype.forEach ( callbackfn [ , thisArg ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static Object forEach(Object self, Object callbackfn, Object thisArg) {
        return new IteratorAction<Object>(Global.toObject(self), callbackfn, thisArg, ScriptRuntime.UNDEFINED) {
            private final MethodHandle forEachInvoker = getFOREACH_CALLBACK_INVOKER();

            @Override
            protected boolean forEach(Object val, double i) throws Throwable {
                forEachInvoker.invokeExact(callbackfn, thisArg, val, i, self);
                return true;
            }
        }.apply();
    }

    /**
     * ECMA 15.4.4.19 Array.prototype.map ( callbackfn [ , thisArg ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static NativeArray map(Object self, Object callbackfn, Object thisArg) {
        return new IteratorAction<NativeArray>(Global.toObject(self), callbackfn, thisArg, null) {
            private final MethodHandle mapInvoker = getMAP_CALLBACK_INVOKER();

            @Override
            protected boolean forEach(Object val, double i) throws Throwable {
                var r = mapInvoker.invokeExact(callbackfn, thisArg, val, i, self);
                result.defineOwnProperty(ArrayIndex.getArrayIndex(index), r);
                return true;
            }

            @Override
            public void applyLoopBegin(ArrayLikeIterator<Object> iter0) {
                // map return array should be of same length as source array even if callback reduces source array length
                result = new NativeArray(iter0.getLength());
            }
        }.apply();
    }

    /**
     * ECMA 15.4.4.20 Array.prototype.filter ( callbackfn [ , thisArg ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static NativeArray filter(Object self, Object callbackfn, Object thisArg) {
        return new IteratorAction<NativeArray>(Global.toObject(self), callbackfn, thisArg, new NativeArray()) {
            private long to = 0;
            private final MethodHandle filterInvoker = getFILTER_CALLBACK_INVOKER();

            @Override
            protected boolean forEach(Object val, double i) throws Throwable {
                if ((boolean)filterInvoker.invokeExact(callbackfn, thisArg, val, i, self)) {
                    result.defineOwnProperty(ArrayIndex.getArrayIndex(to++), val);
                }
                return true;
            }
        }.apply();
    }

    private static Object reduceInner(ArrayLikeIterator<Object> iter, Object self, Object... args) {
        var callbackfn = args.length > 0 ? args[0] : ScriptRuntime.UNDEFINED;
        var initialValuePresent = args.length > 1;

        var initialValue = initialValuePresent ? args[1] : ScriptRuntime.UNDEFINED;

        if (callbackfn == ScriptRuntime.UNDEFINED) {
            throw typeError("not.a.function", "undefined");
        }

        if (!initialValuePresent) {
            if (iter.hasNext()) {
                initialValue = iter.next();
            } else {
                throw typeError("array.reduce.invalid.init");
            }
        }

        // if initial value is ScriptRuntime.UNDEFINED - step forward once.
        return new IteratorAction<Object>(Global.toObject(self), callbackfn, ScriptRuntime.UNDEFINED, initialValue, iter) {
            private final MethodHandle reduceInvoker = getREDUCE_CALLBACK_INVOKER();

            @Override
            protected boolean forEach(Object val, double i) throws Throwable {
                // TODO: why can't I declare the second arg as Undefined.class?
                result = reduceInvoker.invokeExact(callbackfn, ScriptRuntime.UNDEFINED, result, val, i, self);
                return true;
            }
        }.apply();
    }

    /**
     * ECMA 15.4.4.21 Array.prototype.reduce ( callbackfn [ , initialValue ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static Object reduce(Object self, Object... args) {
        return reduceInner(arrayLikeIterator(self), self, args);
    }

    /**
     * ECMA 15.4.4.22 Array.prototype.reduceRight ( callbackfn [ , initialValue ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static Object reduceRight(Object self, Object... args) {
        return reduceInner(reverseArrayLikeIterator(self), self, args);
    }

    /**
     * ECMA6 22.1.3.4 Array.prototype.entries ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object entries(Object self) {
        return ArrayIterator.newArrayKeyValueIterator(self);
    }

    /**
     * ECMA6 22.1.3.13 Array.prototype.keys ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object keys(Object self) {
        return ArrayIterator.newArrayKeyIterator(self);
    }

    /**
     * ECMA6 22.1.3.29 Array.prototype.values ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object values(Object self) {
        return ArrayIterator.newArrayValueIterator(self);
    }

    /**
     * 22.1.3.30 Array.prototype [ @@iterator ] ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, name = "@@iterator")
    public static Object getIterator(Object self) {
        return ArrayIterator.newArrayValueIterator(self);
    }

    /**
     * Determine if Java bulk array operations may be used on the underlying storage.
     * This is possible only if the object's prototype chain is empty or each of the prototypes in the chain is empty.
     */
    private static boolean bulkable(ScriptObject self) {
        return self.isArray() && !hasInheritedArrayEntries(self) && !self.isLengthNotWritable();
    }

    private static boolean hasInheritedArrayEntries(ScriptObject self) {
        var proto = self.getProto();
        while (proto != null) {
            if (proto.hasArrayEntries()) {
                return true;
            }
            proto = proto.getProto();
        }
        return false;
    }

    @Override
    public String toString() {
        return "NativeArray@" + Util.id(this) + " [" + getArray().getClass().getSimpleName() + ']';
    }

    @Override
    public SpecializedFunction.LinkLogic getLinkLogic(Class<? extends LinkLogic> clazz) {
        if (clazz == PushLinkLogic.class) {
            return PushLinkLogic.INSTANCE;
        } else if (clazz == PopLinkLogic.class) {
            return PopLinkLogic.INSTANCE;
        } else if (clazz == ConcatLinkLogic.class) {
            return ConcatLinkLogic.INSTANCE;
        }
        return null;
    }

    @Override
    public boolean hasPerInstanceAssumptions() {
        return true; //length writable switchpoint
    }

    /**
     * This is an abstract super class that contains common functionality for all specialized optimistic builtins in NativeArray.
     * For example, it handles the modification switchpoint which is touched when length is written.
     */
    private static abstract class ArrayLinkLogic extends SpecializedFunction.LinkLogic {
        protected ArrayLinkLogic() {}

        protected static ContinuousArrayData getContinuousArrayData(Object self) {
            try {
                //cast to NativeArray, to avoid cases like x = {0:0, 1:1}, x.length = 2, where we can't use the array push/pop
                return (ContinuousArrayData)((NativeArray)self).getArray();
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * Push and pop callsites can throw ClassCastException as a mechanism to have them relinked - this enabled fast checks of the kind of ((IntArrayData)arrayData).push(x) for an IntArrayData only push - if this fails, a CCE will be thrown and we will relink
         */
        @Override
        public Class<? extends Throwable> getRelinkException() {
            return ClassCastException.class;
        }
    }

    /**
     * This is linker logic for optimistic concatenations
     */
    private static final class ConcatLinkLogic extends ArrayLinkLogic {
        private static final LinkLogic INSTANCE = new ConcatLinkLogic();

        @Override
        public boolean canLink(Object self, CallSiteDescriptor desc, LinkRequest request) {
            var args = request.getArguments();

            if (args.length != 3) { //single argument check
                return false;
            }

            var selfData = getContinuousArrayData(self);
            if (selfData == null) {
                return false;
            }

            var arg = args[2];
            // The generic version uses its own logic and ArrayLikeIterator to decide if an object should be iterated over or added as single element.
            // To avoid duplication of code and err on the safe side we only use the specialized version if arg is either a continuous array or a JS primitive.
            if (arg instanceof NativeArray) {
                return (getContinuousArrayData(arg) != null);
            }

            return JSType.isPrimitive(arg);
        }
    }

    /**
     * This is linker logic for optimistic pushes
     */
    private static final class PushLinkLogic extends ArrayLinkLogic {
        private static final LinkLogic INSTANCE = new PushLinkLogic();

        @Override
        public boolean canLink(Object self, CallSiteDescriptor desc, LinkRequest request) {
            return getContinuousArrayData(self) != null;
        }
    }

    /**
     * This is linker logic for optimistic pops
     */
    private static final class PopLinkLogic extends ArrayLinkLogic {
        private static final LinkLogic INSTANCE = new PopLinkLogic();

        /**
         * We need to check if we are dealing with a continuous non empty array data here,
         * as pop with a primitive return value returns undefined for arrays with length 0
         */
        @Override
        public boolean canLink(Object self, CallSiteDescriptor desc, LinkRequest request) {
            var data = getContinuousNonEmptyArrayData(self);
            if (data != null) {
                var elementType = data.getElementType();
                var returnType = desc.getMethodType().returnType();
                var typeFits = JSType.getAccessorTypeIndex(returnType) >= JSType.getAccessorTypeIndex(elementType);
                return typeFits;
            }
            return false;
        }

        private static ContinuousArrayData getContinuousNonEmptyArrayData(Object self) {
            var data = getContinuousArrayData(self);
            if (data != null) {
                return data.length() == 0 ? null : data;
            }
            return null;
        }
    }

    // Runtime calls for push and pops.
    // They could be used as guards, but they also perform the runtime logic, so rather than synthesizing them into a guard method handle that would also perform the push on the retrieved receiver, we use this as runtime logic

    // TODO - fold these into the Link logics, but I'll do that as a later step, as I want to do a checkin where everything works first

    private static <T> ContinuousArrayData getContinuousNonEmptyArrayDataCCE(Object self, Class<T> clazz) {
        try {
            @SuppressWarnings("unchecked")
            var data = (ContinuousArrayData)(T)((NativeArray)self).getArray();
            if (data.length() != 0L) {
                return data; // if length is 0 we cannot pop and have to relink, because then we'd have to return an undefined, which is a wider type than e.g. int
           }
        } catch (NullPointerException e) {
            // fallthru
        }
        throw new ClassCastException();
    }

    private static ContinuousArrayData getContinuousArrayDataCCE(Object self) {
        try {
            return (ContinuousArrayData)((NativeArray)self).getArray();
         } catch (NullPointerException e) {
             throw new ClassCastException();
         }
    }

    private static ContinuousArrayData getContinuousArrayDataCCE(Object self, Class<?> elementType) {
        try {
            return (ContinuousArrayData)((NativeArray)self).getArray(elementType); //ensure element type can fit "elementType"
        } catch (NullPointerException e) {
            throw new ClassCastException();
        }
    }

}
