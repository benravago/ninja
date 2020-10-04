/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

import nashorn.internal.Util;
import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.Getter;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.ConsString;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.Undefined;
import nashorn.internal.runtime.linker.Bootstrap;

import static nashorn.internal.runtime.ECMAErrors.typeError;

/**
 * This implements the ECMA6 Map object.
 */
@ScriptClass("Map")
public class NativeMap extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    // our underlying map
    private final LinkedMap map = new LinkedMap();

    // key for the forEach invoker callback
    private final static Object FOREACH_INVOKER_KEY = new Object();

    private NativeMap(ScriptObject proto, PropertyMap map) {
        super(proto, map);
    }

    /**
     * ECMA6 23.1.1 The Map Constructor
     */
    @Constructor(arity = 0)
    public static Object construct(boolean isNew, Object self, Object arg) {
        if (!isNew) {
            throw typeError("constructor.requires.new", "Map");
        }
        var global = Global.instance();
        var map = new NativeMap(global.getMapPrototype(), $nasgenmap$);
        populateMap(map.getJavaMap(), arg, global);
        return map;
    }

    /**
     * ECMA6 23.1.3.1 Map.prototype.clear ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static void clear(Object self) {
        getNativeMap(self).map.clear();
    }

    /**
     * ECMA6 23.1.3.3 Map.prototype.delete ( key )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean delete(Object self, Object key) {
        return getNativeMap(self).map.delete(convertKey(key));
    }

    /**
     * ECMA6 23.1.3.7 Map.prototype.has ( key )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean has(Object self, Object key) {
        return getNativeMap(self).map.has(convertKey(key));
    }

    /**
     * ECMA6 23.1.3.9 Map.prototype.set ( key , value )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object set(Object self, Object key, Object value) {
        getNativeMap(self).map.set(convertKey(key), value);
        return self;
    }

    /**
     * ECMA6 23.1.3.6 Map.prototype.get ( key )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object get(Object self, Object key) {
        return getNativeMap(self).map.get(convertKey(key));
    }

    /**
     * ECMA6 23.1.3.10 get Map.prototype.size
     */
    @Getter(attributes = Attribute.NOT_ENUMERABLE | Attribute.IS_ACCESSOR, where = Where.PROTOTYPE)
    public static int size(Object self) {
        return getNativeMap(self).map.size();
    }

    /**
     * ECMA6 23.1.3.4 Map.prototype.entries ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object entries(Object self) {
        return new MapIterator(getNativeMap(self), AbstractIterator.IterationKind.KEY_VALUE, Global.instance());
    }

    /**
     * ECMA6 23.1.3.8 Map.prototype.keys ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object keys(Object self) {
        return new MapIterator(getNativeMap(self), AbstractIterator.IterationKind.KEY, Global.instance());
    }

    /**
     * ECMA6 23.1.3.11 Map.prototype.values ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object values(Object self) {
        return new MapIterator(getNativeMap(self), AbstractIterator.IterationKind.VALUE, Global.instance());
    }

    /**
     * ECMA6 23.1.3.12 Map.prototype [ @@iterator ]( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, name = "@@iterator")
    public static Object getIterator(Object self) {
        return new MapIterator(getNativeMap(self), AbstractIterator.IterationKind.KEY_VALUE, Global.instance());
    }

    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static void forEach(Object self, Object callbackFn, Object thisArg) {
        var map = getNativeMap(self);
        if (!Bootstrap.isCallable(callbackFn)) {
            throw typeError("not.a.function", ScriptRuntime.safeToString(callbackFn));
        }
        var invoker = Global.instance().getDynamicInvoker(FOREACH_INVOKER_KEY,
            () -> Bootstrap.createDynamicCallInvoker(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class));

        var iterator = map.getJavaMap().getIterator();
        for (;;) {
            var node = iterator.next();
            if (node == null) {
                break;
            }

            try {
                var result = invoker.invokeExact(callbackFn, thisArg, node.getValue(), node.getKey(), self);
            } catch (Throwable t) {
                Util.uncheck(t);
            }
        }
    }

    @Override
    public String getClassName() {
        return "Map";
    }

    static void populateMap(LinkedMap map, Object arg, Global global) {
        if (arg != null && arg != Undefined.getUndefined()) {
            AbstractIterator.iterate(arg, global, value -> {
                if (JSType.isPrimitive(value)) {
                    throw typeError(global, "not.an.object", ScriptRuntime.safeToString(value));
                }
                if (value instanceof ScriptObject) {
                    var sobj = (ScriptObject) value;
                    map.set(convertKey(sobj.get(0)), sobj.get(1));
                }
            });
        }
    }

    /**
     * Returns a canonicalized key object by converting numbers to their narrowest representation and ConsStrings to strings.
     * Conversion of Double to Integer also takes care of converting -0 to 0 as required by step 6 of ECMA6 23.1.3.9.
     */
    static Object convertKey(Object key) {
        if (key instanceof ConsString) {
            return key.toString();
        }
        if (key instanceof Double) {
            var d = (Double) key;
            if (JSType.isRepresentableAsInt(d.doubleValue())) {
                return d.intValue();
            }
        }
        return key;
    }

    /**
     * Get the underlying Java map.
     */
    LinkedMap getJavaMap() {
        return map;
    }

    private static NativeMap getNativeMap(Object self) {
        if (self instanceof NativeMap) {
            return (NativeMap)self;
        } else {
            throw typeError("not.a.map", ScriptRuntime.safeToString(self));
        }
    }

}
