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

import nashorn.internal.codegen.Label;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;

/**
 * IR representation for CONTINUE statements.
 */
@Immutable
public class ContinueNode extends JumpStatement {

    /**
     * Constructor
     */
    public ContinueNode(int lineNumber, long token, int finish, String labelName) {
        super(lineNumber, token, finish, labelName);
    }

    private ContinueNode(ContinueNode continueNode, LocalVariableConversion conversion) {
        super(continueNode, conversion);
    }

    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterContinueNode(this)) {
            return visitor.leaveContinueNode(this);
        }
        return this;
    }

    @Override
    JumpStatement createNewJumpStatement(LocalVariableConversion conversion) {
        return new ContinueNode(this, conversion);
    }

    @Override
    String getStatementName() {
        return "continue";
    }


    @Override
    public BreakableNode getTarget(LexicalContext lc) {
        return lc.getContinueTo(getLabelName());
    }

    @Override
    Label getTargetLabel(BreakableNode target) {
        return ((LoopNode)target).getContinueLabel();
    }

}
