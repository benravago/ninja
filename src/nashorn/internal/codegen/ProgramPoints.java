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

import java.util.HashSet;
import java.util.Set;

import nashorn.internal.IntDeque;
import nashorn.internal.ir.AccessNode;
import nashorn.internal.ir.BinaryNode;
import nashorn.internal.ir.CallNode;
import nashorn.internal.ir.Expression;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.IndexNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.Optimistic;
import nashorn.internal.ir.UnaryNode;
import nashorn.internal.ir.VarNode;
import nashorn.internal.ir.visitor.SimpleNodeVisitor;
import static nashorn.internal.runtime.UnwarrantedOptimismException.FIRST_PROGRAM_POINT;
import static nashorn.internal.runtime.linker.NashornCallSiteDescriptor.MAX_PROGRAM_POINT_VALUE;

/**
 * Find program points in the code that are needed for optimistic assumptions
 */
class ProgramPoints extends SimpleNodeVisitor {

    private final IntDeque nextProgramPoint = new IntDeque();
    private final Set<Node> noProgramPoint = new HashSet<>();

    private int next() {
        var next = nextProgramPoint.getAndIncrement();
        if (next > MAX_PROGRAM_POINT_VALUE) {
            throw new AssertionError("Function has more than " + MAX_PROGRAM_POINT_VALUE + " program points");
        }
        return next;
    }

    @Override
    public boolean enterFunctionNode(FunctionNode functionNode) {
        nextProgramPoint.push(FIRST_PROGRAM_POINT);
        return true;
    }

    @Override
    public Node leaveFunctionNode(FunctionNode functionNode) {
        nextProgramPoint.pop();
        return functionNode;
    }

    private Expression setProgramPoint(Optimistic optimistic) {
        if (noProgramPoint.contains(optimistic)) {
            return (Expression)optimistic;
        }
        return (Expression)(optimistic.canBeOptimistic() ? optimistic.setProgramPoint(next()) : optimistic);
    }

    @Override
    public boolean enterVarNode(VarNode varNode) {
        noProgramPoint.add(varNode.getName());
        return true;
    }

    @Override
    public boolean enterIdentNode(IdentNode identNode) {
        if (identNode.isInternal()) {
            noProgramPoint.add(identNode);
        }
        return true;
    }

    @Override
    public Node leaveIdentNode(IdentNode identNode) {
        if (identNode.isPropertyName()) {
            return identNode;
        }
        return setProgramPoint(identNode);
    }

    @Override
    public Node leaveCallNode(CallNode callNode) {
        return setProgramPoint(callNode);
    }

    @Override
    public Node leaveAccessNode(AccessNode accessNode) {
        return setProgramPoint(accessNode);
    }

    @Override
    public Node leaveIndexNode(IndexNode indexNode) {
        return setProgramPoint(indexNode);
    }

    @Override
    public Node leaveBinaryNode(BinaryNode binaryNode) {
        return setProgramPoint(binaryNode);
    }

    @Override
    public Node leaveUnaryNode(UnaryNode unaryNode) {
        return setProgramPoint(unaryNode);
    }

}
