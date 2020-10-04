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

package nashorn.internal.codegen;

import java.util.List;

import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.Expression;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.Property;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.arrays.ArrayData;
import nashorn.internal.runtime.arrays.ArrayIndex;
import nashorn.internal.scripts.JD;
import nashorn.internal.scripts.JO;
import static nashorn.internal.codegen.CompilerConstants.constructorNoLookup;
import static nashorn.internal.codegen.CompilerConstants.virtualCallNoLookup;


/**
 * An object creator that uses spill properties.
 */
public final class SpillObjectCreator extends ObjectCreator<Expression> {

    /**
     * Constructor.
     * @param codegen  code generator
     * @param tuples   tuples for key, symbol, value
     */
    SpillObjectCreator(CodeGenerator codegen, List<MapTuple<Expression>> tuples) {
        super(codegen, tuples, false, false);
        makeMap();
    }

    @Override
    public void createObject(MethodEmitter method) {
        assert !isScope() : "spill scope objects are not currently supported";

        var length = tuples.size();
        var dualFields = codegen.useDualFields();
        var spillLength = ScriptObject.spillAllocationLength(length);
        var jpresetValues = dualFields ? new long[spillLength] : null;
        var opresetValues = new Object[spillLength];
        var objectClass = getAllocatorClass();
        var arrayData = ArrayData.allocate(ScriptRuntime.EMPTY_ARRAY);

        // Compute constant property values
        var pos = 0;
        for (var tuple : tuples) {
            var key = tuple.key;
            var value = tuple.value;

            //this is a nop of tuple.key isn't e.g. "apply" or another special name
            method.invalidateSpecialName(tuple.key);

            if (value != null) {
                var constantValue = LiteralNode.objectAsConstant(value);
                if (constantValue != LiteralNode.POSTSET_MARKER) {
                    var property = propertyMap.findProperty(key);
                    if (property != null) {
                        // normal property key
                        property.setType(dualFields ? JSType.unboxedFieldType(constantValue) : Object.class);
                        var slot = property.getSlot();
                        if (dualFields && constantValue instanceof Number) {
                            jpresetValues[slot] = ObjectClassGenerator.pack((Number)constantValue);
                        } else {
                            opresetValues[slot] = constantValue;
                        }
                    } else {
                        // array index key
                        var oldLength = arrayData.length();
                        var index = ArrayIndex.getArrayIndex(key);
                        var longIndex = ArrayIndex.toLongIndex(index);

                        assert ArrayIndex.isValidArrayIndex(index);

                        if (longIndex >= oldLength) {
                            arrayData = arrayData.ensure(longIndex);
                        }

                        //avoid blowing up the array if we can
                        if (constantValue instanceof Integer) {
                            arrayData = arrayData.set(index, ((Integer)constantValue).intValue()); // false
                        } else if (constantValue instanceof Double) {
                            arrayData = arrayData.set(index, ((Double)constantValue).doubleValue()); // false
                        } else {
                            arrayData = arrayData.set(index, constantValue); // false
                        }

                        if (longIndex > oldLength) {
                            arrayData = arrayData.delete(oldLength, longIndex - 1);
                        }
                    }
                }
            }
            pos++;
        }

        // create object and invoke constructor
        method._new(objectClass).dup();
        codegen.loadConstant(propertyMap);

        // load primitive value spill array
        if (dualFields) {
            codegen.loadConstant(jpresetValues);
        } else {
            method.loadNull();
        }
        // load object value spill array
        codegen.loadConstant(opresetValues);

        // instantiate the script object with spill objects
        method.invoke(constructorNoLookup(objectClass, PropertyMap.class, long[].class, Object[].class));

        // Set prefix array data if any
        if (arrayData.length() > 0) {
            method.dup();
            codegen.loadConstant(arrayData);
            method.invoke(virtualCallNoLookup(ScriptObject.class, "setArray", void.class, ArrayData.class));
        }
    }

    @Override
    public void populateRange(MethodEmitter method, Type objectType, int objectSlot, int start, int end) {
        method.load(objectType, objectSlot);

        // set postfix values
        for (var i = start; i < end; i++) {
            var tuple = tuples.get(i);

            if (LiteralNode.isConstant(tuple.value)) {
                continue;
            }

            var property = propertyMap.findProperty(tuple.key);

            if (property == null) {
                var index = ArrayIndex.getArrayIndex(tuple.key);
                assert ArrayIndex.isValidArrayIndex(index);
                method.dup();
                loadIndex(method, ArrayIndex.toLongIndex(index));
                loadTuple(method, tuple, false);
                method.dynamicSetIndex(0);
            } else {
                assert property.getKey() instanceof String; // symbol keys not yet supported in object literals
                method.dup();
                loadTuple(method, tuple, false);
                method.dynamicSet((String) property.getKey(), 0, false);
            }
        }
    }

    @Override
    protected PropertyMap makeMap() {
        assert propertyMap == null : "property map already initialized";
        var clazz = getAllocatorClass();
        propertyMap = new MapCreator<>(clazz, tuples).makeSpillMap(false, codegen.useDualFields());
        return propertyMap;
    }

    @Override
    protected void loadValue(Expression expr, Type type) {
        // Use generic type in order to avoid conversion between object types
        codegen.loadExpressionAsType(expr, Type.generic(type));
    }

    @Override
    protected Class<? extends ScriptObject> getAllocatorClass() {
        return codegen.useDualFields() ? JD.class : JO.class;
    }

}
