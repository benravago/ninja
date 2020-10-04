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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.BinaryNode;
import nashorn.internal.ir.BlockStatement;
import nashorn.internal.ir.EmptyNode;
import nashorn.internal.ir.Expression;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IfNode;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.LiteralNode.ArrayLiteralNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.Statement;
import nashorn.internal.ir.SwitchNode;
import nashorn.internal.ir.TernaryNode;
import nashorn.internal.ir.UnaryNode;
import nashorn.internal.ir.VarNode;
import nashorn.internal.ir.visitor.SimpleNodeVisitor;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.logging.Loggable;
import nashorn.internal.runtime.logging.Logger;

/**
 * Simple constant folding pass, executed before IR is starting to be lowered.
 */
@Logger(name="fold")
final class FoldConstants extends SimpleNodeVisitor implements Loggable {

    private final DebugLogger log;

    FoldConstants(Compiler compiler) {
        this.log = initLogger(compiler.getContext());
    }

    @Override
    public DebugLogger getLogger() {
        return log;
    }

    @Override
    public DebugLogger initLogger(Context context) {
        return context.getLogger(this.getClass());
    }

    @Override
    public Node leaveUnaryNode(UnaryNode unaryNode) {
        var literalNode = new UnaryNodeConstantEvaluator(unaryNode).eval();
        if (literalNode != null) {
            log.info("Unary constant folded ", unaryNode, " to ", literalNode);
            return literalNode;
        }
        return unaryNode;
    }

    @Override
    public Node leaveBinaryNode(BinaryNode binaryNode) {
        var literalNode = new BinaryNodeConstantEvaluator(binaryNode).eval();
        if (literalNode != null) {
            log.info("Binary constant folded ", binaryNode, " to ", literalNode);
            return literalNode;
        }
        return binaryNode;
    }

    @Override
    public Node leaveFunctionNode(FunctionNode functionNode) {
        return functionNode;
    }

    @Override
    public Node leaveIfNode(IfNode ifNode) {
        var test = ifNode.getTest();
        if (test instanceof LiteralNode.PrimitiveLiteralNode) {
            var isTrue = ((LiteralNode.PrimitiveLiteralNode<?>)test).isTrue();
            var executed = isTrue ? ifNode.getPass() : ifNode.getFail();
            var dropped  = isTrue ? ifNode.getFail() : ifNode.getPass();
            var statements = new ArrayList<Statement>();

            if (executed != null) {
                statements.addAll(executed.getStatements()); // Get statements form executed branch
            }
            if (dropped != null) {
                extractVarNodesFromDeadCode(dropped, statements); // Get var-nodes from non-executed branch
            }
            if (statements.isEmpty()) {
                return new EmptyNode(ifNode);
            }
            return BlockStatement.createReplacement(ifNode, ifNode.getFinish(), statements);
        }
        return ifNode;
    }

    @Override
    public Node leaveTernaryNode(TernaryNode ternaryNode) {
        var test = ternaryNode.getTest();
        if (test instanceof LiteralNode.PrimitiveLiteralNode) {
            return (((LiteralNode.PrimitiveLiteralNode<?>)test).isTrue() ? ternaryNode.getTrueExpression() : ternaryNode.getFalseExpression()).getExpression();
        }
        return ternaryNode;
    }

    @Override
    public Node leaveSwitchNode(SwitchNode switchNode) {
        return switchNode.setUniqueInteger(lc, isUniqueIntegerSwitchNode(switchNode));
    }

    private static boolean isUniqueIntegerSwitchNode(SwitchNode switchNode) {
        var alreadySeen = new HashSet<Integer>();
        for (var caseNode : switchNode.getCases()) {
            var test = caseNode.getTest();
            if (test != null && !isUniqueIntegerLiteral(test, alreadySeen)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUniqueIntegerLiteral(Expression expr, Set<Integer> alreadySeen) {
        if (expr instanceof LiteralNode) {
            var value = ((LiteralNode<?>)expr).getValue();
            if (value instanceof Integer) {
                return alreadySeen.add((Integer)value);
            }
        }
        return false;
    }

    /**
     * Helper class to evaluate constant expressions at compile time.
     * This is also a simplifier used by BinaryNode visits, UnaryNode visits and conversions.
     */
    abstract static class ConstantEvaluator<T extends Node> {
        protected T parent;
        protected final long token;
        protected final int finish;

        protected ConstantEvaluator(T parent) {
            this.parent = parent;
            this.token  = parent.getToken();
            this.finish = parent.getFinish();
        }

        /**
         * Returns a literal node that replaces the given parent node, or null if replacement is impossible
         */
        protected abstract LiteralNode<?> eval();
    }

    /**
     * When we eliminate dead code, we must preserve var declarations as they are scoped to the whole function.
     * This method gathers var nodes from code passed to it, removing their initializers.
     * @param deadCodeRoot the root node of eliminated dead code
     * @param statements a list that will be receiving the var nodes from the dead code, with their initializers removed.
     */
    static void extractVarNodesFromDeadCode(Node deadCodeRoot, List<Statement> statements) {
        deadCodeRoot.accept(new SimpleNodeVisitor() {
            @Override
            public boolean enterVarNode(VarNode varNode) {
                statements.add(varNode.setInit(null));
                return false;
            }

            @Override
            public boolean enterFunctionNode(FunctionNode functionNode) {
                // Don't descend into nested functions
                return false;
            }
        });
    }

    private static class UnaryNodeConstantEvaluator extends ConstantEvaluator<UnaryNode> {
        UnaryNodeConstantEvaluator(UnaryNode parent) {
            super(parent);
        }

        @Override
        protected LiteralNode<?> eval() {
            var rhsNode = parent.getExpression();

            if (!(rhsNode instanceof LiteralNode)) {
                return null;
            }

            if (rhsNode instanceof ArrayLiteralNode) {
                return null;
            }

            var rhs = (LiteralNode<?>)rhsNode;
            var rhsType = rhs.getType();
            var rhsInteger = rhsType.isInteger() || rhsType.isBoolean();

            LiteralNode<?> literalNode;

            switch (parent.tokenType()) {
                case POS -> {
                    if (rhsInteger) {
                        literalNode = LiteralNode.newInstance(token, finish, rhs.getInt32());
                    } else if (rhsType.isLong()) {
                        literalNode = LiteralNode.newInstance(token, finish, rhs.getLong());
                    } else {
                        literalNode = LiteralNode.newInstance(token, finish, rhs.getNumber());
                    }
                }
                case NEG -> {
                    if (rhsInteger && rhs.getInt32() != 0) { // @see test/script/basic/minuszero.js
                        literalNode = LiteralNode.newInstance(token, finish, -rhs.getInt32());
                    } else if (rhsType.isLong() && rhs.getLong() != 0L) {
                        literalNode = LiteralNode.newInstance(token, finish, -rhs.getLong());
                    } else {
                        literalNode = LiteralNode.newInstance(token, finish, -rhs.getNumber());
                    }
                }
                case NOT -> {
                    literalNode = LiteralNode.newInstance(token, finish, !rhs.getBoolean());
                }
                case BIT_NOT -> {
                    literalNode = LiteralNode.newInstance(token, finish, ~rhs.getInt32());
                }
                default -> {
                    return null;
                }
            }

            return literalNode;
        }
    }

    //TODO add AND and OR with one constant parameter (bitwise)
    private static class BinaryNodeConstantEvaluator extends ConstantEvaluator<BinaryNode> {
        BinaryNodeConstantEvaluator(BinaryNode parent) {
            super(parent);
        }

        @Override
        protected LiteralNode<?> eval() {
            LiteralNode<?> result;

            result = reduceTwoLiterals();
            if (result != null) {
                return result;
            }

            result = reduceOneLiteral();
            if (result != null) {
                return result;
            }

            return null;
        }

        @SuppressWarnings("static-method")
        private LiteralNode<?> reduceOneLiteral() {
            // TODO: handle patterns like AND, OR, numeric ops that can be strength reduced but not replaced by a single literal node etc
            return null;
        }

        private LiteralNode<?> reduceTwoLiterals() {
            if (!(parent.lhs() instanceof LiteralNode && parent.rhs() instanceof LiteralNode)) {
                return null;
            }

            var lhs = (LiteralNode<?>)parent.lhs();
            var rhs = (LiteralNode<?>)parent.rhs();

            if (lhs instanceof ArrayLiteralNode || rhs instanceof ArrayLiteralNode) {
                return null;
            }

            var widest = Type.widest(lhs.getType(), rhs.getType());

            var isInteger = widest.isInteger();
            double value;

            switch (parent.tokenType()) {
                case DIV -> {
                    value = lhs.getNumber() / rhs.getNumber();
                }
                case ADD -> {
                    if ((lhs.isString() || rhs.isNumeric()) && (rhs.isString() || rhs.isNumeric())) {
                        final Object res = ScriptRuntime.ADD(lhs.getObject(), rhs.getObject());
                        if (res instanceof Number) {
                            value = ((Number)res).doubleValue();
                            break;
                        }
                        assert res instanceof CharSequence : res + " was not a CharSequence, it was a " + res.getClass();
                        return LiteralNode.newInstance(token, finish, res.toString());
                    }
                    return null;
                }
                case MUL -> {
                    value = lhs.getNumber() * rhs.getNumber();
                }
                case MOD -> {
                    value = lhs.getNumber() % rhs.getNumber();
                }
                case SUB -> {
                    value = lhs.getNumber() - rhs.getNumber();
                }
                case SHR -> {
                    var result = JSType.toUint32(lhs.getInt32() >>> rhs.getInt32());
                    return LiteralNode.newInstance(token, finish, JSType.toNarrowestNumber(result));
                }
                case SAR -> {
                    return LiteralNode.newInstance(token, finish, lhs.getInt32() >> rhs.getInt32());
                }
                case SHL -> {
                    return LiteralNode.newInstance(token, finish, lhs.getInt32() << rhs.getInt32());
                }
                case BIT_XOR -> {
                    return LiteralNode.newInstance(token, finish, lhs.getInt32() ^ rhs.getInt32());
                }
                case BIT_AND -> {
                    return LiteralNode.newInstance(token, finish, lhs.getInt32() & rhs.getInt32());
                }
                case BIT_OR -> {
                    return LiteralNode.newInstance(token, finish, lhs.getInt32() | rhs.getInt32());
                }
                case GE -> {
                    return LiteralNode.newInstance(token, finish, ScriptRuntime.GE(lhs.getObject(), rhs.getObject()));
                }
                case LE -> {
                    return LiteralNode.newInstance(token, finish, ScriptRuntime.LE(lhs.getObject(), rhs.getObject()));
                }
                case GT -> {
                    return LiteralNode.newInstance(token, finish, ScriptRuntime.GT(lhs.getObject(), rhs.getObject()));
                }
                case LT -> {
                    return LiteralNode.newInstance(token, finish, ScriptRuntime.LT(lhs.getObject(), rhs.getObject()));
                }
                case NE -> {
                    return LiteralNode.newInstance(token, finish, ScriptRuntime.NE(lhs.getObject(), rhs.getObject()));
                }
                case NE_STRICT -> {
                    return LiteralNode.newInstance(token, finish, ScriptRuntime.NE_STRICT(lhs.getObject(), rhs.getObject()));
                }
                case EQ -> {
                    return LiteralNode.newInstance(token, finish, ScriptRuntime.EQ(lhs.getObject(), rhs.getObject()));
                }
                case EQ_STRICT -> {
                    return LiteralNode.newInstance(token, finish, ScriptRuntime.EQ_STRICT(lhs.getObject(), rhs.getObject()));
                }
                default -> {
                    return null;
                }
            }

            isInteger &= JSType.isStrictlyRepresentableAsInt(value);

            if (isInteger) {
                return LiteralNode.newInstance(token, finish, (int)value);
            }

            return LiteralNode.newInstance(token, finish, value);
        }
    }

}
