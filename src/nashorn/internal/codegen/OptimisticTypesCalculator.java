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

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;

import nashorn.internal.ir.AccessNode;
import nashorn.internal.ir.BinaryNode;
import nashorn.internal.ir.CallNode;
import nashorn.internal.ir.CatchNode;
import nashorn.internal.ir.Expression;
import nashorn.internal.ir.ExpressionStatement;
import nashorn.internal.ir.ForNode;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.IfNode;
import nashorn.internal.ir.IndexNode;
import nashorn.internal.ir.JoinPredecessorExpression;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.LoopNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.ObjectNode;
import nashorn.internal.ir.Optimistic;
import nashorn.internal.ir.PropertyNode;
import nashorn.internal.ir.Symbol;
import nashorn.internal.ir.TernaryNode;
import nashorn.internal.ir.UnaryNode;
import nashorn.internal.ir.VarNode;
import nashorn.internal.ir.WhileNode;
import nashorn.internal.ir.visitor.SimpleNodeVisitor;
import nashorn.internal.parser.TokenType;
import nashorn.internal.runtime.ScriptObject;
import static nashorn.internal.runtime.UnwarrantedOptimismException.isValid;

/**
 * Assigns optimistic types to expressions that can have them.
 *
 * This class mainly contains logic for which expressions must not ever be marked as optimistic, assigning narrowest non-invalidated types to program points from the compilation environment, as well as initializing optimistic types of global properties for scripts.
 */
final class OptimisticTypesCalculator extends SimpleNodeVisitor {

    final Compiler compiler;

    // Per-function bit set of program points that must never be optimistic.
    final Deque<BitSet> neverOptimistic = new ArrayDeque<>();

    OptimisticTypesCalculator(Compiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public boolean enterAccessNode(AccessNode accessNode) {
        tagNeverOptimistic(accessNode.getBase());
        return true;
    }

    @Override
    public boolean enterPropertyNode(PropertyNode propertyNode) {
        if (ScriptObject.PROTO_PROPERTY_NAME.equals(propertyNode.getKeyName())) {
            tagNeverOptimistic(propertyNode.getValue());
        }
        return super.enterPropertyNode(propertyNode);
    }

    @Override
    public boolean enterBinaryNode(BinaryNode binaryNode) {
        if (binaryNode.isAssignment()) {
            var lhs = binaryNode.lhs();
            if (!binaryNode.isSelfModifying()) {
                tagNeverOptimistic(lhs);
            }
            if (lhs instanceof IdentNode) {
                var symbol = ((IdentNode)lhs).getSymbol();
                // Assignment to internal symbols is never optimistic, except for self-assignment expressions
                if (symbol.isInternal() && !binaryNode.rhs().isSelfModifying()) {
                    tagNeverOptimistic(binaryNode.rhs());
                }
            }
        } else if (binaryNode.isTokenType(TokenType.INSTANCEOF) || binaryNode.isTokenType(TokenType.EQ_STRICT) || binaryNode.isTokenType(TokenType.NE_STRICT)) {
            tagNeverOptimistic(binaryNode.lhs());
            tagNeverOptimistic(binaryNode.rhs());
        }
        return true;
    }

    @Override
    public boolean enterCallNode(CallNode callNode) {
        tagNeverOptimistic(callNode.getFunction());
        return true;
    }

    @Override
    public boolean enterCatchNode(CatchNode catchNode) {
        // Condition is never optimistic (always coerced to boolean).
        tagNeverOptimistic(catchNode.getExceptionCondition());
        return true;
    }

    @Override
    public boolean enterExpressionStatement(ExpressionStatement expressionStatement) {
        var expr = expressionStatement.getExpression();
        if (!expr.isSelfModifying()) {
            tagNeverOptimistic(expr);
        }
        return true;
    }

    @Override
    public boolean enterForNode(ForNode forNode) {
        if (forNode.isForInOrOf()) {
            // for..in has the iterable in its "modify"
            tagNeverOptimistic(forNode.getModify());
        } else {
            // Test is never optimistic (always coerced to boolean).
            tagNeverOptimisticLoopTest(forNode);
        }
        return true;
    }

    @Override
    public boolean enterFunctionNode(FunctionNode functionNode) {
        if (!neverOptimistic.isEmpty() && compiler.isOnDemandCompilation()) {
            // This is a nested function, and we're doing on-demand compilation.
            // In these compilations, we never descend into nested functions.
            return false;
        }
        neverOptimistic.push(new BitSet());
        return true;
    }

    @Override
    public boolean enterIfNode(IfNode ifNode) {
        // Test is never optimistic (always coerced to boolean).
        tagNeverOptimistic(ifNode.getTest());
        return true;
    }

    @Override
    public boolean enterIndexNode(IndexNode indexNode) {
        tagNeverOptimistic(indexNode.getBase());
        return true;
    }

    @Override
    public boolean enterTernaryNode(TernaryNode ternaryNode) {
        // Test is never optimistic (always coerced to boolean).
        tagNeverOptimistic(ternaryNode.getTest());
        return true;
    }

    @Override
    public boolean enterUnaryNode(UnaryNode unaryNode) {
        if (unaryNode.isTokenType(TokenType.NOT) || unaryNode.isTokenType(TokenType.NEW)) {
            // Operand of boolean negation is never optimistic (always coerced to boolean).
            // Operand of "new" is never optimistic (always coerced to Object).
            tagNeverOptimistic(unaryNode.getExpression());
        }
        return true;
    }

    @Override
    public boolean enterVarNode(VarNode varNode) {
        tagNeverOptimistic(varNode.getName());
        return true;
    }

    @Override
    public boolean enterObjectNode(ObjectNode objectNode) {
        if (objectNode.getSplitRanges() != null) {
            return false;
        }
        return super.enterObjectNode(objectNode);
    }

    @Override
    public boolean enterLiteralNode(LiteralNode<?> literalNode) {
        if (literalNode.isArray() && ((LiteralNode.ArrayLiteralNode) literalNode).getSplitRanges() != null) {
            return false;
        }
        return super.enterLiteralNode(literalNode);
    }

    @Override
    public boolean enterWhileNode(WhileNode whileNode) {
        // Test is never optimistic (always coerced to boolean).
        tagNeverOptimisticLoopTest(whileNode);
        return true;
    }

    @Override
    protected Node leaveDefault(Node node) {
        if(node instanceof Optimistic) {
            return leaveOptimistic((Optimistic)node);
        }
        return node;
    }

    @Override
    public Node leaveFunctionNode(FunctionNode functionNode) {
        neverOptimistic.pop();
        return functionNode;
    }

    @Override
    public Node leaveIdentNode(IdentNode identNode) {
        var symbol = identNode.getSymbol();
        if (symbol == null) {
            assert identNode.isPropertyName();
            return identNode;
        } else if (symbol.isBytecodeLocal()) {
            // Identifiers accessing bytecode local variables will never be optimistic, as type calculation phase over them will always assign them statically provable types.
            // Note that access to function parameters can still be optimistic if the parameter needs to be in scope as it's used by a nested function.
            return identNode;
        } else if (symbol.isParam() && lc.getCurrentFunction().isVarArg()) {
            // Parameters in vararg methods are not optimistic; we always access them using Object getters.
            return identNode.setType(identNode.getMostPessimisticType());
        } else {
            assert symbol.isScope();
            return leaveOptimistic(identNode);
        }
    }

    private Expression leaveOptimistic(Optimistic opt) {
        var pp = opt.getProgramPoint();
        if (isValid(pp) && !neverOptimistic.peek().get(pp)) {
            return (Expression)opt.setType(compiler.getOptimisticType(opt));
        }
        return (Expression)opt;
    }

    private void tagNeverOptimistic(Expression expr) {
        if (expr instanceof Optimistic) {
            var pp = ((Optimistic)expr).getProgramPoint();
            if (isValid(pp)) {
                neverOptimistic.peek().set(pp);
            }
        }
    }

    private void tagNeverOptimisticLoopTest(LoopNode loopNode) {
        var test = loopNode.getTest();
        if (test != null) {
            tagNeverOptimistic(test.getExpression());
        }
    }

}
