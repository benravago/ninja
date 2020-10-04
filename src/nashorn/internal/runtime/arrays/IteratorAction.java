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

package nashorn.internal.runtime.arrays;

import nashorn.internal.Util;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.linker.Bootstrap;

/**
 * Helper class for the various map/apply functions in {@link nashorn.internal.objects.NativeArray}.
 *
 * @param <T> element type of results from application callback
 */
public abstract class IteratorAction<T> {

    /** Self object */
    protected final Object self;

    /** This for the callback invocation */
    protected Object thisArg;

    /** Callback function to be applied to elements */
    protected final Object callbackfn;

    /** Result of array iteration */
    protected T result;

    /** Current array index of iterator */
    protected long index;

    /** Iterator object */
    private final ArrayLikeIterator<Object> iter;

    /**
     * Constructor
     */
    public IteratorAction(Object self, Object callbackfn, Object thisArg, T initialResult) {
        this(self, callbackfn, thisArg, initialResult, ArrayLikeIterator.arrayLikeIterator(self));
    }

    /**
     * Constructor
     */
    public IteratorAction(Object self, Object callbackfn, Object thisArg, T initialResult, ArrayLikeIterator<Object> iter) {
        this.self       = self;           // self reference to array object
        this.callbackfn = callbackfn;     // callback function for each element
        this.result     = initialResult;  // result accumulator initialization
        this.iter       = iter;           // custom element iterator
        this.thisArg    = thisArg;        // the reference
    }

    /**
     * An action to be performed once at the start of the apply loop.
     */
    protected void applyLoopBegin(ArrayLikeIterator<Object> iterator) {
        //empty
    }

    /**
     * Apply action main loop.
     */
    public final T apply() {

        // might need to translate undefined thisArg to be global object
        thisArg = (thisArg == ScriptRuntime.UNDEFINED && !Bootstrap.isCallable(callbackfn)) ? Context.getGlobal() : thisArg;

        applyLoopBegin(iter);
        var reverse = iter.isReverse();
        while (iter.hasNext()) {

            var val = iter.next();
            index = iter.nextIndex() + (reverse ? 1 : -1);

            try {
                if (!forEach(val, index)) {
                    return result;
                }
            } catch (Throwable t) {
                Util.uncheck(t);
            }
        }

        return result;
    }

    /**
     * For each callback
     */
    protected abstract boolean forEach(Object val, double i) throws Throwable;

}
