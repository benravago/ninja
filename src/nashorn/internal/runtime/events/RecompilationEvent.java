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

package nashorn.internal.runtime.events;

import java.util.logging.Level;

import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.RecompilableScriptFunctionData;
import nashorn.internal.runtime.RewriteException;

/**
 * Subclass of runtime event for {@link RewriteException}.
 *
 * In order not to leak memory, RewriteExceptions get their return value destroyed and nulled out during recompilation.
 * If we are running with event logging enabled, we need to retain the returnValue, hence the extra field
 */
public final class RecompilationEvent extends RuntimeEvent<RewriteException> {

    private final Object returnValue;

    /**
     * Constructor.
     * 'rewriteException' is the exception wrapped by this RuntimeEvent.
     * 'returnValue' is the rewriteException return value - as we don't want to make {@code RewriteException.getReturnValueNonDestructive()} public, we pass it as an extra parameter, rather than querying the getter from another package.
     */
    public RecompilationEvent(Level level, RewriteException rewriteException, Object returnValue) {
        super(level, rewriteException);
        assert Context.getContext().getLogger(RecompilableScriptFunctionData.class).isEnabled() :
            "Unit test/instrumentation purpose only: RecompilationEvent instances should not be created without '--log=recompile', or we will leak memory in the general case";
        this.returnValue = returnValue;
    }

    /**
     * Get the preserved return value for the RewriteException
     */
    public Object getReturnValue() {
        return returnValue;
    }

}
