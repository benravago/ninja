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

package nashorn.api.scripting;

import java.lang.invoke.MethodHandle;

import jdk.dynalink.beans.StaticClass;
import jdk.dynalink.linker.LinkerServices;

import nashorn.internal.Util;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.ScriptFunction;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.linker.Bootstrap;

/**
 * Utilities that are to be called from script code.
 *
 * @since 1.8u40
 */
public final class ScriptUtils {
    private ScriptUtils() {}

    /**
     * Method which converts javascript types to java types for the String.format method (jrunscript function sprintf).
     * 'format' is a format string.
     * 'args' are the arguments referenced by the format specifiers in format
     */
    public static String format(String format, Object[] args) {
        return Formatter.format(format, args);
    }

    /**
     * Create a wrapper function that calls {@code func} synchronized on {@code sync} or, if that is undefined, {@code self}.
     * Used to implement "sync" function in resources/mozilla_compat.js.
     * 'func' is the function to wrap.
     * 'sync' is the object to synchronize on.
     */
    public static Object makeSynchronizedFunction(Object func, Object sync) {
        var unwrapped = unwrap(func);
        if (unwrapped instanceof ScriptFunction) {
            return ((ScriptFunction)unwrapped).createSynchronized(unwrap(sync));
        }
        throw new IllegalArgumentException();
    }

    /**
     * Make a script object mirror on given object if needed.
     */
    public static ScriptObjectMirror wrap(Object obj) {
        if (obj instanceof ScriptObjectMirror) {
            return (ScriptObjectMirror)obj;
        }

        if (obj instanceof ScriptObject) {
            var sobj = (ScriptObject)obj;
            return (ScriptObjectMirror) ScriptObjectMirror.wrap(sobj, Context.getGlobal());
        }

        throw new IllegalArgumentException();
    }

    /**
     * Unwrap a script object mirror if needed.
     */
    public static Object unwrap(Object obj) {
        if (obj instanceof ScriptObjectMirror) {
            return ScriptObjectMirror.unwrap(obj, Context.getGlobal());
        }
        return obj;
    }

    /**
     * Wrap an array of object to script object mirrors if needed.
     */
    public static Object[] wrapArray(Object[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        return ScriptObjectMirror.wrapArray(args, Context.getGlobal());
    }

    /**
     * Unwrap an array of script object mirrors if needed.
     */
    public static Object[] unwrapArray(Object[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        return ScriptObjectMirror.unwrapArray(args, Context.getGlobal());
    }

    /**
     * Convert the given object to the given type.
     * 'obj' is the object to be converted.
     * 'type' is the destination type to convert to; either a Class or nashorn representation of a Java type returned by Java.type() call in script.
     */
    public static Object convert(Object obj, Object type) {
        if (obj == null) {
            return null;
        }

        Class<?> clazz;
        if (type instanceof Class) {
            clazz = (Class<?>)type;
        } else if (type instanceof StaticClass) {
            clazz = ((StaticClass)type).getRepresentedClass();
        } else {
            throw new IllegalArgumentException("type expected");
        }

        var linker = Bootstrap.getLinkerServices();
        var objToConvert = unwrap(obj);
        var converter = linker.getTypeConverter(objToConvert.getClass(), clazz);
        if (converter == null) {
            // no supported conversion!
            throw new UnsupportedOperationException("conversion not supported");
        }

        try {
            return converter.invoke(objToConvert);
        } catch (Throwable t) {
            return Util.uncheck(t);
        }
    }

}
