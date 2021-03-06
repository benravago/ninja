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
import static nashorn.internal.runtime.UnwarrantedOptimismException.isValid;

import java.lang.invoke.MethodHandle;
import jdk.dynalink.linker.LinkRequest;
import nashorn.internal.codegen.ObjectClassGenerator;
import nashorn.internal.objects.Global;

/**
 * This class represents the result from a find property search.
 */
public final class FindProperty {

    /** Object where search began. */
    private final ScriptObject self;

    /** Object where search finish. */
    private final ScriptObject prototype;

    /** Found property. */
    private final Property     property;

    /**
     * Constructor
     * @param self      script object where search began
     * @param prototype prototype where property was found, may be {@code self} if not inherited
     * @param property  property that was search result
     */
    public FindProperty(ScriptObject self, ScriptObject prototype, Property property) {
        this.self = self;
        this.prototype = prototype;
        this.property  = property;
    }

    /**
     * Return a copy of this FindProperty with a different property.
     */
    public FindProperty replaceProperty(Property newProperty) {
        assert this.property.getKey().equals(newProperty.getKey());
        assert this.property.getSlot() == newProperty.getSlot();
        return new FindProperty(self, prototype, newProperty);
    }

    /**
     * Ask for a getter that returns the given type.
     * The type has nothing to do with the internal representation of the property.
     * It may be an Object (boxing primitives) or a primitive (primitive fields with -Dnashorn.fields.dual=true)
     * @see ObjectClassGenerator
     * @param type type of getter, e.g. int.class if we want a function with {@code get()I} signature
     * @param programPoint program point, or INVALID_PROGRAM_POINT if pessimistic
     * @param request link request
     * @return method handle for the getter
     */
    public MethodHandle getGetter(Class<?> type, int programPoint, LinkRequest request) {
        MethodHandle getter;
        if (isValid(programPoint)) {
            getter = property.getOptimisticGetter(type, programPoint);
        } else {
            getter = property.getGetter(type);
        }
        if (property instanceof UserAccessorProperty) {
            getter = MH.insertArguments(getter, 1, UserAccessorProperty.getINVOKE_UA_GETTER(type, programPoint));
            if (isValid(programPoint) && type.isPrimitive()) {
                getter = MH.insertArguments(getter, 1, programPoint);
            }
            property.setType(type);
            return insertAccessorsGetter((UserAccessorProperty) property, request, getter);
        }
        return getter;
    }

    /**
     * Ask for a setter that sets the given type.
     * The type has nothing to do with the internal representation of the property.
     * It may be an Object (boxing primitives) or a primitive (primitive fields with -Dnashorn.fields.dual=true)
     * @see ObjectClassGenerator
     * @param type type of setter, e.g. int.class if we want a function with {@code set(I)V} signature
     * @param request link request
     * @return method handle for the getter
     */
    public MethodHandle getSetter(Class<?> type, LinkRequest request) {
        var setter = property.getSetter(type, getOwner().getMap());
        if (property instanceof UserAccessorProperty) {
            setter =  MH.insertArguments(setter, 1, UserAccessorProperty.getINVOKE_UA_SETTER(type), property.getKey());
            property.setType(type);
            return insertAccessorsGetter((UserAccessorProperty) property, request, setter);
        }

        return setter;
    }

    // Fold an accessor getter into the method handle of a user accessor property.
    private MethodHandle insertAccessorsGetter(UserAccessorProperty uap, LinkRequest request, MethodHandle mh) {
        var superGetter = uap.getAccessorsGetter();
        if (!isSelf()) {
            superGetter = ScriptObject.addProtoFilter(superGetter, getProtoChainLength());
        }
        if (request != null && !(request.getReceiver() instanceof ScriptObject)) {
            var wrapFilter = Global.getPrimitiveWrapFilter(request.getReceiver());
            superGetter = MH.filterArguments(superGetter, 0, wrapFilter.asType(wrapFilter.type().changeReturnType(superGetter.type().parameterType(0))));
        }
        superGetter = MH.asType(superGetter, superGetter.type().changeParameterType(0, Object.class));

        return MH.foldArguments(mh, superGetter);
    }

    /**
     * Return the {@code ScriptObject} owning of the property:  this means the prototype.
     */
    public ScriptObject getOwner() {
        return prototype;
    }

    /**
     * Return the {@code ScriptObject} where the search started.
     * This is usually the ScriptObject the operation was started on, except for properties found inside a 'with' statement, where it is the top-level 'with' expression object.
     */
    public ScriptObject getSelf() {
        return self;
    }

    /**
     * Return the appropriate receiver for a getter.
     */
    public ScriptObject getGetterReceiver() {
        return property != null && property.isAccessorProperty() ? self : prototype;
    }

    /**
     * Return the appropriate receiver for a setter.
     */
    public ScriptObject getSetterReceiver() {
        return property != null && property.hasSetterFunction(prototype) ? self : prototype;
    }

    /**
     * Return the property that was found
     */
    public Property getProperty() {
        return property;
    }

    /**
     * Check if the property found was inherited from a prototype and it is an ordinary property (one that has no accessor function).
     */
    public boolean isInheritedOrdinaryProperty() {
        return !isSelf() && !getProperty().isAccessorProperty();
    }

    /**
     * Check if the property found was NOT inherited, i.e. defined in the script object, rather than in the prototype
     */
    public boolean isSelf() {
        return self == prototype;
    }

    /**
     * Check if the property is in the scope
     */
    public boolean isScope() {
        return prototype.isScope();
    }

    /**
     * Get the property value from self as object.
     */
    public int getIntValue() {
        return property.getIntValue(getGetterReceiver(), getOwner());
    }
    /**
     * Get the property value from self as object.
     */
    public double getDoubleValue() {
        return property.getDoubleValue(getGetterReceiver(), getOwner());
    }
    /**
     * Get the property value from self as object.
     */
    public Object getObjectValue() {
        return property.getObjectValue(getGetterReceiver(), getOwner());
    }

    /**
     * Set the property value in self.
     */
    public void setValue(int value) {
        property.setValue(getSetterReceiver(), getOwner(), value);
    }

    /**
     * Set the property value in self.
     */
    public void setValue(double value) {
        property.setValue(getSetterReceiver(), getOwner(), value);
    }

    /**
     * Set the property value in self.
     */
    public void setValue(Object value) {
        property.setValue(getSetterReceiver(), getOwner(), value);
    }

    /**
     * Get the number of objects in the prototype chain between the {@code self} and the {@code owner} objects.
     */
    int getProtoChainLength() {
        assert self != null;
        var length = 0;
        for (var obj = self; obj != prototype; obj = obj.getProto()) {
            assert !(obj instanceof WithObject);
            ++length;
        }
        return length;
    }

    @Override
    public String toString() {
        return "[FindProperty: " + property.getKey() + ']';
    }

}
