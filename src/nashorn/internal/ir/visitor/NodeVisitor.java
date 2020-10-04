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

package nashorn.internal.ir.visitor;

import nashorn.internal.ir.AccessNode;
import nashorn.internal.ir.BinaryNode;
import nashorn.internal.ir.Block;
import nashorn.internal.ir.BlockStatement;
import nashorn.internal.ir.BreakNode;
import nashorn.internal.ir.CallNode;
import nashorn.internal.ir.CaseNode;
import nashorn.internal.ir.CatchNode;
import nashorn.internal.ir.ContinueNode;
import nashorn.internal.ir.DebuggerNode;
import nashorn.internal.ir.EmptyNode;
import nashorn.internal.ir.ErrorNode;
import nashorn.internal.ir.ExpressionStatement;
import nashorn.internal.ir.ForNode;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.GetSplitState;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.IfNode;
import nashorn.internal.ir.IndexNode;
import nashorn.internal.ir.JoinPredecessorExpression;
import nashorn.internal.ir.JumpToInlinedFinally;
import nashorn.internal.ir.LabelNode;
import nashorn.internal.ir.LexicalContext;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.ObjectNode;
import nashorn.internal.ir.PropertyNode;
import nashorn.internal.ir.ReturnNode;
import nashorn.internal.ir.RuntimeNode;
import nashorn.internal.ir.SetSplitState;
import nashorn.internal.ir.SplitNode;
import nashorn.internal.ir.SplitReturn;
import nashorn.internal.ir.SwitchNode;
import nashorn.internal.ir.TemplateLiteral;
import nashorn.internal.ir.TernaryNode;
import nashorn.internal.ir.ThrowNode;
import nashorn.internal.ir.TryNode;
import nashorn.internal.ir.UnaryNode;
import nashorn.internal.ir.VarNode;
import nashorn.internal.ir.WhileNode;

/**
 * Visitor used to navigate the IR.
 *
 * @param <T> lexical context class used by this visitor
 */
public abstract class NodeVisitor<T extends LexicalContext> {

    /** lexical context in use */
    protected final T lc;

    /**
     * Constructor.
     * 'lc' is a custom lexical context
     */
    public NodeVisitor(T lc) {
        this.lc = lc;
    }

    /**
     * Get the lexical context of this node visitor.
     */
    public T getLexicalContext() {
        return lc;
    }

    /**
     * Override this method to do a double inheritance pattern, e.g. avoid using
     *
     * <p>
     * if (x instanceof NodeTypeA) {
     *    ...
     * } else if (x instanceof NodeTypeB) {
     *    ...
     * } else {
     *    ...
     * }
     *
     * <p>
     * Use a NodeVisitor instead, and this method contents forms the else case.
     * @see NodeVisitor#leaveDefault(Node)
     *
     * 'node' is the node to visit.
     * Returns true if traversal should continue and node children be traversed, false otherwise.
     */
    protected boolean enterDefault(Node node) {
        return true;
    }

    /**
     * Override this method to do a double inheritance pattern, e.g. avoid using
     *
     * <p>
     * if (x instanceof NodeTypeA) {
     *    ...
     * } else if (x instanceof NodeTypeB) {
     *    ...
     * } else {
     *    ...
     * }
     *
     * <p>
     * Use a NodeVisitor instead, and this method contents forms the else case.
     * @see NodeVisitor#enterDefault(Node)
     *
     * 'node' the node to visit.
     * Returns the processed node, which will replace the original one, or the original node or null if traversal should end.
     */
    protected Node leaveDefault(Node node) {
        return node;
    }

    /** Callback for entering an AccessNode */
    public boolean enterAccessNode(AccessNode accessNode) {
        return enterDefault(accessNode);
    }

    /** Callback for entering an AccessNode */
    public Node leaveAccessNode(AccessNode accessNode) {
        return leaveDefault(accessNode);
    }

    /** Callback for entering a Block */
    public boolean enterBlock(Block block) {
        return enterDefault(block);
    }

    /**
     * Callback for leaving a Block */
    public Node leaveBlock(Block block) {
        return leaveDefault(block);
    }

    /** Callback for entering a BinaryNode */
    public boolean enterBinaryNode(BinaryNode binaryNode) {
        return enterDefault(binaryNode);
    }

    /** Callback for leaving a BinaryNode */
    public Node leaveBinaryNode(BinaryNode binaryNode) {
        return leaveDefault(binaryNode);
    }

    /** Callback for entering a BreakNode */
    public boolean enterBreakNode(BreakNode breakNode) {
        return enterDefault(breakNode);
    }

    /** Callback for leaving a BreakNode */
    public Node leaveBreakNode(BreakNode breakNode) {
        return leaveDefault(breakNode);
    }

    /** Callback for entering a CallNode */
    public boolean enterCallNode(CallNode callNode) {
        return enterDefault(callNode);
    }

    /** Callback for leaving a CallNode */
    public Node leaveCallNode(CallNode callNode) {
        return leaveDefault(callNode);
    }

    /** Callback for entering a CaseNode */
    public boolean enterCaseNode(CaseNode caseNode) {
        return enterDefault(caseNode);
    }

    /** Callback for leaving a CaseNode */
    public Node leaveCaseNode(CaseNode caseNode) {
        return leaveDefault(caseNode);
    }

    /** Callback for entering a CatchNode */
    public boolean enterCatchNode(CatchNode catchNode) {
        return enterDefault(catchNode);
    }

    /** Callback for leaving a CatchNode */
    public Node leaveCatchNode(CatchNode catchNode) {
        return leaveDefault(catchNode);
    }

    /** Callback for entering a ContinueNode */
    public boolean enterContinueNode(ContinueNode continueNode) {
        return enterDefault(continueNode);
    }

    /** Callback for leaving a ContinueNode */
    public Node leaveContinueNode(ContinueNode continueNode) {
        return leaveDefault(continueNode);
    }


    /** Callback for entering a DebuggerNode */
    public boolean enterDebuggerNode(DebuggerNode debuggerNode) {
        return enterDefault(debuggerNode);
    }

    /** Callback for leaving a DebuggerNode */
    public Node leaveDebuggerNode(DebuggerNode debuggerNode) {
        return leaveDefault(debuggerNode);
    }

    /** Callback for entering an EmptyNode */
    public boolean enterEmptyNode(EmptyNode emptyNode) {
        return enterDefault(emptyNode);
    }

    /** Callback for leaving an EmptyNode */
    public Node leaveEmptyNode(EmptyNode emptyNode) {
        return leaveDefault(emptyNode);
    }

    /** Callback for entering an ErrorNode */
    public boolean enterErrorNode(ErrorNode errorNode) {
        return enterDefault(errorNode);
    }

    /** Callback for leaving an ErrorNode */
    public Node leaveErrorNode(ErrorNode errorNode) {
        return leaveDefault(errorNode);
    }

    /** Callback for entering an ExpressionStatement */
    public boolean enterExpressionStatement(ExpressionStatement expressionStatement) {
        return enterDefault(expressionStatement);
    }

    /** Callback for leaving an ExpressionStatement */
    public Node leaveExpressionStatement(ExpressionStatement expressionStatement) {
        return leaveDefault(expressionStatement);
    }

    /** Callback for entering a BlockStatement */
    public boolean enterBlockStatement(BlockStatement blockStatement) {
        return enterDefault(blockStatement);
    }

    /** Callback for leaving a BlockStatement */
    public Node leaveBlockStatement(BlockStatement blockStatement) {
        return leaveDefault(blockStatement);
    }

    /** Callback for entering a ForNode */
    public boolean enterForNode(ForNode forNode) {
        return enterDefault(forNode);
    }

    /** Callback for leaving a ForNode */
    public Node leaveForNode(ForNode forNode) {
        return leaveDefault(forNode);
    }

    /** Callback for entering a FunctionNode */
    public boolean enterFunctionNode(FunctionNode functionNode) {
        return enterDefault(functionNode);
    }

    /** Callback for leaving a FunctionNode */
    public Node leaveFunctionNode(FunctionNode functionNode) {
        return leaveDefault(functionNode);
    }

    /** Callback for entering a {@link GetSplitState}. */
    public boolean enterGetSplitState(GetSplitState getSplitState) {
        return enterDefault(getSplitState);
    }

    /** Callback for leaving a {@link GetSplitState}. */
    public Node leaveGetSplitState(GetSplitState getSplitState) {
        return leaveDefault(getSplitState);
    }

    /** Callback for entering an IdentNode */
    public boolean enterIdentNode(IdentNode identNode) {
        return enterDefault(identNode);
    }

    /** Callback for leaving an IdentNode */
    public Node leaveIdentNode(IdentNode identNode) {
        return leaveDefault(identNode);
    }

    /** Callback for entering an IfNode */
    public boolean enterIfNode(IfNode ifNode) {
        return enterDefault(ifNode);
    }

    /** Callback for leaving an IfNode */
    public Node leaveIfNode(IfNode ifNode) {
        return leaveDefault(ifNode);
    }

    /** Callback for entering an IndexNode */
    public boolean enterIndexNode(IndexNode indexNode) {
        return enterDefault(indexNode);
    }

    /** Callback for leaving an IndexNode */
    public Node leaveIndexNode(IndexNode indexNode) {
        return leaveDefault(indexNode);
    }

    /** Callback for entering a JumpToInlinedFinally */
    public boolean enterJumpToInlinedFinally(JumpToInlinedFinally jumpToInlinedFinally) {
        return enterDefault(jumpToInlinedFinally);
    }

    /** Callback for leaving a JumpToInlinedFinally */
    public Node leaveJumpToInlinedFinally(JumpToInlinedFinally jumpToInlinedFinally) {
        return leaveDefault(jumpToInlinedFinally);
    }

    /** Callback for entering a LabelNode */
    public boolean enterLabelNode(LabelNode labelNode) {
        return enterDefault(labelNode);
    }

    /** Callback for leaving a LabelNode */
    public Node leaveLabelNode(LabelNode labelNode) {
        return leaveDefault(labelNode);
    }

    /** Callback for entering a LiteralNode */
    public boolean enterLiteralNode(LiteralNode<?> literalNode) {
        return enterDefault(literalNode);
    }

    /** Callback for leaving a LiteralNode */
    public Node leaveLiteralNode(LiteralNode<?> literalNode) {
        return leaveDefault(literalNode);
    }

    /** Callback for entering an ObjectNode */
    public boolean enterObjectNode(ObjectNode objectNode) {
        return enterDefault(objectNode);
    }

    /** Callback for leaving an ObjectNode */
    public Node leaveObjectNode(ObjectNode objectNode) {
        return leaveDefault(objectNode);
    }

    /** Callback for entering a PropertyNode */
    public boolean enterPropertyNode(PropertyNode propertyNode) {
        return enterDefault(propertyNode);
    }

    /** Callback for leaving a PropertyNode */
    public Node leavePropertyNode(PropertyNode propertyNode) {
        return leaveDefault(propertyNode);
    }

    /** Callback for entering a ReturnNode */
    public boolean enterReturnNode(ReturnNode returnNode) {
        return enterDefault(returnNode);
    }

    /** Callback for leaving a ReturnNode */
    public Node leaveReturnNode(ReturnNode returnNode) {
        return leaveDefault(returnNode);
    }

    /** Callback for entering a RuntimeNode */
    public boolean enterRuntimeNode(RuntimeNode runtimeNode) {
        return enterDefault(runtimeNode);
    }

    /** Callback for leaving a RuntimeNode */
    public Node leaveRuntimeNode(RuntimeNode runtimeNode) {
        return leaveDefault(runtimeNode);
    }

    /** Callback for entering a {@link SetSplitState}. */
    public boolean enterSetSplitState(SetSplitState setSplitState) {
        return enterDefault(setSplitState);
    }

    /** Callback for leaving a {@link SetSplitState}. */
    public Node leaveSetSplitState(SetSplitState setSplitState) {
        return leaveDefault(setSplitState);
    }

    /** Callback for entering a SplitNode */
    public boolean enterSplitNode(SplitNode splitNode) {
        return enterDefault(splitNode);
    }

    /** Callback for leaving a SplitNode */
    public Node leaveSplitNode(SplitNode splitNode) {
        return leaveDefault(splitNode);
    }

    /** Callback for entering a SplitReturn */
    public boolean enterSplitReturn(SplitReturn splitReturn) {
        return enterDefault(splitReturn);
    }

    /** Callback for leaving a SplitReturn */
    public Node leaveSplitReturn(SplitReturn splitReturn) {
        return leaveDefault(splitReturn);
    }

    /** Callback for entering a SwitchNode */
    public boolean enterSwitchNode(SwitchNode switchNode) {
        return enterDefault(switchNode);
    }

    /** Callback for leaving a SwitchNode */
    public Node leaveSwitchNode(SwitchNode switchNode) {
        return leaveDefault(switchNode);
    }

    /** Callback for entering a TemplateLiteral (used only in --parse-only mode) */
    public boolean enterTemplateLiteral(TemplateLiteral templateLiteral) {
        return enterDefault(templateLiteral);
    }

    /** Callback for leaving a TemplateLiteral (used only in --parse-only mode) */
    public Node leaveTemplateLiteral(TemplateLiteral templateLiteral) {
        return leaveDefault(templateLiteral);
    }

    /** Callback for entering a TernaryNode */
    public boolean enterTernaryNode(TernaryNode ternaryNode) {
        return enterDefault(ternaryNode);
    }

    /** Callback for leaving a TernaryNode */
    public Node leaveTernaryNode(TernaryNode ternaryNode) {
        return leaveDefault(ternaryNode);
    }

    /** Callback for entering a ThrowNode */
    public boolean enterThrowNode(ThrowNode throwNode) {
        return enterDefault(throwNode);
    }

    /** Callback for leaving a ThrowNode */
    public Node leaveThrowNode(ThrowNode throwNode) {
        return leaveDefault(throwNode);
    }

    /** Callback for entering a TryNode */
    public boolean enterTryNode(TryNode tryNode) {
        return enterDefault(tryNode);
    }

    /** Callback for leaving a TryNode */
    public Node leaveTryNode(TryNode tryNode) {
        return leaveDefault(tryNode);
    }

    /** Callback for entering a UnaryNode */
    public boolean enterUnaryNode(UnaryNode unaryNode) {
        return enterDefault(unaryNode);
    }

    /** Callback for leaving a UnaryNode */
    public Node leaveUnaryNode(UnaryNode unaryNode) {
        return leaveDefault(unaryNode);
    }

    /** Callback for entering a {@link JoinPredecessorExpression}. */
    public boolean enterJoinPredecessorExpression(JoinPredecessorExpression expr) {
        return enterDefault(expr);
    }

    /** Callback for leaving a {@link JoinPredecessorExpression}. */
    public Node leaveJoinPredecessorExpression(JoinPredecessorExpression expr) {
        return leaveDefault(expr);
    }


    /** Callback for entering a VarNode */
    public boolean enterVarNode(VarNode varNode) {
        return enterDefault(varNode);
    }

    /** Callback for leaving a VarNode */
    public Node leaveVarNode(VarNode varNode) {
        return leaveDefault(varNode);
    }

    /** Callback for entering a WhileNode */
    public boolean enterWhileNode(WhileNode whileNode) {
        return enterDefault(whileNode);
    }

    /** Callback for leaving a WhileNode */
    public Node leaveWhileNode(WhileNode whileNode) {
        return leaveDefault(whileNode);
    }

}
