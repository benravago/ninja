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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Pattern;

import nashorn.internal.ir.AccessNode;
import nashorn.internal.ir.BaseNode;
import nashorn.internal.ir.BinaryNode;
import nashorn.internal.ir.Block;
import nashorn.internal.ir.BlockLexicalContext;
import nashorn.internal.ir.BlockStatement;
import nashorn.internal.ir.BreakNode;
import nashorn.internal.ir.CallNode;
import nashorn.internal.ir.CaseNode;
import nashorn.internal.ir.CatchNode;
import nashorn.internal.ir.ContinueNode;
import nashorn.internal.ir.DebuggerNode;
import nashorn.internal.ir.EmptyNode;
import nashorn.internal.ir.Expression;
import nashorn.internal.ir.ExpressionStatement;
import nashorn.internal.ir.ForNode;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.IfNode;
import nashorn.internal.ir.IndexNode;
import nashorn.internal.ir.JumpStatement;
import nashorn.internal.ir.JumpToInlinedFinally;
import nashorn.internal.ir.LabelNode;
import nashorn.internal.ir.LexicalContext;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.LiteralNode.PrimitiveLiteralNode;
import nashorn.internal.ir.LoopNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.ReturnNode;
import nashorn.internal.ir.RuntimeNode;
import nashorn.internal.ir.Statement;
import nashorn.internal.ir.SwitchNode;
import nashorn.internal.ir.Symbol;
import nashorn.internal.ir.ThrowNode;
import nashorn.internal.ir.TryNode;
import nashorn.internal.ir.UnaryNode;
import nashorn.internal.ir.VarNode;
import nashorn.internal.ir.WhileNode;
import nashorn.internal.ir.visitor.NodeOperatorVisitor;
import nashorn.internal.ir.visitor.SimpleNodeVisitor;
import nashorn.internal.parser.Token;
import nashorn.internal.parser.TokenType;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.Source;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.logging.Loggable;
import nashorn.internal.runtime.logging.Logger;
import static nashorn.internal.codegen.CompilerConstants.EVAL;
import static nashorn.internal.codegen.CompilerConstants.RETURN;
import static nashorn.internal.ir.Expression.isAlwaysTrue;

/**
 * Lower to more primitive operations.
 *
 * After lowering, an AST still has no symbols and types, but several nodes have been turned into more low level constructs and control flow termination criteria have been computed.
 * <p>
 * We do things like code copying/inlining of finallies here, as it is much harder and context dependent to do any code copying after symbols have been finalized.
 */
@Logger(name="lower")
final class Lower extends NodeOperatorVisitor<BlockLexicalContext> implements Loggable {

    private final DebugLogger log;

    // Conservative pattern to test if element names consist of characters valid for identifiers.
    // This matches any non-zero length alphanumeric string including _ and $ and not starting with a digit.
    private static final Pattern SAFE_PROPERTY_NAME = Pattern.compile("[a-zA-Z_$][\\w$]*");

    /**
     * Constructor.
     */
    Lower(Compiler compiler) {
        super(new BlockLexicalContext() {

            @Override
            public List<Statement> popStatements() {
                var newStatements = new ArrayList<Statement>();
                var terminated = false;

                var statements = super.popStatements();
                for (var statement : statements) {
                    if (!terminated) {
                        newStatements.add(statement);
                        if (statement.isTerminal() || statement instanceof JumpStatement) { //TODO hasGoto? But some Loops are hasGoto too - why?
                            terminated = true;
                        }
                    } else {
                        FoldConstants.extractVarNodesFromDeadCode(statement, newStatements);
                    }
                }
                return newStatements;
            }

            @Override
            protected Block afterSetStatements(Block block) {
                var stmts = block.getStatements();
                for (var li = stmts.listIterator(stmts.size()); li.hasPrevious();) {
                    var stmt = li.previous();
                    // popStatements() guarantees that the only thing after a terminal statement are uninitialized VarNodes.
                    // We skip past those, and set the terminal state of the block to the value of the terminal state of the first statement that is not an uninitialized VarNode.
                    if (!(stmt instanceof VarNode && ((VarNode)stmt).getInit() == null)) {
                        return block.setIsTerminal(this, stmt.isTerminal());
                    }
                }
                return block.setIsTerminal(this, false);
            }
        });

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
    public boolean enterBreakNode(BreakNode breakNode) {
        addStatement(breakNode);
        return false;
    }

    @Override
    public Node leaveCallNode(CallNode callNode) {
        return checkEval(callNode.setFunction(markerFunction(callNode.getFunction())));
    }

    @Override
    public boolean enterCatchNode(CatchNode catchNode) {
        return true;
    }

    @Override
    public Node leaveCatchNode(CatchNode catchNode) {
        return addStatement(catchNode);
    }

    @Override
    public boolean enterContinueNode(ContinueNode continueNode) {
        addStatement(continueNode);
        return false;
    }

    @Override
    public boolean enterDebuggerNode(DebuggerNode debuggerNode) {
        var line = debuggerNode.getLineNumber();
        var token = debuggerNode.getToken();
        var finish = debuggerNode.getFinish();
        addStatement(new ExpressionStatement(line, token, finish, new RuntimeNode(token, finish, RuntimeNode.Request.DEBUGGER, new ArrayList<Expression>())));
        return false;
    }

    @Override
    public boolean enterJumpToInlinedFinally(JumpToInlinedFinally jumpToInlinedFinally) {
        addStatement(jumpToInlinedFinally);
        return false;
    }

    @Override
    public boolean enterEmptyNode(EmptyNode emptyNode) {
        return false;
    }

    @Override
    public Node leaveIndexNode(IndexNode indexNode) {
        var name = getConstantPropertyName(indexNode.getIndex());
        if (name != null) {
            // If index node is a constant property name convert index node to access node.
            assert indexNode.isIndex();
            return new AccessNode(indexNode.getToken(), indexNode.getFinish(), indexNode.getBase(), name);
        }
        return super.leaveIndexNode(indexNode);
    }

    @Override
    public Node leaveDELETE(UnaryNode delete) {
        var expression = delete.getExpression();
        if (expression instanceof IdentNode || expression instanceof BaseNode) {
            return delete;
        }
        return new BinaryNode(Token.recast(delete.getToken(), TokenType.COMMARIGHT), expression, LiteralNode.newInstance(delete.getToken(), delete.getFinish(), true));
    }

    // If expression is a primitive literal that is not an array index and does return its string value. Else return null.
    private static String getConstantPropertyName(Expression expression) {
        if (expression instanceof LiteralNode.PrimitiveLiteralNode) {
            var value = ((LiteralNode) expression).getValue();
            if (value instanceof String && SAFE_PROPERTY_NAME.matcher((String) value).matches()) {
                return (String) value;
            }
        }
        return null;
    }

    @Override
    public Node leaveExpressionStatement(ExpressionStatement expressionStatement) {
        var expr = expressionStatement.getExpression();
        ExpressionStatement node = expressionStatement;

        var currentFunction = lc.getCurrentFunction();

        if (currentFunction.isProgram()) {
            if (!isInternalExpression(expr) && !isEvalResultAssignment(expr)) {
                node = expressionStatement.setExpression(new BinaryNode(Token.recast(expressionStatement.getToken(), TokenType.ASSIGN), compilerConstant(RETURN), expr));
            }
        }

        return addStatement(node);
    }

    @Override
    public Node leaveBlockStatement(BlockStatement blockStatement) {
        return addStatement(blockStatement);
    }

    @Override
    public boolean enterForNode(ForNode forNode) {
        return super.enterForNode(forNode);
    }

    @Override
    public Node leaveForNode(ForNode forNode) {
        var newForNode = forNode;

        var test = forNode.getTest();
        if (!forNode.isForInOrOf() && isAlwaysTrue(test)) {
            newForNode = forNode.setTest(lc, null);
        }

        newForNode = checkEscape(newForNode);
        addStatement(newForNode);
        return newForNode;
    }

    @Override
    public boolean enterFunctionNode(FunctionNode functionNode) {

        var numParams = functionNode.getNumOfParams();
        if (numParams > 0) {
            var lastParam = functionNode.getParameter(numParams - 1);
        }

        return super.enterFunctionNode(functionNode);
    }

    @Override
    public Node leaveFunctionNode(FunctionNode functionNode) {
        log.info("END FunctionNode: ", functionNode.getName());
        return functionNode;
    }

    @Override
    public Node leaveIfNode(IfNode ifNode) {
        return addStatement(ifNode);
    }

    @Override
    public Node leaveIN(BinaryNode binaryNode) {
        return new RuntimeNode(binaryNode);
    }

    @Override
    public Node leaveINSTANCEOF(BinaryNode binaryNode) {
        return new RuntimeNode(binaryNode);
    }

    @Override
    public Node leaveLabelNode(LabelNode labelNode) {
        return addStatement(labelNode);
    }

    @Override
    public Node leaveReturnNode(ReturnNode returnNode) {
        addStatement(returnNode); //ReturnNodes are always terminal, marked as such in constructor
        return returnNode;
    }

    @Override
    public Node leaveCaseNode(CaseNode caseNode) {
        // Try to represent the case test as an integer
        var test = caseNode.getTest();
        if (test instanceof LiteralNode) {
            var lit = (LiteralNode<?>)test;
            if (lit.isNumeric() && !(lit.getValue() instanceof Integer)) {
                if (JSType.isRepresentableAsInt(lit.getNumber())) {
                    return caseNode.setTest((Expression)LiteralNode.newInstance(lit, lit.getInt32()).accept(this));
                }
            }
        }
        return caseNode;
    }

    @Override
    public Node leaveSwitchNode(SwitchNode switchNode) {
        if (!switchNode.isUniqueInteger()) {
            // Wrap it in a block so its internally created tag is restricted in scope
            addStatementEnclosedInBlock(switchNode);
        } else {
            addStatement(switchNode);
        }
        return switchNode;
    }

    @Override
    public Node leaveThrowNode(ThrowNode throwNode) {
        return addStatement(throwNode); //ThrowNodes are always terminal, marked as such in constructor
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> T ensureUniqueNamesIn(T node) {
        return (T)node.accept(new SimpleNodeVisitor() {
            @Override
            public Node leaveFunctionNode(FunctionNode functionNode) {
                var name = functionNode.getName();
                return functionNode.setName(lc, lc.getCurrentFunction().uniqueName(name));
            }
            @Override
            public Node leaveDefault(Node labelledNode) {
                return labelledNode.ensureUniqueLabels(lc);
            }
        });
    }

    private static Block createFinallyBlock(Block finallyBody) {
        var newStatements = new ArrayList<Statement>();
        for (var statement : finallyBody.getStatements()) {
            newStatements.add(statement);
            if (statement.hasTerminalFlags()) {
                break;
            }
        }
        return finallyBody.setStatements(null, newStatements);
    }

    private Block catchAllBlock(TryNode tryNode) {
        var lineNumber = tryNode.getLineNumber();
        var token = tryNode.getToken();
        var finish = tryNode.getFinish();

        var exception = new IdentNode(token, finish, lc.getCurrentFunction().uniqueName(CompilerConstants.EXCEPTION_PREFIX.symbolName()));

        var catchBody = new Block(token, finish, new ThrowNode(lineNumber, token, finish, new IdentNode(exception), true));
        assert catchBody.isTerminal(); //ends with throw, so terminal

        var catchAllNode = new CatchNode(lineNumber, token, finish, new IdentNode(exception), null, catchBody, true);
        var catchAllBlock = new Block(token, finish, catchAllNode);

        //catchallblock -> catchallnode (catchnode) -> exception -> throw

        return (Block)catchAllBlock.accept(this); //not accepted. has to be accepted by lower
    }

    private IdentNode compilerConstant(CompilerConstants cc) {
        var functionNode = lc.getCurrentFunction();
        return new IdentNode(functionNode.getToken(), functionNode.getFinish(), cc.symbolName());
    }

    private static boolean isTerminalFinally(Block finallyBlock) {
        return finallyBlock.getLastStatement().hasTerminalFlags();
    }

    /**
     * Splice finally code into all endpoints of a trynode
     * @param tryNode the try node
     * @param rethrow the rethrowing throw nodes from the synthetic catch block
     * @param finallyBody the code in the original finally block
     * @return new try node after splicing finally code (same if nop)
     */
    private TryNode spliceFinally(TryNode tryNode, ThrowNode rethrow, Block finallyBody) {
        assert tryNode.getFinallyBody() == null;

        var finallyBlock = createFinallyBlock(finallyBody);
        var inlinedFinallies = new ArrayList<Block>();
        var fn = lc.getCurrentFunction();
        var newTryNode = (TryNode)tryNode.accept(new SimpleNodeVisitor() {

            @Override
            public boolean enterFunctionNode(FunctionNode functionNode) {
                // do not enter function nodes - finally code should not be inlined into them
                return false;
            }

            @Override
            public Node leaveThrowNode(ThrowNode throwNode) {
                if (rethrow == throwNode) {
                    return new BlockStatement(prependFinally(finallyBlock, throwNode));
                }
                return throwNode;
            }

            @Override
            public Node leaveBreakNode(BreakNode breakNode) {
                return leaveJumpStatement(breakNode);
            }

            @Override
            public Node leaveContinueNode(ContinueNode continueNode) {
                return leaveJumpStatement(continueNode);
            }

            private Node leaveJumpStatement(JumpStatement jump) {
                // NOTE: leaveJumpToInlinedFinally deliberately does not delegate to this method, only break and continue are edited.
                // JTIF nodes should not be changed, rather the surroundings of break/continue/return that were moved into the inlined finally block itself will be changed.

                // If this visitor's lc doesn't find the target of the jump, it means it's external to the try block.
                if (jump.getTarget(lc) == null) {
                    return createJumpToInlinedFinally(fn, inlinedFinallies, prependFinally(finallyBlock, jump));
                }
                return jump;
            }

            @Override
            public Node leaveReturnNode(ReturnNode returnNode) {
                var expr = returnNode.getExpression();
                if (isTerminalFinally(finallyBlock)) {
                    if (expr == null) {
                        // Terminal finally; no return expression.
                        return createJumpToInlinedFinally(fn, inlinedFinallies, ensureUniqueNamesIn(finallyBlock));
                    }
                    // Terminal finally; has a return expression.
                    var newStatements = new ArrayList<Statement>(2);
                    var retLineNumber = returnNode.getLineNumber();
                    var retToken = returnNode.getToken();
                    // Expression is evaluated for side effects.
                    newStatements.add(new ExpressionStatement(retLineNumber, retToken, returnNode.getFinish(), expr));
                    newStatements.add(createJumpToInlinedFinally(fn, inlinedFinallies, ensureUniqueNamesIn(finallyBlock)));
                    return new BlockStatement(retLineNumber, new Block(retToken, finallyBlock.getFinish(), newStatements));
                } else if (expr == null || expr instanceof PrimitiveLiteralNode<?> || (expr instanceof IdentNode && RETURN.symbolName().equals(((IdentNode)expr).getName()))) {
                    // Nonterminal finally; no return expression, or returns a primitive literal, or returns :return.
                    // Just move the return expression into the finally block.
                    return createJumpToInlinedFinally(fn, inlinedFinallies, prependFinally(finallyBlock, returnNode));
                } else {
                    // We need to evaluate the result of the return in case it is complex while still in the try block, store it in :return, and return it afterwards.
                    var newStatements = new ArrayList<Statement>();
                    var retLineNumber = returnNode.getLineNumber();
                    var retToken = returnNode.getToken();
                    var retFinish = returnNode.getFinish();
                    var resultNode = new IdentNode(expr.getToken(), expr.getFinish(), RETURN.symbolName());
                    // ":return = <expr>;"
                    newStatements.add(new ExpressionStatement(retLineNumber, retToken, retFinish, new BinaryNode(Token.recast(returnNode.getToken(), TokenType.ASSIGN), resultNode, expr)));
                    // inline finally and end it with "return :return;"
                    newStatements.add(createJumpToInlinedFinally(fn, inlinedFinallies, prependFinally(finallyBlock, returnNode.setExpression(resultNode))));
                    return new BlockStatement(retLineNumber, new Block(retToken, retFinish, newStatements));
                }
            }
        });
        addStatement(inlinedFinallies.isEmpty() ? newTryNode : newTryNode.setInlinedFinallies(lc, inlinedFinallies));
        // TODO: if finallyStatement is terminal, we could just have sites of inlined finallies jump here.
        addStatement(new BlockStatement(finallyBlock));

        return newTryNode;
    }

    private static JumpToInlinedFinally createJumpToInlinedFinally(FunctionNode fn, List<Block> inlinedFinallies, Block finallyBlock) {
        var labelName = fn.uniqueName(":finally");
        var token = finallyBlock.getToken();
        var finish = finallyBlock.getFinish();
        inlinedFinallies.add(new Block(token, finish, new LabelNode(finallyBlock.getFirstStatementLineNumber(), token, finish, labelName, finallyBlock)));
        return new JumpToInlinedFinally(labelName);
    }

    private static Block prependFinally(Block finallyBlock, Statement statement) {
        var inlinedFinally = ensureUniqueNamesIn(finallyBlock);
        if (isTerminalFinally(finallyBlock)) {
            return inlinedFinally;
        }
        var stmts = inlinedFinally.getStatements();
        var newStmts = new ArrayList<Statement>(stmts.size() + 1);
        newStmts.addAll(stmts);
        newStmts.add(statement);
        return new Block(inlinedFinally.getToken(), statement.getFinish(), newStmts);
    }

    @Override
    public Node leaveTryNode(TryNode tryNode) {
        var finallyBody = tryNode.getFinallyBody();
        var newTryNode = tryNode.setFinallyBody(lc, null);

        // No finally or empty finally
        if (finallyBody == null || finallyBody.getStatementCount() == 0) {
            var catches = newTryNode.getCatches();
            if (catches == null || catches.isEmpty()) {
                // A completely degenerate try block: empty finally, no catches. Replace it with try body.
                return addStatement(new BlockStatement(tryNode.getBody()));
            }
            return addStatement(ensureUnconditionalCatch(newTryNode));
        }

        /*
         * create a new try node
         *    if we have catches:
         *
         *    try            try
         *       x              try
         *    catch               x
         *       y              catch
         *    finally z           y
         *                   catchall
         *                        rethrow
         *
         *   otherwise
         *
         *   try              try
         *      x               x
         *   finally          catchall
         *      y               rethrow
         *
         *
         *   now splice in finally code wherever needed
         *
         */
        var catchAll = catchAllBlock(tryNode);

        var rethrows = new ArrayList<ThrowNode>(1);
        catchAll.accept(new SimpleNodeVisitor() {
            @Override
            public boolean enterThrowNode(ThrowNode throwNode) {
                rethrows.add(throwNode);
                return true;
            }
        });
        assert rethrows.size() == 1;

        if (!tryNode.getCatchBlocks().isEmpty()) {
            var outerBody = new Block(newTryNode.getToken(), newTryNode.getFinish(), ensureUnconditionalCatch(newTryNode));
            newTryNode = newTryNode.setBody(lc, outerBody).setCatchBlocks(lc, null);
        }

        newTryNode = newTryNode.setCatchBlocks(lc, Arrays.asList(catchAll));

        /*
         * Now that the transform is done, we have to go into the try and splice the finally block in front of any statement that is outside the try
         */
        return (TryNode)lc.replace(tryNode, spliceFinally(newTryNode, rethrows.get(0), finallyBody));
    }

    private TryNode ensureUnconditionalCatch(TryNode tryNode) {
        var catches = tryNode.getCatches();
        if (catches == null || catches.isEmpty() || catches.get(catches.size() - 1).getExceptionCondition() == null) {
            return tryNode;
        }
        // If the last catch block is conditional, add an unconditional rethrow block
        var newCatchBlocks = new ArrayList<Block>(tryNode.getCatchBlocks());

        newCatchBlocks.add(catchAllBlock(tryNode));
        return tryNode.setCatchBlocks(lc, newCatchBlocks);
    }

    @Override
    public Node leaveVarNode(VarNode varNode) {
        addStatement(varNode);
        if (varNode.getFlag(VarNode.IS_LAST_FUNCTION_DECLARATION) && lc.getCurrentFunction().isProgram() && ((FunctionNode) varNode.getInit()).isAnonymous()) {
            new ExpressionStatement(varNode.getLineNumber(), varNode.getToken(), varNode.getFinish(), new IdentNode(varNode.getName())).accept(this);
        }
        return varNode;
    }

    @Override
    public Node leaveWhileNode(WhileNode whileNode) {
        var test = whileNode.getTest();
        var body = whileNode.getBody();

        if (isAlwaysTrue(test)) {
            // turn it into a for node without a test.
            var forNode = (ForNode)new ForNode(whileNode.getLineNumber(), whileNode.getToken(), whileNode.getFinish(), body, 0).accept(this);
            lc.replace(whileNode, forNode);
            return forNode;
        }

         return addStatement(checkEscape(whileNode));
    }

    /**
     * Given a function node that is a callee in a CallNode, replace it with the appropriate marker function.
     * This is used by {@link CodeGenerator} for fast scope calls
     * @param function function called by a CallNode
     * @return transformed node to marker function or identity if not ident/access/indexnode
     */
    private static Expression markerFunction(Expression function) {
        if (function instanceof IdentNode) {
            return ((IdentNode)function).setIsFunction();
        } else if (function instanceof BaseNode) {
            return ((BaseNode)function).setIsFunction();
        }
        return function;
    }

    /**
     * Calculate a synthetic eval location for a node for the stacktrace, for example src#17<eval>
     */
    private String evalLocation(IdentNode node) {
        var source = lc.getCurrentFunction().getSource();
        var pos = node.position();
        return new StringBuilder().
            append(source.getName()).
            append('#').
            append(source.getLine(pos)).
            append(':').
            append(source.getColumn(pos)).
            append("<eval>").
            toString();
    }

    /**
     * Check whether a call node may be a call to eval.
     * In that case we clone the args in order to create the following construct in {@link CodeGenerator}
     * <pre>
     * if (calledFuntion == buildInEval) {
     *    eval(cloned arg);
     * } else {
     *    cloned arg;
     * }
     * </pre>
     * @param callNode call node to check if it's an eval
     */
    private CallNode checkEval(CallNode callNode) {
        if (callNode.getFunction() instanceof IdentNode) {

            var args = callNode.getArgs();
            var callee = (IdentNode)callNode.getFunction();

            // 'eval' call with at least one argument
            if (args.size() >= 1 && EVAL.symbolName().equals(callee.getName())) {
                var evalArgs = new ArrayList<Expression>(args.size());
                for (var arg : args) {
                    evalArgs.add((Expression)ensureUniqueNamesIn(arg).accept(this));
                }
                return callNode.setEvalArgs(new CallNode.EvalArgs(evalArgs, evalLocation(callee)));
            }
        }

        return callNode;
    }

    /**
     * Helper that given a loop body makes sure that it is not terminal if it has a continue that leads to the loop header or to outer loops' loop headers.
     * This means that, even if the body ends with a terminal statement, we cannot tag it as terminal
     * @param loopBody the loop body to check
     * @return true if control flow may escape the loop
     */
    private static boolean controlFlowEscapes(LexicalContext lex, Block loopBody) {
        var escapes = new ArrayList<Node>();

        loopBody.accept(new SimpleNodeVisitor() {
            @Override
            public Node leaveBreakNode(BreakNode node) {
                escapes.add(node);
                return node;
            }
            @Override
            public Node leaveContinueNode(ContinueNode node) {
                // all inner loops have been popped.
                if (lex.contains(node.getTarget(lex))) {
                    escapes.add(node);
                }
                return node;
            }
        });

        return !escapes.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private <T extends LoopNode> T checkEscape(T loopNode) {
        var escapes = controlFlowEscapes(lc, loopNode.getBody());
        if (escapes) {
            return (T)loopNode
                .setBody(lc, loopNode.getBody().setIsTerminal(lc, false))
                .setControlFlowEscapes(lc, escapes);
        }
        return loopNode;
    }


    private Node addStatement(Statement statement) {
        lc.appendStatement(statement);
        return statement;
    }

    private void addStatementEnclosedInBlock(Statement stmt) {
        var b = BlockStatement.createReplacement(stmt, Collections.<Statement>singletonList(stmt));
        if (stmt.isTerminal()) {
            b = b.setBlock(b.getBlock().setIsTerminal(null, true));
        }
        addStatement(b);
    }

    /**
     * An internal expression has a symbol that is tagged internal.
     * Check if this is such a node
     * @param expression expression to check for internal symbol
     * @return true if internal, false otherwise
     */
    private static boolean isInternalExpression(Expression expression) {
        if (!(expression instanceof IdentNode)) {
            return false;
        }
        var symbol = ((IdentNode)expression).getSymbol();
        return symbol != null && symbol.isInternal();
    }

    /**
     * Is this an assignment to the special variable that hosts scripting eval results, i.e. __return__?
     * @param expression expression to check whether it is $evalresult = X
     * @return true if an assignment to eval result, false otherwise
     */
    private static boolean isEvalResultAssignment(Node expression) {
        var e = expression;
        if (e instanceof BinaryNode) {
            var lhs = ((BinaryNode)e).lhs();
            if (lhs instanceof IdentNode) {
                return ((IdentNode)lhs).getName().equals(RETURN.symbolName());
            }
        }
        return false;
    }

}
