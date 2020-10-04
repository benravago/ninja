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

package nashorn.internal.lookup;

import static nashorn.internal.runtime.JSType.isString;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;

import java.lang.reflect.Method;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;

import nashorn.internal.runtime.Context;
import nashorn.internal.Util;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.logging.Loggable;
import nashorn.internal.runtime.logging.Logger;
import nashorn.internal.runtime.options.Options;

/**
 * This class is abstraction for all method handle, switchpoint and method type operations.
 *
 * This enables the functionality interface to be subclassed and instrumented, as it has been proven vital to keep the number of method handles in the system down.
 *
 * All operations of the above type should go through this class, and not directly into java.lang.invoke
 */
public final class MethodHandleFactory {
    private MethodHandleFactory() {}

    private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();
    private static final MethodHandles.Lookup LOOKUP        = MethodHandles.lookup();

    private static final Level TRACE_LEVEL = Level.INFO;

    /**
     * Runtime exception that collects every reason that a method handle lookup operation can go wrong
     */
    public static class LookupException extends RuntimeException {
        /**
         * Constructor
         */
        public LookupException(Exception e) {
            super(e);
        }
    }

    /**
     * Helper function that takes a class or an object with a toString override and shortens it to notation after last dot.
     * This is used to facilitate pretty printouts in various debug loggers - internal only.
     */
    public static String stripName(Object obj) {
        if (obj == null) {
            return "null";
        }

        if (obj instanceof Class) {
            return ((Class<?>)obj).getSimpleName();
        }
        return obj.toString();
    }

    private static final MethodHandleFunctionality FUNC = new StandardMethodHandleFunctionality();
    private static final boolean PRINT_STACKTRACE = Options.getBooleanProperty("nashorn.methodhandles.debug.stacktrace");

    /**
     * Return the method handle functionality used for all method handle operations.
     */
    public static MethodHandleFunctionality getFunctionality() {
        return FUNC;
    }

    private static final MethodHandle TRACE             = FUNC.findStatic(LOOKUP, MethodHandleFactory.class, "traceArgs",   MethodType.methodType(void.class, DebugLogger.class, String.class, int.class, Object[].class));
    private static final MethodHandle TRACE_RETURN      = FUNC.findStatic(LOOKUP, MethodHandleFactory.class, "traceReturn", MethodType.methodType(Object.class, DebugLogger.class, Object.class));
    private static final MethodHandle TRACE_RETURN_VOID = FUNC.findStatic(LOOKUP, MethodHandleFactory.class, "traceReturnVoid", MethodType.methodType(void.class, DebugLogger.class));

    private static final String VOID_TAG = "[VOID]";

    private static void err(String str) {
        Context.getContext().getErr().println(str);
    }

    /**
     * Tracer that is applied before a value is returned from the traced function.
     * It will output the return value and its class
     */
    static Object traceReturn(DebugLogger logger, Object value) {
        var str = "    return" +
            (VOID_TAG.equals(value) ? ";" : " " + stripName(value) + "; // [type=" +
                (value == null ? "null]" : stripName(value.getClass()) + ']'));

        if (logger == null) {
            err(str);
        } else if (logger.isEnabled()) {
            logger.log(TRACE_LEVEL, str);
        }

        return value;
    }

    static void traceReturnVoid(DebugLogger logger) {
        traceReturn(logger, VOID_TAG);
    }

    /**
     * Tracer that is applied before a function is called, printing the arguments
     * 'tag' is the tag to start the debug printout string.
     * 'paramStart' is the param index to start outputting from.
     * 'args' are the arguments to the function.
     */
    static void traceArgs(DebugLogger logger, String tag, int paramStart, Object... args) {
        var sb = new StringBuilder();

        sb.append(tag);

        for (var i = paramStart; i < args.length; i++) {
            if (i == paramStart) {
                sb.append(" => args: ");
            }

            sb.append('\'')
              .append(stripName(argString(args[i])))
              .append("\' [type=")
              .append(args[i] == null ? "null" : stripName(args[i].getClass()))
              .append(']');

            if (i + 1 < args.length) {
                sb.append(", ");
            }
        }

        if (logger == null) {
            err(sb.toString());
        } else {
            logger.log(TRACE_LEVEL, sb);
        }
        stacktrace(logger);
    }

    private static void stacktrace(DebugLogger logger) {
        if (!PRINT_STACKTRACE) {
            return;
        }
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos);
        new Throwable().printStackTrace(ps);
        var st = baos.toString();
        if (logger == null) {
            err(st);
        } else {
            logger.log(TRACE_LEVEL, st);
        }
    }

    private static String argString(Object arg) {
        if (arg == null) {
            return "null";
        }

        if (arg.getClass().isArray()) {
            var list = new ArrayList<Object>();
            for (var elem : (Object[])arg) {
                list.add('\'' + argString(elem) + '\'');
            }

            return list.toString();
        }

        if (arg instanceof ScriptObject) {
            return arg.toString() + " (map=" + Util.id(((ScriptObject)arg).getMap()) + ')';
        }

        return arg.toString();
    }

    /**
     * Add a debug printout to a method handle, tracing parameters and return values.
     */
    public static MethodHandle addDebugPrintout(MethodHandle mh, Object tag) {
        return addDebugPrintout(null, Level.OFF, mh, 0, true, tag);
    }

    /**
     * Add a debug printout to a method handle, tracing parameters and return values.
     */
    public static MethodHandle addDebugPrintout(DebugLogger logger, Level level, MethodHandle mh, Object tag) {
        return addDebugPrintout(logger, level, mh, 0, true, tag);
    }

    /**
     * Add a debug printout to a method handle, tracing parameters and return values.
     */
    public static MethodHandle addDebugPrintout(MethodHandle mh, int paramStart, boolean printReturnValue, Object tag) {
        return addDebugPrintout(null, Level.OFF, mh, paramStart, printReturnValue, tag);
    }

    /**
     * Add a debug printout to a method handle, tracing parameters and return values.
     * 'logger' is the specific logger to which to write the output.
     * 'level' is the level over which to print.
     * 'mh' is the method handle to trace.
     * 'paramStart' is the first param to print/trace.
     * 'printReturnValue' if we should print/trace return value if available?
     * 'tag' is the start of trace message.
     */
    public static MethodHandle addDebugPrintout(DebugLogger logger, Level level, MethodHandle mh, int paramStart, boolean printReturnValue, Object tag) {
        var type = mh.type();

        // if there is no logger, or if it's set to log only coarser events than the trace level, skip and return
        if (logger == null || !logger.isLoggable(level)) {
            return mh;
        }

        assert TRACE != null;

        var trace = MethodHandles.insertArguments(TRACE, 0, logger, tag, paramStart);

        trace = MethodHandles.foldArguments(mh, trace.asCollector( Object[].class, type.parameterCount()).asType(type.changeReturnType(void.class)));

        var retType = type.returnType();
        if (printReturnValue) {
            if (retType != void.class) {
                var traceReturn = MethodHandles.insertArguments(TRACE_RETURN, 0, logger);
                trace = MethodHandles.filterReturnValue(trace, traceReturn.asType(traceReturn.type().changeParameterType(0, retType).changeReturnType(retType)));
            } else {
                trace = MethodHandles.filterReturnValue(trace, MethodHandles.insertArguments(TRACE_RETURN_VOID, 0, logger));
            }
        }

        return trace;
    }

    /**
     * Class that marshalls all method handle operations to the java.lang.invoke package.
     * This exists only so that it can be subclassed and method handles created from Nashorn made possible to instrument.
     * All Nashorn classes should use the MethodHandleFactory for their method handle operations
     */
    @Logger(name="methodhandles")
    private static class StandardMethodHandleFunctionality implements MethodHandleFunctionality, Loggable {
        public StandardMethodHandleFunctionality() {}

        // For bootstrapping reasons, because a lot of static fields use MH for lookups, we need to set the logger when the Global object is finished.
        // This means that we don't get instrumentation for public static final MethodHandle SOMETHING = MH... in the builtin classes, but that doesn't matter, because this is usually not where we want it
        private DebugLogger log = DebugLogger.DISABLED_LOGGER;

        @Override
        public DebugLogger initLogger(Context context) {
            return this.log = context.getLogger(this.getClass());
        }

        @Override
        public DebugLogger getLogger() {
            return log;
        }

        protected static String describe(Object... data) {
            var sb = new StringBuilder();

            for (var i = 0; i < data.length; i++) {
                var d = data[i];
                if (d == null) {
                    sb.append("<null> ");
                } else if (isString(d)) {
                    sb.append(d.toString())
                      .append(' ');
                } else if (d.getClass().isArray()) {
                    sb.append("[ ");
                    for (var da : (Object[])d) {
                        sb.append(describe(new Object[]{ da })).append(' ');
                    }
                    sb.append("] ");
                } else {
                    sb.append(d)
                      .append('{')
                      .append(Integer.toHexString(System.identityHashCode(d)))
                      .append('}');
                }

                if (i + 1 < data.length) {
                    sb.append(", ");
                }
            }

            return sb.toString();
        }

        public MethodHandle debug(MethodHandle master, String str, Object... args) {
            if (log.isEnabled()) {
                if (PRINT_STACKTRACE) {
                    stacktrace(log);
                }
                return addDebugPrintout(log, Level.INFO, master, Integer.MAX_VALUE, false, str + ' ' + describe(args));
            }
            return master;
        }

        @Override
        public MethodHandle filterArguments(MethodHandle target, int pos, MethodHandle... filters) {
            var mh = MethodHandles.filterArguments(target, pos, filters);
            return debug(mh, "filterArguments", target, pos, filters);
        }

        @Override
        public MethodHandle filterReturnValue(MethodHandle target, MethodHandle filter) {
            var mh = MethodHandles.filterReturnValue(target, filter);
            return debug(mh, "filterReturnValue", target, filter);
        }

        @Override
        public MethodHandle guardWithTest(MethodHandle test, MethodHandle target, MethodHandle fallback) {
            var mh = MethodHandles.guardWithTest(test, target, fallback);
            return debug(mh, "guardWithTest", test, target, fallback);
        }

        @Override
        public MethodHandle insertArguments(MethodHandle target, int pos, Object... values) {
            var mh = MethodHandles.insertArguments(target, pos, values);
            return debug(mh, "insertArguments", target, pos, values);
        }

        @Override
        public MethodHandle dropArguments(MethodHandle target, int pos, Class<?>... values) {
            var mh = MethodHandles.dropArguments(target, pos, values);
            return debug(mh, "dropArguments", target, pos, values);
        }

        @Override
        public MethodHandle dropArguments(MethodHandle target, int pos, List<Class<?>> values) {
            var mh = MethodHandles.dropArguments(target, pos, values);
            return debug(mh, "dropArguments", target, pos, values);
        }

        @Override
        public MethodHandle asType(MethodHandle handle, MethodType type) {
            var mh = handle.asType(type);
            return debug(mh, "asType", handle, type);
        }

        @Override
        public MethodHandle bindTo(MethodHandle handle, Object x) {
            var mh = handle.bindTo(x);
            return debug(mh, "bindTo", handle, x);
        }

        @Override
        public MethodHandle foldArguments(MethodHandle target, MethodHandle combiner) {
            var mh = MethodHandles.foldArguments(target, combiner);
            return debug(mh, "foldArguments", target, combiner);
        }

        @Override
        public MethodHandle explicitCastArguments(MethodHandle target, MethodType type) {
            var mh = MethodHandles.explicitCastArguments(target, type);
            return debug(mh, "explicitCastArguments", target, type);
        }

        @Override
        public MethodHandle arrayElementGetter(Class<?> type) {
            var mh = MethodHandles.arrayElementGetter(type);
            return debug(mh, "arrayElementGetter", type);
        }

        @Override
        public MethodHandle arrayElementSetter(Class<?> type) {
            var mh = MethodHandles.arrayElementSetter(type);
            return debug(mh, "arrayElementSetter", type);
        }

        @Override
        public MethodHandle throwException(Class<?> returnType, Class<? extends Throwable> exType) {
            var mh = MethodHandles.throwException(returnType, exType);
            return debug(mh, "throwException", returnType, exType);
        }

        @Override
        public MethodHandle catchException(MethodHandle target, Class<? extends Throwable> exType, MethodHandle handler) {
            var mh = MethodHandles.catchException(target, exType, handler);
            return debug(mh, "catchException", exType);
        }

        @Override
        public MethodHandle constant(Class<?> type, Object value) {
            var mh = MethodHandles.constant(type, value);
            return debug(mh, "constant", type, value);
        }

        @Override
        public MethodHandle identity(Class<?> type) {
            var mh = MethodHandles.identity(type);
            return debug(mh, "identity", type);
        }

        @Override
        public MethodHandle asCollector(MethodHandle handle, Class<?> arrayType, int arrayLength) {
            var mh = handle.asCollector(arrayType, arrayLength);
            return debug(mh, "asCollector", handle, arrayType, arrayLength);
        }

        @Override
        public MethodHandle asSpreader(MethodHandle handle, Class<?> arrayType, int arrayLength) {
            var mh = handle.asSpreader(arrayType, arrayLength);
            return debug(mh, "asSpreader", handle, arrayType, arrayLength);
        }

        @Override
        public MethodHandle getter(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, Class<?> type) {
            try {
                var mh = explicitLookup.findGetter(clazz, name, type);
                return debug(mh, "getter", explicitLookup, clazz, name, type);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new LookupException(e);
            }
        }

        @Override
        public MethodHandle staticGetter(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, Class<?> type) {
            try {
                var mh = explicitLookup.findStaticGetter(clazz, name, type);
                return debug(mh, "static getter", explicitLookup, clazz, name, type);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new LookupException(e);
            }
        }

        @Override
        public MethodHandle setter(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, Class<?> type) {
            try {
                var mh = explicitLookup.findSetter(clazz, name, type);
                return debug(mh, "setter", explicitLookup, clazz, name, type);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new LookupException(e);
            }
        }

        @Override
        public MethodHandle staticSetter(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, Class<?> type) {
            try {
                var mh = explicitLookup.findStaticSetter(clazz, name, type);
                return debug(mh, "static setter", explicitLookup, clazz, name, type);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new LookupException(e);
            }
        }

        @Override
        public MethodHandle find(Method method) {
            try {
                var mh = PUBLIC_LOOKUP.unreflect(method);
                return debug(mh, "find", method);
            } catch (IllegalAccessException e) {
                throw new LookupException(e);
            }
        }

        @Override
        public MethodHandle findStatic(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, MethodType type) {
            try {
                var mh = explicitLookup.findStatic(clazz, name, type);
                return debug(mh, "findStatic", explicitLookup, clazz, name, type);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new LookupException(e);
            }
        }

        @Override
        public MethodHandle findSpecial(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, MethodType type, Class<?> thisClass) {
            try {
                var mh = explicitLookup.findSpecial(clazz, name, type, thisClass);
                return debug(mh, "findSpecial", explicitLookup, clazz, name, type);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new LookupException(e);
            }
        }

        @Override
        public MethodHandle findVirtual(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, MethodType type) {
            try {
                var mh = explicitLookup.findVirtual(clazz, name, type);
                return debug(mh, "findVirtual", explicitLookup, clazz, name, type);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new LookupException(e);
            }
        }

        @Override
        public SwitchPoint createSwitchPoint() {
            var sp = new SwitchPoint();
            log.log(TRACE_LEVEL, "createSwitchPoint ", sp);
            return sp;
        }

        @Override
        public MethodHandle guardWithTest(SwitchPoint sp, MethodHandle before, MethodHandle after) {
            var mh = sp.guardWithTest(before, after);
            return debug(mh, "guardWithTest", sp, before, after);
        }

        @Override
        public MethodType type(Class<?> returnType, Class<?>... paramTypes) {
            var mt = MethodType.methodType(returnType, paramTypes);
            log.log(TRACE_LEVEL, "methodType ", returnType, " ", Arrays.toString(paramTypes), " ", mt);
            return mt;
        }
    }

}
