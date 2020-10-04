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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;

import java.lang.reflect.Method;

import java.util.List;

/**
 * Wrapper for all method handle related functions used in Nashorn.
 *
 * This interface only exists so that instrumentation can be added to all method handle operations.
 */

public interface MethodHandleFunctionality {

    /**
     * Wrapper for {@link MethodHandles#filterArguments(MethodHandle, int, MethodHandle...)}
     */
    MethodHandle filterArguments(MethodHandle target, int pos, MethodHandle... filters);

    /**
     * Wrapper for {@link MethodHandles#filterReturnValue(MethodHandle, MethodHandle)}
     */
    MethodHandle filterReturnValue(MethodHandle target, MethodHandle filter);

    /**
     * Wrapper for {@link MethodHandles#guardWithTest(MethodHandle, MethodHandle, MethodHandle)}
     */
    MethodHandle guardWithTest(MethodHandle test, MethodHandle target, MethodHandle fallback);

    /**
     * Wrapper for {@link MethodHandles#insertArguments(MethodHandle, int, Object...)}
     */
    MethodHandle insertArguments(MethodHandle target, int pos, Object... values);

    /**
     * Wrapper for {@link MethodHandles#dropArguments(MethodHandle, int, Class...)}
     */
    MethodHandle dropArguments(MethodHandle target, int pos, Class<?>... valueTypes);

    /**
     * Wrapper for {@link MethodHandles#dropArguments(MethodHandle, int, List)}
     */
    MethodHandle dropArguments(MethodHandle target, int pos, List<Class<?>> valueTypes);

    /**
     * Wrapper for {@link MethodHandles#foldArguments(MethodHandle, MethodHandle)}
     */
    MethodHandle foldArguments(MethodHandle target, MethodHandle combiner);

    /**
     * Wrapper for {@link MethodHandles#explicitCastArguments(MethodHandle, MethodType)}
     */
    MethodHandle explicitCastArguments(MethodHandle target, MethodType type);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles#arrayElementGetter(Class)}
     */
    MethodHandle arrayElementGetter(Class<?> arrayClass);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles#arrayElementSetter(Class)}
     */
    MethodHandle arrayElementSetter(Class<?> arrayClass);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles#throwException(Class, Class)}
     */
    MethodHandle throwException(Class<?> returnType, Class<? extends Throwable> exType);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles#catchException(MethodHandle, Class, MethodHandle)}
     */
    MethodHandle catchException(MethodHandle target, Class<? extends Throwable> exType, MethodHandle handler);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles#constant(Class, Object)}
     */
    MethodHandle constant(Class<?> type, Object value);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles#identity(Class)}
     */
    MethodHandle identity(Class<?> type);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandle#asType(MethodType)}
     */
    MethodHandle asType(MethodHandle handle, MethodType type);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandle#asCollector(Class, int)}
     */
    MethodHandle asCollector(MethodHandle handle, Class<?> arrayType, int arrayLength);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandle#asSpreader(Class, int)}
     */
    MethodHandle asSpreader(MethodHandle handle, Class<?> arrayType, int arrayLength);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandle#bindTo(Object)}
     */
    MethodHandle bindTo(MethodHandle handle, Object x);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles.Lookup#findGetter(Class, String, Class)}
     */
    MethodHandle getter(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, Class<?> type);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles.Lookup#findStaticGetter(Class, String, Class)}
     */
    MethodHandle staticGetter(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, Class<?> type);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles.Lookup#findSetter(Class, String, Class)}
     */
    MethodHandle setter(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, Class<?> type);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles.Lookup#findStaticSetter(Class, String, Class)}
     */
    MethodHandle staticSetter(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, Class<?> type);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles.Lookup#unreflect(Method)}
     * Unreflect a method as a method handle
     */
    MethodHandle find(Method method);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles.Lookup#findStatic(Class, String, MethodType)}
     */
    MethodHandle findStatic(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, MethodType type);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles.Lookup#findVirtual(Class, String, MethodType)}
     */
    MethodHandle findVirtual(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, MethodType type);

    /**
     * Wrapper for {@link java.lang.invoke.MethodHandles.Lookup#findSpecial(Class, String, MethodType, Class)}
     */
    MethodHandle findSpecial(MethodHandles.Lookup explicitLookup, Class<?> clazz, String name, MethodType type, Class<?> thisClass);

    /**
     * Wrapper for SwitchPoint creation. Just like {@code new SwitchPoint()} but potentially
     * tracked
     */
    SwitchPoint createSwitchPoint();

    /**
     * Wrapper for {@link SwitchPoint#guardWithTest(MethodHandle, MethodHandle)}
     */
    MethodHandle guardWithTest(SwitchPoint sp, MethodHandle before, MethodHandle after);

    /**
     * Wrapper for {@link MethodType#methodType(Class, Class...)}
     */
    MethodType type(Class<?> returnType, Class<?>... paramTypes);

}
