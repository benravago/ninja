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

import java.util.Collections;
import java.util.List;

import nashorn.internal.codegen.Label;
import nashorn.internal.ir.annotations.Immutable;

@Immutable
abstract class BreakableStatement extends LexicalContextStatement implements BreakableNode {

    /** break label. */
    protected final Label breakLabel;

    final LocalVariableConversion conversion;

    /**
     * Constructor
     */
    protected BreakableStatement(int lineNumber, long token, int finish, Label breakLabel) {
        super(lineNumber, token, finish);
        this.breakLabel = breakLabel;
        this.conversion = null;
    }

    /**
     * Copy constructor
     */
    protected BreakableStatement(BreakableStatement breakableNode, LocalVariableConversion conversion) {
        super(breakableNode);
        this.breakLabel = new Label(breakableNode.getBreakLabel());
        this.conversion = conversion;
    }

    /**
     * Check whether this can be broken out from without using a label,
     */
    @Override
    public boolean isBreakableWithoutLabel() {
        return true;
    }

    /**
     * Return the break label, i.e. the location to go to on break.
     */
    @Override
    public Label getBreakLabel() {
        return breakLabel;
    }

    /**
     * Return the labels associated with this node.
     * Breakable nodes that aren't LoopNodes only have a break label - the location immediately afterwards the node in code
     */
    @Override
    public List<Label> getLabels() {
        return Collections.unmodifiableList(Collections.singletonList(breakLabel));
    }

    @Override
    public JoinPredecessor setLocalVariableConversion(LexicalContext lc, LocalVariableConversion conversion) {
        if (this.conversion == conversion) {
            return this;
        }
        return setLocalVariableConversionChanged(lc, conversion);
    }

    @Override
    public LocalVariableConversion getLocalVariableConversion() {
        return conversion;
    }

    abstract JoinPredecessor setLocalVariableConversionChanged(LexicalContext lc, LocalVariableConversion conversion);

}
