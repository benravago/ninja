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

import java.lang.invoke.MethodHandle;

import java.util.function.Consumer;

import nashorn.internal.Util;
import nashorn.internal.objects.annotations.Attribute;
import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.linker.Bootstrap;
import nashorn.internal.runtime.linker.InvokeByName;
import nashorn.internal.runtime.linker.NashornCallSiteDescriptor;
import static nashorn.internal.runtime.ECMAErrors.typeError;

/**
 * ECMA6 25.1.2 The %IteratorPrototype% Object
 */
@ScriptClass("Iterator")
public abstract class AbstractIterator extends ScriptObject {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private final static Object ITERATOR_INVOKER_KEY = new Object();
    private final static Object NEXT_INVOKER_KEY     = new Object();
    private final static Object DONE_INVOKER_KEY     = new Object();
    private final static Object VALUE_INVOKER_KEY    = new Object();

    /** ECMA6 iteration kinds */
    enum IterationKind {
        /** key iteration */
        KEY,
        /** value iteration */
        VALUE,
        /** key+value iteration */
        KEY_VALUE
    }

    /**
     * Create an abstract iterator object with the given prototype and property map.
     */
    protected AbstractIterator(ScriptObject prototype, PropertyMap map) {
        super(prototype, map);
    }

    /**
     * 25.1.2.1 %IteratorPrototype% [ @@iterator ] ( )
     */
    @Function(attributes = Attribute.NOT_ENUMERABLE, name = "@@iterator")
    public static Object getIterator(Object self) {
        return self;
    }

    @Override
    public String getClassName() {
        return "Iterator";
    }

    /**
     * ES6 25.1.1.2 The Iterator Interface
     */
    protected abstract IteratorResult next(Object arg);

    /**
     * ES6 25.1.1.3 The IteratorResult Interface
     */
    protected IteratorResult makeResult(Object value, Boolean done, Global global) {
        return new IteratorResult(value, done, global);
    }

    static MethodHandle getIteratorInvoker(Global global) {
        return global.getDynamicInvoker(ITERATOR_INVOKER_KEY,
            () -> Bootstrap.createDynamicCallInvoker(Object.class, Object.class, Object.class));
    }

    /**
     * Get the invoker for the ES6 iterator {@code next} method.
     */
    public static InvokeByName getNextInvoker(Global global) {
        return global.getInvokeByName(AbstractIterator.NEXT_INVOKER_KEY,
            () -> new InvokeByName("next", Object.class, Object.class, Object.class));
    }

    /**
     * Get the invoker for the ES6 iterator result {@code done} property.
     */
    public static MethodHandle getDoneInvoker(Global global) {
        return global.getDynamicInvoker(AbstractIterator.DONE_INVOKER_KEY,
            () -> Bootstrap.createDynamicInvoker("done", NashornCallSiteDescriptor.GET_PROPERTY, Object.class, Object.class));
    }

    /**
     * Get the invoker for the ES6 iterator result {@code value} property.
     */
    public static MethodHandle getValueInvoker(Global global) {
        return global.getDynamicInvoker(AbstractIterator.VALUE_INVOKER_KEY,
            () -> Bootstrap.createDynamicInvoker("value", NashornCallSiteDescriptor.GET_PROPERTY, Object.class, Object.class));
    }

    /**
     * ES6 7.4.1 GetIterator abstract operation
     */
    public static Object getIterator(Object iterable, Global global) {
        var object = Global.toObject(iterable);

        if (object instanceof ScriptObject) {
            // TODO we need to implement fast property access for Symbol keys in order to use InvokeByName here.
            var getter = ((ScriptObject) object).get(NativeSymbol.iterator);

            if (Bootstrap.isCallable(getter)) {
                try {
                    var invoker = getIteratorInvoker(global);
                    var value = invoker.invokeExact(getter, iterable);
                    if (JSType.isPrimitive(value)) {
                        throw typeError("not.an.object", ScriptRuntime.safeToString(value));
                    }
                    return value;

                } catch (Throwable t) {
                    Util.uncheck(t);
                }
            }
            throw typeError("not.a.function", ScriptRuntime.safeToString(getter));
        }

        throw typeError("cannot.get.iterator", ScriptRuntime.safeToString(iterable));
    }

    /**
     * Iterate over an iterable object, passing every value to {@code consumer}.
     */
    public static void iterate(Object iterable, Global global, Consumer<Object> consumer) {

        var iterator = AbstractIterator.getIterator(Global.toObject(iterable), global);

        var nextInvoker = getNextInvoker(global);
        var doneInvoker = getDoneInvoker(global);
        var valueInvoker = getValueInvoker(global);

        try {
            do {
                var next = nextInvoker.getGetter().invokeExact(iterator);
                if (!Bootstrap.isCallable(next)) {
                    break;
                }

                var result = nextInvoker.getInvoker().invokeExact(next, iterator, (Object) null);
                if (!(result instanceof ScriptObject)) {
                    break;
                }

                var done = doneInvoker.invokeExact(result);
                if (JSType.toBoolean(done)) {
                    break;
                }

                consumer.accept(valueInvoker.invokeExact(result));

            } while (true);

        } catch (Throwable t) {
            Util.uncheck(t);
        }

    }

}

