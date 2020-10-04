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
import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ECMAErrors.typeError;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;
import static nashorn.internal.runtime.UnwarrantedOptimismException.INVALID_PROGRAM_POINT;
import static nashorn.internal.runtime.linker.NashornCallSiteDescriptor.CALLSITE_PROGRAM_POINT_SHIFT;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.Callable;

import nashorn.internal.Util;
import nashorn.internal.lookup.Lookup;
import nashorn.internal.runtime.linker.Bootstrap;
import nashorn.internal.runtime.linker.NashornCallSiteDescriptor;

/**
 * Property with user defined getters/setters.
 * Actual getter and setter functions are stored in underlying ScriptObject.
 * Only the 'slot' info is stored in the property.
 */
public final class UserAccessorProperty extends SpillProperty {

    static final class Accessors {

        Object getter;
        Object setter;

        Accessors(Object getter, Object setter) {
            set(getter, setter);
        }

        final void set(Object getter, Object setter) {
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        public String toString() {
            return "[getter=" + getter + " setter=" + setter + ']';
        }
    }

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** Getter method handle */
    private final static MethodHandle INVOKE_OBJECT_GETTER = findOwnMH_S("invokeObjectGetter", Object.class, Accessors.class, MethodHandle.class, Object.class);
    private final static MethodHandle INVOKE_INT_GETTER  = findOwnMH_S("invokeIntGetter", int.class, Accessors.class, MethodHandle.class, int.class, Object.class);
    private final static MethodHandle INVOKE_NUMBER_GETTER  = findOwnMH_S("invokeNumberGetter", double.class, Accessors.class, MethodHandle.class, int.class, Object.class);

    /** Setter method handle */
    private final static MethodHandle INVOKE_OBJECT_SETTER = findOwnMH_S("invokeObjectSetter", void.class, Accessors.class, MethodHandle.class, String.class, Object.class, Object.class);
    private final static MethodHandle INVOKE_INT_SETTER = findOwnMH_S("invokeIntSetter", void.class, Accessors.class, MethodHandle.class, String.class, Object.class, int.class);
    private final static MethodHandle INVOKE_NUMBER_SETTER = findOwnMH_S("invokeNumberSetter", void.class, Accessors.class, MethodHandle.class, String.class, Object.class, double.class);

    private static final Object OBJECT_GETTER_INVOKER_KEY = new Object();

    private static MethodHandle getObjectGetterInvoker() {
        return Context.getGlobal().getDynamicInvoker(OBJECT_GETTER_INVOKER_KEY, new Callable<MethodHandle>() {
            @Override
            public MethodHandle call() throws Exception {
                return getINVOKE_UA_GETTER(Object.class, INVALID_PROGRAM_POINT);
            }
        });
    }

    static MethodHandle getINVOKE_UA_GETTER(Class<?> returnType, int programPoint) {
        if (UnwarrantedOptimismException.isValid(programPoint)) {
            var flags = NashornCallSiteDescriptor.CALL | NashornCallSiteDescriptor.CALLSITE_OPTIMISTIC | programPoint << CALLSITE_PROGRAM_POINT_SHIFT;
            return Bootstrap.createDynamicInvoker("", flags, returnType, Object.class, Object.class);
        } else {
            return Bootstrap.createDynamicCallInvoker(Object.class, Object.class, Object.class);
        }
    }

    private static final Object OBJECT_SETTER_INVOKER_KEY = new Object();

    private static MethodHandle getObjectSetterInvoker() {
        return Context.getGlobal().getDynamicInvoker(OBJECT_SETTER_INVOKER_KEY, new Callable<MethodHandle>() {
            @Override
            public MethodHandle call() throws Exception {
                return getINVOKE_UA_SETTER(Object.class);
            }
        });
    }

    static MethodHandle getINVOKE_UA_SETTER(Class<?> valueType) {
        return Bootstrap.createDynamicCallInvoker(void.class, Object.class, Object.class, valueType);
    }

    /**
     * Constructor
     * @param key   property key
     * @param flags property flags
     * @param slot  spill slot
     */
    UserAccessorProperty(Object key, int flags, int slot) {
        // Always set accessor property flag for this class
        super(key, flags | IS_ACCESSOR_PROPERTY, slot);
    }

    private UserAccessorProperty(UserAccessorProperty property) {
        super(property);
    }

    private UserAccessorProperty(UserAccessorProperty property, Class<?> newType) {
        super(property, newType);
    }

    @Override
    public Property copy() {
        return new UserAccessorProperty(this);
    }

    @Override
    public Property copy(Class<?> newType) {
        return new UserAccessorProperty(this, newType);
    }

    void setAccessors(ScriptObject sobj, PropertyMap map, Accessors gs) {
        try {
            //invoke the getter and find out
            super.getSetter(Object.class, map).invokeExact((Object)sobj, (Object)gs);
        } catch (Throwable t) {
            Util.uncheck(t);
        }
    }

    //pick the getter setter out of the correct spill slot in sobj
    Accessors getAccessors(ScriptObject sobj) {
        try {
            // invoke the super getter with this spill slot get the getter setter from the correct spill slot
            var gs = super.getGetter(Object.class).invokeExact((Object)sobj);
            return (Accessors)gs;
        } catch (Throwable t) {
            return Util.uncheck(t);
        }
    }

    @Override
    protected Class<?> getLocalType() {
        return Object.class;
    }

    @Override
    public boolean hasGetterFunction(ScriptObject sobj) {
        return getAccessors(sobj).getter != null;
    }

    @Override
    public boolean hasSetterFunction(ScriptObject sobj) {
        return getAccessors(sobj).setter != null;
    }

    @Override
    public int getIntValue(ScriptObject self, ScriptObject owner) {
        return (int)getObjectValue(self, owner);
    }

    @Override
    public double getDoubleValue(ScriptObject self, ScriptObject owner) {
        return (double)getObjectValue(self, owner);
    }

    @Override
    public Object getObjectValue(ScriptObject self, ScriptObject owner) {
        try {
            return invokeObjectGetter(getAccessors((owner != null) ? owner : self), getObjectGetterInvoker(), self);
        } catch (Throwable t) {
            return Util.uncheck(t);
        }
    }

    @Override
    public void setValue(ScriptObject self, ScriptObject owner, int value) {
        setValue(self, owner, (Object) value);
    }

    @Override
    public void setValue(ScriptObject self, ScriptObject owner, double value) {
        setValue(self, owner, (Object) value);
    }

    @Override
    public void setValue(ScriptObject self, ScriptObject owner, Object value) {
        try {
            invokeObjectSetter(getAccessors((owner != null) ? owner : self), getObjectSetterInvoker(), getKey().toString(), self, value);
        } catch (Throwable t) {
            Util.uncheck(t);
        }
    }

    @Override
    public MethodHandle getGetter(Class<?> type) {
        // this returns a getter on the format (Accessors, Object receiver)
        return Lookup.filterReturnType(INVOKE_OBJECT_GETTER, type);
    }

    @Override
    public MethodHandle getOptimisticGetter(Class<?> type, int programPoint) {
        if (type == int.class) {
            return INVOKE_INT_GETTER;
        } else if (type == double.class) {
            return INVOKE_NUMBER_GETTER;
        } else {
            assert type == Object.class;
            return INVOKE_OBJECT_GETTER;
        }
    }

    @Override
    void initMethodHandles(Class<?> structure) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScriptFunction getGetterFunction(ScriptObject sobj) {
        var value = getAccessors(sobj).getter;
        return (value instanceof ScriptFunction) ? (ScriptFunction)value : null;
    }

    @Override
    public MethodHandle getSetter(Class<?> type, PropertyMap currentMap) {
        if (type == int.class) {
            return INVOKE_INT_SETTER;
        } else if (type == double.class) {
            return INVOKE_NUMBER_SETTER;
        } else {
            assert type == Object.class;
            return INVOKE_OBJECT_SETTER;
        }
    }

    @Override
    public ScriptFunction getSetterFunction(ScriptObject sobj) {
        var value = getAccessors(sobj).setter;
        return (value instanceof ScriptFunction) ? (ScriptFunction)value : null;
    }

    /**
     * Get the getter for the {@code Accessors} object.
     * This is the the super {@code Object} type getter with {@code Accessors} return type.
     */
    MethodHandle getAccessorsGetter() {
        return super.getGetter(Object.class).asType(MethodType.methodType(Accessors.class, Object.class));
    }

    // User defined getter and setter are always called by StandardOperation.CALL.
    // Note that the user getter/setter may be inherited.
    // If so, proto is bound during lookup. In eitherinherited or self case, slot is also bound during lookup.
    // Actual ScriptFunction to be called is retrieved everytime and applied.
    @SuppressWarnings("unused")
    private static Object invokeObjectGetter(Accessors gs, MethodHandle invoker, Object self) throws Throwable {
        var func = gs.getter;
        if (func instanceof ScriptFunction) {
            return invoker.invokeExact(func, self);
        }

        return UNDEFINED;
    }

    @SuppressWarnings("unused")
    private static int invokeIntGetter(Accessors gs, MethodHandle invoker, int programPoint, Object self) throws Throwable {
        var func = gs.getter;
        if (func instanceof ScriptFunction) {
            return (int) invoker.invokeExact(func, self);
        }

        throw new UnwarrantedOptimismException(UNDEFINED, programPoint);
    }

    @SuppressWarnings("unused")
    private static double invokeNumberGetter(Accessors gs, MethodHandle invoker, int programPoint, Object self) throws Throwable {
        var func = gs.getter;
        if (func instanceof ScriptFunction) {
            return (double) invoker.invokeExact(func, self);
        }

        throw new UnwarrantedOptimismException(UNDEFINED, programPoint);
    }

    @SuppressWarnings("unused")
    private static void invokeObjectSetter(Accessors gs, MethodHandle invoker, String name, Object self, Object value) throws Throwable {
        var func = gs.setter;
        if (func instanceof ScriptFunction) {
            invoker.invokeExact(func, self, value);
        } else if (name != null) {
            throw typeError("property.has.no.setter", name, ScriptRuntime.safeToString(self));
        }
    }

    @SuppressWarnings("unused")
    private static void invokeIntSetter(Accessors gs, MethodHandle invoker, String name, Object self, int value) throws Throwable {
        var func = gs.setter;
        if (func instanceof ScriptFunction) {
            invoker.invokeExact(func, self, value);
        } else if (name != null) {
            throw typeError("property.has.no.setter", name, ScriptRuntime.safeToString(self));
        }
    }

    @SuppressWarnings("unused")
    private static void invokeNumberSetter(Accessors gs, MethodHandle invoker, String name, Object self, double value) throws Throwable {
        var func = gs.setter;
        if (func instanceof ScriptFunction) {
            invoker.invokeExact(func, self, value);
        } else if (name != null) {
            throw typeError("property.has.no.setter", name, ScriptRuntime.safeToString(self));
        }
    }

    private static MethodHandle findOwnMH_S(String name, Class<?> rtype, Class<?>... types) {
        return MH.findStatic(LOOKUP, UserAccessorProperty.class, name, MH.type(rtype, types));
    }

}
