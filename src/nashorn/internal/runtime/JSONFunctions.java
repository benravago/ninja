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

package nashorn.internal.runtime;

import java.lang.invoke.MethodHandle;
import java.util.concurrent.Callable;

import nashorn.internal.Util;
import nashorn.internal.objects.Global;
import nashorn.internal.parser.JSONParser;
import nashorn.internal.runtime.arrays.ArrayIndex;
import nashorn.internal.runtime.linker.Bootstrap;

/**
 * Utilities used by "JSON" object implementation.
 */
public final class JSONFunctions {
    private JSONFunctions() {}

    private static final Object REVIVER_INVOKER = new Object();

    private static MethodHandle getREVIVER_INVOKER() {
        return Context.getGlobal().getDynamicInvoker(REVIVER_INVOKER,
            new Callable<MethodHandle>() {
                @Override
                public MethodHandle call() {
                    return Bootstrap.createDynamicCallInvoker(Object.class, Object.class, Object.class, String.class, Object.class);
                }
            });
    }

    /**
     * Returns JSON-compatible quoted version of the given string.
     * @param str String to be quoted
     * @return JSON-compatible quoted string
     */
    public static String quote(String str) {
        return JSONParser.quote(str);
    }

    /**
     * Parses the given JSON text string and returns object representation.
     * @param text JSON text to be parsed
     * @param reviver  optional value: function that takes two parameters (key, value)
     * @return Object representation of JSON text given
     */
    public static Object parse(Object text, Object reviver) {
        var str = JSType.toString(text);
        var global = Context.getGlobal();
        var dualFields = ((ScriptObject) global).useDualFields();
        var parser = new JSONParser(str, global, dualFields);
        Object value;

        try {
            value = parser.parse();
        } catch (ParserException e) {
            throw ECMAErrors.syntaxError(e, "invalid.json", e.getMessage());
        }

        return applyReviver(global, value, reviver);
    }

    // -- Internals only below this point

    // parse helpers

    // apply 'reviver' function if available
    private static Object applyReviver(Global global, Object unfiltered, Object reviver) {
        if (Bootstrap.isCallable(reviver)) {
            var root = global.newObject();
            root.addOwnProperty("", Property.WRITABLE_ENUMERABLE_CONFIGURABLE, unfiltered);
            return walk(root, "", reviver);
        }
        return unfiltered;
    }

    // This is the abstract "Walk" operation from the spec.
    private static Object walk(ScriptObject holder, Object name, Object reviver) {
        var val = holder.get(name);
        if (val instanceof ScriptObject) {
            var valueObj = (ScriptObject)val;
            if (valueObj.isArray()) {
                var length = JSType.toInteger(valueObj.getLength());
                for (var i = 0; i < length; i++) {
                    var key = Integer.toString(i);
                    var newElement = walk(valueObj, key, reviver);

                    if (newElement == ScriptRuntime.UNDEFINED) {
                        valueObj.delete(i); // false
                    } else {
                        setPropertyValue(valueObj, key, newElement);
                    }
                }
            } else {
                var keys = valueObj.getOwnKeys(false);
                for (var key : keys) {
                    var newElement = walk(valueObj, key, reviver);

                    if (newElement == ScriptRuntime.UNDEFINED) {
                        valueObj.delete(key); // false
                    } else {
                        setPropertyValue(valueObj, key, newElement);
                    }
                }
            }
        }

        try {
             // Object.class, ScriptFunction.class, ScriptObject.class, String.class, Object.class);
             return getREVIVER_INVOKER().invokeExact(reviver, (Object)holder, JSType.toString(name), val);
        } catch (Throwable t) {
            return Util.uncheck(t);
        }
    }

    // add a new property if does not exist already, or else set old property
    private static void setPropertyValue(ScriptObject sobj, String name, Object value) {
        var index = ArrayIndex.getArrayIndex(name);
        if (ArrayIndex.isValidArrayIndex(index)) {
            // array index key
            sobj.defineOwnProperty(index, value);
        } else if (sobj.getMap().findProperty(name) != null) {
            // pre-existing non-inherited property, call set
            sobj.set(name, value, 0);
        } else {
            // add new property
            sobj.addOwnProperty(name, Property.WRITABLE_ENUMERABLE_CONFIGURABLE, value);
        }
    }

}
