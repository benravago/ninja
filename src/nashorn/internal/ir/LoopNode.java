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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nashorn.internal.codegen.Label;

/**
 * A loop node, for example a while node, do while node or for node
 */
public abstract class LoopNode extends BreakableStatement {

    /** loop continue label. */
    protected final Label continueLabel;

    /** Loop test node, null if infinite */
    protected final JoinPredecessorExpression test;

    /** Loop body */
    protected final Block body;

    /** Can control flow escape from loop, e.g. through breaks or continues to outer loops? */
    protected final boolean controlFlowEscapes;

    /**
     * Constructor
     */
    protected LoopNode(int lineNumber, long token, int finish, Block body, JoinPredecessorExpression test, boolean controlFlowEscapes) {
        super(lineNumber, token, finish, new Label("while_break"));
        this.continueLabel = new Label("while_continue");
        this.body = body;
        this.controlFlowEscapes = controlFlowEscapes;
        this.test = test;
    }

    /**
     * Constructor
     */
    protected LoopNode(LoopNode loopNode, JoinPredecessorExpression test, Block body, boolean controlFlowEscapes, LocalVariableConversion conversion) {
        super(loopNode, conversion);
        this.continueLabel = new Label(loopNode.continueLabel);
        this.test = test;
        this.body = body;
        this.controlFlowEscapes = controlFlowEscapes;
    }

    @Override
    public abstract Node ensureUniqueLabels(LexicalContext lc);

    /**
     * Does the control flow escape from this loop, i.e. through breaks or continues to outer loops?
     */
    public boolean controlFlowEscapes() {
        return controlFlowEscapes;
    }

    @Override
    public boolean isTerminal() {
        if (!mustEnter()) {
            return false;
        }
        // must enter but control flow may escape - then not terminal
        if (controlFlowEscapes) {
            return false;
        }
        // must enter, but body ends with return - then terminal
        if (body.isTerminal()) {
            return true;
        }
        // no breaks or returns, it is still terminal if we can never exit
        return test == null;
    }

    /**
     * Conservative check: does this loop have to be entered?
     */
    public abstract boolean mustEnter();

    /**
     * Get the continue label for this while node, i.e. location to go to on continue
     */
    public Label getContinueLabel() {
        return continueLabel;
    }

    @Override
    public List<Label> getLabels() {
        return Collections.unmodifiableList(Arrays.asList(breakLabel, continueLabel));
    }

    @Override
    public boolean isLoop() {
        return true;
    }

    /**
     * Get the body for this for node
     */
    public abstract Block getBody();

    /**
     * Set the body for this for node
     */
    public abstract LoopNode setBody(LexicalContext lc, Block body);

    /**
     * Get the test for this for node
     */
    public final JoinPredecessorExpression getTest() {
        return test;
    }

    /**
     * Set the test for this for node
     */
    public abstract LoopNode setTest(LexicalContext lc, JoinPredecessorExpression test);

    /**
     * Set the control flow escapes flag for this node.
     * TODO  - integrate this with Lowering in a better way
     */
    public abstract LoopNode setControlFlowEscapes(LexicalContext lc, boolean controlFlowEscapes);

    /**
     * Does this loop have a LET declaration and hence require a per-iteration scope?
     */
    public abstract boolean hasPerIterationScope();

}
