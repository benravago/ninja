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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

import nashorn.api.scripting.JSObject;
import nashorn.api.scripting.ScriptObjectMirror;
import nashorn.internal.Util;
import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.objects.annotations.Where;
import nashorn.internal.runtime.ConsString;
import nashorn.internal.runtime.JSONFunctions;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.arrays.ArrayLikeIterator;
import nashorn.internal.runtime.linker.Bootstrap;
import nashorn.internal.runtime.linker.InvokeByName;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

/**
 * ECMAScript 262 Edition 5, Section 15.12 The NativeJSON Object
 *
 */
@ScriptClass("JSON")
public final class NativeJSON extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private static final Object TO_JSON = new Object();

    private static InvokeByName getTO_JSON() {
        return Global.instance().getInvokeByName(TO_JSON,
            new Callable<InvokeByName>() {
                @Override
                public InvokeByName call() {
                    return new InvokeByName("toJSON", ScriptObject.class, Object.class, Object.class);
                }
            });
    }

    private static final Object JSOBJECT_INVOKER = new Object();

    private static MethodHandle getJSOBJECT_INVOKER() {
        return Global.instance().getDynamicInvoker(JSOBJECT_INVOKER,
             new Callable<MethodHandle>() {
                @Override
                public MethodHandle call() {
                    return Bootstrap.createDynamicCallInvoker(Object.class, Object.class, Object.class);
                }
            });
    }

    private static final Object REPLACER_INVOKER = new Object();

    private static MethodHandle getREPLACER_INVOKER() {
        return Global.instance().getDynamicInvoker(REPLACER_INVOKER,
            new Callable<MethodHandle>() {
                @Override
                public MethodHandle call() {
                    return Bootstrap.createDynamicCallInvoker(Object.class,
                        Object.class, Object.class, Object.class, Object.class);
                }
            });
    }

    private NativeJSON() {
        // don't create me!!
        throw new UnsupportedOperationException();
    }

    /**
     * ECMA 15.12.2 parse ( text [ , reviver ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object parse(Object self, Object text, Object reviver) {
        return JSONFunctions.parse(text, reviver);
    }

    /**
     * ECMA 15.12.3 stringify ( value [ , replacer [ , space ] ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, where = Where.CONSTRUCTOR)
    public static Object stringify(Object self, Object value, Object replacer, Object space) {

        // The stringify method takes a value and an optional replacer, and an optional space parameter, and returns a JSON text.
        // The replacer can be a function that can replace values, or an array of strings that will select the keys.
        // A default replacer method can be provided.
        // Use of the space parameter can produce text that is more easily readable.

        var state = new StringifyState();

        // If there is a replacer, it must be a function or an array.
        if (Bootstrap.isCallable(replacer)) {
            state.replacerFunction = replacer;
        } else if (isArray(replacer) || isJSObjectArray(replacer) || replacer instanceof Iterable || (replacer != null && replacer.getClass().isArray())) {
            state.propertyList = new ArrayList<>();

            var iter = ArrayLikeIterator.arrayLikeIterator(replacer);

            while (iter.hasNext()) {
                String item = null;
                var v = iter.next();

                if (v instanceof String) {
                    item = (String) v;
                } else if (v instanceof ConsString) {
                    item = v.toString();
                } else if (v instanceof Number || v instanceof NativeNumber || v instanceof NativeString) {
                    item = JSType.toString(v);
                }

                if (item != null) {
                    state.propertyList.add(item);
                }
            }
        }

        // If the space parameter is a number, make an indent string containing that many spaces.

        String gap;

        // modifiable 'space' - parameter is final
        var modSpace = space;
        if (modSpace instanceof NativeNumber) {
            modSpace = JSType.toNumber(JSType.toPrimitive(modSpace, Number.class));
        } else if (modSpace instanceof NativeString) {
            modSpace = JSType.toString(JSType.toPrimitive(modSpace, String.class));
        }

        if (modSpace instanceof Number) {
            var indent = Math.min(10, JSType.toInteger(modSpace));
            if (indent < 1) {
                gap = "";
            } else {
                var sb = new StringBuilder();
                for (var i = 0; i < indent; i++) {
                    sb.append(' ');
                }
                gap = sb.toString();
            }
        } else if (JSType.isString(modSpace)) {
            var str = modSpace.toString();
            gap = str.substring(0, Math.min(10, str.length()));
        } else {
            gap = "";
        }

        state.gap = gap;

        var wrapper = Global.newEmptyInstance();
        wrapper.set("", value, 0);

        return str("", wrapper, state);
    }

    // -- Internals only below this point

    // stringify helpers.

    private static class StringifyState {
        final Map<Object, Object> stack = new IdentityHashMap<>();

        StringBuilder indent = new StringBuilder();
        String gap = "";
        List<String> propertyList = null;
        Object replacerFunction = null;
    }

    // Spec: The abstract operation Str(key, holder).
    private static Object str(Object key, Object holder, StringifyState state) {
        assert holder instanceof ScriptObject || holder instanceof JSObject;

        var value = getProperty(holder, key);
        try {
            if (value instanceof ScriptObject) {
                var toJSONInvoker = getTO_JSON();
                var svalue = (ScriptObject)value;
                var toJSON = toJSONInvoker.getGetter().invokeExact(svalue);
                if (Bootstrap.isCallable(toJSON)) {
                    value = toJSONInvoker.getInvoker().invokeExact(toJSON, svalue, key);
                }
            } else if (value instanceof JSObject) {
                var jsObj = (JSObject)value;
                var toJSON = jsObj.getMember("toJSON");
                if (Bootstrap.isCallable(toJSON)) {
                    value = getJSOBJECT_INVOKER().invokeExact(toJSON, value);
                }
            }

            if (state.replacerFunction != null) {
                value = getREPLACER_INVOKER().invokeExact(state.replacerFunction, holder, key, value);
            }
        } catch (Throwable t) {
            Util.uncheck(t);
        }
        var isObj = (value instanceof ScriptObject);
        if (isObj) {
            if (value instanceof NativeNumber) {
                value = JSType.toNumber(value);
            } else if (value instanceof NativeString) {
                value = JSType.toString(value);
            } else if (value instanceof NativeBoolean) {
                value = ((NativeBoolean)value).booleanValue();
            }
        }

        if (value == null) {
            return "null";
        } else if (Boolean.TRUE.equals(value)) {
            return "true";
        } else if (Boolean.FALSE.equals(value)) {
            return "false";
        }

        if (value instanceof String) {
            return JSONFunctions.quote((String)value);
        } else if (value instanceof ConsString) {
            return JSONFunctions.quote(value.toString());
        }

        if (value instanceof Number) {
            return JSType.isFinite(((Number)value).doubleValue()) ? JSType.toString(value) : "null";
        }

        var type = JSType.of(value);
        if (type == JSType.OBJECT) {
            if (isArray(value) || isJSObjectArray(value)) {
                return JA(value, state);
            } else if (value instanceof ScriptObject || value instanceof JSObject) {
                return JO(value, state);
            }
        }

        return UNDEFINED;
    }

    // Spec: The abstract operation JO(value) serializes an object.
    private static String JO(Object value, StringifyState state) {
        assert value instanceof ScriptObject || value instanceof JSObject;

        if (state.stack.containsKey(value)) {
            throw typeError("JSON.stringify.cyclic");
        }

        state.stack.put(value, value);
        var stepback = new StringBuilder(state.indent.toString());
        state.indent.append(state.gap);

        var finalStr = new StringBuilder();
        var partial = new ArrayList<Object>();
        var k = state.propertyList == null ? Arrays.asList(getOwnKeys(value)) : state.propertyList;

        for (var p : k) {
            var strP = str(p, value, state);

            if (strP != UNDEFINED) {
                var member = new StringBuilder();

                member.append(JSONFunctions.quote(p.toString())).append(':');
                if (!state.gap.isEmpty()) {
                    member.append(' ');
                }

                member.append(strP);
                partial.add(member);
            }
        }

        if (partial.isEmpty()) {
            finalStr.append("{}");
        } else {
            if (state.gap.isEmpty()) {
                var size = partial.size();
                var index = 0;

                finalStr.append('{');

                for (var str : partial) {
                    finalStr.append(str);
                    if (index < size - 1) {
                        finalStr.append(',');
                    }
                    index++;
                }

                finalStr.append('}');
            } else {
                var size = partial.size();
                var index = 0;

                finalStr.append("{\n");
                finalStr.append(state.indent);

                for (var str : partial) {
                    finalStr.append(str);
                    if (index < size - 1) {
                        finalStr.append(",\n");
                        finalStr.append(state.indent);
                    }
                    index++;
                }

                finalStr.append('\n');
                finalStr.append(stepback);
                finalStr.append('}');
            }
        }

        state.stack.remove(value);
        state.indent = stepback;

        return finalStr.toString();
    }

    // Spec: The abstract operation JA(value) serializes an array.
    private static Object JA(Object value, StringifyState state) {
        assert value instanceof ScriptObject || value instanceof JSObject;

        if (state.stack.containsKey(value)) {
            throw typeError("JSON.stringify.cyclic");
        }

        state.stack.put(value, value);
        var stepback = new StringBuilder(state.indent.toString());
        state.indent.append(state.gap);
        var partial = new ArrayList<Object>();

        var length = JSType.toInteger(getLength(value));
        var index = 0;

        while (index < length) {
            var strP = str(index, value, state);
            if (strP == UNDEFINED) {
                strP = "null";
            }
            partial.add(strP);
            index++;
        }

        var finalStr = new StringBuilder();
        if (partial.isEmpty()) {
            finalStr.append("[]");
        } else {
            if (state.gap.isEmpty()) {
                var size = partial.size();
                index = 0;
                finalStr.append('[');
                for (var str : partial) {
                    finalStr.append(str);
                    if (index < size - 1) {
                        finalStr.append(',');
                    }
                    index++;
                }

                finalStr.append(']');
            } else {
                var size = partial.size();
                index = 0;
                finalStr.append("[\n");
                finalStr.append(state.indent);
                for (var str : partial) {
                    finalStr.append(str);
                    if (index < size - 1) {
                        finalStr.append(",\n");
                        finalStr.append(state.indent);
                    }
                    index++;
                }

                finalStr.append('\n');
                finalStr.append(stepback);
                finalStr.append(']');
            }
        }

        state.stack.remove(value);
        state.indent = stepback;

        return finalStr.toString();
    }

    private static String[] getOwnKeys(Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).getOwnKeys(false);
        } else if (obj instanceof ScriptObjectMirror) {
            return ((ScriptObjectMirror)obj).getOwnKeys(false);
        } else if (obj instanceof JSObject) {
            // No notion of "own keys" or "proto" for general JSObject!
            // We just return all keys of the object.
            // This will be useful for POJOs implementing JSObject interface.
            return ((JSObject)obj).keySet().toArray(new String[0]);
        } else {
            throw new AssertionError("should not reach here");
        }
    }

    private static Object getLength(Object obj) {
        if (obj instanceof ScriptObject) {
            return ((ScriptObject)obj).getLength();
        } else if (obj instanceof JSObject) {
            return ((JSObject)obj).getMember("length");
        } else {
            throw new AssertionError("should not reach here");
        }
    }

    private static boolean isJSObjectArray(Object obj) {
        return (obj instanceof JSObject) && ((JSObject)obj).isArray();
    }

    private static Object getProperty(Object holder, Object key) {
        if (holder instanceof ScriptObject) {
            return ((ScriptObject)holder).get(key);
        } else if (holder instanceof JSObject) {
            var jsObj = (JSObject)holder;
            if (key instanceof Integer) {
                return jsObj.getSlot((Integer)key);
            } else {
                return jsObj.getMember(Objects.toString(key));
            }
        } else {
            return new AssertionError("should not reach here");
        }
    }

}
