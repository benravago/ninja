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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import java.nio.ByteBuffer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import jdk.dynalink.CallSiteDescriptor;
import jdk.dynalink.Operation;
import jdk.dynalink.beans.BeansLinker;
import jdk.dynalink.beans.StaticClass;
import jdk.dynalink.linker.GuardedInvocation;
import jdk.dynalink.linker.GuardingDynamicLinker;
import jdk.dynalink.linker.LinkRequest;
import jdk.dynalink.linker.support.SimpleLinkRequest;
import static jdk.dynalink.StandardNamespace.METHOD;
import static jdk.dynalink.StandardNamespace.PROPERTY;
import static jdk.dynalink.StandardOperation.GET;
import static jdk.dynalink.StandardOperation.SET;

import nashorn.api.scripting.ScriptObjectMirror;
import nashorn.internal.Util;
import nashorn.internal.lookup.Lookup;
import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.AccessorProperty;
import nashorn.internal.runtime.ECMAException;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.Property;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.arrays.ArrayData;
import nashorn.internal.runtime.arrays.ArrayIndex;
import nashorn.internal.runtime.linker.Bootstrap;
import nashorn.internal.runtime.linker.InvokeByName;
import nashorn.internal.runtime.linker.NashornBeansLinker;
import nashorn.internal.runtime.linker.NashornCallSiteDescriptor;
import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

/**
 * ECMA 15.2 Object objects.
 *
 * JavaScript Object constructor/prototype.
 * Note: instances of this class are never created.
 * This class is not even a subclass of ScriptObject.
 * But, we use this class to generate prototype and constructor for "Object".
 */
@ScriptClass("Object")
public final class NativeObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    /** Methodhandle to proto getter */
    public static final MethodHandle GET__PROTO__ = findOwnMH("get__proto__", ScriptObject.class, Object.class);

    /** Methodhandle to proto setter */
    public static final MethodHandle SET__PROTO__ = findOwnMH("set__proto__", Object.class, Object.class, Object.class);

    private static final Object TO_STRING = new Object();

    private static InvokeByName getTO_STRING() {
        return Global.instance().getInvokeByName(TO_STRING,
            new Callable<InvokeByName>() {
                @Override
                public InvokeByName call() {
                    return new InvokeByName("toString", ScriptObject.class);
                }
            });
    }

    private static final Operation GET_METHOD   = GET.withNamespace(METHOD);
    private static final Operation GET_PROPERTY = GET.withNamespace(PROPERTY);
    private static final Operation SET_PROPERTY = SET.withNamespace(PROPERTY);

    @SuppressWarnings("unused")
    private static ScriptObject get__proto__(Object self) {
        // See ES6 draft spec: B.2.2.1.1 get Object.prototype.__proto__
        // Step 1 Let O be the result of calling ToObject passing the this.
        var sobj = Global.checkObject(Global.toObject(self));
        return sobj.getProto();
    }

    @SuppressWarnings("unused")
    private static Object set__proto__(Object self, Object proto) {
        // See ES6 draft spec: B.2.2.1.2 set Object.prototype.__proto__
        // Step 1
        Global.checkObjectCoercible(self);
        // Step 4
        if (! (self instanceof ScriptObject)) {
            return UNDEFINED;
        }

        var sobj = (ScriptObject)self;
        // __proto__ assignment ignores non-nulls and non-objects
        // step 3: If Type(proto) is neither Object nor Null, then return undefined.
        if (proto == null || proto instanceof ScriptObject) {
            sobj.setPrototypeOf(proto);
        }
        return UNDEFINED;
    }

    private static final MethodType MIRROR_GETTER_TYPE = MethodType.methodType(Object.class, ScriptObjectMirror.class);
    private static final MethodType MIRROR_SETTER_TYPE = MethodType.methodType(Object.class, ScriptObjectMirror.class, Object.class);

    private NativeObject() {
        // don't create me!
        throw new UnsupportedOperationException();
    }

    private static ECMAException notAnObject(Object obj) {
        return typeError("not.an.object", ScriptRuntime.safeToString(obj));
    }

    /**
     * Nashorn extension: setIndexedPropertiesToExternalArrayData
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static ScriptObject setIndexedPropertiesToExternalArrayData(Object self, Object obj, Object buf) {
        Global.checkObject(obj);
        var sobj = (ScriptObject)obj;
        if (buf instanceof ByteBuffer) {
            sobj.setArray(ArrayData.allocate((ByteBuffer)buf));
        } else {
            throw typeError("not.a.bytebuffer", "setIndexedPropertiesToExternalArrayData's buf argument");
        }
        return sobj;
    }

    /**
     * ECMA 15.2.3.2 Object.getPrototypeOf ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object getPrototypeOf(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).getProto();
        } else if (obj instanceof ScriptObjectMirror) {
            return ((ScriptObjectMirror)obj).getProto();
        } else {
            final JSType type = JSType.of(obj);
            if (type == JSType.OBJECT) {
                // host (Java) objects have null __proto__
                return null;
            }

            // must be some JS primitive
            throw notAnObject(obj);
        }
    }

    /**
     * Nashorn extension: Object.setPrototypeOf ( O, proto ).
     * Also found in ES6 draft specification.
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object setPrototypeOf(Object self, Object obj, Object proto) {
        if (obj instanceof ScriptObject) {
            ((ScriptObject)obj).setPrototypeOf(proto);
            return obj;
        } else if (obj instanceof ScriptObjectMirror) {
            ((ScriptObjectMirror)obj).setProto(proto);
            return obj;
        }
        throw notAnObject(obj);
    }

    /**
     * ECMA 15.2.3.3 Object.getOwnPropertyDescriptor ( O, P )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object getOwnPropertyDescriptor(Object self, Object obj, Object prop) {
        if (obj instanceof ScriptObject) {
            var key = JSType.toString(prop);
            var sobj = (ScriptObject)obj;
            return sobj.getOwnPropertyDescriptor(key);
        } else if (obj instanceof ScriptObjectMirror) {
            var key = JSType.toString(prop);
            var sobjMirror = (ScriptObjectMirror)obj;
            return sobjMirror.getOwnPropertyDescriptor(key);
        } else {
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 15.2.3.4 Object.getOwnPropertyNames ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static ScriptObject getOwnPropertyNames(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            return new NativeArray(((ScriptObject)obj).getOwnKeys(true));
        } else if (obj instanceof ScriptObjectMirror) {
            return new NativeArray(((ScriptObjectMirror)obj).getOwnKeys(true));
        } else {
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 2 19.1.2.8 Object.getOwnPropertySymbols ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static ScriptObject getOwnPropertySymbols(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            return new NativeArray(((ScriptObject)obj).getOwnSymbols(true));
        } else {
            // TODO: we don't support this on ScriptObjectMirror objects yet
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 15.2.3.5 Object.create ( O [, Properties] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static ScriptObject create(Object self, Object proto, Object props) {
        if (proto != null) {
            Global.checkObject(proto);
        }

        // FIXME: should we create a proper object with correct number of properties?
        var newObj = Global.newEmptyInstance();
        newObj.setProto((ScriptObject)proto);
        if (props != UNDEFINED) {
            NativeObject.defineProperties(self, newObj, props);
        }

        return newObj;
    }

    /**
     * ECMA 15.2.3.6 Object.defineProperty ( O, P, Attributes )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static ScriptObject defineProperty(Object self, Object obj, Object prop, Object attr) {
        var sobj = Global.checkObject(obj);
        sobj.defineOwnProperty(JSType.toPropertyKey(prop), attr, true);
        return sobj;
    }

    /**
     * ECMA 5.2.3.7 Object.defineProperties ( O, Properties )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static ScriptObject defineProperties(Object self, Object obj, Object props) {
        var sobj = Global.checkObject(obj);
        var propsObj = Global.toObject(props);

        if (propsObj instanceof ScriptObject) {
            var keys = ((ScriptObject)propsObj).getOwnKeys(false);
            for (var key : keys) {
                var prop = JSType.toString(key);
                sobj.defineOwnProperty(prop, ((ScriptObject)propsObj).get(prop), true);
            }
        }
        return sobj;
    }

    /**
     * ECMA 15.2.3.8 Object.seal ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object seal(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).seal();
        } else if (obj instanceof ScriptObjectMirror) {
            return ((ScriptObjectMirror)obj).seal();
        } else {
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 15.2.3.9 Object.freeze ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object freeze(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).freeze();
        } else if (obj instanceof ScriptObjectMirror) {
            return ((ScriptObjectMirror)obj).freeze();
        } else {
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 15.2.3.10 Object.preventExtensions ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object preventExtensions(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).preventExtensions();
        } else if (obj instanceof ScriptObjectMirror) {
            return ((ScriptObjectMirror)obj).preventExtensions();
        } else {
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 15.2.3.11 Object.isSealed ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static boolean isSealed(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).isSealed();
        } else if (obj instanceof ScriptObjectMirror) {
            return ((ScriptObjectMirror)obj).isSealed();
        } else {
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 15.2.3.12 Object.isFrozen ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static boolean isFrozen(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).isFrozen();
        } else if (obj instanceof ScriptObjectMirror) {
            return ((ScriptObjectMirror)obj).isFrozen();
        } else {
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 15.2.3.13 Object.isExtensible ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static boolean isExtensible(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).isExtensible();
        } else if (obj instanceof ScriptObjectMirror) {
            return ((ScriptObjectMirror)obj).isExtensible();
        } else {
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 15.2.3.14 Object.keys ( O )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static ScriptObject keys(Object self, Object obj) {
        if (obj instanceof ScriptObject) {
            var sobj = (ScriptObject)obj;
            return new NativeArray(sobj.getOwnKeys(false));
        } else if (obj instanceof ScriptObjectMirror) {
            var sobjMirror = (ScriptObjectMirror)obj;
            return new NativeArray(sobjMirror.getOwnKeys(false));
        } else {
            throw notAnObject(obj);
        }
    }

    /**
     * ECMA 15.2.2.1 , 15.2.1.1 new Object([value]) and Object([value])
     */
    @Constructor
    public static Object construct(boolean newObj, Object self, Object value) {
        var type = JSType.ofNoFunction(value);

        // Object(null), Object(undefined), Object() are same as "new Object()"

        if (newObj || type == JSType.NULL || type == JSType.UNDEFINED) {
            switch (type) {
                case BOOLEAN:
                case NUMBER:
                case STRING:
                case SYMBOL: {
                    return Global.toObject(value);
                }
                case OBJECT: {
                    return value;
                }
                case NULL:
                case UNDEFINED:
                    // FALL-THROUGH
                default: {
                    break;
                }
            }
            return Global.newEmptyInstance();
        }
        return Global.toObject(value);
    }

    /**
     * ECMA 15.2.4.2 Object.prototype.toString ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static String toString(Object self) {
        return ScriptRuntime.builtinObjectToString(self);
    }

    /**
     * ECMA 15.2.4.3 Object.prototype.toLocaleString ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object toLocaleString(Object self) {
        var obj = JSType.toScriptObject(self);
        if (obj instanceof ScriptObject) {
            var toStringInvoker = getTO_STRING();
            var sobj = (ScriptObject)obj;
            try {
                var toString = toStringInvoker.getGetter().invokeExact(sobj);

                if (Bootstrap.isCallable(toString)) {
                    return toStringInvoker.getInvoker().invokeExact(toString, sobj);
                }
            } catch (Throwable t) {
                Util.uncheck(t);
            }
            throw typeError("not.a.function", "toString");
        }
        return ScriptRuntime.builtinObjectToString(self);
    }

    /**
     * ECMA 15.2.4.4 Object.prototype.valueOf ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object valueOf(Object self) {
        return Global.toObject(self);
    }

    /**
     * ECMA 15.2.4.5 Object.prototype.hasOwnProperty (V)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean hasOwnProperty(Object self, Object v) {
        // Convert ScriptObjects to primitive with String.class hint but no need to convert other primitives to string.
        var key = JSType.toPrimitive(v, String.class);
        var obj = Global.toObject(self);
        return obj instanceof ScriptObject && ((ScriptObject)obj).hasOwnProperty(key);
    }

    /**
     * ECMA 15.2.4.6 Object.prototype.isPrototypeOf (V)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean isPrototypeOf(Object self, Object v) {
        if (!(v instanceof ScriptObject)) {
            return false;
        }

        var obj   = Global.toObject(self);
        var proto = (ScriptObject)v;

        do {
            proto = proto.getProto();
            if (proto == obj) {
                return true;
            }
        } while (proto != null);

        return false;
    }

    /**
     * ECMA 15.2.4.7 Object.prototype.propertyIsEnumerable (V)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean propertyIsEnumerable(Object self, Object v) {
        var str = JSType.toString(v);
        var obj = Global.toObject(self);

        if (obj instanceof ScriptObject) {
            var sobj = (ScriptObject) obj;
            var property = sobj.getProperty(str);
            if (property != null) {
                return property.isEnumerable();
            } else {
                return (sobj.getArray().has(ArrayIndex.getArrayIndex(v)));
            }
        }

        return false;
    }

    /**
     * Nashorn extension: Object.bindProperties.
     * <p>
     * Binds the source object's properties to the target object.
     * Binding properties allows two-way read/write for the properties of the source object.
     * <p>
     * Example:
     * <pre>
     * var obj = { x: 34, y: 100 };
     * var foo = {}
     *
     * // bind properties of "obj" to "foo" object
     * Object.bindProperties(foo, obj);
     *
     * // now, we can access/write on 'foo' properties
     * print(foo.x); // prints obj.x which is 34
     *
     * // update obj.x via foo.x
     * foo.x = "hello";
     * print(obj.x); // prints "hello" now
     *
     * obj.x = 42;   // foo.x also becomes 42
     * print(foo.x); // prints 42
     * </pre>
     * <p>
     * The source object bound can be a ScriptObject or a ScriptOjectMirror.
     * null or undefined source object results in TypeError being thrown.
     * <p>
     * Example:
     * <pre>
     * var obj = loadWithNewGlobal({
     *    name: "test",
     *    script: "obj = { x: 33, y: 'hello' }"
     * });
     *
     * // bind 'obj's properties to global scope 'this'
     * Object.bindProperties(this, obj);
     * print(x);         // prints 33
     * print(y);         // prints "hello"
     * x = Math.PI;      // changes obj.x to Math.PI
     * print(obj.x);     // prints Math.PI
     * </pre>
     * <p>
     * Limitations of property binding:
     * <ul>
     * <li> Only enumerable, immediate (not proto inherited) properties of the source object are bound.
     * <li> If the target object already contains a property called "foo", the source's "foo" is skipped (not bound).
     * <li> Properties added to the source object after binding to the target are not bound.
     * <li> Property configuration changes on the source object (or on the target) is not propagated.
     * <li> Delete of property on the target (or the source) is not propagated - only the property value is set to 'undefined' if the property happens to be a data property.
     * </ul>
     * <p>
     * It is recommended that the bound properties be treated as non-configurable properties to avoid surprises.
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object bindProperties(Object self, Object target, Object source) {
        // target object has to be a ScriptObject
        var targetObj = Global.checkObject(target);
        // check null or undefined source object
        Global.checkObjectCoercible(source);

        if (source instanceof ScriptObject) {
            var sourceObj = (ScriptObject)source;

            var sourceMap = sourceObj.getMap();
            var properties = sourceMap.getProperties();
            // replace the map and blow up everything to objects to work with dual fields :-(

            // filter non-enumerable properties
            var propList = new ArrayList<Property>();
            for (var prop : properties) {
                if (prop.isEnumerable()) {
                    var value = sourceObj.get(prop.getKey());
                    prop.setType(Object.class);
                    prop.setValue(sourceObj, sourceObj, value); // false
                    propList.add(prop);
                }
            }

            if (!propList.isEmpty()) {
                targetObj.addBoundProperties(sourceObj, propList.toArray(new Property[0]));
            }
        } else if (source instanceof ScriptObjectMirror) {
            // get enumerable, immediate properties of mirror
            var mirror = (ScriptObjectMirror)source;
            var keys = mirror.getOwnKeys(false);
            if (keys.length == 0) {
                // nothing to bind
                return target;
            }

            // make accessor properties using dynamic invoker getters and setters
            var props = new AccessorProperty[keys.length];
            for (var idx = 0; idx < keys.length; idx++) {
                props[idx] = createAccessorProperty(keys[idx]);
            }

            targetObj.addBoundProperties(source, props);
        } else if (source instanceof StaticClass) {
            var clazz = ((StaticClass)source).getRepresentedClass();
            Bootstrap.checkReflectionAccess(clazz, true);
            bindBeanProperties(targetObj, source,
                BeansLinker.getReadableStaticPropertyNames(clazz),
                BeansLinker.getWritableStaticPropertyNames(clazz),
                BeansLinker.getStaticMethodNames(clazz));
        } else {
            var clazz = source.getClass();
            Bootstrap.checkReflectionAccess(clazz, false);
            bindBeanProperties(targetObj, source,
                BeansLinker.getReadableInstancePropertyNames(clazz),
                BeansLinker.getWritableInstancePropertyNames(clazz),
                BeansLinker.getInstanceMethodNames(clazz));
        }

        return target;
    }

    private static AccessorProperty createAccessorProperty(String name) {
        var getter = Bootstrap.createDynamicInvoker(name, NashornCallSiteDescriptor.GET_METHOD_PROPERTY, MIRROR_GETTER_TYPE);
        var setter = Bootstrap.createDynamicInvoker(name, NashornCallSiteDescriptor.SET_PROPERTY, MIRROR_SETTER_TYPE);
        return AccessorProperty.create(name, 0, getter, setter);
    }

    /**
     * Binds the source mirror object's properties to the target object.
     * Binding properties allows two-way read/write for the properties of the source object.
     * All inherited, enumerable properties are also bound.
     * This method is used to to make 'with' statement work with ScriptObjectMirror as scope object.
     */
    public static Object bindAllProperties(ScriptObject target, ScriptObjectMirror source) {
        var keys = source.keySet();
        // make accessor properties using dynamic invoker getters and setters
        var props = new AccessorProperty[keys.size()];
        int idx = 0;
        for (String name : keys) {
            props[idx] = createAccessorProperty(name);
            idx++;
        }

        target.addBoundProperties(source, props);
        return target;
    }

    private static void bindBeanProperties(ScriptObject targetObj, Object source, Collection<String> readablePropertyNames, Collection<String> writablePropertyNames, Collection<String> methodNames) {
        var  propertyNames = new HashSet<String>(readablePropertyNames);
        propertyNames.addAll(writablePropertyNames);

        var clazz = source.getClass();

        var getterType = MethodType.methodType(Object.class, clazz);
        var setterType = MethodType.methodType(Object.class, clazz, Object.class);

        var linker = Bootstrap.getBeanLinkerForClass(clazz);

        var properties = new ArrayList<AccessorProperty>(propertyNames.size() + methodNames.size());
        for (var methodName: methodNames) {
            MethodHandle method;
            try {
                method = getBeanOperation(linker, GET_METHOD, methodName, getterType, source);
            } catch(IllegalAccessError e) {
                // Presumably, this was a caller sensitive method. Ignore it and carry on.
                continue;
            }
            properties.add(AccessorProperty.create(methodName, Property.NOT_WRITABLE, getBoundBeanMethodGetter(source, method), Lookup.EMPTY_SETTER));
        }
        for (var propertyName: propertyNames) {
            MethodHandle getter;
            if (readablePropertyNames.contains(propertyName)) {
                try {
                    getter = getBeanOperation(linker, GET_PROPERTY, propertyName, getterType, source);
                } catch(IllegalAccessError e) {
                    // Presumably, this was a caller sensitive method. Ignore it and carry on.
                    getter = Lookup.EMPTY_GETTER;
                }
            } else {
                getter = Lookup.EMPTY_GETTER;
            }
            var isWritable = writablePropertyNames.contains(propertyName);
            MethodHandle setter;
            if (isWritable) {
                try {
                    setter = getBeanOperation(linker, SET_PROPERTY, propertyName, setterType, source);
                } catch(IllegalAccessError e) {
                    // Presumably, this was a caller sensitive method. Ignore it and carry on.
                    setter = Lookup.EMPTY_SETTER;
                }
            } else {
                setter = Lookup.EMPTY_SETTER;
            }
            if (getter != Lookup.EMPTY_GETTER || setter != Lookup.EMPTY_SETTER) {
                properties.add(AccessorProperty.create(propertyName, isWritable ? 0 : Property.NOT_WRITABLE, getter, setter));
            }
        }

        targetObj.addBoundProperties(source, properties.toArray(new AccessorProperty[0]));
    }

    private static MethodHandle getBoundBeanMethodGetter(Object source, MethodHandle methodGetter) {
        try {
            // NOTE: we're relying on the fact that StandardOperation.GET_METHOD return value is constant for any given method name and object linked with BeansLinker.
            // (Actually, an even stronger assumption is true: return value is constant for any given method name and object's class.)
            return MethodHandles.dropArguments(MethodHandles.constant(Object.class, Bootstrap.bindCallable(methodGetter.invoke(source), source, null)), 0, Object.class);
        } catch (Throwable t) {
            return Util.uncheck(t);
        }
    }

    private static MethodHandle getBeanOperation(GuardingDynamicLinker linker, Operation operation, String name, MethodType methodType, Object source) {
        GuardedInvocation inv;
        try {
            inv = NashornBeansLinker.getGuardedInvocation(linker, createLinkRequest(operation.named(name), methodType, source), Bootstrap.getLinkerServices());
            assert passesGuard(source, inv.getGuard());
        } catch (Throwable t) {
            return Util.uncheck(t);
        }
        assert inv.getSwitchPoints() == null; // Linkers in Dynalink's beans package don't use switchpoints.
        // We discard the guard, as all method handles will be bound to a specific object.
        return inv.getInvocation();
    }

    private static boolean passesGuard(Object obj, MethodHandle guard) throws Throwable {
        return guard == null || (boolean)guard.invoke(obj);
    }

    private static LinkRequest createLinkRequest(Operation operation, MethodType methodType, Object source) {
        return new SimpleLinkRequest(new CallSiteDescriptor(MethodHandles.publicLookup(), operation, methodType), false, source);
    }

    private static MethodHandle findOwnMH(String name, Class<?> rtype, Class<?>... types) {
        return MH.findStatic(MethodHandles.lookup(), NativeObject.class, name, MH.type(rtype, types));
    }

}
