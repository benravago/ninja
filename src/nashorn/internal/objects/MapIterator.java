/*
 * Copyright (c) 2010, 2014, Oracle and/or its affiliates. All rights reserved.
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
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.Undefined;

import static nashorn.internal.runtime.ECMAErrors.typeError;

/**
 * ECMA6 23.1.5 Map Iterator Objects
 */
@ScriptClass("MapIterator")
public class MapIterator extends AbstractIterator {

    // initialized by nasgen
    private static PropertyMap $nasgenmap$;

    private LinkedMap.LinkedMapIterator iterator;

    private final IterationKind iterationKind;

    // Cached global needed for each iteration result
    private final Global global;

    /**
     * Constructor for Map iterators.
     */
    MapIterator(NativeMap map, IterationKind iterationKind, Global global) {
        super(global.getMapIteratorPrototype(), $nasgenmap$);
        this.iterator = map.getJavaMap().getIterator();
        this.iterationKind = iterationKind;
        this.global = global;
    }

    /**
     * ES6 23.1.5.2.1 %MapIteratorPrototype%.next()
     */
    @Function
    public static Object next(Object self, Object arg) {
        if (!(self instanceof MapIterator)) {
            throw typeError("not.a.map.iterator", ScriptRuntime.safeToString(self));
        }
        return ((MapIterator)self).next(arg);
    }

    @Override
    public String getClassName() {
        return "Map Iterator";
    }

    @Override
    protected  IteratorResult next(Object arg) {
        if (iterator == null) {
            return makeResult(Undefined.getUndefined(), Boolean.TRUE, global);
        }

        var node = iterator.next();

        if (node == null) {
            iterator = null;
            return makeResult(Undefined.getUndefined(), Boolean.TRUE, global);
        }

        if (iterationKind == IterationKind.KEY_VALUE) {
            var array = new NativeArray(new Object[] {node.getKey(), node.getValue()});
            return makeResult(array, Boolean.FALSE, global);
        }

        return makeResult(iterationKind == IterationKind.KEY ? node.getKey() : node.getValue(), Boolean.FALSE, global);
    }

}
