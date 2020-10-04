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

import java.util.Map;
import java.util.WeakHashMap;

import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.Undefined;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.JSType.isPrimitive;

/**
 * This implements the ECMA6 WeakMap object.
 */
@ScriptClass("WeakMap")
public class NativeWeakMap extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private final Map<Object, Object> jmap = new WeakHashMap<>();

    private NativeWeakMap(ScriptObject proto, PropertyMap map) {
        super(proto, map);
    }

    /**
     * ECMA6 23.3.1 The WeakMap Constructor
     */
    @Constructor(arity = 0)
    public static Object construct(boolean isNew, Object self, Object arg) {
        if (!isNew) {
            throw typeError("constructor.requires.new", "WeakMap");
        }
        var global = Global.instance();
        var weakMap = new NativeWeakMap(global.getWeakMapPrototype(), $nasgenmap$);
        populateMap(weakMap.jmap, arg, global);
        return weakMap;
    }

    /**
     * ECMA6 23.3.3.5 WeakMap.prototype.set ( key , value )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object set(Object self, Object key, Object value) {
        var map = getMap(self);
        map.jmap.put(checkKey(key), value);
        return self;
    }

    /**
     * ECMA6 23.3.3.3 WeakMap.prototype.get ( key )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object get(Object self, Object key) {
        var map = getMap(self);
        if (isPrimitive(key)) {
            return Undefined.getUndefined();
        }
        return map.jmap.get(key);
    }

    /**
     * ECMA6 23.3.3.2 WeakMap.prototype.delete ( key )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean delete(Object self, Object key) {
        var map = getMap(self).jmap;
        if (isPrimitive(key)) {
            return false;
        }
        var returnValue = map.containsKey(key);
        map.remove(key);
        return returnValue;
    }

    /**
     * ECMA6 23.3.3.4 WeakMap.prototype.has ( key )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static boolean has(Object self, Object key) {
        var map = getMap(self);
        return !isPrimitive(key) && map.jmap.containsKey(key);
    }

    @Override
    public String getClassName() {
        return "WeakMap";
    }

    /**
     * Make sure {@code key} is not a JavaScript primitive value.
     */
    static Object checkKey(Object key) {
        if (isPrimitive(key)) {
            throw typeError("invalid.weak.key", ScriptRuntime.safeToString(key));
        }
        return key;
    }

    static void populateMap(Map<Object, Object> map, Object arg, Global global) {
        // This method is similar to NativeMap.populateMap, but it uses a different map implementation and the checking/conversion of keys differs as well.
        if (arg != null && arg != Undefined.getUndefined()) {
            AbstractIterator.iterate(arg, global, value -> {
                if (isPrimitive(value)) {
                    throw typeError(global, "not.an.object", ScriptRuntime.safeToString(value));
                }
                if (value instanceof ScriptObject) {
                    var sobj = (ScriptObject) value;
                    map.put(checkKey(sobj.get(0)), sobj.get(1));
                }
            });
        }
    }

    private static NativeWeakMap getMap(Object self) {
        if (self instanceof NativeWeakMap) {
            return (NativeWeakMap)self;
        } else {
            throw typeError("not.a.weak.map", ScriptRuntime.safeToString(self));
        }
    }

}
