/*
 * Copyright (c) 2010, 2017, Oracle and/or its affiliates. All rights reserved.
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

package nashorn.internal.runtime;

import static nashorn.internal.codegen.CompilerConstants.staticCallNoLookup;
import static nashorn.internal.codegen.CompilerConstants.virtualCall;
import static nashorn.internal.codegen.CompilerConstants.virtualCallNoLookup;
import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ECMAErrors.referenceError;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.JSType.UNDEFINED_DOUBLE;
import static nashorn.internal.runtime.JSType.UNDEFINED_INT;
import static nashorn.internal.runtime.PropertyDescriptor.CONFIGURABLE;
import static nashorn.internal.runtime.PropertyDescriptor.ENUMERABLE;
import static nashorn.internal.runtime.PropertyDescriptor.GET;
import static nashorn.internal.runtime.PropertyDescriptor.SET;
import static nashorn.internal.runtime.PropertyDescriptor.VALUE;
import static nashorn.internal.runtime.PropertyDescriptor.WRITABLE;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;
import static nashorn.internal.runtime.UnwarrantedOptimismException.INVALID_PROGRAM_POINT;
import static nashorn.internal.runtime.UnwarrantedOptimismException.isValid;
import static nashorn.internal.runtime.arrays.ArrayIndex.getArrayIndex;
import static nashorn.internal.runtime.arrays.ArrayIndex.isValidArrayIndex;
import static nashorn.internal.runtime.linker.NashornCallSiteDescriptor.isScopeFlag;
import static nashorn.internal.runtime.linker.NashornGuards.explicitInstanceOfCheck;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;
import java.lang.reflect.Array;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import jdk.dynalink.CallSiteDescriptor;
import jdk.dynalink.NamedOperation;
import jdk.dynalink.linker.GuardedInvocation;
import jdk.dynalink.linker.LinkRequest;
import nashorn.internal.codegen.CompilerConstants.Call;
import nashorn.internal.Util;
import nashorn.internal.codegen.ObjectClassGenerator;
import nashorn.internal.codegen.types.Type;
import nashorn.internal.lookup.Lookup;
import nashorn.internal.objects.AccessorPropertyDescriptor;
import nashorn.internal.objects.DataPropertyDescriptor;
import nashorn.internal.objects.Global;
import nashorn.internal.objects.NativeArray;
import nashorn.internal.runtime.arrays.ArrayData;
import nashorn.internal.runtime.arrays.ArrayIndex;
import nashorn.internal.runtime.linker.LinkerCallSite;
import nashorn.internal.runtime.linker.NashornCallSiteDescriptor;
import nashorn.internal.runtime.linker.NashornGuards;

/**
 * Base class for generic JavaScript objects.
 * <p>
 * Notes:
 * <ul>
 * <li>The map is used to identify properties in the object.</li>
 * <li>If the map is modified then it must be cloned and replaced.
 *     This notifies any code that made assumptions about the object that things have changed.
 *     Ex. CallSites that have been validated must check to see if the map has changed (or a map from a different object type) and hence relink the method to call.</li>
 * <li>Modifications of the map include adding/deleting attributes or changing a function field value.</li>
 * </ul>
 */
public abstract class ScriptObject implements PropertyAccess, Cloneable {

    /** __proto__ special property name inside object literals. ES6 draft. */
    public static final String PROTO_PROPERTY_NAME   = "__proto__";

    /** Search fall back routine name for "no such method" */
    public static final String NO_SUCH_METHOD_NAME   = "__noSuchMethod__";

    /** Search fall back routine name for "no such property" */
    public static final String NO_SUCH_PROPERTY_NAME = "__noSuchProperty__";

    /** Per ScriptObject flag - is this an array object? */
    public static final int IS_ARRAY               = 1 << 0;

    /** Per ScriptObject flag - is this an arguments object? */
    public static final int IS_ARGUMENTS           = 1 << 1;

    /** Is length property not-writable? */
    public static final int IS_LENGTH_NOT_WRITABLE = 1 << 2;

    /** Is this a builtin object? */
    public static final int IS_BUILTIN             = 1 << 3;

    /** Is this an internal object that should not be visible to scripts? */
    public static final int IS_INTERNAL            = 1 << 4;

    /**
     * Spill growth rate - by how many elements does {@link ScriptObject#primitiveSpill} and {@link ScriptObject#objectSpill} when full
     */
    public static final int SPILL_RATE = 8;

    /** Map to property information and accessor functions. Ordered by insertion. */
    private PropertyMap map;

    /** objects proto. */
    private ScriptObject proto;

    /** Object flags. */
    private int flags;

    /** Area for primitive properties added to object after instantiation, see {@link AccessorProperty} */
    protected long[]   primitiveSpill;

    /** Area for reference properties added to object after instantiation, see {@link AccessorProperty} */
    protected Object[] objectSpill;

    /** Indexed array data. */
    private ArrayData arrayData;

    /** Method handle to retrieve prototype of this object */
    public static final MethodHandle GETPROTO = findOwnMH_V("getProto", ScriptObject.class);

    static final MethodHandle MEGAMORPHIC_GET = findOwnMH_V("megamorphicGet", Object.class, String.class, boolean.class, boolean.class);
    static final MethodHandle GLOBALFILTER = findOwnMH_S("globalFilter", Object.class, Object.class);
    static final MethodHandle DECLARE_AND_SET = findOwnMH_V("declareAndSet", void.class, String.class, Object.class);

    private static final MethodHandle TRUNCATINGFILTER = findOwnMH_S("truncatingFilter", Object[].class, int.class, Object[].class);
    private static final MethodHandle KNOWNFUNCPROPGUARDSELF = findOwnMH_S("knownFunctionPropertyGuardSelf", boolean.class, Object.class, PropertyMap.class, MethodHandle.class, ScriptFunction.class);
    private static final MethodHandle KNOWNFUNCPROPGUARDPROTO = findOwnMH_S("knownFunctionPropertyGuardProto", boolean.class, Object.class, PropertyMap.class, MethodHandle.class, int.class, ScriptFunction.class);

    private static final ArrayList<MethodHandle> PROTO_FILTERS = new ArrayList<>();

    /** Method handle for getting the array data */
    public static final Call GET_ARRAY = virtualCall(MethodHandles.lookup(), ScriptObject.class, "getArray", ArrayData.class);

    /** Method handle for getting a function argument at a given index. Used from MapCreator */
    public static final Call GET_ARGUMENT = virtualCall(MethodHandles.lookup(), ScriptObject.class, "getArgument", Object.class, int.class);

    /** Method handle for setting a function argument at a given index. Used from MapCreator */
    public static final Call SET_ARGUMENT = virtualCall(MethodHandles.lookup(), ScriptObject.class, "setArgument", void.class, int.class, Object.class);

    /** Method handle for getting the proto of a ScriptObject */
    public static final Call GET_PROTO = virtualCallNoLookup(ScriptObject.class, "getProto", ScriptObject.class);

    /** Method handle for getting the proto of a ScriptObject */
    public static final Call GET_PROTO_DEPTH = virtualCallNoLookup(ScriptObject.class, "getProto", ScriptObject.class, int.class);

    /** Method handle for setting the proto of a ScriptObject */
    public static final Call SET_GLOBAL_OBJECT_PROTO = staticCallNoLookup(ScriptObject.class, "setGlobalObjectProto", void.class, ScriptObject.class);

    /** Method handle for setting the proto of a ScriptObject after checking argument */
    public static final Call SET_PROTO_FROM_LITERAL = virtualCallNoLookup(ScriptObject.class, "setProtoFromLiteral", void.class, Object.class);

    /** Method handle for setting the user accessors of a ScriptObject */
    //TODO fastpath this
    public static final Call SET_USER_ACCESSORS = virtualCallNoLookup(ScriptObject.class, "setUserAccessors", void.class, Object.class, ScriptFunction.class, ScriptFunction.class);

    /** Method handle for generic property setter */
    public static final Call GENERIC_SET = virtualCallNoLookup(ScriptObject.class, "set", void.class, Object.class, Object.class, int.class);

    public static final Call DELETE = virtualCall(MethodHandles.lookup(), ScriptObject.class, "delete", boolean.class, Object.class);

    static final MethodHandle[] SET_SLOW = new MethodHandle[] {
        findOwnMH_V("set", void.class, Object.class, int.class, int.class),
        findOwnMH_V("set", void.class, Object.class, double.class, int.class),
        findOwnMH_V("set", void.class, Object.class, Object.class, int.class)
    };

    /** Method handle to reset the map of this ScriptObject */
    public static final Call SET_MAP = virtualCallNoLookup(ScriptObject.class, "setMap", void.class, PropertyMap.class);

    static final MethodHandle CAS_MAP = findOwnMH_V("compareAndSetMap", boolean.class, PropertyMap.class, PropertyMap.class);
    static final MethodHandle EXTENSION_CHECK = findOwnMH_V("extensionCheck", boolean.class, String.class);
    static final MethodHandle ENSURE_SPILL_SIZE = findOwnMH_V("ensureSpillSize", Object.class, int.class);

    private static final GuardedInvocation DELETE_GUARDED = new GuardedInvocation(DELETE.methodHandle(), NashornGuards.getScriptObjectGuard());

    /**
     * Constructor
     */
    public ScriptObject() {
        this(null);
    }

    /**
    * Constructor
    * @param map {@link PropertyMap} used to create the initial object
    */
    public ScriptObject(PropertyMap map) {
        this.arrayData = ArrayData.EMPTY_ARRAY;
        this.setMap(map == null ? PropertyMap.newMap() : map);
    }

    /**
     * Constructor that directly sets the prototype to {@code proto} and property map to {@code map} without invalidating the map as calling {@link #setProto(ScriptObject)} would do.
     * This should only be used for objects that are always constructed with the same combination of prototype and property map.
     * @param proto the prototype object
     * @param map initial {@link PropertyMap}
     */
    protected ScriptObject(ScriptObject proto, PropertyMap map) {
        this(map);
        this.proto = proto;
    }

    /**
     * Constructor used to instantiate spill properties directly.
     * Used from SpillObjectCreator.
     * @param map            property maps
     * @param primitiveSpill primitive spills
     * @param objectSpill    reference spills
     */
    public ScriptObject(PropertyMap map, long[] primitiveSpill, Object[] objectSpill) {
        this(map);
        this.primitiveSpill = primitiveSpill;
        this.objectSpill    = objectSpill;
        assert primitiveSpill == null || primitiveSpill.length == objectSpill.length : " primitive spill pool size is not the same length as object spill pool size";
    }

    /**
     * Check whether this is a global object
     */
    protected boolean isGlobal() {
        return false;
    }

    private static int alignUp(int size, int alignment) {
        return size + alignment - 1 & ~(alignment - 1);
    }

    /**
     * Given a number of properties, return the aligned to SPILL_RATE buffer size required for the smallest spill pool needed to house them.
     * @param nProperties number of properties
     * @return property buffer length, a multiple of SPILL_RATE
     */
    public static int spillAllocationLength(int nProperties) {
        return alignUp(nProperties, SPILL_RATE);
    }

    /**
     * Copy all properties from the source object with their receiver bound to the source.
     * @param source The source object to copy from.
     */
    public void addBoundProperties(ScriptObject source) {
        addBoundProperties(source, source.getMap().getProperties());
    }

    /**
     * Copy all properties from the array with their receiver bound to the source.
     */
    public void addBoundProperties(ScriptObject source, Property[] properties) {
        var newMap = this.getMap();
        var extensible = newMap.isExtensible();

        for (var property : properties) {
            newMap = addBoundProperty(newMap, source, property, extensible);
        }

        this.setMap(newMap);
    }

    /**
     * Add a bound property from {@code source}, using the interim property map {@code propMap}, and return the new interim property map.
     * @param propMap the property map
     * @param source the source object
     * @param property the property to be added
     * @param extensible whether the current object is extensible or not
     * @return the new property map
     */
    protected PropertyMap addBoundProperty(PropertyMap propMap, ScriptObject source, Property property, boolean extensible) {
        var newMap = propMap;
        var key = property.getKey();
        var oldProp = newMap.findProperty(key);
        if (oldProp == null) {
            if (! extensible) {
                throw typeError("object.non.extensible", key.toString(), ScriptRuntime.safeToString(this));
            }

            if (property instanceof UserAccessorProperty) {
                // Note: we copy accessor functions to this object which is semantically different from binding.
                var prop = this.newUserAccessors(key, property.getFlags(), property.getGetterFunction(source), property.getSetterFunction(source));
                newMap = newMap.addPropertyNoHistory(prop);
            } else {
                newMap = newMap.addPropertyBind((AccessorProperty)property, source);
            }
        } else {
            // See ECMA section 10.5 Declaration Binding Instantiation step 5 processing each function declaration.
            if (property.isFunctionDeclaration() && !oldProp.isConfigurable()) {
                if (oldProp instanceof UserAccessorProperty || !(oldProp.isWritable() && oldProp.isEnumerable())) {
                    throw typeError("cant.redefine.property", key.toString(), ScriptRuntime.safeToString(this));
                }
            }
        }
        return newMap;
    }

    /**
     * Copy all properties from the array with their receiver bound to the source.
     */
    public void addBoundProperties(Object source, AccessorProperty[] properties) {
        var newMap = this.getMap();
        var extensible = newMap.isExtensible();

        for (var property : properties) {
            var key = property.getKey();

            if (newMap.findProperty(key) == null) {
                if (! extensible) {
                    throw typeError("object.non.extensible", key.toString(), ScriptRuntime.safeToString(this));
                }
                newMap = newMap.addPropertyBind(property, source);
            }
        }

        this.setMap(newMap);
    }

    /**
     * Bind the method handle to the specified receiver, while preserving its original type (it will just ignore the first argument in lieu of the bound argument).
     */
    static MethodHandle bindTo(MethodHandle methodHandle, Object receiver) {
        return MH.dropArguments(MH.bindTo(methodHandle, receiver), 0, methodHandle.type().parameterType(0));
    }

    /**
     * Return a property iterator.
     */
    public Iterator<String> propertyIterator() {
        return new KeyIterator(this);
    }

    /**
     * Return a property value iterator.
     */
    public Iterator<Object> valueIterator() {
        return new ValueIterator(this);
    }

    /**
     * ECMA 8.10.1 IsAccessorDescriptor ( Desc )
     * @return true if this has a {@link AccessorPropertyDescriptor} with a getter or a setter
     */
    public final boolean isAccessorDescriptor() {
        return has(GET) || has(SET);
    }

    /**
     * ECMA 8.10.2 IsDataDescriptor ( Desc )
     * @return true if this has a {@link DataPropertyDescriptor}, i.e. the object has a property value and is writable
     */
    public final boolean isDataDescriptor() {
        return has(VALUE) || has(WRITABLE);
    }

    /**
      * ECMA 8.10.5 ToPropertyDescriptor ( Obj )
      * @return property descriptor
      */
    public final PropertyDescriptor toPropertyDescriptor() {
        var global = Context.getGlobal();

        PropertyDescriptor desc;
        if (isDataDescriptor()) {
            if (has(SET) || has(GET)) {
                throw typeError(global, "inconsistent.property.descriptor");
            }
            desc = global.newDataDescriptor(UNDEFINED, false, false, false);
        }
        else if (isAccessorDescriptor()) {
            if (has(VALUE) || has(WRITABLE)) {
                throw typeError(global, "inconsistent.property.descriptor");
            }
            desc = global.newAccessorDescriptor(UNDEFINED, UNDEFINED, false, false);
        } else {
            desc = global.newGenericDescriptor(false, false);
        }

        return desc.fillFrom(this);
    }

    /**
     * ECMA 8.10.5 ToPropertyDescriptor ( Obj )
     * @param global  global scope object
     * @param obj object to create property descriptor from
     * @return property descriptor
     */
    public static PropertyDescriptor toPropertyDescriptor(Global global, Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).toPropertyDescriptor();
        }

        throw typeError(global, "not.an.object", ScriptRuntime.safeToString(obj));
    }

    /**
     * ECMA 8.12.1 [[GetOwnProperty]] (P)
     * @param key property key
     * @return Returns the Property Descriptor of the named own property of this object, or undefined if absent.
     */
    public Object getOwnPropertyDescriptor(Object key) {
        var property = getMap().findProperty(key);

        var global = Context.getGlobal();

        if (property != null) {
            var get = property.getGetterFunction(this);
            var set = property.getSetterFunction(this);

            var configurable = property.isConfigurable();
            var enumerable = property.isEnumerable();
            var writable = property.isWritable();

            if (property.isAccessorProperty()) {
                return global.newAccessorDescriptor(
                    get != null ? get : UNDEFINED,
                    set != null ? set : UNDEFINED,
                    configurable,
                    enumerable);
            }

            return global.newDataDescriptor(getWithProperty(property), configurable, enumerable, writable);
        }

        var index = getArrayIndex(key);
        var array = getArray();

        if (array.has(index)) {
            return array.getDescriptor(global, index);
        }

        return UNDEFINED;
    }

    /**
     * ECMA 8.12.2 [[GetProperty]] (P)
     * @param key property key
     * @return Returns the fully populated Property Descriptor of the named property of this object, or undefined if absent.
     */
    public Object getPropertyDescriptor(String key) {
        var res = getOwnPropertyDescriptor(key);

        if (res != UNDEFINED) {
            return res;
        } else if (getProto() != null) {
            return getProto().getOwnPropertyDescriptor(key);
        } else {
            return UNDEFINED;
        }
    }

    /**
     * Invalidate any existing global constant method handles that may exist for {@code key}.
     */
    protected void invalidateGlobalConstant(Object key) {
        var globalConstants = getGlobalConstants();
        if (globalConstants != null) {
            globalConstants.delete(key);
        }
    }

    /**
     * ECMA 8.12.9 [[DefineOwnProperty]] (P, Desc, Throw)
     * @param key the property key
     * @param propertyDesc the property descriptor
     * @param reject is the property extensible - true means new definitions are rejected
     * @return true if property was successfully defined
     */
    public boolean defineOwnProperty(Object key, Object propertyDesc, boolean reject) {
        var global = Context.getGlobal();
        var desc = toPropertyDescriptor(global, propertyDesc);
        var current = getOwnPropertyDescriptor(key);

        invalidateGlobalConstant(key);

        if (current == UNDEFINED) {
            if (isExtensible()) {
                // add a new own property
                addOwnProperty(key, desc);
                return true;
            }
            // new property added to non-extensible object
            if (reject) {
                throw typeError(global, "object.non.extensible", key.toString(), ScriptRuntime.safeToString(this));
            }
            return false;
        }

        // modifying an existing property
        var currentDesc = (PropertyDescriptor)current;
        var newDesc = desc;

        if (newDesc.type() == PropertyDescriptor.GENERIC && !newDesc.has(CONFIGURABLE) && !newDesc.has(ENUMERABLE)) {
            // every descriptor field is absent
            return true;
        }

        if (newDesc.hasAndEquals(currentDesc)) {
            // every descriptor field of the new is same as the current
            return true;
        }

        if (!currentDesc.isConfigurable()) {
            if (newDesc.has(CONFIGURABLE) && newDesc.isConfigurable()) {
                // not configurable can not be made configurable
                if (reject) {
                    throw typeError(global, "cant.redefine.property", key.toString(), ScriptRuntime.safeToString(this));
                }
                return false;
            }

            if (newDesc.has(ENUMERABLE) &&
                currentDesc.isEnumerable() != newDesc.isEnumerable()) {
                // cannot make non-enumerable as enumerable or vice-versa
                if (reject) {
                    throw typeError(global, "cant.redefine.property", key.toString(), ScriptRuntime.safeToString(this));
                }
                return false;
            }
        }

        var propFlags = Property.mergeFlags(currentDesc, newDesc);
        var property = getMap().findProperty(key);

        if (currentDesc.type() == PropertyDescriptor.DATA && (newDesc.type() == PropertyDescriptor.DATA || newDesc.type() == PropertyDescriptor.GENERIC)) {
            if (!currentDesc.isConfigurable() && !currentDesc.isWritable()) {
                if (newDesc.has(WRITABLE) && newDesc.isWritable() || newDesc.has(VALUE) && !ScriptRuntime.sameValue(currentDesc.getValue(), newDesc.getValue())) {
                    if (reject) {
                        throw typeError(global, "cant.redefine.property", key.toString(), ScriptRuntime.safeToString(this));
                    }
                    return false;
                }
            }

            var newValue = newDesc.has(VALUE);
            var value = newValue ? newDesc.getValue() : currentDesc.getValue();

            if (newValue && property != null) {
                // Temporarily clear flags.
                property = modifyOwnProperty(property, 0);
                set(key, value, 0);
                // This might change the map if we change types of the property hence we need to read it again.
                // Note that we should probably have the setter return the new property throughout and in general respect Property return values from modify and add functions - which we don't seem to do at all here :-(
                // There is already a bug filed to generify PropertyAccess so we can have the setter return e.g. a Property
                property = getMap().findProperty(key);
            }

            if (property == null) {
                // promoting an arrayData value to actual property
                addOwnProperty(key, propFlags, value);
                checkIntegerKey(key);
            } else {
                // Now set the new flags
                modifyOwnProperty(property, propFlags);
            }
        } else if (currentDesc.type() == PropertyDescriptor.ACCESSOR && (newDesc.type() == PropertyDescriptor.ACCESSOR || newDesc.type() == PropertyDescriptor.GENERIC)) {
            if (!currentDesc.isConfigurable()) {
                if (newDesc.has(PropertyDescriptor.GET) && !ScriptRuntime.sameValue(currentDesc.getGetter(), newDesc.getGetter()) || newDesc.has(PropertyDescriptor.SET) && !ScriptRuntime.sameValue(currentDesc.getSetter(), newDesc.getSetter())) {
                    if (reject) {
                        throw typeError(global, "cant.redefine.property", key.toString(), ScriptRuntime.safeToString(this));
                    }
                    return false;
                }
            }
            // New set the new features.
            modifyOwnProperty(property, propFlags, newDesc.has(GET) ? newDesc.getGetter() : currentDesc.getGetter(), newDesc.has(SET) ? newDesc.getSetter() : currentDesc.getSetter());
        } else {
            // changing descriptor type
            if (!currentDesc.isConfigurable()) {
                // not configurable can not be made configurable
                if (reject) {
                    throw typeError(global, "cant.redefine.property", key.toString(), ScriptRuntime.safeToString(this));
                }
                return false;
            }

            propFlags = 0;

            // Preserve only configurable and enumerable from current desc if those are not overridden in the new property descriptor.
            var value = newDesc.has(CONFIGURABLE) ? newDesc.isConfigurable() : currentDesc.isConfigurable();
            if (!value) {
                propFlags |= Property.NOT_CONFIGURABLE;
            }
            value = newDesc.has(ENUMERABLE)? newDesc.isEnumerable() : currentDesc.isEnumerable();
            if (!value) {
                propFlags |= Property.NOT_ENUMERABLE;
            }

            var type = newDesc.type();
            if (type == PropertyDescriptor.DATA) {
                // get writable from the new descriptor
                value = newDesc.has(WRITABLE) && newDesc.isWritable();
                if (!value) {
                    propFlags |= Property.NOT_WRITABLE;
                }

                // delete the old property
                deleteOwnProperty(property);
                // add new data property
                addOwnProperty(key, propFlags, newDesc.getValue());
            } else if (type == PropertyDescriptor.ACCESSOR) {
                if (property == null) {
                    addOwnProperty(key, propFlags, newDesc.has(GET) ? newDesc.getGetter() : null, newDesc.has(SET) ? newDesc.getSetter() : null);
                } else {
                    // Modify old property with the new features.
                    modifyOwnProperty(property, propFlags, newDesc.has(GET) ? newDesc.getGetter() : null, newDesc.has(SET) ? newDesc.getSetter() : null);
                }
            }
        }

        checkIntegerKey(key);

        return true;
    }

    /**
     * Almost like defineOwnProperty(int,Object) for arrays this one does not add 'gap' elements (like the array one does).
     * @param index key for property
     * @param value value to define
     */
    public void defineOwnProperty(int index, Object value) {
        assert isValidArrayIndex(index) : "invalid array index";
        var longIndex = ArrayIndex.toLongIndex(index);
        var oldLength = getArray().length();
        if (longIndex >= oldLength) {
            setArray(getArray().ensure(longIndex).safeDelete(oldLength, longIndex - 1)); // false
        }
        setArray(getArray().set(index, value)); // false
    }

    private void checkIntegerKey(Object key) {
        var index = getArrayIndex(key);

        if (isValidArrayIndex(index)) {
            var data = getArray();

            if (data.has(index)) {
                setArray(data.delete(index));
            }
        }
    }

    /**
      * Add a new property to the object.
      */
    public final void addOwnProperty(Object key, PropertyDescriptor propertyDesc) {
        // Already checked that there is no own property with that key.
        var pdesc = propertyDesc;

        var propFlags = Property.toFlags(pdesc);

        if (pdesc.type() == PropertyDescriptor.GENERIC) {
            var global = Context.getGlobal();
            var dDesc = global.newDataDescriptor(UNDEFINED, false, false, false);

            dDesc.fillFrom((ScriptObject)pdesc);
            pdesc = dDesc;
        }

        var type = pdesc.type();
        if (type == PropertyDescriptor.DATA) {
            addOwnProperty(key, propFlags, pdesc.getValue());
        } else if (type == PropertyDescriptor.ACCESSOR) {
            addOwnProperty(key, propFlags, pdesc.has(GET) ? pdesc.getGetter() : null, pdesc.has(SET) ? pdesc.getSetter() : null);
        }

        checkIntegerKey(key);
    }

    /**
     * Low level property API (not using property descriptors)
     * <p>
     * Find a property in the prototype hierarchy.
     * Note: this is final and not a good idea to override.
     * If you have to, use {nashorn.internal.objects.NativeArray{@link #getProperty(String)} or {nashorn.internal.objects.NativeArray{@link #getPropertyDescriptor(String)} as the overriding way to find array properties
     * @see nashorn.internal.objects.NativeArray
     * @param key  Property key.
     * @param deep Whether the search should look up proto chain.
     * @return FindPropertyData or null if not found.
     */
    public final FindProperty findProperty(Object key, boolean deep) {
        return findProperty(key, deep, false, this);
    }

    /**
     * Low level property API (not using property descriptors)
     * <p>
     * Find a property in the prototype hierarchy.
     * Note: this is not a good idea to override except as it was done in {@link WithObject}.
     * If you have to, use {nashorn.internal.objects.NativeArray{@link #getProperty(String)} or {nashorn.internal.objects.NativeArray{@link #getPropertyDescriptor(String)} as the overriding way to find array properties
     * @see nashorn.internal.objects.NativeArray
     * @param key  Property key.
     * @param deep true if the search should look up proto chain
     * @param isScope true if this is a scope access
     * @param start the object on which the lookup was originally initiated
     * @return FindPropertyData or null if not found.
     */
    protected FindProperty findProperty(Object key, boolean deep, boolean isScope, ScriptObject start) {

        var selfMap = getMap();
        var property = selfMap.findProperty(key);

        if (property != null) {
            return new FindProperty(start, this, property);
        }

        if (deep) {
            var myProto = getProto();
            var find = myProto == null ? null : myProto.findProperty(key, true, isScope, start);
            // checkSharedProtoMap must be invoked after myProto.checkSharedProtoMap to propagate shared proto invalidation up the prototype chain. It also must be invoked when prototype is null.
            checkSharedProtoMap();
            return find;
        }

        return null;
    }

    /**
     * Low level property API.
     * This is similar to {@link #findProperty(Object, boolean)} but returns a {@code boolean} value instead of a {@link FindProperty} object.
     * @param key Property key.
     * @param deep Whether the search should look up proto chain.
     * @return true if the property was found.
     */
    boolean hasProperty(Object key, boolean deep) {
        if (getMap().findProperty(key) != null) {
            return true;
        }

        if (deep) {
            var myProto = getProto();
            if (myProto != null) {
                return myProto.hasProperty(key, true);
            }
        }

        return false;
    }

    private SwitchPoint findBuiltinSwitchPoint(Object key) {
        for (var myProto = getProto(); myProto != null; myProto = myProto.getProto()) {
            var prop = myProto.getMap().findProperty(key);
            if (prop != null) {
                var sp = prop.getBuiltinSwitchPoint();
                if (sp != null && !sp.hasBeenInvalidated()) {
                    return sp;
                }
            }
        }
        return null;
    }

    /**
     * Add a new property to the object.
     * This a more "low level" way that doesn't involve {@link PropertyDescriptor}s
     */
    public final Property addOwnProperty(Object key, int propertyFlags, ScriptFunction getter, ScriptFunction setter) {
        return addOwnProperty(newUserAccessors(key, propertyFlags, getter, setter));
    }

    /**
     * Add a new property to the object.
     * This a more "low level" way that doesn't involve {@link PropertyDescriptor}s
     */
    public final Property addOwnProperty(Object key, int propertyFlags, Object value) {
        return addSpillProperty(key, propertyFlags, value, true);
    }

    /**
     * Add a new property to the object.
     * This a more "low level" way that doesn't involve {@link PropertyDescriptor}s
     */
    public final Property addOwnProperty(Property newProperty) {
        var oldMap = getMap();
        for (;;) {
            var newMap = oldMap.addProperty(newProperty);
            if (!compareAndSetMap(oldMap, newMap)) {
                oldMap = getMap();
                var oldProperty = oldMap.findProperty(newProperty.getKey());

                if (oldProperty != null) {
                    return oldProperty;
                }
            } else {
                return newProperty;
            }
        }
    }

    private void erasePropertyValue(Property property) {
        // Erase the property field value with undefined.
        // If the property is an accessor property we don't want to call the setter!!
        if (property != null && !property.isAccessorProperty()) {
            property.setValue(this, this, UNDEFINED); // false
        }
    }

    /**
     * Delete a property from the object.
     */
    public final boolean deleteOwnProperty(Property property) {
        erasePropertyValue(property);
        PropertyMap oldMap = getMap();

        for (;;) {
            var newMap = oldMap.deleteProperty(property);

            if (newMap == null) {
                return false;
            }

            if (!compareAndSetMap(oldMap, newMap)) {
                oldMap = getMap();
            } else {
                // delete getter and setter function references so that we don't leak
                if (property instanceof UserAccessorProperty) {
                    ((UserAccessorProperty)property).setAccessors(this, getMap(), null);
                }

                invalidateGlobalConstant(property.getKey());
                return true;
            }
        }

    }

    /**
     * Fast initialization functions for ScriptFunctions, to avoid creating setters that probably aren't used.
     * Inject directly into the spill pool the defaults for "arguments" and "caller", asserting the property is already defined in the map.
     */
    protected final void initUserAccessors(String key, ScriptFunction getter, ScriptFunction setter) {
        var map = getMap();
        var property = map.findProperty(key);
        if (property instanceof UserAccessorProperty) { // TODO: review
            ensureSpillSize(property.getSlot());
            objectSpill[property.getSlot()] = new UserAccessorProperty.Accessors(getter, setter);
        }
    }

    /**
     * Modify a property in the object
     */
    public final Property modifyOwnProperty(Property oldProperty, int propertyFlags, ScriptFunction getter, ScriptFunction setter) {
        Property newProperty;

        if (oldProperty instanceof UserAccessorProperty) {
            var uc = (UserAccessorProperty)oldProperty;
            var slot = uc.getSlot();

            assert uc.getLocalType() == Object.class;
            var gs = uc.getAccessors(this); //this crashes
            assert gs != null;
            // reuse existing getter setter for speed
            gs.set(getter, setter);
            if (uc.getFlags() == (propertyFlags | Property.IS_ACCESSOR_PROPERTY)) {
                return oldProperty;
            }
            newProperty = new UserAccessorProperty(uc.getKey(), propertyFlags, slot);
        } else {
            // erase old property value and create new user accessor property
            erasePropertyValue(oldProperty);
            newProperty = newUserAccessors(oldProperty.getKey(), propertyFlags, getter, setter);
        }

        return modifyOwnProperty(oldProperty, newProperty);
    }

    /**
      * Modify a property in the object
      */
    public final Property modifyOwnProperty(Property oldProperty, int propertyFlags) {
        return modifyOwnProperty(oldProperty, oldProperty.setFlags(propertyFlags));
    }

    /**
     * Modify a property in the object, replacing a property with a new one
     */
    private Property modifyOwnProperty(Property oldProperty, Property newProperty) {
        if (oldProperty == newProperty) {
            return newProperty; //nop
        }

        assert newProperty.getKey().equals(oldProperty.getKey()) : "replacing property with different key";

        var oldMap = getMap();

        for (;;) {
            var newMap = oldMap.replaceProperty(oldProperty, newProperty);

            if (!compareAndSetMap(oldMap, newMap)) {
                oldMap = getMap();
                var oldPropertyLookup = oldMap.findProperty(oldProperty.getKey());

                if (oldPropertyLookup != null && oldPropertyLookup.equals(newProperty)) {
                    return oldPropertyLookup;
                }
            } else {
                return newProperty;
            }
        }
    }

    /**
     * Update getter and setter in an object literal.
     */
    public final void setUserAccessors(Object key, ScriptFunction getter, ScriptFunction setter) {
        var realKey = JSType.toPropertyKey(key);
        var oldProperty = getMap().findProperty(realKey);
        if (oldProperty instanceof UserAccessorProperty) {
            modifyOwnProperty(oldProperty, oldProperty.getFlags(), getter, setter);
        } else {
            addOwnProperty(newUserAccessors(realKey, oldProperty != null ? oldProperty.getFlags() : 0, getter, setter));
        }
    }

    private static int getIntValue(FindProperty find, int programPoint) {
        var getter = find.getGetter(int.class, programPoint, null);
        if (getter != null) {
            try {
                return (int)getter.invokeExact((Object)find.getGetterReceiver());
            } catch (Throwable e) {
                Util.uncheck(e);
            }
        }

        return UNDEFINED_INT;
    }

    private static double getDoubleValue(FindProperty find, int programPoint) {
        var getter = find.getGetter(double.class, programPoint, null);
        if (getter != null) {
            try {
                return (double)getter.invokeExact((Object)find.getGetterReceiver());
            } catch (Throwable e) {
                Util.uncheck(e);
            }
        }

        return UNDEFINED_DOUBLE;
    }

    /**
     * Return methodHandle of value function for call.
     */
    protected static MethodHandle getCallMethodHandle(FindProperty find, MethodType type, String bindName) {
        return getCallMethodHandle(find.getObjectValue(), type, bindName);
    }

    /**
     * Return methodHandle of value function for call.
     */
    private static MethodHandle getCallMethodHandle(Object value, MethodType type, String bindName) {
        return value instanceof ScriptFunction ? ((ScriptFunction)value).getCallMethodHandle(type, bindName) : null;
    }

    /**
     * Get value using found property.
     */
    public final Object getWithProperty(Property property) {
        return new FindProperty(this, this, property).getObjectValue();
    }

    /**
     * Get a property given a key
     */
    public final Property getProperty(String key) {
        return getMap().findProperty(key);
    }

    /**
     * Overridden by {@link nashorn.internal.objects.NativeArguments} class (internal use.)
     * Used for argument access in a vararg function using parameter name.
     * Returns the argument at a given key (index)
     * @param key argument index
     * @return the argument at the given position, or undefined if not present
     */
    public Object getArgument(int key) {
        return get(key);
    }

    /**
     * Overridden by {@link nashorn.internal.objects.NativeArguments} class (internal use.)
     * Used for argument access in a vararg function using parameter name.
     * Returns the argument at a given key (index)
     * @param key   argument index
     * @param value the value to write at the given index
     */
    public void setArgument(int key, Object value) {
        set(key, value, 0);
    }

    /**
     * Return the current context from the object's map.
     */
    protected Context getContext() {
        return Context.fromClass(getClass());
    }

    /**
     * Return the map of an object.
     */
    public final PropertyMap getMap() {
        return map;
    }

    /**
     * Set the initial map.
     */
    public final void setMap(PropertyMap map) {
        this.map = map;
    }

    /**
     * Conditionally set the new map if the old map is the same.
     * @param oldMap Map prior to manipulation.
     * @param newMap Replacement map.
     * @return true if the operation succeeded.
     */
    protected final boolean compareAndSetMap(PropertyMap oldMap, PropertyMap newMap) {
        if (oldMap == this.map) {
            this.map = newMap;
            return true;
        }
        return false;
     }

    /**
     * Return the __proto__ of an object.
     */
    public final ScriptObject getProto() {
        return proto;
    }

    /**
     * Get the proto of a specific depth
     * @param n depth
     * @return proto at given depth
     */
    public final ScriptObject getProto(int n) {
        var p = this;
        for (var i = n; i > 0; i--) {
            p = p.getProto();
        }
        return p;
    }

    /**
     * Set the __proto__ of an object.
     */
    public final void setProto(ScriptObject newProto) {
        var oldProto = proto;

        if (oldProto != newProto) {
            proto = newProto;

            // Let current listeners know that the prototype has changed
            getMap().protoChanged();
            // Replace our current allocator map with one that is associated with the new prototype.
            setMap(getMap().changeProto(newProto));
        }
    }

    /**
     * Set the initial __proto__ of this object.
     * This should be used instead of {@link #setProto} if it is known that the current property map will not be used on a new object with any other parent property map, so we can pass over property map invalidation/evolution.
     */
    public void setInitialProto(ScriptObject initialProto) {
        this.proto = initialProto;
    }

    /**
     * Invoked from generated bytecode to initialize the prototype of object literals to the global Object prototype.
     * @param obj the object literal that needs to have its prototype initialized to the global Object prototype.
     */
    public static void setGlobalObjectProto(ScriptObject obj) {
        obj.setInitialProto(Global.objectPrototype());
    }

    /**
     * Set the __proto__ of an object with checks.
     * This is the built-in operation [[SetPrototypeOf]]
     * See ES6 draft spec: 9.1.2 [[SetPrototypeOf]] (V)
     * @param newProto Prototype to set.
     */
    public final void setPrototypeOf(Object newProto) {
        if (newProto == null || newProto instanceof ScriptObject) {
            if (! isExtensible()) {
                // okay to set same proto again - even if non-extensible

                if (newProto == getProto()) {
                    return;
                }
                throw typeError("__proto__.set.non.extensible", ScriptRuntime.safeToString(this));
            }

            // check for circularity
            var p = (ScriptObject)newProto;
            while (p != null) {
                if (p == this) {
                    throw typeError("circular.__proto__.set", ScriptRuntime.safeToString(this));
                }
                p = p.getProto();
            }
            setProto((ScriptObject) newProto);
        } else {
            throw typeError("cant.set.proto.to.non.object", ScriptRuntime.safeToString(this), ScriptRuntime.safeToString(newProto));
        }
    }

    /**
     * Set the __proto__ of an object from an object literal.
     * See ES6 draft spec: B.3.1 __proto__ Property Names in Object Initializers. Step 6 handling of "__proto__".
     */
    public final void setProtoFromLiteral(Object newProto) {
        if (newProto == null || newProto instanceof ScriptObject) {
            setPrototypeOf(newProto);
        } else {
            // Some non-object, non-null.
            // Then, we need to set Object.prototype as the new __proto__
            setPrototypeOf(Global.objectPrototype());
        }
    }

    /**
     * return an array of all property keys - all inherited, non-enumerable included.
     * This is meant for source code completion by interactive shells or editors.
     */
    public String[] getAllKeys() {
        var keys = new HashSet<String>();
        var nonEnumerable = new HashSet<String>();
        for (var self = this; self != null; self = self.getProto()) {
            keys.addAll(Arrays.asList(self.getOwnKeys(String.class, true, nonEnumerable)));
        }
        return keys.toArray(new String[0]);
    }

    /**
     * Return an array of own property keys associated with the object.
     * @param all True if to include non-enumerable keys.
     */
    public final String[] getOwnKeys(boolean all) {
        return getOwnKeys(String.class, all, null);
    }

    /**
     * Return an array of own property keys associated with the object.
     * @param all True if to include non-enumerable keys.
     */
    public final Symbol[] getOwnSymbols(boolean all) {
        return getOwnKeys(Symbol.class, all, null);
    }

    /**
     * return an array of own property keys associated with the object.
     * @param <T> the type returned keys.
     * @param type the type of keys to return, either {@code String.class} or {@code Symbol.class}.
     * @param all True if to include non-enumerable keys.
     * @param nonEnumerable set of non-enumerable properties seen already. Used to filter out shadowed, but enumerable properties from proto children.
     * @return Array of keys.
     */
    protected <T> T[] getOwnKeys(Class<T> type, boolean all, Set<T> nonEnumerable) {
        var keys = new ArrayList<Object>();
        var selfMap = this.getMap();

        var array = getArray();

        if (type == String.class) {
            for (var iter = array.indexIterator(); iter.hasNext(); ) {
                keys.add(JSType.toString(iter.next().longValue()));
            }
        }

        for (var property : selfMap.getProperties()) {
            var enumerable = property.isEnumerable();
            var key = property.getKey();
            if (!type.isInstance(key)) {
                continue;
            }
            if (all) {
                keys.add(key);
            } else if (enumerable) {
                // either we don't have non-enumerable filter set or filter set does not contain the current property.
                if (nonEnumerable == null || !nonEnumerable.contains(key)) {
                    keys.add(key);
                }
            } else {
                // store this non-enumerable property for later proto walk
                if (nonEnumerable != null) {
                    nonEnumerable.add((T) key);
                }
            }
        }

        return keys.toArray((T[]) Array.newInstance(type, keys.size()));
    }

    /**
     * Check if this ScriptObject has array entries.
     * This means that someone has set values with numeric keys in the object.
     */
    public boolean hasArrayEntries() {
        return getArray().length() > 0 || getMap().containsArrayKeys();
    }

    /**
     * Return the valid JavaScript type name descriptor
     */
    public String getClassName() {
        return "Object";
    }

    /**
     * {@code length} is a well known property.
     * This is its getter.
     * Note that this *may* be optimized by other classes
     * @return length property value for this ScriptObject
     */
    public Object getLength() {
        return get("length");
    }

    /**
     * Stateless toString for ScriptObjects.
     * @return string description of this object, e.g. {@code [object Object]}
     */
    public String safeToString() {
        return "[object " + getClassName() + "]";
    }

    /**
     * Return the default value of the object with a given preferred type hint.
     * The preferred type hints are String.class for type String, Number.class for type Number.
     * <p>
     * A <code>hint</code> of null means "no hint".
     * ECMA 8.12.8 [[DefaultValue]](hint)
     * @param typeHint the preferred type hint
     * @return the default value
     */
    public Object getDefaultValue(Class<?> typeHint) {
        // We delegate to Global, as the implementation uses dynamic call sites to invoke object's "toString" and "valueOf" methods, and in order to avoid those call sites from becoming megamorphic when multiple contexts are being executed in a long-running program, we move the code and their associated dynamic call sites (Global.TO_STRING and Global.VALUE_OF) into per-context code.
        return Context.getGlobal().getDefaultValue(this, typeHint);
    }

    /**
     * Checking whether a script object is an instance of another.
     * Used in {@link ScriptFunction} for hasInstance implementation, walks the proto chain
     * @param instance instance to check
     * @return true if 'instance' is an instance of this object
     */
    public boolean isInstance(ScriptObject instance) {
        return false;
    }

    /**
     * Flag this ScriptObject as non extensible
     * @return the object after being made non extensible
     */
    public ScriptObject preventExtensions() {
        var oldMap = getMap();
        while (!compareAndSetMap(oldMap,  getMap().preventExtensions())) {
            oldMap = getMap();
        }

        //invalidate any fast array setters
        var array = getArray();
        assert array != null;
        setArray(ArrayData.preventExtension(array));
        return this;
    }

    /**
     * Check whether if an Object (not just a ScriptObject) represents JavaScript array
     */
    public static boolean isArray(Object obj) {
        return obj instanceof ScriptObject && ((ScriptObject)obj).isArray();
    }

    /**
     * Check if this ScriptObject is an array
     */
    public final boolean isArray() {
        return (flags & IS_ARRAY) != 0;
    }

    /**
     * Flag this ScriptObject as being an array
     */
    public final void setIsArray() {
        flags |= IS_ARRAY;
    }

    /**
     * Check if this ScriptObject is an {@code arguments} vector
     */
    public final boolean isArguments() {
        return (flags & IS_ARGUMENTS) != 0;
    }

    /**
     * Flag this ScriptObject as being an {@code arguments} vector
     */
    public final void setIsArguments() {
        flags |= IS_ARGUMENTS;
    }

    /**
     * Check if this object has non-writable length property
     */
    public boolean isLengthNotWritable() {
        return (flags & IS_LENGTH_NOT_WRITABLE) != 0;
    }

    /**
     * Flag this object as having non-writable length property.
     */
    public void setIsLengthNotWritable() {
        flags |= IS_LENGTH_NOT_WRITABLE;
    }

    /**
     * Get the {@link ArrayData}, for this ScriptObject, ensuring it is of a type that can handle elementType
     */
    public final ArrayData getArray(Class<?> elementType) {
        if (elementType == null) {
            return arrayData;
        }
        var newArrayData = arrayData.convert(elementType);
        if (newArrayData != arrayData) {
            arrayData = newArrayData;
        }
        return newArrayData;
    }

    /**
     * Get the {@link ArrayData} for this ScriptObject if it is an array
     */
    public final ArrayData getArray() {
        return arrayData;
    }

    /**
     * Set the {@link ArrayData} for this ScriptObject if it is to be an array
     */
    public final void setArray(ArrayData arrayData) {
        this.arrayData = arrayData;
    }

    /**
     * Check if this ScriptObject is extensible
     */
    public boolean isExtensible() {
        return getMap().isExtensible();
    }

    /**
     * ECMAScript 15.2.3.8 - seal implementation
     */
    public ScriptObject seal() {
        var oldMap = getMap();

        for (;;) {
            var newMap = getMap().seal();

            if (!compareAndSetMap(oldMap, newMap)) {
                oldMap = getMap();
            } else {
                setArray(ArrayData.seal(getArray()));
                return this;
            }
        }
    }

    /**
     * Check whether this ScriptObject is sealed
     */
    public boolean isSealed() {
        return getMap().isSealed();
    }

    /**
     * ECMA 15.2.39 - freeze implementation.
     * Freeze this ScriptObject
     * @return the frozen ScriptObject
     */
    public ScriptObject freeze() {
        var oldMap = getMap();

        for (;;) {
            var newMap = getMap().freeze();

            if (!compareAndSetMap(oldMap, newMap)) {
                oldMap = getMap();
            } else {
                setArray(ArrayData.freeze(getArray()));
                return this;
            }
        }
    }

    /**
     * Check whether this ScriptObject is frozen
     */
    public boolean isFrozen() {
        return getMap().isFrozen();
    }

    /**
     * Check whether this ScriptObject is scope
     */
    public boolean isScope() {
        return false;
    }

    /**
     * Tag this script object as built in
     */
    public final void setIsBuiltin() {
        flags |= IS_BUILTIN;
    }

    /**
     * Check if this script object is built in
     */
    public final boolean isBuiltin() {
        return (flags & IS_BUILTIN) != 0;
    }

    /**
     * Tag this script object as internal object that should not be visible to script code.
     */
    public final void setIsInternal() {
        flags |= IS_INTERNAL;
    }

    /**
     * Check if this script object is an internal object that should not be visible to script code.
     */
    public final boolean isInternal() {
        return (flags & IS_INTERNAL) != 0;
    }

    /**
     * Clears the properties from a ScriptObject
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     */
    public void clear() {
        var iter = propertyIterator();
        while (iter.hasNext()) {
            delete(iter.next());
        }
    }

    /**
     * Checks if a property with a given key is present in a ScriptObject
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     * @param key the key to check for
     * @return true if a property with the given key exists, false otherwise
     */
    public boolean containsKey(Object key) {
        return has(key);
    }

    /**
     * Checks if a property with a given value is present in a ScriptObject
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     * @param value value to check for
     * @return true if a property with the given value exists, false otherwise
     */
    public boolean containsValue(Object value) {
        var iter = valueIterator();
        while (iter.hasNext()) {
            if (iter.next().equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the set of {@literal <property, value>} entries that make up this ScriptObject's properties
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     * @return an entry set of all the properties in this object
     */
    public Set<Map.Entry<Object, Object>> entrySet() {
        var iter = propertyIterator();
        var entries = new HashSet<Map.Entry<Object, Object>>();
        while (iter.hasNext()) {
            var key = iter.next();
            entries.add(new AbstractMap.SimpleImmutableEntry<>(key, get(key)));
        }
        return Collections.unmodifiableSet(entries);
    }

    /**
     * Check whether a ScriptObject contains no properties
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     * @return true if object has no properties
     */
    public boolean isEmpty() {
        return !propertyIterator().hasNext();
    }

    /**
     * Return the set of keys (property names) for all properties in this ScriptObject
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     * @return keySet of this ScriptObject
     */
    public Set<Object> keySet() {
        var iter = propertyIterator();
        var keySet = new HashSet<Object>();
        while (iter.hasNext()) {
            keySet.add(iter.next());
        }
        return Collections.unmodifiableSet(keySet);
    }

    /**
     * Put a property in the ScriptObject
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     */
    public Object put(Object key, Object value) {
        var oldValue = get(key);
        set(key, value, 0);
        return oldValue;
    }

    /**
     * Put several properties in the ScriptObject given a mapping of their keys to their values
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     * @param otherMap a {@literal <key,value>} map of properties to add
     */
    public void putAll(Map<?,?> otherMap) {
        for (var entry : otherMap.entrySet()) {
            set(entry.getKey(), entry.getValue(), 0);
        }
    }

    /**
     * Remove a property from the ScriptObject.
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     */
    public Object remove(Object key) {
        var oldValue = get(key);
        delete(key);
        return oldValue;
    }

    /**
     * Return the size of the ScriptObject - i.e. the number of properties it contains
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     */
    public int size() {
        var n = 0;
        for (var iter = propertyIterator(); iter.hasNext(); iter.next()) {
            n++;
        }
        return n;
    }

    /**
     * Return the values of the properties in the ScriptObject
     * (java.util.Map-like method to help ScriptObjectMirror implementation)
     * @return collection of values for the properties in this ScriptObject
     */
    public Collection<Object> values() {
        var values = new ArrayList<>(size());
        var iter = valueIterator();
        while (iter.hasNext()) {
            values.add(iter.next());
        }
        return Collections.unmodifiableList(values);
    }

    /**
     * Lookup method that, given a CallSiteDescriptor, looks up the target MethodHandle and creates a GuardedInvocation with the appropriate guard(s).
     * @param desc call site descriptor
     * @param request the link request
     * @return GuardedInvocation for the callsite
     */
    public GuardedInvocation lookup(CallSiteDescriptor desc, LinkRequest request) {
        // NOTE: we support GET:ELEMENT and SET:ELEMENT as JavaScript doesn't distinguish items from properties.
        // Nashorn itself emits "GET:PROPERTY|ELEMENT|METHOD:identifier" for "<expr>.<identifier>" and "GET:ELEMENT|PROPERTY|METHOD" for "<expr>[<expr>]", but we are more flexible here and dispatch not on operation name (getProp vs. getElem), but rather on whether the operation has an associated name or not.
        return switch (NashornCallSiteDescriptor.getStandardOperation(desc)) {
            case GET -> desc.getOperation() instanceof NamedOperation ? findGetMethod(desc, request) : findGetIndexMethod(desc, request);
            case SET -> desc.getOperation() instanceof NamedOperation ? findSetMethod(desc, request) : findSetIndexMethod(desc, request);
            case REMOVE -> findRemoveMethod(desc);
            case CALL -> findCallMethod(desc, request);
            case NEW -> findNewMethod(desc, request);
            default -> null;
        };
    }

    static GuardedInvocation findRemoveMethod(CallSiteDescriptor desc) {
        var inv = DELETE_GUARDED;
        var name = NamedOperation.getName(desc.getOperation());
        if (name != null) {
            return inv.replaceMethods(MH.insertArguments(inv.getInvocation(), 1, name), inv.getGuard());
        }
        return inv;
    }

    /**
     * Find the appropriate New method for an invoke dynamic call.
     * @param desc The invoke dynamic call site descriptor.
     * @param request The link request
     * @return GuardedInvocation to be invoked at call site.
     */
    protected GuardedInvocation findNewMethod(CallSiteDescriptor desc, LinkRequest request) {
        return notAFunction(desc);
    }

    /**
     * Find the appropriate CALL method for an invoke dynamic call.
     * This generates "not a function" always
     * @param desc    the call site descriptor.
     * @param request the link request
     * @return GuardedInvocation to be invoked at call site.
     */
    protected GuardedInvocation findCallMethod(CallSiteDescriptor desc, LinkRequest request) {
        return notAFunction(desc);
    }

    private GuardedInvocation notAFunction(CallSiteDescriptor desc) {
        throw typeError("not.a.function", NashornCallSiteDescriptor.getFunctionErrorMessage(desc, this));
    }

    /**
     * Test whether this object contains in its prototype chain or is itself a with-object.
     * @return true if a with-object was found
     */
    boolean hasWithScope() {
        return false;
    }

    /**
     * Add a filter to the first argument of {@code methodHandle} that calls its {@link #getProto()} method {@code depth} times.
     * @param methodHandle a method handle
     * @param depth distance to target prototype
     * @return the filtered method handle
     */
    static MethodHandle addProtoFilter(MethodHandle methodHandle, int depth) {
        if (depth == 0) {
            return methodHandle;
        }
        var listIndex = depth - 1; // We don't need 0-deep walker
        var filter = listIndex < PROTO_FILTERS.size() ? PROTO_FILTERS.get(listIndex) : null;

        if (filter == null) {
            filter = addProtoFilter(GETPROTO, depth - 1);
            PROTO_FILTERS.add(null);
            PROTO_FILTERS.set(listIndex, filter);
        }

        return MH.filterArguments(methodHandle, 0, filter.asType(filter.type().changeReturnType(methodHandle.type().parameterType(0))));
    }

    /**
     * Find the appropriate GET method for an invoke dynamic call.
     * @param desc     the call site descriptor
     * @param request  the link request
     * @return GuardedInvocation to be invoked at call site.
     */
    protected GuardedInvocation findGetMethod(CallSiteDescriptor desc, LinkRequest request) {
        var explicitInstanceOfCheck = explicitInstanceOfCheck(desc, request);

        var name = NashornCallSiteDescriptor.getOperand(desc);
        if (NashornCallSiteDescriptor.isApplyToCall(desc)) {
            if (Global.isBuiltinFunctionPrototypeApply()) {
                name = "call";
            }
        }

        if (request.isCallSiteUnstable() || hasWithScope()) {
            return findMegaMorphicGetMethod(desc, name, NashornCallSiteDescriptor.isMethodFirstOperation(desc));
        }

        var find = findProperty(name, true, NashornCallSiteDescriptor.isScope(desc), this);
        MethodHandle mh;

        if (find == null) {
            if (!NashornCallSiteDescriptor.isMethodFirstOperation(desc)) {
                return noSuchProperty(desc, request);
            } else {
                return noSuchMethod(desc, request);
            }
        }

        var globalConstants = getGlobalConstants();
        if (globalConstants != null) {
            var cinv = globalConstants.findGetMethod(find, this, desc);
            if (cinv != null) {
                return cinv;
            }
        }

        var returnType = desc.getMethodType().returnType();
        var property = find.getProperty();

        var programPoint = NashornCallSiteDescriptor.isOptimistic(desc) ? NashornCallSiteDescriptor.getProgramPoint(desc) : UnwarrantedOptimismException.INVALID_PROGRAM_POINT;

        mh = find.getGetter(returnType, programPoint, request);
        // Get the appropriate guard for this callsite and property.
        var guard = NashornGuards.getGuard(this, property, desc, explicitInstanceOfCheck);
        var owner = find.getOwner();
        var exception = explicitInstanceOfCheck ? null : ClassCastException.class;

        SwitchPoint[] protoSwitchPoints;

        if (mh == null) {
            mh = Lookup.emptyGetter(returnType);
            protoSwitchPoints = getProtoSwitchPoints(name, owner);
        } else if (!find.isSelf()) {
            assert mh.type().returnType().equals(returnType) : "return type mismatch for getter " + mh.type().returnType() + " != " + returnType;
            if (!property.isAccessorProperty()) {
                // Add a filter that replaces the self object with the prototype owning the property.
                mh = addProtoFilter(mh, find.getProtoChainLength());
            }
            protoSwitchPoints = getProtoSwitchPoints(name, owner);
        } else {
            protoSwitchPoints = null;
        }

        var inv = new GuardedInvocation(mh, guard, protoSwitchPoints, exception);
        return inv.addSwitchPoint(findBuiltinSwitchPoint(name));
    }

    private static GuardedInvocation findMegaMorphicGetMethod(CallSiteDescriptor desc, String name, boolean isMethod) {
        Context.getContextTrusted().getLogger(ObjectClassGenerator.class).warning("Megamorphic getter: ", desc, " ", name + " ", isMethod);
        var invoker = MH.insertArguments(MEGAMORPHIC_GET, 1, name, isMethod, NashornCallSiteDescriptor.isScope(desc));
        var guard   = getScriptObjectGuard(desc.getMethodType(), true);
        return new GuardedInvocation(invoker, guard);
    }

    @SuppressWarnings("unused")
    private Object megamorphicGet(String key, boolean isMethod, boolean isScope) {
        var find = findProperty(key, true, isScope, this);
        if (find != null) {
            // If this is a method invocation, and found property has a different self object than this, then return a function bound to the self object.
            // This is the case for functions in with expressions.
            var value = find.getObjectValue();
            if (isMethod && value instanceof ScriptFunction && find.getSelf() != this && !find.getSelf().isInternal()) {
                return ((ScriptFunction) value).createBound(find.getSelf(), ScriptRuntime.EMPTY_ARRAY);
            }
            return value;
        }

        return isMethod ? getNoSuchMethod(key, isScope, INVALID_PROGRAM_POINT) : invokeNoSuchProperty(key, isScope, INVALID_PROGRAM_POINT);
    }

    // Marks a property as declared and sets its value. Used as slow path for block-scoped LET and CONST
    @SuppressWarnings("unused")
    private void declareAndSet(String key, Object value) {
        declareAndSet(findProperty(key, false), value);
    }

    private void declareAndSet(FindProperty find, Object value) {
        var oldMap = getMap();
        assert find != null;

        var property = find.getProperty();
        assert property != null;
        assert property.needsDeclaration();

        var newMap = oldMap.replaceProperty(property, property.removeFlags(Property.NEEDS_DECLARATION));
        setMap(newMap);
        set(property.getKey(), value, NashornCallSiteDescriptor.CALLSITE_DECLARE);
    }

    /**
     * Find the appropriate GETINDEX method for an invoke dynamic call.
     * @param desc    the call site descriptor
     * @param request the link request
     * @return GuardedInvocation to be invoked at call site.
     */
    protected GuardedInvocation findGetIndexMethod(CallSiteDescriptor desc, LinkRequest request) {
        var callType = desc.getMethodType();
        var returnType = callType.returnType();
        var returnClass = returnType.isPrimitive() ? returnType : Object.class;
        var keyClass = callType.parameterType(1);
        var explicitInstanceOfCheck = explicitInstanceOfCheck(desc, request);

        String name;
        if (returnClass.isPrimitive()) {
            // turn e.g. get with a double into getDouble
            var returnTypeName = returnClass.getName();
            name = "get" + Character.toUpperCase(returnTypeName.charAt(0)) + returnTypeName.substring(1, returnTypeName.length());
        } else {
            name = "get";
        }

        var mh = findGetIndexMethodHandle(returnClass, name, keyClass, desc);
        return new GuardedInvocation(mh, getScriptObjectGuard(callType, explicitInstanceOfCheck), (SwitchPoint)null, explicitInstanceOfCheck ? null : ClassCastException.class);
    }

    private static MethodHandle getScriptObjectGuard(MethodType type, boolean explicitInstanceOfCheck) {
        return ScriptObject.class.isAssignableFrom(type.parameterType(0)) ? null : NashornGuards.getScriptObjectGuard(explicitInstanceOfCheck);
    }

    /**
     * Find a handle for a getIndex method
     * @param returnType     return type for getter
     * @param name           name
     * @param elementType    index type for getter
     * @param desc           call site descriptor
     * @return method handle for getter
     */
    private static MethodHandle findGetIndexMethodHandle(Class<?> returnType, String name, Class<?> elementType, CallSiteDescriptor desc) {
        if (!returnType.isPrimitive()) {
            return findOwnMH_V(name, returnType, elementType);
        }

        return MH.insertArguments(findOwnMH_V(name, returnType, elementType, int.class), 2, NashornCallSiteDescriptor.isOptimistic(desc) ? NashornCallSiteDescriptor.getProgramPoint(desc) : INVALID_PROGRAM_POINT);
    }

    /**
     * Get an array of switch points for a property with the given {@code name} that will be invalidated when the property definition is changed in this object's prototype chain.
     * Returns {@code null} if the property is defined in this object itself.
     * @param name the property name
     * @param owner the property owner, null if property is not defined
     * @return an array of SwitchPoints or null
     */
    public final SwitchPoint[] getProtoSwitchPoints(String name, ScriptObject owner) {
        if (owner == this || getProto() == null) {
            return null;
        }

        var switchPoints = new HashSet<SwitchPoint>();
        var switchPoint = getProto().getMap().getSwitchPoint(name);

        if (switchPoint == null) {
            switchPoint = new SwitchPoint();
            for (var obj = this; obj != owner && obj.getProto() != null; obj = obj.getProto()) {
                obj.getProto().getMap().addSwitchPoint(name, switchPoint);
            }
        }

        switchPoints.add(switchPoint);

        for (var obj = this; obj != owner && obj.getProto() != null; obj = obj.getProto()) {
            var sharedProtoSwitchPoint = obj.getProto().getMap().getSharedProtoSwitchPoint();
            if (sharedProtoSwitchPoint != null && !sharedProtoSwitchPoint.hasBeenInvalidated()) {
                switchPoints.add(sharedProtoSwitchPoint);
            }
        }

        return switchPoints.toArray(new SwitchPoint[0]);
    }

    // Similar to getProtoSwitchPoints method above, but used for additional prototype switchpoints of properties that are known not to exist, e.g. the original property name in a __noSuchProperty__ invocation.
    final SwitchPoint getProtoSwitchPoint(String name) {
        if (getProto() == null) {
            return null;
        }

        var switchPoint = getProto().getMap().getSwitchPoint(name);

        if (switchPoint == null) {
            switchPoint = new SwitchPoint();
            for (var obj = this; obj.getProto() != null; obj = obj.getProto()) {
                obj.getProto().getMap().addSwitchPoint(name, switchPoint);
            }
        }

        return switchPoint;
    }

    private void checkSharedProtoMap() {
        // Check if our map has an expected shared prototype property map.
        // If it has, make sure that the prototype map has not been invalidated, and that it does match the actual map of the prototype.
        if (getMap().isInvalidSharedMapFor(getProto())) {
            // Change our own map to one that does not assume a shared prototype map.
            setMap(getMap().makeUnsharedCopy());
        }
    }

    /**
     * Find the appropriate SET method for an invoke dynamic call.
     * @param desc    the call site descriptor
     * @param request the link request
     * @return GuardedInvocation to be invoked at call site.
     */
    protected GuardedInvocation findSetMethod(CallSiteDescriptor desc, LinkRequest request) {
        var name = NashornCallSiteDescriptor.getOperand(desc);

        if (request.isCallSiteUnstable() || hasWithScope()) {
            return findMegaMorphicSetMethod(desc, name);
        }

        var explicitInstanceOfCheck = explicitInstanceOfCheck(desc, request);

        /*
         * If doing property set on a scope object, we should stop proto search on the first non-scope object.
         * Without this, for example, when assigning "toString" on global scope, we'll end up assigning it on it's proto - which is Object.prototype.toString !!
         * toString = function() { print("global toString"); } // don't affect Object.prototype.toString
         */
        var find = findProperty(name, true, NashornCallSiteDescriptor.isScope(desc), this);

        // If it's not a scope search, then we don't want any inherited properties except those with user defined accessors.
        if (find != null && find.isInheritedOrdinaryProperty()) {
            // We should still check if inherited data property is not writable
            if (isExtensible() && !find.getProperty().isWritable()) {
                return createEmptySetMethod(desc, explicitInstanceOfCheck, "property.not.writable", true);
            }
            // Otherwise, forget the found property unless this is a scope callsite and the owner is a scope object as well.
            if (!NashornCallSiteDescriptor.isScope(desc) || !find.getOwner().isScope()) {
                find = null;
            }
        }

        if (find != null) {
            if (!find.getProperty().isWritable() && !NashornCallSiteDescriptor.isDeclaration(desc)) {
                if (NashornCallSiteDescriptor.isScope(desc) && find.getProperty().isLexicalBinding()) {
                    throw typeError("assign.constant", name); // Overwriting ES6 const
                }
                // Existing, non-writable data property
                return createEmptySetMethod(desc, explicitInstanceOfCheck, "property.not.writable", true);
            }
            if (!find.getProperty().hasNativeSetter()) {
                // Existing accessor property without setter
                return createEmptySetMethod(desc, explicitInstanceOfCheck, "property.has.no.setter", true);
            }
        } else {
            if (!isExtensible()) {
                return createEmptySetMethod(desc, explicitInstanceOfCheck, "object.non.extensible", false);
            }
        }

        var inv = new SetMethodCreator(this, find, desc, request).createGuardedInvocation(findBuiltinSwitchPoint(name));

        var globalConstants = getGlobalConstants();
        if (globalConstants != null) {
            var cinv = globalConstants.findSetMethod(find, this, inv, desc, request);
            if (cinv != null) {
                return cinv;
            }
        }

        return inv;
    }

    private GlobalConstants getGlobalConstants() {
        // Avoid hitting getContext() which might be costly for a non-Global unless needed.
        return GlobalConstants.GLOBAL_ONLY && !isGlobal() ? null : getContext().getGlobalConstants();
    }

    private GuardedInvocation createEmptySetMethod(CallSiteDescriptor desc, boolean explicitInstanceOfCheck, String errorMessage, boolean canBeFastScope) {
        var name = NashornCallSiteDescriptor.getOperand(desc);
        throw typeError(errorMessage, name, ScriptRuntime.safeToString(this));
    }

    @SuppressWarnings("unused")
    private boolean extensionCheck(String name) {
        if (isExtensible()) {
            return true; // go on and do the set. this is our guard
        } else {
            return false; // throw typeError("object.non.extensible", name, ScriptRuntime.safeToString(this));
        }
    }

    private static GuardedInvocation findMegaMorphicSetMethod(CallSiteDescriptor desc, String name) {
        Context.getContextTrusted().getLogger(ObjectClassGenerator.class).warning("Megamorphic setter: ", desc, " ", name);
        var type = desc.getMethodType().insertParameterTypes(1, Object.class);
        // never bother with ClassCastExceptionGuard for megamorphic callsites
        var inv = findSetIndexMethod(desc, false, type);
        return inv.replaceMethods(MH.insertArguments(inv.getInvocation(), 1, name), inv.getGuard());
    }

    @SuppressWarnings("unused")
    private static Object globalFilter(Object object) {
        var sobj = (ScriptObject) object;
        while (sobj != null && !(sobj instanceof Global)) {
            sobj = sobj.getProto();
        }
        return sobj;
    }

    /**
     * Lookup function for the set index method, available for subclasses as well, e.g. {@link NativeArray} provides special quick accessor linkage for continuous arrays that are represented as Java arrays
     * @param desc    call site descriptor
     * @param request link request
     * @return GuardedInvocation to be invoked at call site.
     */
    protected GuardedInvocation findSetIndexMethod(CallSiteDescriptor desc, LinkRequest request) { // array, index, value
        return findSetIndexMethod(desc, explicitInstanceOfCheck(desc, request), desc.getMethodType());
    }

    /**
     * Find the appropriate SETINDEX method for an invoke dynamic call.
     * @param desc  the call site descriptor
     * @param explicitInstanceOfCheck add an explicit instanceof check?
     * @param callType the method type at the call site
     * @return GuardedInvocation to be invoked at call site.
     */
    private static GuardedInvocation findSetIndexMethod(CallSiteDescriptor desc, boolean explicitInstanceOfCheck, MethodType callType) {
        assert callType.parameterCount() == 3;
        var keyClass = callType.parameterType(1);
        var valueClass = callType.parameterType(2);
        var methodHandle = findOwnMH_V("set", void.class, keyClass, valueClass, int.class);
        methodHandle = MH.insertArguments(methodHandle, 3, NashornCallSiteDescriptor.getFlags(desc));
        return new GuardedInvocation(methodHandle, getScriptObjectGuard(callType, explicitInstanceOfCheck), (SwitchPoint)null, explicitInstanceOfCheck ? null : ClassCastException.class);
    }

    /**
     * Fall back if a function property is not found.
     * @param desc The call site descriptor
     * @param request the link request
     * @return GuardedInvocation to be invoked at call site.
     */
    public GuardedInvocation noSuchMethod(CallSiteDescriptor desc, LinkRequest request) {
        var name = NashornCallSiteDescriptor.getOperand(desc);
        var find = findProperty(NO_SUCH_METHOD_NAME, true);
        var scopeCall = isScope() && NashornCallSiteDescriptor.isScope(desc);

        if (find == null) {
            return noSuchProperty(desc, request)
                // Add proto switchpoint to switch from no-such-property to no-such-method if it is ever defined.
                .addSwitchPoint(getProtoSwitchPoint(NO_SUCH_METHOD_NAME));
        }

        var explicitInstanceOfCheck = explicitInstanceOfCheck(desc, request);

        var value = find.getObjectValue();
        if (!(value instanceof ScriptFunction)) {
            return createEmptyGetter(desc, explicitInstanceOfCheck, name);
        }

        var func = (ScriptFunction)value;
        var thiz = scopeCall ? UNDEFINED : this;
        // TODO: It'd be awesome if we could bind "name" without binding "this".
        // Since we're binding this we must use an identity guard here.
        return new GuardedInvocation(
                MH.dropArguments(MH.constant(ScriptFunction.class, func.createBound(thiz, new Object[] { name })), 0, Object.class), NashornGuards.combineGuards(NashornGuards.getIdentityGuard(this), NashornGuards.getMapGuard(getMap(), true)))
                // Add a protoype switchpoint for the original name so this gets invalidated if it is ever defined.
                .addSwitchPoint(getProtoSwitchPoint(name));
    }

    /**
     * Fall back if a property is not found.
     * @param desc the call site descriptor.
     * @param request the link request
     * @return GuardedInvocation to be invoked at call site.
     */
    public GuardedInvocation noSuchProperty(CallSiteDescriptor desc, LinkRequest request) {
        var name = NashornCallSiteDescriptor.getOperand(desc);
        var find = findProperty(NO_SUCH_PROPERTY_NAME, true);
        var scopeAccess = isScope() && NashornCallSiteDescriptor.isScope(desc);

        if (find != null) {
            var value = find.getObjectValue();
            ScriptFunction func = null;
            MethodHandle mh = null;

            if (value instanceof ScriptFunction) {
                func = (ScriptFunction)value;
                mh = getCallMethodHandle(func, desc.getMethodType(), name);
            }

            if (mh != null) {
                assert func != null;
                if (scopeAccess) {
                    mh = bindTo(mh, UNDEFINED);
                }

                return new GuardedInvocation(
                        mh,
                        find.isSelf()
                            ? getKnownFunctionPropertyGuardSelf(getMap(), find.getGetter(Object.class, INVALID_PROGRAM_POINT, request), func)
                            // TODO this always does a scriptobject check
                            : getKnownFunctionPropertyGuardProto(getMap(), find.getGetter(Object.class, INVALID_PROGRAM_POINT, request), find.getProtoChainLength(), func),
                        getProtoSwitchPoints(NO_SUCH_PROPERTY_NAME, find.getOwner()),
                        //TODO this doesn't need a ClassCastException as guard always checks script object
                        null)
                        // Add a protoype switchpoint for the original name so this gets invalidated if it is ever defined.
                        .addSwitchPoint(getProtoSwitchPoint(name));
            }
        }

        if (scopeAccess) {
            throw referenceError("not.defined", name);
        }

        return createEmptyGetter(desc, explicitInstanceOfCheck(desc, request), name);
    }

    /**
     * Invoke fall back if a property is not found.
     * @param key Name of property.
     * @param isScope is this a scope access?
     * @param programPoint program point
     * @return Result from call.
     */
    protected Object invokeNoSuchProperty(Object key, boolean isScope, int programPoint) {
        var find = findProperty(NO_SUCH_PROPERTY_NAME, true);
        var func = (find != null)? find.getObjectValue() : null;

        Object ret = UNDEFINED;
        if (func instanceof ScriptFunction) {
            var sfunc = (ScriptFunction)func;
            var self = isScope ? UNDEFINED : this;
            ret = ScriptRuntime.apply(sfunc, self, key);
        } else if (isScope) {
            throw referenceError("not.defined", key.toString());
        }

        if (isValid(programPoint)) {
            throw new UnwarrantedOptimismException(ret, programPoint);
        }

        return ret;
    }


    /**
     * Get __noSuchMethod__ as a function bound to this object and {@code name} if it is defined.
     * @param name the method name
     * @param isScope is this a scope access?
     * @return the bound function, or undefined
     */
    private Object getNoSuchMethod(String name, boolean isScope, int programPoint) {
        var find = findProperty(NO_SUCH_METHOD_NAME, true);

        if (find == null) {
            return invokeNoSuchProperty(name, isScope, programPoint);
        }

        var value = find.getObjectValue();
        if (!(value instanceof ScriptFunction)) {
            if (isScope) {
                throw referenceError("not.defined", name);
            }
            return UNDEFINED;
        }

        var func = (ScriptFunction)value;
        var self = isScope ? UNDEFINED : this;
        return func.createBound(self, new Object[] {name});
    }

    private GuardedInvocation createEmptyGetter(CallSiteDescriptor desc, boolean explicitInstanceOfCheck, String name) {
        if (NashornCallSiteDescriptor.isOptimistic(desc)) {
            throw new UnwarrantedOptimismException(UNDEFINED, NashornCallSiteDescriptor.getProgramPoint(desc), Type.OBJECT);
        }

        return new GuardedInvocation(Lookup.emptyGetter(desc.getMethodType().returnType()),
            NashornGuards.getMapGuard(getMap(), explicitInstanceOfCheck), getProtoSwitchPoints(name, null),
            explicitInstanceOfCheck ? null : ClassCastException.class);
    }

    private abstract static class ScriptObjectIterator <T extends Object> implements Iterator<T> {
        protected T[] values;
        protected final ScriptObject object;
        private int index;

        ScriptObjectIterator(ScriptObject object) {
            this.object = object;
        }

        protected abstract void init();

        @Override
        public boolean hasNext() {
            if (values == null) {
                init();
            }
            return index < values.length;
        }

        @Override
        public T next() {
            if (values == null) {
                init();
            }
            return values[index++];
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("remove");
        }
    }

    private static class KeyIterator extends ScriptObjectIterator<String> {
        KeyIterator(ScriptObject object) {
            super(object);
        }

        @Override
        protected void init() {
            var keys = new LinkedHashSet<String>();
            var nonEnumerable = new HashSet<String>();
            for (var self = object; self != null; self = self.getProto()) {
                keys.addAll(Arrays.asList(self.getOwnKeys(String.class, false, nonEnumerable)));
            }
            this.values = keys.toArray(new String[0]);
        }
    }

    private static class ValueIterator extends ScriptObjectIterator<Object> {
        ValueIterator(ScriptObject object) {
            super(object);
        }

        @Override
        protected void init() {
            var valueList = new ArrayList<Object>();
            var nonEnumerable = new HashSet<String>();
            for (var self = object; self != null; self = self.getProto()) {
                for (var key : self.getOwnKeys(String.class, false, nonEnumerable)) {
                    valueList.add(self.get(key));
                }
            }
            this.values = valueList.toArray(new Object[0]);
        }
    }

    /**
     * Add a spill property for the given key.
     */
    private Property addSpillProperty(Object key, int flags, Object value, boolean hasInitialValue) {
        var propertyMap = getMap();
        var fieldSlot  = propertyMap.getFreeFieldSlot();
        var propertyFlags = flags | (useDualFields() ? Property.DUAL_FIELDS : 0);

        Property property;
        if (fieldSlot > -1) {
            property = hasInitialValue ? new AccessorProperty(key, propertyFlags, fieldSlot, this, value) : new AccessorProperty(key, propertyFlags, getClass(), fieldSlot);
            property = addOwnProperty(property);
        } else {
            var spillSlot = propertyMap.getFreeSpillSlot();
            property = hasInitialValue ? new SpillProperty(key, propertyFlags, spillSlot, this, value) : new SpillProperty(key, propertyFlags, spillSlot);
            property = addOwnProperty(property);
            ensureSpillSize(property.getSlot());
        }
        return property;
    }

    /**
     * Add a spill entry for the given key.
     */
    MethodHandle addSpill(Class<?> type, String key) {
        return addSpillProperty(key, 0, null, false).getSetter(type, getMap());
    }

    /**
     * Make sure arguments are paired correctly, with respect to more parameters than declared, fewer parameters than declared and other things that JavaScript allows.
     * This might involve creating collectors.
     */
    protected static MethodHandle pairArguments(MethodHandle methodHandle, MethodType callType) {
        return pairArguments(methodHandle, callType, null);
    }

    /**
     * Make sure arguments are paired correctly, with respect to more parameters than declared, fewer parameters than declared and other things that JavaScript allows.
     * This might involve creating collectors.
     * Make sure arguments are paired correctly.
     * @param methodHandle MethodHandle to adjust.
     * @param callType     MethodType of the call site.
     * @param callerVarArg true if the caller is vararg, false otherwise, null if it should be inferred from the {@code callType}; basically, if the last parameter type of the call site is an array, it'll be considered a variable arity call site. These are ordinarily rare; Nashorn code generator creates variable arity call sites when the call has more than {@link LinkerCallSite#ARGLIMIT} parameters.
     * @return method handle with adjusted arguments
     */
    public static MethodHandle pairArguments(MethodHandle methodHandle, MethodType callType, Boolean callerVarArg) {
        var methodType = methodHandle.type();
        if (methodType.equals(callType.changeReturnType(methodType.returnType()))) {
            return methodHandle;
        }

        var parameterCount = methodType.parameterCount();
        var callCount = callType.parameterCount();

        var isCalleeVarArg = parameterCount > 0 && methodType.parameterType(parameterCount - 1).isArray();
        var isCallerVarArg = callerVarArg != null ? callerVarArg : callCount > 0 && callType.parameterType(callCount - 1).isArray();

        if (isCalleeVarArg) {
            return isCallerVarArg ? methodHandle : MH.asCollector(methodHandle, Object[].class, callCount - parameterCount + 1);
        }

        if (isCallerVarArg) {
            return adaptHandleToVarArgCallSite(methodHandle, callCount);
        }

        if (callCount < parameterCount) {
            var missingArgs = parameterCount - callCount;
            var fillers = new Object[missingArgs];

            Arrays.fill(fillers, UNDEFINED);

            if (isCalleeVarArg) {
                fillers[missingArgs - 1] = ScriptRuntime.EMPTY_ARRAY;
            }

            return MH.insertArguments(methodHandle, parameterCount - missingArgs, fillers);
        }

        if (callCount > parameterCount) {
            var discardedArgs = callCount - parameterCount;
            var discards = new Class<?>[discardedArgs];
            Arrays.fill(discards, Object.class);

            return MH.dropArguments(methodHandle, callCount - discardedArgs, discards);
        }

        return methodHandle;
    }

    static MethodHandle adaptHandleToVarArgCallSite(MethodHandle mh, int callSiteParamCount) {
        var spreadArgs = mh.type().parameterCount() - callSiteParamCount + 1;
        return MH.filterArguments(
            MH.asSpreader(mh, Object[].class, spreadArgs),
            callSiteParamCount - 1,
            MH.insertArguments(TRUNCATINGFILTER, 0, spreadArgs)
            );
    }

    @SuppressWarnings("unused")
    private static Object[] truncatingFilter(int n, Object[] array) {
        var length = array == null ? 0 : array.length;
        if (n == length) {
            return array == null ? ScriptRuntime.EMPTY_ARRAY : array;
        }

        var newArray = new Object[n];

        if (array != null) {
            System.arraycopy(array, 0, newArray, 0, Math.min(n, length));
        }

        if (length < n) {
            var fill = UNDEFINED;

            for (var i = length; i < n; i++) {
                newArray[i] = fill;
            }
        }

        return newArray;
    }

    /**
      * Numeric length setter for length property
      */
    public final void setLength(long newLength) {
        var data = getArray();
        var arrayLength = data.length();
        if (newLength == arrayLength) {
            return;
        }

        if (newLength > arrayLength) {
            setArray(data.ensure(newLength - 1).safeDelete(arrayLength, newLength - 1)); // false
            return;
        }

        if (newLength < arrayLength) {
           long actualLength = newLength;

           // Check for numeric keys in property map and delete them or adjust length, depending on whether they're defined as configurable. See ES5 #15.4.5.2
           if (getMap().containsArrayKeys()) {

               for (var l = arrayLength - 1; l >= newLength; l--) {
                   var find = findProperty(JSType.toString(l), false);

                   if (find != null) {

                       if (find.getProperty().isConfigurable()) {
                           deleteOwnProperty(find.getProperty());
                       } else {
                           actualLength = l + 1;
                           break;
                       }
                   }
               }
           }

           setArray(data.shrink(actualLength));
           data.setLength(actualLength);
       }
    }

    private int getInt(int index, Object key, int programPoint) {
        if (isValidArrayIndex(index)) {
            for (var object = this; ; ) {
                if (object.getMap().containsArrayKeys()) {
                    var find = object.findProperty(key, false);

                    if (find != null) {
                        return getIntValue(find, programPoint);
                    }
                }

                if ((object = object.getProto()) == null) {
                    break;
                }

                var array = object.getArray();

                if (array.has(index)) {
                    return isValid(programPoint) ? array.getIntOptimistic(index, programPoint) : array.getInt(index);
                }
            }
        } else {
            var find = findProperty(key, true);

            if (find != null) {
                return getIntValue(find, programPoint);
            }
        }

        return JSType.toInt32(invokeNoSuchProperty(key, false, programPoint));
    }

    @Override
    public int getInt(Object key, int programPoint) {
        var primitiveKey = JSType.toPrimitive(key, String.class);
        var index = getArrayIndex(primitiveKey);
        var array = getArray();

        if (array.has(index)) {
            return isValid(programPoint) ? array.getIntOptimistic(index, programPoint) : array.getInt(index);
        }

        return getInt(index, JSType.toPropertyKey(primitiveKey), programPoint);
    }

    @Override
    public int getInt(double key, int programPoint) {
        var index = getArrayIndex(key);
        var array = getArray();

        if (array.has(index)) {
            return isValid(programPoint) ? array.getIntOptimistic(index, programPoint) : array.getInt(index);
        }

        return getInt(index, JSType.toString(key), programPoint);
    }

    @Override
    public int getInt(int key, int programPoint) {
        var index = getArrayIndex(key);
        var array = getArray();

        if (array.has(index)) {
            return isValid(programPoint) ? array.getIntOptimistic(key, programPoint) : array.getInt(key);
        }

        return getInt(index, JSType.toString(key), programPoint);
    }

    private double getDouble(int index, Object key, int programPoint) {
        if (isValidArrayIndex(index)) {
            for (var object = this; ; ) {
                if (object.getMap().containsArrayKeys()) {
                    var find = object.findProperty(key, false);
                    if (find != null) {
                        return getDoubleValue(find, programPoint);
                    }
                }

                if ((object = object.getProto()) == null) {
                    break;
                }

                var array = object.getArray();

                if (array.has(index)) {
                    return isValid(programPoint) ? array.getDoubleOptimistic(index, programPoint) : array.getDouble(index);
                }
            }
        } else {
            var find = findProperty(key, true);

            if (find != null) {
                return getDoubleValue(find, programPoint);
            }
        }

        return JSType.toNumber(invokeNoSuchProperty(key, false, INVALID_PROGRAM_POINT));
    }

    @Override
    public double getDouble(Object key, int programPoint) {
        var primitiveKey = JSType.toPrimitive(key, String.class);
        var index = getArrayIndex(primitiveKey);
        var array = getArray();

        if (array.has(index)) {
            return isValid(programPoint) ? array.getDoubleOptimistic(index, programPoint) : array.getDouble(index);
        }

        return getDouble(index, JSType.toPropertyKey(primitiveKey), programPoint);
    }

    @Override
    public double getDouble(double key, int programPoint) {
        var index = getArrayIndex(key);
        var array = getArray();

        if (array.has(index)) {
            return isValid(programPoint) ? array.getDoubleOptimistic(index, programPoint) : array.getDouble(index);
        }

        return getDouble(index, JSType.toString(key), programPoint);
    }

    @Override
    public double getDouble(int key, int programPoint) {
        var index = getArrayIndex(key);
        var array = getArray();

        if (array.has(index)) {
            return isValid(programPoint) ? array.getDoubleOptimistic(key, programPoint) : array.getDouble(key);
        }

        return getDouble(index, JSType.toString(key), programPoint);
    }

    private Object get(int index, Object key) {
        if (isValidArrayIndex(index)) {
            for (var object = this; ; ) {
                if (object.getMap().containsArrayKeys()) {
                    var find = object.findProperty(key, false);

                    if (find != null) {
                        return find.getObjectValue();
                    }
                }

                if ((object = object.getProto()) == null) {
                    break;
                }

                var array = object.getArray();

                if (array.has(index)) {
                    return array.getObject(index);
                }
            }
        } else {
            var find = findProperty(key, true);

            if (find != null) {
                return find.getObjectValue();
            }
        }

        return invokeNoSuchProperty(key, false, INVALID_PROGRAM_POINT);
    }

    @Override
    public Object get(Object key) {
        var primitiveKey = JSType.toPrimitive(key, String.class);
        var index = getArrayIndex(primitiveKey);
        var array = getArray();

        if (array.has(index)) {
            return array.getObject(index);
        }

        return get(index, JSType.toPropertyKey(primitiveKey));
    }

    @Override
    public Object get(double key) {
        var index = getArrayIndex(key);
        var array = getArray();

        if (array.has(index)) {
            return array.getObject(index);
        }

        return get(index, JSType.toString(key));
    }

    @Override
    public Object get(int key) {
        var index = getArrayIndex(key);
        var array = getArray();

        if (array.has(index)) {
            return array.getObject(index);
        }

        return get(index, JSType.toString(key));
    }

    private boolean doesNotHaveCheckArrayKeys(long longIndex, int value, int callSiteFlags) {
        if (hasDefinedArrayProperties()) {
            var key  = JSType.toString(longIndex);
            var find = findProperty(key, true);
            if (find != null) {
                setObject(find, callSiteFlags, key, value);
                return true;
            }
        }
        return false;
    }

    private boolean doesNotHaveCheckArrayKeys(long longIndex, double value, int callSiteFlags) {
         if (hasDefinedArrayProperties()) {
            var key = JSType.toString(longIndex);
            var find = findProperty(key, true);
            if (find != null) {
                setObject(find, callSiteFlags, key, value);
                return true;
            }
        }
        return false;
    }

    private boolean doesNotHaveCheckArrayKeys(long longIndex, Object value, int callSiteFlags) {
        if (hasDefinedArrayProperties()) {
            var key  = JSType.toString(longIndex);
            var find = findProperty(key, true);
            if (find != null) {
                setObject(find, callSiteFlags, key, value);
                return true;
            }
        }
        return false;
    }

    private boolean hasDefinedArrayProperties() {
        for (var obj = this; obj != null; obj = obj.getProto()) {
            if (obj.getMap().containsArrayKeys()) {
                return true;
            }
        }
        return false;
    }

    // value agnostic
    private boolean doesNotHaveEnsureLength(long longIndex, long oldLength, int callSiteFlags) {
        if (longIndex >= oldLength) {
            if (!isExtensible()) {
                throw typeError("object.non.extensible", JSType.toString(longIndex), ScriptRuntime.safeToString(this));
            }
            setArray(getArray().ensure(longIndex));
        }
        return false;
    }

    private void doesNotHave(int index, int value, int callSiteFlags) {
        var oldLength = getArray().length();
        var longIndex = ArrayIndex.toLongIndex(index);
        if (!doesNotHaveCheckArrayKeys(longIndex, value, callSiteFlags) && !doesNotHaveEnsureLength(longIndex, oldLength, callSiteFlags)) {
            setArray(getArray().set(index, value).safeDelete(oldLength, longIndex - 1));
        }
    }

    private void doesNotHave(int index, double value, int callSiteFlags) {
        var oldLength = getArray().length();
        var longIndex = ArrayIndex.toLongIndex(index);
        if (!doesNotHaveCheckArrayKeys(longIndex, value, callSiteFlags) && !doesNotHaveEnsureLength(longIndex, oldLength, callSiteFlags)) {
            setArray(getArray().set(index, value).safeDelete(oldLength, longIndex - 1));
        }
    }

    private void doesNotHave(int index, Object value, int callSiteFlags) {
        var oldLength = getArray().length();
        var longIndex = ArrayIndex.toLongIndex(index);
        if (!doesNotHaveCheckArrayKeys(longIndex, value, callSiteFlags) && !doesNotHaveEnsureLength(longIndex, oldLength, callSiteFlags)) {
            setArray(getArray().set(index, value).safeDelete(oldLength, longIndex - 1));
        }
    }

    /**
     * This is the most generic of all Object setters. Most of the others use this in some form.
     * TODO: should be further specialized
     * @param find          found property
     * @param callSiteFlags callsite flags
     * @param key           property key
     * @param value         property value
     */
    public final void setObject(FindProperty find, int callSiteFlags, Object key, Object value) {
        var f = find;

        invalidateGlobalConstant(key);

        if (f != null && f.isInheritedOrdinaryProperty()) {
            var isScope = isScopeFlag(callSiteFlags);
            // If the start object of the find is not this object it means the property was found inside a 'with' statement expression (see WithObject.findProperty()).
            // In this case we forward the 'set' to the 'with' object.
            // Note that although a 'set' operation involving a with statement follows scope rules outside the 'with' expression (the 'set' operation is performed on the owning prototype if it exists), it follows non-scope rules inside the 'with' expression (set is performed on the top level object).
            // This is why we clear the callsite flags and FindProperty in the forward call to the 'with' object.
            if (isScope && f.getSelf() != this) {
                f.getSelf().setObject(null, 0, key, value);
                return;
            }
            // Setting a property should not modify the property in prototype unless this is a scope callsite and the owner is a scope object as well (with the exception of 'with' statement handled above).
            if (!isScope || !f.getOwner().isScope()) {
                f = null;
            }
        }

        if (f != null) {
            if ((!f.getProperty().isWritable() && !NashornCallSiteDescriptor.isDeclaration(callSiteFlags)) || !f.getProperty().hasNativeSetter()) {
                if (isScopeFlag(callSiteFlags) && f.getProperty().isLexicalBinding()) {
                    throw typeError("assign.constant", key.toString()); // Overwriting ES6 const
                }
                throw typeError(
                    f.getProperty().isAccessorProperty() ? "property.has.no.setter" : "property.not.writable",
                    key.toString(), ScriptRuntime.safeToString(this));
            }

            if (NashornCallSiteDescriptor.isDeclaration(callSiteFlags) && f.getProperty().needsDeclaration()) {
                f.getOwner().declareAndSet(f, value);
                return;
            }

            f.setValue(value);

        } else if (!isExtensible()) {
            throw typeError("object.non.extensible", key.toString(), ScriptRuntime.safeToString(this));
        } else {
            var sobj = this;
            // undefined scope properties are set in the global object.
            if (isScope()) {
                while (sobj != null && !(sobj instanceof Global)) {
                    sobj = sobj.getProto();
                }
                assert sobj != null : "no parent global object in scope";
            }
            // this will unbox any Number object to its primitive type in case the property supports primitive types, so it doesn't matter that it comes in as an Object.
            sobj.addSpillProperty(key, 0, value, true);
        }
    }

    @Override
    public void set(Object key, int value, int callSiteFlags) {
        var primitiveKey = JSType.toPrimitive(key, String.class);
        var index = getArrayIndex(primitiveKey);

        if (isValidArrayIndex(index)) {
            var data = getArray();
            if (data.has(index)) {
                setArray(data.set(index, value));
            } else {
                doesNotHave(index, value, callSiteFlags);
            }

            return;
        }

        var propName = JSType.toPropertyKey(primitiveKey);
        setObject(findProperty(propName, true), callSiteFlags, propName, JSType.toObject(value));
    }

    @Override
    public void set(Object key, double value, int callSiteFlags) {
        var primitiveKey = JSType.toPrimitive(key, String.class);
        var index = getArrayIndex(primitiveKey);

        if (isValidArrayIndex(index)) {
            var data = getArray();
            if (data.has(index)) {
                setArray(data.set(index, value));
            } else {
                doesNotHave(index, value, callSiteFlags);
            }

            return;
        }

        var propName = JSType.toPropertyKey(primitiveKey);
        setObject(findProperty(propName, true), callSiteFlags, propName, JSType.toObject(value));
    }

    @Override
    public void set(Object key, Object value, int callSiteFlags) {
        var primitiveKey = JSType.toPrimitive(key, String.class);
        var index = getArrayIndex(primitiveKey);

        if (isValidArrayIndex(index)) {
            var data = getArray();
            if (data.has(index)) {
                setArray(data.set(index, value));
            } else {
                doesNotHave(index, value, callSiteFlags);
            }

            return;
        }

        var propName = JSType.toPropertyKey(primitiveKey);
        setObject(findProperty(propName, true), callSiteFlags, propName, value);
    }

    @Override
    public void set(double key, int value, int callSiteFlags) {
        var index = getArrayIndex(key);

        if (isValidArrayIndex(index)) {
            var data = getArray();
            if (data.has(index)) {
                setArray(data.set(index, value));
            } else {
                doesNotHave(index, value, callSiteFlags);
            }

            return;
        }

        var propName = JSType.toString(key);
        setObject(findProperty(propName, true), callSiteFlags, propName, JSType.toObject(value));
    }

    @Override
    public void set(double key, double value, int callSiteFlags) {
        var index = getArrayIndex(key);

        if (isValidArrayIndex(index)) {
            var data = getArray();
            if (data.has(index)) {
                setArray(data.set(index, value));
            } else {
                doesNotHave(index, value, callSiteFlags);
            }

            return;
        }

        var propName = JSType.toString(key);
        setObject(findProperty(propName, true), callSiteFlags, propName, JSType.toObject(value));
    }

    @Override
    public void set(double key, Object value, int callSiteFlags) {
        var index = getArrayIndex(key);

        if (isValidArrayIndex(index)) {
            var data = getArray();
            if (data.has(index)) {
                setArray(data.set(index, value));
            } else {
                doesNotHave(index, value, callSiteFlags);
            }

            return;
        }

        var propName = JSType.toString(key);
        setObject(findProperty(propName, true), callSiteFlags, propName, value);
    }

    @Override
    public void set(int key, int value, int callSiteFlags) {
        var index = getArrayIndex(key);
        if (isValidArrayIndex(index)) {
            if (getArray().has(index)) {
                final ArrayData data = getArray();
                setArray(data.set(index, value));
            } else {
                doesNotHave(index, value, callSiteFlags);
            }
            return;
        }

        var propName = JSType.toString(key);
        setObject(findProperty(propName, true), callSiteFlags, propName, JSType.toObject(value));
    }

    @Override
    public void set(int key, double value, int callSiteFlags) {
        var index = getArrayIndex(key);

        if (isValidArrayIndex(index)) {
            var data = getArray();
            if (data.has(index)) {
                setArray(data.set(index, value));
            } else {
                doesNotHave(index, value, callSiteFlags);
            }

            return;
        }

        var propName = JSType.toString(key);
        setObject(findProperty(propName, true), callSiteFlags, propName, JSType.toObject(value));
    }

    @Override
    public void set(int key, Object value, int callSiteFlags) {
        var index = getArrayIndex(key);

        if (isValidArrayIndex(index)) {
            var data = getArray();
            if (data.has(index)) {
                setArray(data.set(index, value));
            } else {
                doesNotHave(index, value, callSiteFlags);
            }

            return;
        }

        var propName = JSType.toString(key);
        setObject(findProperty(propName, true), callSiteFlags, propName, value);
    }

    @Override
    public boolean has(Object key) {
        var primitiveKey = JSType.toPrimitive(key);
        var index = getArrayIndex(primitiveKey);
        return isValidArrayIndex(index) ? hasArrayProperty(index) : hasProperty(JSType.toPropertyKey(primitiveKey), true);
    }

    @Override
    public boolean has(double key) {
        var index = getArrayIndex(key);
        return isValidArrayIndex(index) ? hasArrayProperty(index) : hasProperty(JSType.toString(key), true);
    }

    @Override
    public boolean has(int key) {
        var index = getArrayIndex(key);
        return isValidArrayIndex(index) ? hasArrayProperty(index) : hasProperty(JSType.toString(key), true);
    }

    private boolean hasArrayProperty(int index) {
        var hasArrayKeys = false;

        for (var self = this; self != null; self = self.getProto()) {
            if (self.getArray().has(index)) {
                return true;
            }
            hasArrayKeys = hasArrayKeys || self.getMap().containsArrayKeys();
        }

        return hasArrayKeys && hasProperty(ArrayIndex.toKey(index), true);
    }

    @Override
    public boolean hasOwnProperty(Object key) {
        var primitiveKey = JSType.toPrimitive(key, String.class);
        var index = getArrayIndex(primitiveKey);
        return isValidArrayIndex(index) ? hasOwnArrayProperty(index) : hasProperty(JSType.toPropertyKey(primitiveKey), false);
    }

    @Override
    public boolean hasOwnProperty(int key) {
        var index = getArrayIndex(key);
        return isValidArrayIndex(index) ? hasOwnArrayProperty(index) : hasProperty(JSType.toString(key), false);
    }

    @Override
    public boolean hasOwnProperty(double key) {
        var index = getArrayIndex(key);
        return isValidArrayIndex(index) ? hasOwnArrayProperty(index) : hasProperty(JSType.toString(key), false);
    }

    private boolean hasOwnArrayProperty(int index) {
        return getArray().has(index) || getMap().containsArrayKeys() && hasProperty(ArrayIndex.toKey(index), false);
    }

    @Override
    public boolean delete(int key) {
        var index = getArrayIndex(key);
        var array = getArray();

        if (array.has(index)) {
            if (array.canDelete(index)) {
                setArray(array.delete(index));
                return true;
            }
            return false;
        }
        return deleteObject(JSType.toObject(key));
    }

    @Override
    public boolean delete(double key) {
        var index = getArrayIndex(key);
        var array = getArray();

        if (array.has(index)) {
            if (array.canDelete(index)) {
                setArray(array.delete(index));
                return true;
            }
            return false;
        }

        return deleteObject(JSType.toObject(key));
    }

    @Override
    public boolean delete(Object key) {
        var primitiveKey = JSType.toPrimitive(key, String.class);
        var index = getArrayIndex(primitiveKey);
        var array = getArray();

        if (array.has(index)) {
            if (array.canDelete(index)) {
                setArray(array.delete(index));
                return true;
            }
            return false;
        }

        return deleteObject(primitiveKey);
    }

    private boolean deleteObject(Object key) {
        var propName = JSType.toPropertyKey(key);
        var find = findProperty(propName, false);

        if (find == null) {
            return true;
        }

        if (!find.getProperty().isConfigurable()) {
            return false; // throw typeError("cant.delete.property", propName.toString(), ScriptRuntime.safeToString(this));
        }

        var prop = find.getProperty();
        deleteOwnProperty(prop);

        return true;
    }

    /**
     * Return a shallow copy of this ScriptObject.
     * @return a shallow copy.
     */
    public final ScriptObject copy() {
        try {
            return clone();
        } catch (CloneNotSupportedException e) {
            return Util.uncheck(e);
        }
    }

    @Override
    protected ScriptObject clone() throws CloneNotSupportedException {
        var clone = (ScriptObject) super.clone();
        if (objectSpill != null) {
            clone.objectSpill = objectSpill.clone();
            if (primitiveSpill != null) {
                clone.primitiveSpill = primitiveSpill.clone();
            }
        }
        clone.arrayData = arrayData.copy();
        return clone;
    }

    /**
     * Make a new UserAccessorProperty property.
     * getter and setter functions are stored in this ScriptObject and slot values are used in property object.
     * @param key the property name
     * @param propertyFlags attribute flags of the property
     * @param getter getter function for the property
     * @param setter setter function for the property
     * @return the newly created UserAccessorProperty
     */
    protected final UserAccessorProperty newUserAccessors(Object key, int propertyFlags, ScriptFunction getter, ScriptFunction setter) {
        var uc = getMap().newUserAccessors(key, propertyFlags);
        // property.getSetter(Object.class, getMap());
        uc.setAccessors(this, getMap(), new UserAccessorProperty.Accessors(getter, setter));
        return uc;
    }

    /**
     * Returns {@code true} if properties for this object should use dual field mode, {@code false} otherwise.
     */
    protected boolean useDualFields() {
        return !StructureLoader.isSingleFieldStructure(getClass().getName());
    }

    Object ensureSpillSize(int slot) {
        var oldLength = objectSpill == null ? 0 : objectSpill.length;
        if (slot < oldLength) {
            return this;
        }
        var newLength = alignUp(slot + 1, SPILL_RATE);
        var newObjectSpill = new Object[newLength];
        var newPrimitiveSpill = useDualFields() ? new long[newLength] : null;

        if (objectSpill != null) {
            System.arraycopy(objectSpill, 0, newObjectSpill, 0, oldLength);
            if (primitiveSpill != null && newPrimitiveSpill != null) {
                System.arraycopy(primitiveSpill, 0, newPrimitiveSpill, 0, oldLength);
            }
        }

        this.primitiveSpill = newPrimitiveSpill;
        this.objectSpill    = newObjectSpill;

        return this;
    }

    private static MethodHandle findOwnMH_V(String name, Class<?> rtype, Class<?>... types) {
        return MH.findVirtual(MethodHandles.lookup(), ScriptObject.class, name, MH.type(rtype, types));
    }

    private static MethodHandle findOwnMH_S(String name, Class<?> rtype, Class<?>... types) {
        return MH.findStatic(MethodHandles.lookup(), ScriptObject.class, name, MH.type(rtype, types));
    }

    private static MethodHandle getKnownFunctionPropertyGuardSelf(PropertyMap map, MethodHandle getter, ScriptFunction func) {
        return MH.insertArguments(KNOWNFUNCPROPGUARDSELF, 1, map, getter, func);
    }

    @SuppressWarnings("unused")
    private static boolean knownFunctionPropertyGuardSelf(Object self, PropertyMap map, MethodHandle getter, ScriptFunction func) {
        if (self instanceof ScriptObject && ((ScriptObject)self).getMap() == map) {
            try {
                return getter.invokeExact(self) == func;
            } catch (Throwable t) {
                Util.uncheck(t);
            }
        }

        return false;
    }

    private static MethodHandle getKnownFunctionPropertyGuardProto(PropertyMap map, MethodHandle getter, int depth, ScriptFunction func) {
        return MH.insertArguments(KNOWNFUNCPROPGUARDPROTO, 1, map, getter, depth, func);
    }

    private static ScriptObject getProto(ScriptObject self, int depth) {
        var proto = self;
        for (var d = 0; d < depth; d++) {
            proto = proto.getProto();
            if (proto == null) {
                return null;
            }
        }

        return proto;
    }

    @SuppressWarnings("unused")
    private static boolean knownFunctionPropertyGuardProto(Object self, PropertyMap map, MethodHandle getter, int depth, ScriptFunction func) {
        if (self instanceof ScriptObject && ((ScriptObject)self).getMap() == map) {
            var proto = getProto((ScriptObject)self, depth);
            if (proto == null) {
                return false;
            }
            try {
                return getter.invokeExact((Object)proto) == func;
            } catch (Throwable t) {
                Util.uncheck(t);
            }
        }

        return false;
    }

}
