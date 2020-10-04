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

package nashorn.internal.ir;

import nashorn.internal.codegen.types.Type;
import nashorn.internal.runtime.UnwarrantedOptimismException;

/**
 * Common superclass for all expression nodes.
 *
 * Expression nodes can have an associated symbol as well as a type.
 */
public abstract class Expression extends Node {

    static final String OPT_IDENTIFIER = "%";

    protected Expression(long token, int start, int finish) {
        super(token, start, finish);
    }

    Expression(long token, int finish) {
        super(token, finish);
    }

    Expression(Expression expr) {
        super(expr);
    }

    /**
     * Returns the type of the expression.
     */
    public abstract Type getType();

    /**
     * Returns {@code true} if this expression depends exclusively on state that is constant or local to the currently running function and thus inaccessible to other functions.
     * This implies that a local expression must not call any other functions (neither directly nor implicitly through a getter, setter, or object-to-primitive type conversion).
     */
    public boolean isLocal() {
        return false;
    }

    /**
     * Is this a self modifying assignment? e.g. a++, or a*= 17
     */
    public boolean isSelfModifying() {
        return false;
    }

    /**
     * Returns widest operation type of this operation.
     */
    public Type getWidestOperationType() {
        return Type.OBJECT;
    }

    /**
     * Returns true if the type of this expression is narrower than its widest operation type (thus, it is optimistically typed).
     */
    public final boolean isOptimistic() {
        return getType().narrowerThan(getWidestOperationType());
    }

    void optimisticTypeToString(StringBuilder sb) {
        optimisticTypeToString(sb, isOptimistic());
    }

    void optimisticTypeToString(StringBuilder sb, boolean optimistic) {
        sb.append('{');
        var type = getType();
        var desc = type == Type.UNDEFINED ? "U" : type.getDescriptor();

        sb.append(desc.charAt(desc.length() - 1) == ';' ? "O" : desc);
        if (isOptimistic() && optimistic) {
            sb.append(OPT_IDENTIFIER);
            var pp = ((Optimistic)this).getProgramPoint();
            if (UnwarrantedOptimismException.isValid(pp)) {
                sb.append('_').append(pp);
            }
        }
        sb.append('}');
    }

    /**
     * Returns true if the runtime value of this expression is always false when converted to boolean as per ECMAScript ToBoolean conversion.
     * Used in control flow calculations.
     */
    public boolean isAlwaysFalse() {
        return false;
    }

    /**
     * Returns true if the runtime value of this expression is always true when converted to boolean as per ECMAScript ToBoolean conversion.
     * Used in control flow calculations.
     */
    public boolean isAlwaysTrue() {
        return false;
    }

    /**
     * Returns true if the expression is not null and {@link #isAlwaysFalse()}.
     */
    public static boolean isAlwaysFalse(Expression test) {
        return test != null && test.isAlwaysFalse();
    }

    /**
     * Returns true if the expression is null or {@link #isAlwaysTrue()}.
     * Null is considered to be always true as a for loop with no test is equivalent to a for loop with always-true test.
     */
    public static boolean isAlwaysTrue(Expression test) {
        return test == null || test.isAlwaysTrue();
    }

}
