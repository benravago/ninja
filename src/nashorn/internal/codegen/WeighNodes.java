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

import java.util.Map;

import nashorn.internal.ir.AccessNode;
import nashorn.internal.ir.BinaryNode;
import nashorn.internal.ir.Block;
import nashorn.internal.ir.BreakNode;
import nashorn.internal.ir.CallNode;
import nashorn.internal.ir.CatchNode;
import nashorn.internal.ir.ContinueNode;
import nashorn.internal.ir.ExpressionStatement;
import nashorn.internal.ir.ForNode;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.IfNode;
import nashorn.internal.ir.IndexNode;
import nashorn.internal.ir.JumpToInlinedFinally;
import nashorn.internal.ir.LexicalContext;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.LiteralNode.ArrayLiteralNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.ObjectNode;
import nashorn.internal.ir.PropertyNode;
import nashorn.internal.ir.ReturnNode;
import nashorn.internal.ir.RuntimeNode;
import nashorn.internal.ir.SplitNode;
import nashorn.internal.ir.SwitchNode;
import nashorn.internal.ir.ThrowNode;
import nashorn.internal.ir.TryNode;
import nashorn.internal.ir.UnaryNode;
import nashorn.internal.ir.VarNode;
import nashorn.internal.ir.WhileNode;
import nashorn.internal.ir.visitor.NodeOperatorVisitor;


/**
 * Computes the "byte code" weight of an AST segment.
 *
 * This is used for Splitting too large class files
 */
final class WeighNodes extends NodeOperatorVisitor<LexicalContext> {

    /*
     * Weight constants.
     */
    static final long FUNCTION_WEIGHT  = 40;
    static final long AASTORE_WEIGHT   =  2;
    static final long ACCESS_WEIGHT    =  4;
    static final long ADD_WEIGHT       = 10;
    static final long BREAK_WEIGHT     =  1;
    static final long CALL_WEIGHT      = 10;
    static final long CATCH_WEIGHT     = 10;
    static final long COMPARE_WEIGHT   =  6;
    static final long CONTINUE_WEIGHT  =  1;
    static final long IF_WEIGHT        =  2;
    static final long LITERAL_WEIGHT   = 10;
    static final long LOOP_WEIGHT      =  4;
    static final long NEW_WEIGHT       =  6;
    static final long FUNC_EXPR_WEIGHT = 20;
    static final long RETURN_WEIGHT    =  2;
    static final long SPLIT_WEIGHT     = 40;
    static final long SWITCH_WEIGHT    =  8;
    static final long THROW_WEIGHT     =  2;
    static final long VAR_WEIGHT       = 40;
    static final long WITH_WEIGHT      =  8;
    static final long OBJECT_WEIGHT    = 16;
    static final long SETPROP_WEIGHT   =  5;

    /** Accumulated weight. */
    private long weight;

    /** Optional cache for weight of block nodes. */
    private final Map<Node, Long> weightCache;

    private final FunctionNode topFunction;

    /**
     * Constructor.
     * @param weightCache cache of already calculated block weights
     */
    private WeighNodes(FunctionNode topFunction, Map<Node, Long> weightCache) {
        super(new LexicalContext());
        this.topFunction = topFunction;
        this.weightCache = weightCache;
    }

    static long weigh(Node node) {
        return weigh(node, null);
    }

    static long weigh(Node node, Map<Node, Long> weightCache) {
        var weighNodes = new WeighNodes(node instanceof FunctionNode ? (FunctionNode)node : null, weightCache);
        node.accept(weighNodes);
        return weighNodes.weight;
    }

    @Override
    public Node leaveAccessNode(AccessNode accessNode) {
        weight += ACCESS_WEIGHT;
        return accessNode;
    }

    @Override
    public boolean enterBlock(Block block) {
        if (weightCache != null && weightCache.containsKey(block)) {
            weight += weightCache.get(block);
            return false;
        }
        return true;
    }

    @Override
    public Node leaveBreakNode(BreakNode breakNode) {
        weight += BREAK_WEIGHT;
        return breakNode;
    }

    @Override
    public Node leaveCallNode(CallNode callNode) {
        weight += CALL_WEIGHT;
        return callNode;
    }

    @Override
    public Node leaveCatchNode(CatchNode catchNode) {
        weight += CATCH_WEIGHT;
        return catchNode;
    }

    @Override
    public Node leaveContinueNode(ContinueNode continueNode) {
        weight += CONTINUE_WEIGHT;
        return continueNode;
    }

    @Override
    public Node leaveExpressionStatement(ExpressionStatement expressionStatement) {
        return expressionStatement;
    }

    @Override
    public Node leaveForNode(ForNode forNode) {
        weight += LOOP_WEIGHT;
        return forNode;
    }

    @Override
    public boolean enterFunctionNode(FunctionNode functionNode) {
        if (functionNode == topFunction) {
            // the function being weighted; descend into its statements
            return true;
        }
        // just a reference to inner function from outer function
        weight += FUNC_EXPR_WEIGHT;
        return false;
    }

    @Override
    public Node leaveIdentNode(IdentNode identNode) {
        weight += ACCESS_WEIGHT;
        return identNode;
    }

    @Override
    public Node leaveIfNode(IfNode ifNode) {
        weight += IF_WEIGHT;
        return ifNode;
    }

    @Override
    public Node leaveIndexNode(IndexNode indexNode) {
        weight += ACCESS_WEIGHT;
        return indexNode;
    }

    @Override
    public Node leaveJumpToInlinedFinally(JumpToInlinedFinally jumpToInlinedFinally) {
        weight += BREAK_WEIGHT;
        return jumpToInlinedFinally;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean enterLiteralNode(LiteralNode literalNode) {
        weight += LITERAL_WEIGHT;

        if (literalNode instanceof ArrayLiteralNode) {
            var arrayLiteralNode = (ArrayLiteralNode)literalNode;
            var value = arrayLiteralNode.getValue();
            var postsets = arrayLiteralNode.getPostsets();
            var units = arrayLiteralNode.getSplitRanges();

            if (units == null) {
                for (var postset : postsets) {
                    weight += AASTORE_WEIGHT;
                    var element = value[postset];

                    if (element != null) {
                        element.accept(this);
                    }
                }
            }

            return false;
        }

        return true;
    }

    @Override
    public boolean enterObjectNode(ObjectNode objectNode) {
        weight += OBJECT_WEIGHT;
        var properties = objectNode.getElements();
        var isSpillObject = properties.size() > CodeGenerator.OBJECT_SPILL_THRESHOLD;

        for (var property : properties) {
            if (!LiteralNode.isConstant(property.getValue())) {
                weight += SETPROP_WEIGHT;
                property.getValue().accept(this);
            } else if (!isSpillObject) {
                // constants in spill object are set via preset spill array,
                // but fields objects need to set constants.
                weight += SETPROP_WEIGHT;
            }

        }

        return false;
    }

    @Override
    public Node leavePropertyNode(PropertyNode propertyNode) {
        weight += LITERAL_WEIGHT;
        return propertyNode;
    }

    @Override
    public Node leaveReturnNode(ReturnNode returnNode) {
        weight += RETURN_WEIGHT;
        return returnNode;
    }

    @Override
    public Node leaveRuntimeNode(RuntimeNode runtimeNode) {
        weight += CALL_WEIGHT;
        return runtimeNode;
    }

    @Override
    public boolean enterSplitNode(SplitNode splitNode) {
        weight += SPLIT_WEIGHT;
        return false;
    }

    @Override
    public Node leaveSwitchNode(SwitchNode switchNode) {
        weight += SWITCH_WEIGHT;
        return switchNode;
    }

    @Override
    public Node leaveThrowNode(ThrowNode throwNode) {
        weight += THROW_WEIGHT;
        return throwNode;
    }

    @Override
    public Node leaveTryNode(TryNode tryNode) {
        weight += THROW_WEIGHT;
        return tryNode;
    }

    @Override
    public Node leaveVarNode(VarNode varNode) {
        weight += VAR_WEIGHT;
        return varNode;
    }

    @Override
    public Node leaveWhileNode(WhileNode whileNode) {
        weight += LOOP_WEIGHT;
        return whileNode;
    }

    @Override
    public Node leavePOS(UnaryNode unaryNode) {
        return unaryNodeWeight(unaryNode);
    }

    @Override
    public Node leaveBIT_NOT(UnaryNode unaryNode) {
        return unaryNodeWeight(unaryNode);
    }

    @Override
    public Node leaveDECINC(UnaryNode unaryNode) {
         return unaryNodeWeight(unaryNode);
    }

    @Override
    public Node leaveDELETE(UnaryNode unaryNode) {
        return runtimeNodeWeight(unaryNode);
    }

    @Override
    public Node leaveNEW(UnaryNode unaryNode) {
        weight += NEW_WEIGHT;
        return unaryNode;
    }

    @Override
    public Node leaveNOT(UnaryNode unaryNode) {
        return unaryNodeWeight(unaryNode);
    }

    @Override
    public Node leaveNEG(UnaryNode unaryNode) {
        return unaryNodeWeight(unaryNode);
    }

    @Override
    public Node leaveTYPEOF(UnaryNode unaryNode) {
        return runtimeNodeWeight(unaryNode);
    }

    @Override
    public Node leaveVOID(UnaryNode unaryNode) {
        return unaryNodeWeight(unaryNode);
    }

    @Override
    public Node leaveADD(BinaryNode binaryNode) {
        weight += ADD_WEIGHT;
        return binaryNode;
    }

    @Override
    public Node leaveAND(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_ADD(BinaryNode binaryNode) {
        weight += ADD_WEIGHT;
        return binaryNode;
    }

    @Override
    public Node leaveASSIGN_BIT_AND(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_BIT_OR(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_BIT_XOR(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_DIV(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_MOD(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_MUL(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_SAR(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_SHL(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_SHR(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveASSIGN_SUB(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveARROW(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveBIT_AND(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveBIT_OR(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveBIT_XOR(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveCOMMARIGHT(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveDIV(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveEQ(BinaryNode binaryNode) {
        return compareWeight(binaryNode);
    }

    @Override
    public Node leaveEQ_STRICT(BinaryNode binaryNode) {
        return compareWeight(binaryNode);
    }

    @Override
    public Node leaveGE(BinaryNode binaryNode) {
        return compareWeight(binaryNode);
    }

    @Override
    public Node leaveGT(BinaryNode binaryNode) {
        return compareWeight(binaryNode);
    }

    @Override
    public Node leaveIN(BinaryNode binaryNode) {
        weight += CALL_WEIGHT;
        return binaryNode;
    }

    @Override
    public Node leaveINSTANCEOF(BinaryNode binaryNode) {
        weight += CALL_WEIGHT;
        return binaryNode;
    }

    @Override
    public Node leaveLE(BinaryNode binaryNode) {
        return compareWeight(binaryNode);
    }

    @Override
    public Node leaveLT(BinaryNode binaryNode) {
        return compareWeight(binaryNode);
    }

    @Override
    public Node leaveMOD(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveMUL(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveNE(BinaryNode binaryNode) {
        return compareWeight(binaryNode);
    }

    @Override
    public Node leaveNE_STRICT(BinaryNode binaryNode) {
        return compareWeight(binaryNode);
    }

    @Override
    public Node leaveOR(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveSAR(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveSHL(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveSHR(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    @Override
    public Node leaveSUB(BinaryNode binaryNode) {
        return binaryNodeWeight(binaryNode);
    }

    private Node unaryNodeWeight(UnaryNode unaryNode) {
        weight += 1;
        return unaryNode;
    }

    private Node binaryNodeWeight(BinaryNode binaryNode) {
        weight += 1;
        return binaryNode;
    }

    private Node runtimeNodeWeight(UnaryNode unaryNode) {
        weight += CALL_WEIGHT;
        return unaryNode;
    }

    private Node compareWeight(BinaryNode binaryNode) {
        weight += COMPARE_WEIGHT;
        return binaryNode;
    }

}
