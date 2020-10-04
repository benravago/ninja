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

import java.util.List;

import jdk.dynalink.linker.support.Lookup;

import nashorn.api.scripting.JSObject;
import nashorn.api.scripting.ScriptObjectMirror;
import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Constructor;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.parser.Parser;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.ParserException;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptEnvironment;
import nashorn.internal.runtime.ScriptFunction;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.linker.Bootstrap;
import static nashorn.internal.runtime.ECMAErrors.rangeError;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;
import static nashorn.internal.runtime.Source.sourceFor;

/**
 * ECMA 15.3 Function Objects
 *
 * Note: instances of this class are never created.
 * This class is not even a subclass of ScriptObject.
 * But, we use this class to generate prototype and constructor for "Function".
 */
@ScriptClass("Function")
public final class NativeFunction {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    /** apply arg converter handle */
    public static final MethodHandle TO_APPLY_ARGS = Lookup.findOwnStatic(MethodHandles.lookup(), "toApplyArgs", Object[].class, Object.class);

    // do *not* create me!
    private NativeFunction() {
        throw new UnsupportedOperationException();
    }

    /**
     * ECMA 15.3.4.2 Function.prototype.toString ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static String toString(Object self) {
        if (!(self instanceof ScriptFunction)) {
            throw typeError("not.a.function", ScriptRuntime.safeToString(self));
        }
        return ((ScriptFunction)self).toSource();
    }

    /**
     * ECMA 15.3.4.3 Function.prototype.apply (thisArg, argArray)
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static Object apply(Object self, Object thiz, Object array) {
        checkCallable(self);
        var args = toApplyArgs(array);

        if (self instanceof ScriptFunction) {
            return ScriptRuntime.apply((ScriptFunction)self, thiz, args);
        } else if (self instanceof ScriptObjectMirror) {
            return ((JSObject)self).call(thiz, args);
        } else if (self instanceof JSObject) {
            var global = Global.instance();
            var result = ((JSObject) self).call(ScriptObjectMirror.wrap(thiz, global), ScriptObjectMirror.wrapArray(args, global));
            return ScriptObjectMirror.unwrap(result, global);
        }
        throw new AssertionError("Should not reach here");
    }

    /**
     * Given an array-like object, converts it into a Java object array suitable for invocation of ScriptRuntime.apply or for direct invocation of the applied function.
     */
    public static Object[] toApplyArgs(Object array) {
        if (array instanceof NativeArguments) {
            return ((NativeArguments)array).getArray().asObjectArray();
        } else if (array instanceof ScriptObject) {
            // look for array-like object
            var sobj = (ScriptObject)array;
            var n = lengthToInt(sobj.getLength());

            var args = new Object[n];
            for (var i = 0; i < args.length; i++) {
                args[i] = sobj.get(i);
            }
            return args;
        } else if (array instanceof Object[]) {
            return (Object[])array;
        } else if (array instanceof List) {
            var list = (List<?>)array;
            return list.toArray(new Object[0]);
        } else if (array == null || array == UNDEFINED) {
            return ScriptRuntime.EMPTY_ARRAY;
        } else if (array instanceof JSObject) {
            // look for array-like JSObject object
            var jsObj = (JSObject)array;
            var len = jsObj.hasMember("length")? jsObj.getMember("length") : Integer.valueOf(0);
            var n = lengthToInt(len);

            var args = new Object[n];
            for (var i = 0; i < args.length; i++) {
                args[i] = jsObj.hasSlot(i)? jsObj.getSlot(i) : UNDEFINED;
            }
            return args;
        } else {
            throw typeError("function.apply.expects.array");
        }
    }

    private static int lengthToInt(Object len) {
        var ln = JSType.toUint32(len);
        // NOTE: ECMASCript 5.1 section 15.3.4.3 says length should be treated as Uint32, but we wouldn't be able to allocate a Java array of more than MAX_VALUE elements anyway, so at this point we have to throw an error.
        // People applying a function to more than 2^31 arguments will unfortunately be out of luck.
        if (ln > Integer.MAX_VALUE) {
            throw rangeError("range.error.inappropriate.array.length", JSType.toString(len));
        }
        return (int)ln;
    }

    private static void checkCallable(Object self) {
        if (!(self instanceof ScriptFunction || (self instanceof JSObject && ((JSObject)self).isFunction()))) {
            throw typeError("not.a.function", ScriptRuntime.safeToString(self));
        }
    }

    /**
     * ECMA 15.3.4.4 Function.prototype.call (thisArg [ , arg1 [ , arg2, ... ] ] )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static Object call(Object self, Object... args) {
        checkCallable(self);

        var thiz = (args.length == 0) ? UNDEFINED : args[0];
        Object[] arguments;

        if (args.length > 1) {
            arguments = new Object[args.length - 1];
            System.arraycopy(args, 1, arguments, 0, arguments.length);
        } else {
            arguments = ScriptRuntime.EMPTY_ARRAY;
        }

        if (self instanceof ScriptFunction) {
            return ScriptRuntime.apply((ScriptFunction)self, thiz, arguments);
        } else if (self instanceof JSObject) {
            return ((JSObject)self).call(thiz, arguments);
        }

        throw new AssertionError("should not reach here");
    }

    /**
     * ECMA 15.3.4.5 Function.prototype.bind (thisArg [, arg1 [, arg2, ...]])
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, arity = 1)
    public static Object bind(Object self, Object... args) {
        var thiz = (args.length == 0) ? UNDEFINED : args[0];

        Object[] arguments;
        if (args.length > 1) {
            arguments = new Object[args.length - 1];
            System.arraycopy(args, 1, arguments, 0, arguments.length);
        } else {
            arguments = ScriptRuntime.EMPTY_ARRAY;
        }

        return Bootstrap.bindCallable(self, thiz, arguments);
    }

    /**
     * Nashorn extension: Function.prototype.toSource
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE)
    public static String toSource(Object self) {
        if (!(self instanceof ScriptFunction)) {
            throw typeError("not.a.function", ScriptRuntime.safeToString(self));
        }
        return ((ScriptFunction)self).toSource();
    }

    /**
     * ECMA 15.3.2.1 new Function (p1, p2, ... , pn, body)
     */
    @Constructor(arity = 1)
    public static ScriptFunction function(boolean newObj, Object self, Object... args) {
        var sb = new StringBuilder();

        sb.append("(function (");
        String funcBody;
        if (args.length > 0) {
            var paramListBuf = new StringBuilder();
            for (var i = 0; i < args.length - 1; i++) {
                paramListBuf.append(JSType.toString(args[i]));
                if (i < args.length - 2) {
                    paramListBuf.append(",");
                }
            }

            // now convert function body to a string
            funcBody = JSType.toString(args[args.length - 1]);

            var paramList = paramListBuf.toString();
            if (!paramList.isEmpty()) {
                checkFunctionParameters(paramList);
                sb.append(paramList);
            }
        } else {
            funcBody = null;
        }

        sb.append(") {\n");
        if (args.length > 0) {
            checkFunctionBody(funcBody);
            sb.append(funcBody)
              .append('\n');
        }
        sb.append("})");

        var global = Global.instance();
        var context = global.getContext();
        return (ScriptFunction)context.eval(global, sb.toString(), global, "<function>");
    }

    private static void checkFunctionParameters(String params) {
        var parser = getParser(params);
        try {
            parser.parseFormalParameterList();
        } catch (ParserException pe) {
            pe.throwAsEcmaException();
        }
    }

    private static void checkFunctionBody(String funcBody) {
        var parser = getParser(funcBody);
        try {
            parser.parseFunctionBody();
        } catch (ParserException pe) {
            pe.throwAsEcmaException();
        }
    }

    private static Parser getParser(String sourceText) {
        var env = Global.getEnv();
        return new Parser(env, sourceFor("<function>", sourceText), new Context.ThrowErrorManager(), null);
    }

}
