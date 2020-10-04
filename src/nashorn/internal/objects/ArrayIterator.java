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

import nashorn.internal.objects.annotations.Function;
import nashorn.internal.objects.annotations.ScriptClass;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.Undefined;

import static nashorn.internal.runtime.ECMAErrors.typeError;

@ScriptClass("ArrayIterator")
public class ArrayIterator extends AbstractIterator {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private ScriptObject iteratedObject;
    private long nextIndex = 0L;
    private final IterationKind iterationKind;
    private final Global global;


    private ArrayIterator(Object iteratedObject, IterationKind iterationKind, Global global) {
        super(global.getArrayIteratorPrototype(), $nasgenmap$);
        this.iteratedObject = iteratedObject instanceof ScriptObject ? (ScriptObject) iteratedObject : null;
        this.iterationKind = iterationKind;
        this.global = global;
    }

    static ArrayIterator newArrayValueIterator(Object iteratedObject) {
        return new ArrayIterator(Global.toObject(iteratedObject), IterationKind.VALUE, Global.instance());
    }

    static ArrayIterator newArrayKeyIterator(Object iteratedObject) {
        return new ArrayIterator(Global.toObject(iteratedObject), IterationKind.KEY, Global.instance());
    }

    static ArrayIterator newArrayKeyValueIterator(Object iteratedObject) {
        return new ArrayIterator(Global.toObject(iteratedObject), IterationKind.KEY_VALUE, Global.instance());
    }

    /**
     * 22.1.5.2.1 %ArrayIteratorPrototype%.next()
     */
    @Function
    public static Object next(Object self, Object arg) {
        if (!(self instanceof ArrayIterator)) {
            throw typeError("not.a.array.iterator", ScriptRuntime.safeToString(self));
        }
        return ((ArrayIterator)self).next(arg);
    }

    @Override
    public String getClassName() {
        return "Array Iterator";
    }

    @Override
    protected IteratorResult next(Object arg) {
        var index = nextIndex;

        if (iteratedObject == null || index >= JSType.toUint32(iteratedObject.getLength())) {
            // ES6 22.1.5.2.1 step 10
            iteratedObject = null;
            return makeResult(Undefined.getUndefined(), Boolean.TRUE, global);
        }

        nextIndex++;

        var value = switch (iterationKind) {
            case KEY_VALUE -> new NativeArray(new Object[] {JSType.toNarrowestNumber(index), iteratedObject.get((double) index)});
            case KEY -> JSType.toNarrowestNumber(index);
            default -> iteratedObject.get((double) index);
        };
        return makeResult(value, Boolean.FALSE, global);
    }

}
