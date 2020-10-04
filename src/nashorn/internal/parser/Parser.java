/*
 * Copyright (c) 2010, 2017, Oracle and/or its affiliates. All rights reserved.
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

package nashorn.internal.parser;

import static nashorn.internal.codegen.CompilerConstants.ANON_FUNCTION_PREFIX;
import static nashorn.internal.codegen.CompilerConstants.EVAL;
import static nashorn.internal.codegen.CompilerConstants.PROGRAM;
import static nashorn.internal.parser.TokenType.ARROW;
import static nashorn.internal.parser.TokenType.ASSIGN;
import static nashorn.internal.parser.TokenType.CASE;
import static nashorn.internal.parser.TokenType.CATCH;
import static nashorn.internal.parser.TokenType.COLON;
import static nashorn.internal.parser.TokenType.COMMARIGHT;
import static nashorn.internal.parser.TokenType.CONST;
import static nashorn.internal.parser.TokenType.DECPOSTFIX;
import static nashorn.internal.parser.TokenType.DECPREFIX;
import static nashorn.internal.parser.TokenType.ELSE;
import static nashorn.internal.parser.TokenType.EOF;
import static nashorn.internal.parser.TokenType.EOL;
import static nashorn.internal.parser.TokenType.EQ_STRICT;
import static nashorn.internal.parser.TokenType.FINALLY;
import static nashorn.internal.parser.TokenType.FUNCTION;
import static nashorn.internal.parser.TokenType.IDENT;
import static nashorn.internal.parser.TokenType.IF;
import static nashorn.internal.parser.TokenType.IMPORT;
import static nashorn.internal.parser.TokenType.INCPOSTFIX;
import static nashorn.internal.parser.TokenType.LBRACE;
import static nashorn.internal.parser.TokenType.LBRACKET;
import static nashorn.internal.parser.TokenType.LET;
import static nashorn.internal.parser.TokenType.LPAREN;
import static nashorn.internal.parser.TokenType.MUL;
import static nashorn.internal.parser.TokenType.PERIOD;
import static nashorn.internal.parser.TokenType.RBRACE;
import static nashorn.internal.parser.TokenType.RBRACKET;
import static nashorn.internal.parser.TokenType.RPAREN;
import static nashorn.internal.parser.TokenType.SEMICOLON;
import static nashorn.internal.parser.TokenType.TEMPLATE;
import static nashorn.internal.parser.TokenType.TEMPLATE_HEAD;
import static nashorn.internal.parser.TokenType.TEMPLATE_MIDDLE;
import static nashorn.internal.parser.TokenType.TEMPLATE_TAIL;
import static nashorn.internal.parser.TokenType.TERNARY;
import static nashorn.internal.parser.TokenType.VAR;
import static nashorn.internal.parser.TokenType.VOID;
import static nashorn.internal.parser.TokenType.WHILE;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import nashorn.internal.codegen.CompilerConstants;
import nashorn.internal.codegen.Namespace;
import nashorn.internal.ir.AccessNode;
import nashorn.internal.ir.BaseNode;
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
import nashorn.internal.ir.Expression;
import nashorn.internal.ir.ExpressionList;
import nashorn.internal.ir.ExpressionStatement;
import nashorn.internal.ir.ForNode;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.IfNode;
import nashorn.internal.ir.IndexNode;
import nashorn.internal.ir.JoinPredecessorExpression;
import nashorn.internal.ir.LabelNode;
import nashorn.internal.ir.LexicalContext;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.ObjectNode;
import nashorn.internal.ir.PropertyKey;
import nashorn.internal.ir.PropertyNode;
import nashorn.internal.ir.ReturnNode;
import nashorn.internal.ir.RuntimeNode;
import nashorn.internal.ir.Statement;
import nashorn.internal.ir.SwitchNode;
import nashorn.internal.ir.TemplateLiteral;
import nashorn.internal.ir.TernaryNode;
import nashorn.internal.ir.ThrowNode;
import nashorn.internal.ir.TryNode;
import nashorn.internal.ir.UnaryNode;
import nashorn.internal.ir.VarNode;
import nashorn.internal.ir.WhileNode;
import nashorn.internal.ir.visitor.NodeVisitor;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.ErrorManager;
import nashorn.internal.runtime.JSErrorType;
import nashorn.internal.runtime.ParserException;
import nashorn.internal.runtime.RecompilableScriptFunctionData;
import nashorn.internal.runtime.ScriptEnvironment;
import nashorn.internal.runtime.ScriptFunctionData;
import nashorn.internal.runtime.ScriptingFunctions;
import nashorn.internal.runtime.Source;
import nashorn.internal.runtime.Timing;
import nashorn.internal.runtime.linker.NameCodec;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.logging.Loggable;
import nashorn.internal.runtime.logging.Logger;

/**
 * Builds the IR.
 */
@Logger(name="parser")
public class Parser extends AbstractParser implements Loggable {

    private static final String ARGUMENTS_NAME = CompilerConstants.ARGUMENTS_VAR.symbolName();
    private static final String CONSTRUCTOR_NAME = "constructor";
    private static final String GET_NAME = "get";
    private static final String SET_NAME = "set";

    /** Current env. */
    private final ScriptEnvironment env;

    /** Is scripting mode. */
    private final boolean scripting;

    private List<Statement> functionDeclarations;

    private final ParserContext lc;
    private final Deque<Object> defaultNames;

    /** Namespace for function names where not explicitly given */
    private final Namespace namespace;

    private final DebugLogger log;

    /** to receive line information from Lexer when scanning multine literals. */
    protected final Lexer.LineInfoReceiver lineInfoReceiver;

    private RecompilableScriptFunctionData reparsedFunction;

    /**
     * Constructor
     *
     * @param env     script environment
     * @param source  source to parse
     * @param errors  error manager
     */
    public Parser(ScriptEnvironment env, Source source, ErrorManager errors) {
        this(env, source, errors, null);
    }

    /**
     * Constructor
     *
     * @param env     script environment
     * @param source  source to parse
     * @param errors  error manager
     * @param log debug logger if one is needed
     */
    public Parser(ScriptEnvironment env, Source source, ErrorManager errors, DebugLogger log) {
        this(env, source, errors, 0, log);
    }

    /**
     * Construct a parser.
     *
     * @param env     script environment
     * @param source  source to parse
     * @param errors  error manager
     * @param lineOffset line offset to start counting lines from
     * @param log debug logger if one is needed
     */
    public Parser(ScriptEnvironment env, Source source, ErrorManager errors, int lineOffset, DebugLogger log) {
        super(source, errors, lineOffset);
        this.lc = new ParserContext();
        this.defaultNames = new ArrayDeque<>();
        this.env = env;
        this.namespace = new Namespace(env.getNamespace());
        this.scripting = env._scripting;
        if (this.scripting) {
            this.lineInfoReceiver = new Lexer.LineInfoReceiver() {
                @Override
                public void lineInfo(int receiverLine, int receiverLinePosition) {
                    // update the parser maintained line information
                    Parser.this.line = receiverLine;
                    Parser.this.linePosition = receiverLinePosition;
                }
            };
        } else {
            // non-scripting mode script can't have multi-line literals
            this.lineInfoReceiver = null;
        }

        this.log = log == null ? DebugLogger.DISABLED_LOGGER : log;
    }

    @Override
    public DebugLogger getLogger() {
        return log;
    }

    @Override
    public DebugLogger initLogger(Context context) {
        return context.getLogger(this.getClass());
    }

    /**
     * Sets the name for the first function.
     * This is only used when reparsing anonymous functions to ensure they can preserve their already assigned name, as that name doesn't appear in their source text.
     * @param name the name for the first parsed function.
     */
    public void setFunctionName(String name) {
        defaultNames.push(createIdentNode(0, 0, name));
    }

    /**
     * Sets the {@link RecompilableScriptFunctionData} representing the function being reparsed (when this parser instance is used to reparse a previously parsed function, as part of its on-demand compilation).
     * This will trigger various special behaviors, such as skipping nested function bodies.
     * @param reparsedFunction the function being reparsed.
     */
    public void setReparsedFunction(RecompilableScriptFunctionData reparsedFunction) {
        this.reparsedFunction = reparsedFunction;
    }

    /**
     * Execute parse and return the resulting function node.
     * Errors will be thrown and the error manager will contain information if parsing should fail
     * This is the default parse call, which will name the function node {code :program} {@link CompilerConstants#PROGRAM}
     * @return function node resulting from successful parse
     */
    public FunctionNode parse() {
        return parse(PROGRAM.symbolName(), 0, source.getLength(), 0);
    }

    /**
     * Set up first token. Skips opening EOL.
     */
    private void scanFirstToken() {
        k = -1;
        next();
    }

    /**
     * Execute parse and return the resulting function node.
     * Errors will be thrown and the error manager will contain information if parsing should fail
     * This should be used to create one and only one function node
     * @param scriptName name for the script, given to the parsed FunctionNode
     * @param startPos start position in source
     * @param len length of parse
     * @param reparseFlags flags provided by {@link RecompilableScriptFunctionData} as context for the code being reparsed. This allows us to recognize special forms of functions such as property getters and setters or instances of ES6 method shorthand in object literals.
     * @return function node resulting from successful parse
     */
    public FunctionNode parse(String scriptName, int startPos, int len, int reparseFlags) {
        var isTimingEnabled = env.isTimingEnabled();
        var t0 = isTimingEnabled ? System.nanoTime() : 0L;
        log.info(this, " begin for '", scriptName, "'");

        try {
            stream = new TokenStream();
            lexer  = new Lexer(source, startPos, len, stream, scripting && !env._no_syntax_extensions, reparsedFunction != null);
            lexer.line = lexer.pendingLine = lineOffset + 1;
            line = lineOffset;

            scanFirstToken();
            // Begin parse.
            return program(scriptName, reparseFlags);
        } catch (Exception e) {
            handleParseException(e);

            return null;
        } finally {
            var end = this + " end '" + scriptName + "'";
            if (isTimingEnabled) {
                env._timing.accumulateTime(toString(), System.nanoTime() - t0);
                log.info(end, "' in ", Timing.toMillisPrint(System.nanoTime() - t0), " ms");
            } else {
                log.info(end);
            }
        }
    }

    /**
     * Parse and return the list of function parameter list.
     * A comma separated list of function parameter identifiers is expected to be parsed.
     * Errors will be thrown and the error manager will contain information if parsing should fail.
     * This method is used to check if parameter Strings passed to "Function" constructor is a valid or not.
     * @return the list of IdentNodes representing the formal parameter list
     */
    public List<IdentNode> parseFormalParameterList() {
        try {
            stream = new TokenStream();
            lexer  = new Lexer(source, stream, scripting && !env._no_syntax_extensions);

            scanFirstToken();

            return formalParameterList(TokenType.EOF);
        } catch (Exception e) {
            handleParseException(e);
            return null;
        }
    }

    /**
     * Execute parse and return the resulting function node.
     * Errors will be thrown and the error manager will contain information if parsing should fail.
     * This method is used to check if code String passed to "Function" constructor is a valid function body or not.
     * @return function node resulting from successful parse
     */
    public FunctionNode parseFunctionBody() {
        try {
            stream = new TokenStream();
            lexer = new Lexer(source, stream, scripting && !env._no_syntax_extensions);
            var functionLine = line;

            scanFirstToken();

            // Make a fake token for the function.
            var functionToken = Token.toDesc(FUNCTION, 0, source.getLength());
            // Set up the function to append elements.

            var ident = new IdentNode(functionToken, Token.descPosition(functionToken), PROGRAM.symbolName());
            var function = createParserContextFunctionNode(ident, functionToken, FunctionNode.Kind.NORMAL, functionLine, Collections.<IdentNode>emptyList());
            lc.push(function);

            var body = newBlock();

            functionDeclarations = new ArrayList<>();
            sourceElements(0);
            addFunctionDeclarations(function);
            functionDeclarations = null;

            restoreBlock(body);
            body.setFlag(Block.NEEDS_SCOPE);

            var functionBody = new Block(functionToken, source.getLength() - 1, body.getFlags() | Block.IS_SYNTHETIC, body.getStatements());
            lc.pop(function);

            expect(EOF);

            var functionNode = createFunctionNode(function, functionToken, ident, Collections.<IdentNode>emptyList(), FunctionNode.Kind.NORMAL, functionLine, functionBody);
            return functionNode;
        } catch (Exception e) {
            handleParseException(e);
            return null;
        }
    }

    private void handleParseException(Exception e) {
        // Extract message from exception.
        // The message will be in error message format.
        var message = e.getMessage();

        // If empty message.
        if (message == null) {
            message = e.toString();
        }

        // Issue message.
        if (e instanceof ParserException) {
            errors.error((ParserException)e);
        } else {
            errors.error(message);
        }

        if (env._dump_on_error) {
            e.printStackTrace(env.getErr());
        }
    }

    /**
     * Skip to a good parsing recovery point.
     */
    private void recover(Exception e) {
        if (e != null) {
            // Extract message from exception.
            // The message will be in error message format.
            var message = e.getMessage();

            // If empty message.
            if (message == null) {
                message = e.toString();
            }

            // Issue message.
            if (e instanceof ParserException) {
                errors.error((ParserException)e);
            } else {
                errors.error(message);
            }

            if (env._dump_on_error) {
                e.printStackTrace(env.getErr());
            }
        }

        // Skip to a recovery point.
        loop: for (;;) {
            switch (type) {
                case EOF -> {
                    // Can not go any further.
                    break loop;
                }
                case EOL, SEMICOLON, RBRACE -> {
                    // Good recovery points.
                    next();
                    break loop;
                }
                default -> {
                    // So we can recover after EOL.
                    nextOrEOL();
                }
            }
        }
    }

    /**
     * Set up a new block.
     */
    private ParserContextBlockNode newBlock() {
        return lc.push(new ParserContextBlockNode(token));
    }

    private ParserContextFunctionNode createParserContextFunctionNode(IdentNode ident, long functionToken, FunctionNode.Kind kind, int functionLine, List<IdentNode> parameters) {
        // Build function name.
        var sb = new StringBuilder();

        var parentFunction = lc.getCurrentFunction();
        if (parentFunction != null && !parentFunction.isProgram()) {
            sb.append(parentFunction.getName()).append(CompilerConstants.NESTED_FUNCTION_SEPARATOR.symbolName());
        }

        assert ident.getName() != null;
        sb.append(ident.getName());

        var name = namespace.uniqueName(sb.toString());
        assert parentFunction != null || name.equals(PROGRAM.symbolName()) : "name = " + name;

        var flags = 0;
        if (parentFunction == null) {
            flags |= FunctionNode.IS_PROGRAM;
        }

        var functionNode = new ParserContextFunctionNode(functionToken, ident, name, namespace, functionLine, kind, parameters);
        functionNode.setFlag(flags);
        return functionNode;
    }

    private FunctionNode createFunctionNode(ParserContextFunctionNode function, long startToken, IdentNode ident, List<IdentNode> parameters, FunctionNode.Kind kind, int functionLine, Block body) {
        // assert body.isFunctionBody() || body.getFlag(Block.IS_PARAMETER_BLOCK) && ((BlockStatement) body.getLastStatement()).getBlock().isFunctionBody();

        // Start new block.
        var functionNode = new FunctionNode(
            source,
            functionLine,
            body.getToken(),
            Token.descPosition(body.getToken()),
            startToken,
            function.getLastToken(),
            namespace,
            ident,
            function.getName(),
            parameters,
            function.getParameterExpressions(),
            kind,
            function.getFlags(),
            body,
            function.getEndParserState(),
            function.getDebugFlags());

        return functionNode;
    }

    /**
     * Restore the current block.
     */
    private ParserContextBlockNode restoreBlock(ParserContextBlockNode block) {
        return lc.pop(block);
    }

    /**
     * Get the statements in a block.
     */
    private Block getBlock(boolean needsBraces) {
        var blockToken = token;
        var newBlock = newBlock();
        try {
            // Block opening brace.
            if (needsBraces) {
                expect(LBRACE);
            }
            // Accumulate block statements.
            statementList();

        } finally {
            restoreBlock(newBlock);
        }

        // Block closing brace.
        if (needsBraces) {
            expect(RBRACE);
        }

        var flags = newBlock.getFlags() | (needsBraces ? 0 : Block.IS_SYNTHETIC);
        return new Block(blockToken, finish, flags, newBlock.getStatements());
    }

    /**
     * Get all the statements generated by a single statement.
     */
    private Block getStatement() {
        return getStatement(false);
    }

    private Block getStatement(boolean labelledStatement) {
        if (type == LBRACE) {
            return getBlock(true);
        }
        // Set up new block. Captures first token.
        var newBlock = newBlock();
        try {
            statement(false, 0, true, labelledStatement);
        } finally {
            restoreBlock(newBlock);
        }
        return new Block(newBlock.getToken(), finish, newBlock.getFlags() | Block.IS_SYNTHETIC, newBlock.getStatements());
    }

    /**
     * Detect calls to special functions.
     */
    private void detectSpecialFunction(IdentNode ident) {
        var name = ident.getName();

        if (EVAL.symbolName().equals(name)) {
            markEval(lc);
        }
    }

    /**
     * Detect use of special properties.
     */
    private void detectSpecialProperty(IdentNode ident) {
        if (isArguments(ident)) {
            // skip over arrow functions, e.g. function f() { return (() => arguments.length)(); }
            getCurrentNonArrowFunction().setFlag(FunctionNode.USES_ARGUMENTS);
        }
    }

    private static boolean isArguments(String name) {
        return ARGUMENTS_NAME.equals(name);
    }

    static boolean isArguments(IdentNode ident) {
        return isArguments(ident.getName());
    }

    /**
     * Tells whether a IdentNode can be used as L-value of an assignment
     * @param ident IdentNode to be checked
     * @return whether the ident can be used as L-value
     */
    private static boolean checkIdentLValue(IdentNode ident) {
        return ident.tokenType().getKind() != TokenKind.KEYWORD;
    }

    /**
     * Verify an assignment expression.
     * @param op  Operation token.
     * @param lhs Left hand side expression.
     * @param rhs Right hand side expression.
     * @return Verified expression.
     */
    private Expression verifyAssignment(long op, Expression lhs, Expression rhs) {
        var opType = Token.descType(op);

        switch (opType) {
            // default -> {}

            case ASSIGN, ASSIGN_ADD, ASSIGN_BIT_AND, ASSIGN_BIT_OR, ASSIGN_BIT_XOR, ASSIGN_DIV, ASSIGN_MOD, ASSIGN_MUL, ASSIGN_SAR, ASSIGN_SHL, ASSIGN_SHR, ASSIGN_SUB -> {
                if (lhs instanceof IdentNode) {
                    if (!checkIdentLValue((IdentNode)lhs)) {
                        return referenceError(lhs, rhs, false);
                    }
                    verifyIdent((IdentNode)lhs, "assignment");
                }
                else if (lhs instanceof AccessNode || lhs instanceof IndexNode) {
                    /* no-op */
                } else {
                    return referenceError(lhs, rhs, env._early_lvalue_error);
                }
            }
        }

        // Build up node.
        if (BinaryNode.isLogical(opType)) {
            return new BinaryNode(op, new JoinPredecessorExpression(lhs), new JoinPredecessorExpression(rhs));
        }
        return new BinaryNode(op, lhs, rhs);
    }

    /**
     * Reduce increment/decrement to simpler operations.
     * @param firstToken First token.
     * @param tokenType  Operation token (INCPREFIX/DEC.)
     * @param expression Left hand side expression.
     * @param isPostfix  Prefix or postfix.
     * @return Reduced expression.
     */
    private static UnaryNode incDecExpression(long firstToken, TokenType tokenType, Expression expression, boolean isPostfix) {
        if (isPostfix) {
            return new UnaryNode(Token.recast(firstToken, tokenType == DECPREFIX ? DECPOSTFIX : INCPOSTFIX), expression.getStart(), Token.descPosition(firstToken) + Token.descLength(firstToken), expression);
        }

        return new UnaryNode(firstToken, expression);
    }

    /**
     * -----------------------------------------------------------------------
     *
     * Grammar based on
     *
     *      ECMAScript Language Specification
     *      ECMA-262 5th Edition / December 2009
     *
     * -----------------------------------------------------------------------
     */

    /**
     * Program :
     *      SourceElements?
     *
     * See 14
     *
     * Parse the top level script.
     */
    private FunctionNode program(String scriptName, int reparseFlags) {
        // Make a pseudo-token for the script holding its start and length.
        var functionToken = Token.toDesc(FUNCTION, Token.descPosition(Token.withDelimiter(token)), source.getLength());
        var functionLine = line;

        var ident = new IdentNode(functionToken, Token.descPosition(functionToken), scriptName);
        var script = createParserContextFunctionNode(ident, functionToken, FunctionNode.Kind.SCRIPT, functionLine, Collections.<IdentNode>emptyList());
        lc.push(script);
        var body = newBlock();

        functionDeclarations = new ArrayList<>();
        sourceElements(reparseFlags);
        addFunctionDeclarations(script);
        functionDeclarations = null;

        restoreBlock(body);
        body.setFlag(Block.NEEDS_SCOPE);
        var programBody = new Block(functionToken, finish, body.getFlags() | Block.IS_SYNTHETIC | Block.IS_BODY, body.getStatements());
        lc.pop(script);
        script.setLastToken(token);

        expect(EOF);

        return createFunctionNode(script, functionToken, ident, Collections.<IdentNode>emptyList(), FunctionNode.Kind.SCRIPT, functionLine, programBody);
    }

    /**
     * SourceElements :
     *      SourceElement
     *      SourceElements SourceElement
     *
     * See 14
     *
     * Parse the elements of the script or function.
     */
    private void sourceElements(int reparseFlags) {
        var functionFlags = reparseFlags;

        // If is a script, then process until the end of the script.
        while (type != EOF) {
            // Break if the end of a code block.
            if (type == RBRACE) {
                break;
            }

            try {
                // Get the next element.
                statement(true, functionFlags, false, false);
                functionFlags = 0;

                // check for directive prologues
            } catch (Exception e) {
                var errorLine = line;
                var errorToken = token;
                //recover parsing
                recover(e);
                var errorExpr = new ErrorNode(errorToken, finish);
                var expressionStatement = new ExpressionStatement(errorLine, errorToken, finish, errorExpr);
                appendStatement(expressionStatement);
            }

            // No backtracking from here on.
            stream.commit(k);
        }
    }

    /**
     * Parse any of the basic statement types.
     *
     * Statement :
     *      BlockStatement
     *      VariableStatement
     *      EmptyStatement
     *      ExpressionStatement
     *      IfStatement
     *      BreakableStatement
     *      ContinueStatement
     *      BreakStatement
     *      ReturnStatement
     *      LabelledStatement
     *      ThrowStatement
     *      TryStatement
     *      DebuggerStatement
     *      ImportStatement
     *
     * BreakableStatement :
     *      IterationStatement
     *      SwitchStatement
     *
     * BlockStatement :
     *      Block
     *
     * Block :
     *      { StatementList opt }
     *
     * StatementList :
     *      StatementListItem
     *      StatementList StatementListItem
     *
     * StatementItem :
     *      Statement
     *      Declaration
     *
     * Declaration :
     *     HoistableDeclaration
     *     ClassDeclaration
     *     LexicalDeclaration
     *
     * HoistableDeclaration :
     *     FunctionDeclaration
     */
    private void statement() {
        statement(false, 0, false, false);
    }

    /**
     * @param topLevel does this statement occur at the "top level" of a script or a function?
     * @param reparseFlags reparse flags to decide whether to allow property "get" and "set" functions or ES6 methods.
     * @param singleStatement are we in a single statement context?
     */
    private void statement(boolean topLevel, int reparseFlags, boolean singleStatement, boolean labelledStatement) {
        switch (type) {
            case LBRACE -> block();
            case VAR -> variableStatement(type);
            case SEMICOLON -> emptyStatement();
            case IF -> ifStatement();
            case FOR -> forStatement();
            case WHILE -> whileStatement();
            case DO -> doStatement();
            case CONTINUE -> continueStatement();
            case BREAK -> breakStatement();
            case RETURN -> returnStatement();
            case SWITCH -> switchStatement();
            case THROW -> throwStatement();
            case TRY -> tryStatement();
            case DEBUGGER -> debuggerStatement();
            case RPAREN, RBRACKET, EOF -> expect(SEMICOLON);
            case FUNCTION -> {
                // As per spec (ECMA section 12), function declarations as arbitrary statement is not "portable".
                // Implementation can issue a warning or disallow the same.
                if (singleStatement) {
                    // ES6 B.3.2 Labelled Function Declarations
                    // It is a Syntax Error if any source code matches this rule:
                    // LabelledItem : FunctionDeclaration.
                    if (!labelledStatement) {
                        throw error(AbstractParser.message("expected.stmt", "function declaration"), token);
                    }
                }
                functionExpression(true, topLevel || labelledStatement);
                return;
            }

            default ->  {
                if (type == LET && lookaheadIsLetDeclaration(false) || type == CONST) {
                    if (singleStatement) {
                        throw error(AbstractParser.message("expected.stmt", type.getName() + " declaration"), token);
                    }
                    variableStatement(type);
                    break;
                }
                if (env._const_as_var && type == CONST) {
                    variableStatement(TokenType.VAR);
                    break;
                }

                if (type == IDENT) {
                    if (T(k + 1) == COLON) {
                        labelStatement();
                        return;
                    }

                    var ident = (String) getValue();
                    if ((reparseFlags & ScriptFunctionData.IS_PROPERTY_ACCESSOR) != 0) {
                        var propertyToken = token;
                        var propertyLine = line;
                        if (GET_NAME.equals(ident)) {
                            next();
                            addPropertyFunctionStatement(propertyGetterFunction(propertyToken, propertyLine));
                            return;
                        } else if (SET_NAME.equals(ident)) {
                            next();
                            addPropertyFunctionStatement(propertySetterFunction(propertyToken, propertyLine));
                            return;
                        }
                    }

                    if (Beans.isImported(ident)) {
                        var main = mainScript();
                        if (main != null) {
                            var bean = beanExpression(ident);
                            Beans.addBean(bean,main);
                            return;
                        }
                    }
                }

                if (type == IMPORT) {
                    var main = mainScript();
                    if (main != null) {
                        var name = importStatement();
                        Beans.addImport(name,main);
                        return;
                    }
                }

                if ((reparseFlags & ScriptFunctionData.IS_ES6_METHOD) != 0 && (type == IDENT || type == LBRACKET)) {
                    var ident = (String)getValue();
                    var propertyToken = token;
                    var propertyLine = line;
                    var propertyKey = propertyName();

                    // Code below will need refinement once we fully support ES6 class syntax
                    var flags = CONSTRUCTOR_NAME.equals(ident) ? FunctionNode.ES6_IS_CLASS_CONSTRUCTOR : FunctionNode.ES6_IS_METHOD;
                    addPropertyFunctionStatement(propertyMethodFunction(propertyKey, propertyToken, propertyLine, flags, false));
                    return;
                }

                expressionStatement();
            }
        }
    }

    private void addPropertyFunctionStatement(PropertyFunction propertyFunction) {
        var fn = propertyFunction.functionNode;
        functionDeclarations.add(new ExpressionStatement(fn.getLineNumber(), fn.getToken(), finish, fn));
    }

    /**
     * block :
     *      { StatementList? }
     *
     * see 12.1
     *
     * Parse a statement block.
     */
    private void block() {
        appendStatement(new BlockStatement(line, getBlock(true)));
    }

    /**
     * StatementList :
     *      Statement
     *      StatementList Statement
     *
     * See 12.1
     *
     * Parse a list of statements.
     */
    private void statementList() {
        // Accumulate statements until end of list. */
        loop: while (type != EOF) {
            switch (type) {
                // default -> {}
                case EOF, CASE, DEFAULT, RBRACE -> {
                    break loop;
                }
            }

            // Get next statement.
            statement();
        }
    }

    /**
     * Make sure that the identifier name used is allowed.
     * @param ident         Identifier that is verified
     * @param contextString String used in error message to give context to the user
     */
    private void verifyIdent(IdentNode ident, String contextString) {
        verifyFutureIdent(ident, contextString);
        checkEscapedKeyword(ident);
    }

    /**
     * Make sure that the identifier name used is allowed.
     * @param ident         Identifier that is verified
     * @param contextString String used in error message to give context to the user
     */
    private void verifyFutureIdent(IdentNode ident, String contextString) {
        switch (ident.getName()) {
            // default -> {}
            case "eval", "arguments" -> throw error(AbstractParser.message("strict.name", ident.getName(), contextString), ident.getToken());
        }

        if (ident.isFutureName()) {
            throw error(AbstractParser.message("strict.name", ident.getName(), contextString), ident.getToken());
        }
    }

    /**
     * ES6 11.6.2: A code point in a ReservedWord cannot be expressed by a | UnicodeEscapeSequence.
     */
    private void checkEscapedKeyword(IdentNode ident) {
        if (ident.containsEscapes()) {
            var tokenType = TokenLookup.lookupKeyword(ident.getName().toCharArray(), 0, ident.getName().length());
            if (tokenType != IDENT) {
                throw error(AbstractParser.message("keyword.escaped.character"), ident.getToken());
            }
        }
    }

    /*
     * VariableStatement :
     *      var VariableDeclarationList ;
     *
     * VariableDeclarationList :
     *      VariableDeclaration
     *      VariableDeclarationList , VariableDeclaration
     *
     * VariableDeclaration :
     *      Identifier Initializer?
     *
     * Initializer :
     *      = AssignmentExpression
     *
     * See 12.2
     *
     * Parse a VAR statement.
     * @param isStatement True if a statement (not used in a FOR.)
     */
    private void variableStatement(TokenType varType) {
        variableDeclarationList(varType, true, -1);
    }

    private static final class ForVariableDeclarationListResult {
        /** First missing const or binding pattern initializer. */
        Expression missingAssignment;
        /** First declaration with an initializer. */
        long declarationWithInitializerToken;
        /** Destructuring assignments. */
        Expression init;
        Expression firstBinding;
        Expression secondBinding;

        void recordMissingAssignment(Expression binding) {
            if (missingAssignment == null) {
                missingAssignment = binding;
            }
        }

        void recordDeclarationWithInitializer(long token) {
            if (declarationWithInitializerToken == 0L) {
                declarationWithInitializerToken = token;
            }
        }

        void addBinding(Expression binding) {
            if (firstBinding == null) {
                firstBinding = binding;
            } else if (secondBinding == null)  {
                secondBinding = binding;
            }
            // ignore the rest
        }
    }

    /**
     * @param isStatement {@code true} if a VariableStatement, {@code false} if a {@code for} loop VariableDeclarationList
     */
    private ForVariableDeclarationListResult variableDeclarationList(TokenType varType, boolean isStatement, int sourceOrder) {
        // VAR tested in caller.
        assert varType == VAR || varType == LET || varType == CONST;
        var varLine = line;
        var varToken = token;

        next();

        var varFlags = 0;
        if (varType == LET) {
            varFlags |= VarNode.IS_LET;
        } else if (varType == CONST) {
            varFlags |= VarNode.IS_CONST;
        }

        var forResult = isStatement ? null : new ForVariableDeclarationListResult();
        for (;;) {
            // Get name of var.

            var contextString = "variable name";
            var binding = bindingIdentifierOrPattern(contextString);
            // Assume no init.
            Expression init = null;

            // Look for initializer assignment.
            if (type == ASSIGN) {
                if (!isStatement) {
                    forResult.recordDeclarationWithInitializer(varToken);
                }
                next();

                // Get initializer expression. Suppress IN if not statement.
                defaultNames.push(binding);
                try {
                    init = assignmentExpression(!isStatement);
                } finally {
                    defaultNames.pop();
                }
            } else if (isStatement) {
                if (varType == CONST) {
                    throw error(AbstractParser.message("missing.const.assignment", ((IdentNode)binding).getName()));
                }
                // else, if we are in a for loop, delay checking until we know the kind of loop
            }

            assert init != null || varType != CONST || !isStatement;

            var ident = (IdentNode)binding;
            if (!isStatement && ident.getName().equals("let")) {
                throw error(AbstractParser.message("let.binding.for")); //ES6 13.7.5.1
            }
            // Only set declaration flag on lexically scoped let/const as it adds runtime overhead.
            var name = varType == LET || varType == CONST ? ident.setIsDeclaredHere() : ident;
            if (!isStatement) {
                if (init == null && varType == CONST) {
                    forResult.recordMissingAssignment(name);
                }
                forResult.addBinding(new IdentNode(name));
            }
            var var = new VarNode(varLine, varToken, sourceOrder, finish, name, init, varFlags);
            appendStatement(var);

            if (type != COMMARIGHT) {
                break;
            }
            next();
        }

        // If is a statement then handle end of line.
        if (isStatement) {
            endOfLine();
        }

        return forResult;
    }

    private boolean isBindingIdentifier() {
        return type == IDENT;
    }

    private IdentNode bindingIdentifier(String contextString) {
        var name = getIdent();
        verifyIdent(name, contextString);
        return name;
    }

    private Expression bindingPattern() {
        if (type == LBRACKET) {
            return arrayLiteral();
        } else if (type == LBRACE) {
            return objectLiteral();
        } else {
            throw error(AbstractParser.message("expected.binding"));
        }
    }

    private Expression bindingIdentifierOrPattern(String contextString) {
        if (isBindingIdentifier()) {
            return bindingIdentifier(contextString);
        } else {
            return bindingPattern();
        }
    }

    /**
     * EmptyStatement :
     *      ;
     *
     * See 12.3
     *
     * Parse an empty statement.
     */
    private void emptyStatement() {
        if (env._empty_statements) {
            appendStatement(new EmptyNode(line, token, Token.descPosition(token) + Token.descLength(token)));
        }

        // SEMICOLON checked in caller.
        next();
    }

    /**
     * ExpressionStatement :
     *      Expression ; // [lookahead ~({ or  function )]
     *
     * See 12.4
     *
     * Parse an expression used in a statement block.
     */
    private void expressionStatement() {
        // Lookahead checked in caller.
        var expressionLine  = line;
        var expressionToken = token;

        // Get expression and add as statement.
        var expression = expression();

        if (expression != null) {
            var expressionStatement = new ExpressionStatement(expressionLine, expressionToken, finish, expression);
            appendStatement(expressionStatement);
        } else {
            expect(null);
        }

        endOfLine();
    }

    /**
     * IfStatement :
     *      if ( Expression ) Statement else Statement
     *      if ( Expression ) Statement
     *
     * See 12.5
     *
     * Parse an IF statement.
     */
    private void ifStatement() {
        // Capture IF token.
        var ifLine  = line;
        var ifToken = token;
         // IF tested in caller.
        next();

        expect(LPAREN);
        var test = expression();
        expect(RPAREN);
        var pass = getStatement();

        Block fail = null;
        if (type == ELSE) {
            next();
            fail = getStatement();
        }

        appendStatement(new IfNode(ifLine, ifToken, fail != null ? fail.getFinish() : pass.getFinish(), test, pass, fail));
    }

    /**
     * ... IterationStatement:
     *           ...
     *           for ( Expression[NoIn]?; Expression? ; Expression? ) Statement
     *           for ( var VariableDeclarationList[NoIn]; Expression? ; Expression? ) Statement
     *           for ( LeftHandSideExpression in Expression ) Statement
     *           for ( var VariableDeclaration[NoIn] in Expression ) Statement
     *
     * See 12.6
     *
     * Parse a FOR statement.
     */
    @SuppressWarnings("fallthrough")
    private void forStatement() {
        var forToken = token;
        var forLine = line;
        // start position of this for statement. This is used
        // for sort order for variables declared in the initializer
        // part of this 'for' statement (if any).
        var forStart = Token.descPosition(forToken);
        // When ES6 for-let is enabled we create a container block to capture the LET.
        var outer = newBlock();

        // Create FOR node, capturing FOR token.
        var forNode = new ParserContextLoopNode();
        lc.push(forNode);
        Block body = null;
        Expression init = null;
        JoinPredecessorExpression test = null;
        JoinPredecessorExpression modify = null;
        ForVariableDeclarationListResult varDeclList = null;

        var flags = 0;
        var isForOf = false;

        try {
            // FOR tested in caller.
            next();

            // Nashorn extension: for each expression.
            // iterate property values rather than property names.
            if (!env._no_syntax_extensions && type == IDENT && "each".equals(getValue())) {
                flags |= ForNode.IS_FOR_EACH;
                next();
            }

            expect(LPAREN);

            switch (type) {
                case SEMICOLON -> {}
                case VAR -> {
                    // Var declaration captured in for outer block.
                    varDeclList = variableDeclarationList(type, false, forStart);
                }
                default -> {
                    if (type == LET && lookaheadIsLetDeclaration(true) || type == CONST) {
                        flags |= ForNode.PER_ITERATION_SCOPE;
                        // LET/CONST declaration captured in container block created above.
                        varDeclList = variableDeclarationList(type, false, forStart);
                        break;
                    }
                    if (env._const_as_var && type == CONST) {
                        // Var declaration captured in for outer block.
                        varDeclList = variableDeclarationList(TokenType.VAR, false, forStart);
                        break;
                    }

                    init = expression(unaryExpression(), COMMARIGHT.getPrecedence(), true);
                }
            }

            switch (type) {
                case SEMICOLON: {
                    // for (init; test; modify)
                    if (varDeclList != null) {
                        assert init == null;
                        init = varDeclList.init;
                        // late check for missing assignment, now we know it's a for (init; test; modify) loop
                        if (varDeclList.missingAssignment != null) {
                            throw error(AbstractParser.message("missing.const.assignment", ((IdentNode)varDeclList.missingAssignment).getName()));
                        }
                    }

                    // for each (init; test; modify) is invalid
                    if ((flags & ForNode.IS_FOR_EACH) != 0) {
                        throw error(AbstractParser.message("for.each.without.in"), token);
                    }

                    expect(SEMICOLON);
                    if (type != SEMICOLON) {
                        test = joinPredecessorExpression();
                    }
                    expect(SEMICOLON);
                    if (type != RPAREN) {
                        modify = joinPredecessorExpression();
                    }
                    break;
                }
                case IDENT: {
                    if ("of".equals(getValue())) {
                        isForOf = true;
                        // FALL-THROUGH
                    } else {
                        expect(SEMICOLON); // fail with expected message
                        break;
                    }
                }
                case IN: {
                    flags |= isForOf ? ForNode.IS_FOR_OF : ForNode.IS_FOR_IN;
                    test = new JoinPredecessorExpression();
                    if (varDeclList != null) {
                        // for (var|let|const ForBinding in|of expression)
                        if (varDeclList.secondBinding != null) {
                            // for (var i, j in obj) is invalid
                            throw error(AbstractParser.message("many.vars.in.for.in.loop", isForOf ? "of" : "in"), varDeclList.secondBinding.getToken());
                        }
                        if (varDeclList.declarationWithInitializerToken != 0) {
                            // ES5 legacy: for (var i = AssignmentExpressionNoIn in Expression)
                            throw error(AbstractParser.message("for.in.loop.initializer", isForOf ? "of" : "in"), varDeclList.declarationWithInitializerToken);
                        }
                        init = varDeclList.firstBinding;
                        assert init instanceof IdentNode;
                    } else {
                        // for (expr in obj)
                        assert init != null : "for..in/of init expression can not be null here";

                        // check if initial expression is a valid L-value
                        if (!checkValidLValue(init, isForOf ? "for-of iterator" : "for-in iterator")) {
                            throw error(AbstractParser.message("not.lvalue.for.in.loop", isForOf ? "of" : "in"), init.getToken());
                        }
                    }

                    next();

                    // For-of only allows AssignmentExpression.
                    modify = isForOf ? new JoinPredecessorExpression(assignmentExpression(false)) : joinPredecessorExpression();
                    break;
                }
                default: {
                    expect(SEMICOLON);
                    break;
                }
            }

            expect(RPAREN);

            // Set the for body.
            body = getStatement();
        }
        finally {
            lc.pop(forNode);

            for (var stmt : forNode.getStatements()) {
                assert stmt instanceof VarNode;
                appendStatement(stmt);
            }
            if (body != null) {
                appendStatement(new ForNode(forLine, forToken, body.getFinish(), body, (forNode.getFlags() | flags), init, test, modify));
            }
            if (outer != null) {
                restoreBlock(outer);
                if (body != null) {
                    var statements = new ArrayList<Statement>();
                    for (var stmt : outer.getStatements()) {
                        if (stmt instanceof VarNode && !((VarNode)stmt).isBlockScoped()) {
                            appendStatement(stmt);
                        } else {
                            statements.add(stmt);
                        }
                    }
                    appendStatement(new BlockStatement(forLine, new Block(outer.getToken(), body.getFinish(), statements)));
                }
            }
        }
    }

    private boolean checkValidLValue(Expression init, String contextString) {
        if (init instanceof IdentNode) {
            if (!checkIdentLValue((IdentNode)init)) {
                return false;
            }
            verifyIdent((IdentNode)init, contextString);
            return true;
        }
        return init instanceof AccessNode
            || init instanceof IndexNode;
    }

    @SuppressWarnings("fallthrough")
    private boolean lookaheadIsLetDeclaration(boolean ofContextualKeyword) {
        assert type == LET;
        for (var i = 1;; i++) {
            var t = T(k + i);
            switch (t) {
                case EOL, COMMENT:
                    continue;
                case IDENT:
                    if (ofContextualKeyword && "of".equals(getValue(getToken(k + i)))) {
                        return false;
                    }
                    // FALL-THROUGH
                case LBRACKET, LBRACE:
                    return true;
                default:
                    return false;
            }
        }
    }

    /**
     * ...IterationStatement :
     *           ...
     *           while ( Expression ) Statement
     *           ...
     *
     * See 12.6
     *
     * Parse while statement.
     */
    private void whileStatement() {
        // Capture WHILE token.
        var whileToken = token;
        var whileLine = line;
        // WHILE tested in caller.
        next();

        var whileNode = new ParserContextLoopNode();
        lc.push(whileNode);

        JoinPredecessorExpression test = null;
        Block body = null;

        try {
            expect(LPAREN);
            test = joinPredecessorExpression();
            expect(RPAREN);
            body = getStatement();
        } finally {
            lc.pop(whileNode);
        }

        if (body != null) {
            appendStatement(new WhileNode(whileLine, whileToken, body.getFinish(), false, test, body));
        }
    }

    /**
     * ...IterationStatement :
     *           ...
     *           do Statement while( Expression ) ;
     *           ...
     *
     * See 12.6
     *
     * Parse DO WHILE statement.
     */
    private void doStatement() {
        // Capture DO token.
        var doToken = token;
        var doLine = 0;
        // DO tested in the caller.
        next();

        var doWhileNode = new ParserContextLoopNode();
        lc.push(doWhileNode);

        Block body = null;
        JoinPredecessorExpression test = null;

        try {
           // Get DO body.
            body = getStatement();

            expect(WHILE);
            expect(LPAREN);
            doLine = line;
            test = joinPredecessorExpression();
            expect(RPAREN);

            if (type == SEMICOLON) {
                endOfLine();
            }
        } finally {
            lc.pop(doWhileNode);
        }

        appendStatement(new WhileNode(doLine, doToken, finish, true, test, body));
    }

    /**
     * ContinueStatement :
     *      continue Identifier? ; // [no LineTerminator here]
     *
     * See 12.7
     *
     * Parse CONTINUE statement.
     */
    private void continueStatement() {
        // Capture CONTINUE token.
        var  continueLine  = line;
        var continueToken = token;
        // CONTINUE tested in caller.
        nextOrEOL();

        ParserContextLabelNode labelNode = null;

        // SEMICOLON or label.
        switch (type) {
            case RBRACE, SEMICOLON, EOL, EOF -> {}

            default ->  {
                var ident = getIdent();
                labelNode = lc.findLabel(ident.getName());

                if (labelNode == null) {
                    throw error(AbstractParser.message("undefined.label", ident.getName()), ident.getToken());
                }
            }
        }

        var labelName = labelNode == null ? null : labelNode.getLabelName();
        var targetNode = lc.getContinueTo(labelName);

        if (targetNode == null) {
            throw error(AbstractParser.message("illegal.continue.stmt"), continueToken);
        }

        endOfLine();

        // Construct and add CONTINUE node.
        appendStatement(new ContinueNode(continueLine, continueToken, finish, labelName));
    }

    /**
     * BreakStatement :
     *      break Identifier? ; // [no LineTerminator here]
     *
     * See 12.8
     *
     */
    private void breakStatement() {
        // Capture BREAK token.
        var breakLine  = line;
        var breakToken = token;
        // BREAK tested in caller.
        nextOrEOL();

        ParserContextLabelNode labelNode = null;

        // SEMICOLON or label.
        switch (type) {
            case RBRACE, SEMICOLON, EOL, EOF -> {}

            default ->  {
                var ident = getIdent();
                labelNode = lc.findLabel(ident.getName());

                if (labelNode == null) {
                    throw error(AbstractParser.message("undefined.label", ident.getName()), ident.getToken());
                }
            }
        }

        // either an explicit label - then get its node or just a "break" - get first breakable
        // targetNode is what we are breaking out from.
        var labelName = labelNode == null ? null : labelNode.getLabelName();
        var targetNode = lc.getBreakable(labelName);

        if (targetNode instanceof ParserContextBlockNode) {
            targetNode.setFlag(Block.IS_BREAKABLE);
        }

        if (targetNode == null) {
            throw error(AbstractParser.message("illegal.break.stmt"), breakToken);
        }

        endOfLine();

        // Construct and add BREAK node.
        appendStatement(new BreakNode(breakLine, breakToken, finish, labelName));
    }

    /**
     * ReturnStatement :
     *      return Expression? ; // [no LineTerminator here]
     *
     * See 12.9
     *
     * Parse RETURN statement.
     */
    private void returnStatement() {
        // check for return outside function
        if (lc.getCurrentFunction().getKind() == FunctionNode.Kind.SCRIPT) {
            throw error(AbstractParser.message("invalid.return"));
        }

        // Capture RETURN token.
        var returnLine = line;
        var returnToken = token;
        // RETURN tested in caller.
        nextOrEOL();

        Expression expression = null;

        // SEMICOLON or expression.
        switch (type) {
            case RBRACE, SEMICOLON, EOL, EOF -> {}
            default ->  expression = expression();
        }

        endOfLine();

        // Construct and add RETURN node.
        appendStatement(new ReturnNode(returnLine, returnToken, finish, expression));
    }

    private static UnaryNode newUndefinedLiteral(long token, int finish) {
        return new UnaryNode(Token.recast(token, VOID), LiteralNode.newInstance(token, finish, 0));
    }


    private ParserContextFunctionNode mainScript() {
        var fn = lc.getCurrentFunction();
        return fn != null && fn.getKind() == FunctionNode.Kind.SCRIPT ? fn : null;
    }

    /**
     * ImportStatement :
     *      import Identifier ; // [no LineTerminator here]
     */
    private String importStatement() {
        // IMPORT tested in caller.
        next();

        var importName = new StringBuilder();
        last = PERIOD; // prep for loop
        do {
            if (type == IDENT && last == PERIOD) {
                importName.append(getValue());
            } else if (type == PERIOD && last == IDENT) {
                importName.append('.');
            } else if (type == MUL && last == PERIOD) {
                importName.append('*');
                nextOrEOL();
                break;
            } else {
                break;
            }
            nextOrEOL();
        } while (finish == start);

        if (type != EOL && type != SEMICOLON) {
            throw error(AbstractParser.message("expected", ";", type.toString()));
        }

        endOfLine();

        return importName.toString();
    }

    /**
     * BeanExpression :
     *      Bean ( ElementList )? ObjectLiteral
     *
     * Parse BEAN expression.
     */
    private ObjectNode beanExpression(String ident) {
        // BEAN tested in caller.
        next();

        var arguments = type == LPAREN ? beanArguments() : null;

        // Prepare to accumulate elements.
        var elements = new ArrayList<PropertyNode>();
        var beanObject = type == LBRACE ? objectLiteral(elements) : new ObjectNode(token, finish, elements);

        endOfLine();

        return Beans.setInfo(beanObject,elements,ident,arguments);
    }

    private LiteralNode<Expression[]> beanArguments() {
        // Capture LPAREN token.
        var argsToken = token;
        // LPAREN tested in caller.
        next();

        // Prepare to accumulate elements.
        var elements = new ArrayList<Expression>();

        var commaSeen = true;
        loop: for (;;) {
            switch (type) {
                case RPAREN -> {
                    next();
                    break loop;
                }
                case COMMARIGHT -> {
                    if (commaSeen) {
                        throw error(AbstractParser.message("expected.literal", ","));
                    }
                    next();
                    commaSeen = true;
                }
                default -> {
                    if (!commaSeen) {
                        throw error(AbstractParser.message("expected.comma", type.getNameOrType()));
                    }
                    commaSeen = false;
                    // Add expression element.
                    var expression = assignmentExpression(false);
                    if (expression != null) {
                        elements.add(expression);
                    } else {
                        expect(RPAREN);
                    }
                }
            }
        }

        return LiteralNode.newInstance(argsToken, finish, elements, false);
    }

    /**
     * SwitchStatement :
     *      switch ( Expression ) CaseBlock
     *
     * CaseBlock :
     *      { CaseClauses? }
     *      { CaseClauses? DefaultClause CaseClauses }
     *
     * CaseClauses :
     *      CaseClause
     *      CaseClauses CaseClause
     *
     * CaseClause :
     *      case Expression : StatementList?
     *
     * DefaultClause :
     *      default : StatementList?
     *
     * See 12.11
     *
     * Parse SWITCH statement.
     */
    private void switchStatement() {
        var switchLine = line;
        var switchToken = token;

        // Block to capture variables declared inside the switch statement.
        var switchBlock = newBlock();

        // SWITCH tested in caller.
        next();

        // Create and add switch statement.
        var switchNode = new ParserContextSwitchNode();
        lc.push(switchNode);

        CaseNode defaultCase = null;
        // Prepare to accumulate cases.
        var cases = new ArrayList<CaseNode>();

        Expression expression = null;

        try {
            expect(LPAREN);
            expression = expression();
            expect(RPAREN);

            expect(LBRACE);

            while (type != RBRACE) {
                // Prepare for next case.
                Expression caseExpression = null;
                var caseToken = token;

                switch (type) {
                    case CASE -> {
                        next();
                        caseExpression = expression();
                    }
                    case DEFAULT -> {
                        if (defaultCase != null) {
                            throw error(AbstractParser.message("duplicate.default.in.switch"));
                        }
                        next();
                    }
                    default -> {
                        // Force an error.
                        expect(CASE);
                    }
                }

                expect(COLON);

                // Get CASE body.
                var statements = getBlock(false); // TODO: List<Statement> statements = caseStatementList();
                var caseNode = new CaseNode(caseToken, finish, caseExpression, statements);

                if (caseExpression == null) {
                    defaultCase = caseNode;
                }

                cases.add(caseNode);
            }

            next();
        }
        finally {
            lc.pop(switchNode);
            restoreBlock(switchBlock);
        }

        var switchStatement = new SwitchNode(switchLine, switchToken, finish, expression, cases, defaultCase);
        appendStatement(new BlockStatement(switchLine, new Block(switchToken, finish, switchBlock.getFlags() | Block.IS_SYNTHETIC | Block.IS_SWITCH_BLOCK, switchStatement)));
    }

    /**
     * LabelledStatement :
     *      Identifier : Statement
     *
     * See 12.12
     *
     * Parse label statement.
     */
    private void labelStatement() {
        // Capture label token.
        var labelToken = token;
        // Get label ident.
        var ident = getIdent();

        expect(COLON);

        if (lc.findLabel(ident.getName()) != null) {
            throw error(AbstractParser.message("duplicate.label", ident.getName()), labelToken);
        }

        var labelNode = new ParserContextLabelNode(ident.getName());
        Block body = null;
        try {
            lc.push(labelNode);
            body = getStatement(true);
        } finally {
            assert lc.peek() instanceof ParserContextLabelNode;
            lc.pop(labelNode);
        }

        appendStatement(new LabelNode(line, labelToken, finish, ident.getName(), body));
    }

    /**
     * ThrowStatement :
     *      throw Expression ; // [no LineTerminator here]
     *
     * See 12.13
     *
     * Parse throw statement.
     */
    private void throwStatement() {
        // Capture THROW token.
        var  throwLine  = line;
        var throwToken = token;
        // THROW tested in caller.
        nextOrEOL();

        Expression expression = null;

        // SEMICOLON or expression.
        switch (type) {
            case RBRACE, SEMICOLON, EOL -> {}
            default ->  expression = expression();
        }

        if (expression == null) {
            throw error(AbstractParser.message("expected.operand", type.getNameOrType()));
        }

        endOfLine();

        appendStatement(new ThrowNode(throwLine, throwToken, finish, expression, false));
    }

    /**
     * TryStatement :
     *      try Block Catch
     *      try Block Finally
     *      try Block Catch Finally
     *
     * Catch :
     *      catch( Identifier if Expression ) Block
     *      catch( Identifier ) Block
     *
     * Finally :
     *      finally Block
     *
     * See 12.14
     *
     * Parse TRY statement.
     */
    private void tryStatement() {
        // Capture TRY token.
        var  tryLine  = line;
        var tryToken = token;
        // TRY tested in caller.
        next();

        // Container block needed to act as target for labeled break statements
        var startLine = line;
        var outer = newBlock();
        // Create try.

        try {
            var tryBody = getBlock(true);
            var catchBlocks = new ArrayList<Block>();

            while (type == CATCH) {
                var catchLine = line;
                var catchToken = token;
                next();
                expect(LPAREN);

                // ES6 catch parameter can be a BindingIdentifier or a BindingPattern
                // http://www.ecma-international.org/ecma-262/6.0/
                var contextString = "catch argument";
                var exception = bindingIdentifierOrPattern(contextString);
                // ECMA 12.4.1 strict mode restrictions
                verifyIdent((IdentNode) exception, "catch argument");

                // Nashorn extension: catch clause can have optional condition.
                // So, a single try can have more than one catch clause each with it's own condition.
                Expression ifExpression;
                if (!env._no_syntax_extensions && type == IF) {
                    next();
                    // Get the exception condition.
                    ifExpression = expression();
                } else {
                    ifExpression = null;
                }

                expect(RPAREN);

                var catchBlock = newBlock();
                try {
                    // Get CATCH body.
                    var catchBody = getBlock(true);
                    var catchNode = new CatchNode(catchLine, catchToken, finish, exception, ifExpression, catchBody, false);
                    appendStatement(catchNode);
                } finally {
                    restoreBlock(catchBlock);
                    catchBlocks.add(new Block(catchBlock.getToken(), finish, catchBlock.getFlags() | Block.IS_SYNTHETIC, catchBlock.getStatements()));
                }

                // If unconditional catch then should to be the end.
                if (ifExpression == null) {
                    break;
                }
            }

            // Prepare to capture finally statement.
            Block finallyStatements = null;

            if (type == FINALLY) {
                next();
                finallyStatements = getBlock(true);
            }

            // Need at least one catch or a finally.
            if (catchBlocks.isEmpty() && finallyStatements == null) {
                throw error(AbstractParser.message("missing.catch.or.finally"), tryToken);
            }

            var tryNode = new TryNode(tryLine, tryToken, finish, tryBody, catchBlocks, finallyStatements);
            // Add try.
            assert lc.peek() == outer;
            appendStatement(tryNode);
        } finally {
            restoreBlock(outer);
        }

        appendStatement(new BlockStatement(startLine, new Block(tryToken, finish, outer.getFlags() | Block.IS_SYNTHETIC, outer.getStatements())));
    }

    /**
     * DebuggerStatement :
     *      debugger ;
     *
     * See 12.15
     *
     * Parse debugger statement.
     */
    private void  debuggerStatement() {
        // Capture DEBUGGER token.
        var debuggerLine = line;
        var debuggerToken = token;
        // DEBUGGER tested in caller.
        next();
        endOfLine();
        appendStatement(new DebuggerNode(debuggerLine, debuggerToken, finish));
    }

    /**
     * PrimaryExpression :
     *      this
     *      IdentifierReference
     *      Literal
     *      ArrayLiteral
     *      ObjectLiteral
     *      RegularExpressionLiteral
     *      TemplateLiteral
     *      CoverParenthesizedExpressionAndArrowParameterList
     *
     * CoverParenthesizedExpressionAndArrowParameterList :
     *      ( Expression )
     *      ( )
     *      ( ... BindingIdentifier )
     *      ( Expression , ... BindingIdentifier )
     *
     * Parse primary expression.
     * @return Expression node.
     */
    @SuppressWarnings("fallthrough")
    private Expression primaryExpression() {
        // Capture first token.
        var primaryLine  = line;
        var primaryToken = token;

        switch (type) {
            case THIS -> {
                var name = type.getName();
                next();
                markThis(lc);
                return new IdentNode(primaryToken, finish, name);
            }
            case IDENT -> {
                var ident = getIdent();
                if (ident == null) {
                    break;
                }
                detectSpecialProperty(ident);
                checkEscapedKeyword(ident);
                return ident;
            }
            case OCTAL_LEGACY -> {
                throw error(AbstractParser.message("strict.no.octal"), token);
            }
            case STRING, ESCSTRING, DECIMAL, HEXADECIMAL, OCTAL, BINARY_NUMBER, FLOATING, REGEX -> {
                return getLiteral();
            }
            case EXECSTRING -> {
                return execString(primaryLine, primaryToken);
            }
            case FALSE -> {
                next();
                return LiteralNode.newInstance(primaryToken, finish, false);
            }
            case TRUE -> {
                next();
                return LiteralNode.newInstance(primaryToken, finish, true);
            }
            case NULL -> {
                next();
                return LiteralNode.newInstance(primaryToken, finish);
            }
            case LBRACKET -> {
                return arrayLiteral();
            }
            case LBRACE -> {
                return objectLiteral();
            }
            case LPAREN -> {
                next();
                if (type == RPAREN) {
                    // ()
                    nextOrEOL();
                    expectDontAdvance(ARROW);
                    return new ExpressionList(primaryToken, finish, Collections.emptyList());
                }
                var expression = expression();
                expect(RPAREN);
                return expression;
            }
            case TEMPLATE, TEMPLATE_HEAD -> {
                return templateLiteral();
            }
            default ->  {
                // In this context some operator tokens mark the start of a literal.
                if (lexer.scanLiteral(primaryToken, type, lineInfoReceiver)) {
                    next();
                    return getLiteral();
                }
            }
        }

        return null;
    }

    /**
     * Convert execString to a call to $EXEC.
     * @param primaryToken Original string token.
     * @return callNode to $EXEC.
     */
    CallNode execString(int primaryLine, long primaryToken) {
        // Synthesize an ident to call $EXEC.
        var execIdent = new IdentNode(primaryToken, finish, ScriptingFunctions.EXEC_NAME);
        // Skip over EXECSTRING.
        next();
        // Set up argument list for call.
        // Skip beginning of edit string expression.
        expect(LBRACE);
        // Add the following expression to arguments.
        var arguments = Collections.singletonList(expression());
        // Skip ending of edit string expression.
        expect(RBRACE);

        return new CallNode(primaryLine, primaryToken, finish, execIdent, arguments, false);
    }

    /**
     * ArrayLiteral :
     *      [ Elision? ]
     *      [ ElementList ]
     *      [ ElementList , Elision? ]
     *      [ expression for (LeftHandExpression in expression) ( (if ( Expression ) )? ]
     *
     * ElementList : Elision? AssignmentExpression
     *      ElementList , Elision? AssignmentExpression
     *
     * Elision :
     *      ,
     *      Elision ,
     *
     * See 12.1.4
     * JavaScript 1.8
     *
     * Parse array literal.
     * @return Expression node.
     */
    private LiteralNode<Expression[]> arrayLiteral() {
        // Capture LBRACKET token.
        var arrayToken = token;
        // LBRACKET tested in caller.
        next();

        // Prepare to accumulate elements.
        var elements = new ArrayList<Expression>();
        // Track elisions.
        var elision = true;

        loop: for (;;) {
            switch (type) {
                case RBRACKET -> {
                    next();
                    break loop;
                }
                case COMMARIGHT -> {
                    next();
                    // If no prior expression
                    if (elision) {
                        elements.add(null);
                    }
                    elision = true;
                }
                default -> {
                    if (!elision) {
                        throw error(AbstractParser.message("expected.comma", type.getNameOrType()));
                    }
                    // Add expression element.
                    var expression = assignmentExpression(false);
                    if (expression != null) {
                        elements.add(expression);
                    } else {
                        expect(RBRACKET);
                    }
                    elision = false;
                }
            }
        }

        return LiteralNode.newInstance(arrayToken, finish, elements, elision);
    }

    /**
     * ObjectLiteral :
     *      { }
     *      { PropertyNameAndValueList } { PropertyNameAndValueList , }
     *
     * PropertyNameAndValueList :
     *      PropertyAssignment
     *      PropertyNameAndValueList , PropertyAssignment
     *
     * See 11.1.5
     *
     * Parse an object literal.
     * @return Expression node.
     */
    private ObjectNode objectLiteral() {
        return objectLiteral(new ArrayList<PropertyNode>());
    }
    private ObjectNode objectLiteral(List<PropertyNode> elements) {
        // Capture LBRACE token.
        var objectToken = token;
        // LBRACE tested in caller.
        next();

        // Object context.
        // Prepare to accumulate elements.
        var map = new HashMap<String, Integer>();

        // Create a block for the object literal.
        var commaSeen = true;
        loop: for (;;) {
            switch (type) {
                case RBRACE -> {
                    next();
                    break loop;
                }
                case COMMARIGHT -> {
                    if (commaSeen) {
                        throw error(AbstractParser.message("expected.property.id", type.getNameOrType()));
                    }
                    next();
                    commaSeen = true;
                }

                default -> {
                    if (!commaSeen) {
                        throw error(AbstractParser.message("expected.comma", type.getNameOrType()));
                    }

                    commaSeen = false;
                    // Get and add the next property.
                    var property = propertyAssignment();

                    if (property.isComputed()) {
                        elements.add(property);
                        break;
                    }

                    var key = property.getKeyName();
                    var existing = map.get(key);

                    if (existing == null) {
                        map.put(key, elements.size());
                        elements.add(property);
                        break;
                    }

                    var existingProperty = elements.get(existing);

                    // ECMA section 11.1.5 Object Initialiser
                    // point # 4 on property assignment production
                    var value = property.getValue();
                    var getter = property.getGetter();
                    var setter = property.getSetter();

                    var prevValue = existingProperty.getValue();
                    var prevGetter = existingProperty.getGetter();
                    var prevSetter = existingProperty.getSetter();

                    if (property.getKey() instanceof IdentNode && ((IdentNode)property.getKey()).isProtoPropertyName() && existingProperty.getKey() instanceof IdentNode && ((IdentNode)existingProperty.getKey()).isProtoPropertyName()) {
                        throw error(AbstractParser.message("multiple.proto.key"), property.getToken());
                    }

                    if (value != null || prevValue != null) {
                        map.put(key, elements.size());
                        elements.add(property);
                    } else if (getter != null) {
                        assert prevGetter != null || prevSetter != null;
                        elements.set(existing, existingProperty.setGetter(getter));
                    } else if (setter != null) {
                        assert prevGetter != null || prevSetter != null;
                        elements.set(existing, existingProperty.setSetter(setter));
                    }
                }
            }
        }

        return new ObjectNode(objectToken, finish, elements);
    }

    /**
     * LiteralPropertyName :
     *      IdentifierName
     *      StringLiteral
     *      NumericLiteral
     *
     * @return PropertyName node
     */
    private PropertyKey literalPropertyName() {
        return switch (type) {
            case IDENT -> getIdent().setIsPropertyName();
            case OCTAL_LEGACY -> throw error(AbstractParser.message("strict.no.octal"), token);
            case STRING, ESCSTRING, DECIMAL, HEXADECIMAL, OCTAL, BINARY_NUMBER, FLOATING -> getLiteral();
            default -> getIdentifierName().setIsPropertyName();
        };
    }

    /**
     * ComputedPropertyName :
     *      AssignmentExpression
     *
     * @return PropertyName node
     */
    private Expression computedPropertyName() {
        expect(LBRACKET);
        var expression = assignmentExpression(false);
        expect(RBRACKET);
        return expression;
    }

    /**
     * PropertyName :
     *      LiteralPropertyName
     *      ComputedPropertyName
     *
     * @return PropertyName node
     */
    private Expression propertyName() {
        if (type == LBRACKET) {
            return computedPropertyName();
        } else {
            return (Expression)literalPropertyName();
        }
    }

    /**
     * PropertyAssignment :
     *      PropertyName : AssignmentExpression
     *      get PropertyName ( ) { FunctionBody }
     *      set PropertyName ( PropertySetParameterList ) { FunctionBody }
     *
     * PropertySetParameterList :
     *      Identifier
     *
     * PropertyName :
     *      IdentifierName
     *      StringLiteral
     *      NumericLiteral
     *
     * See 11.1.5
     *
     * Parse an object literal property.
     * @return Property or reference node.
     */
    private PropertyNode propertyAssignment() {
        // Capture firstToken.
        var propertyToken = token;
        var functionLine = line;

        Expression propertyName;
        boolean isIdentifier;

        var computed = type == LBRACKET;
        if (type == IDENT) {
            // Get IDENT.
            var ident = (String)expectValue(IDENT);

            if (type != COLON && type != LPAREN) {
                var getSetToken = propertyToken;

                switch (ident) {
                    // default -> {}
                    case GET_NAME -> {
                        var getter = propertyGetterFunction(getSetToken, functionLine);
                        return new PropertyNode(propertyToken, finish, getter.key, null, getter.functionNode, null, false, getter.computed);
                    }
                    case SET_NAME -> {
                        var setter = propertySetterFunction(getSetToken, functionLine);
                        return new PropertyNode(propertyToken, finish, setter.key, null, null, setter.functionNode, false, setter.computed);
                    }
                }
            }

            isIdentifier = true;
            var identNode = createIdentNode(propertyToken, finish, ident).setIsPropertyName();
            if (type == COLON && ident.equals("__proto__")) {
                identNode = identNode.setIsProtoPropertyName();
            }
            propertyName = identNode;
        } else {
            isIdentifier = false;
            propertyName = propertyName();
        }

        Expression propertyValue;

        if (type == LPAREN) {
            propertyValue = propertyMethodFunction(propertyName, propertyToken, functionLine, FunctionNode.ES6_IS_METHOD, computed).functionNode;
        }
        else if (isIdentifier && (type == COMMARIGHT || type == RBRACE || type == ASSIGN)) {
            propertyValue = createIdentNode(propertyToken, finish, ((IdentNode) propertyName).getPropertyName());
            if (type == ASSIGN) {
                var assignToken = token;
                next();
                var rhs = assignmentExpression(false);
                propertyValue = verifyAssignment(assignToken, propertyValue, rhs);
            }
        } else {
            expect(COLON);

            defaultNames.push(propertyName);
            try {
                var ident = beanType();
                propertyValue = ident != null ? beanExpression(ident) : assignmentExpression(false);
            } finally {
                defaultNames.pop();
            }
        }

        return new PropertyNode(propertyToken, finish, propertyName, propertyValue, null, null, false, computed);
    }

    private String beanType() {
        if (type == IDENT) {
            var ident = (String)getValue();
            if (Beans.isImported(ident)) {
                return ident;
            }
        }
        return null;
    }

    private PropertyFunction propertyGetterFunction(long getSetToken, int functionLine) {
        return propertyGetterFunction(getSetToken, functionLine, FunctionNode.ES6_IS_METHOD);
    }

    private PropertyFunction propertyGetterFunction(long getSetToken, int functionLine, int flags) {
        var computed = type == LBRACKET;
        var propertyName = propertyName();
        var getterName = propertyName instanceof PropertyKey ? ((PropertyKey) propertyName).getPropertyName() : getDefaultValidFunctionName(functionLine, false);
        var getNameNode = createIdentNode((propertyName).getToken(), finish, NameCodec.encode("get " + getterName));
        expect(LPAREN);
        expect(RPAREN);

        var functionNode = createParserContextFunctionNode(getNameNode, getSetToken, FunctionNode.Kind.GETTER, functionLine, Collections.<IdentNode>emptyList());
        functionNode.setFlag(flags);
        if (computed) {
            functionNode.setFlag(FunctionNode.IS_ANONYMOUS);
        }
        lc.push(functionNode);

        Block functionBody;
        try {
            functionBody = functionBody(functionNode);
        } finally {
            lc.pop(functionNode);
        }

        var function = createFunctionNode(functionNode, getSetToken, getNameNode, Collections.<IdentNode>emptyList(), FunctionNode.Kind.GETTER, functionLine, functionBody);
        return new PropertyFunction(propertyName, function, computed);
    }

    private PropertyFunction propertySetterFunction(long getSetToken, int functionLine) {
        return propertySetterFunction(getSetToken, functionLine, FunctionNode.ES6_IS_METHOD);
    }

    private PropertyFunction propertySetterFunction(long getSetToken, int functionLine, int flags) {
        var computed = type == LBRACKET;
        var propertyName = propertyName();
        var setterName = propertyName instanceof PropertyKey ? ((PropertyKey) propertyName).getPropertyName() : getDefaultValidFunctionName(functionLine, false);
        var setNameNode = createIdentNode((propertyName).getToken(), finish, NameCodec.encode("set " + setterName));

        expect(LPAREN);
        // be sloppy and allow missing setter parameter even though spec does not permit it!
        IdentNode argIdent;
        if (isBindingIdentifier()) {
            argIdent = getIdent();
            verifyIdent(argIdent, "setter argument");
        } else {
            argIdent = null;
        }

        expect(RPAREN);
        var parameters = new ArrayList<IdentNode>();
        if (argIdent != null) {
            parameters.add(argIdent);
        }

        var functionNode = createParserContextFunctionNode(setNameNode, getSetToken, FunctionNode.Kind.SETTER, functionLine, parameters);
        functionNode.setFlag(flags);
        if (computed) {
            functionNode.setFlag(FunctionNode.IS_ANONYMOUS);
        }
        lc.push(functionNode);

        Block functionBody;
        try {
            functionBody = functionBody(functionNode);
        } finally {
            lc.pop(functionNode);
        }

        var function = createFunctionNode(functionNode, getSetToken, setNameNode, parameters, FunctionNode.Kind.SETTER, functionLine, functionBody);
        return new PropertyFunction(propertyName, function, computed);
    }

    private PropertyFunction propertyMethodFunction(Expression key, long methodToken, int methodLine, int flags, boolean computed) {
        var methodName = key instanceof PropertyKey ? ((PropertyKey) key).getPropertyName() : getDefaultValidFunctionName(methodLine, false);
        var methodNameNode = createIdentNode(((Node)key).getToken(), finish, methodName);

        var functionKind = FunctionNode.Kind.NORMAL;
        var functionNode = createParserContextFunctionNode(methodNameNode, methodToken, functionKind, methodLine, null);
        functionNode.setFlag(flags);
        if (computed) {
            functionNode.setFlag(FunctionNode.IS_ANONYMOUS);
        }
        lc.push(functionNode);

        try {
            var parameterBlock = newBlock();
            List<IdentNode> parameters;
            try {
                expect(LPAREN);
                parameters = formalParameterList();
                functionNode.setParameters(parameters);
                expect(RPAREN);
            } finally {
                restoreBlock(parameterBlock);
            }

            var functionBody = functionBody(functionNode);
            functionBody = maybeWrapBodyInParameterBlock(functionBody, parameterBlock);

            var function = createFunctionNode(functionNode, methodToken, methodNameNode, parameters, functionKind, methodLine, functionBody);
            return new PropertyFunction(key, function, computed);
        } finally {
            lc.pop(functionNode);
        }
    }

    private static class PropertyFunction {
        final Expression key;
        final FunctionNode functionNode;
        final boolean computed;

        PropertyFunction(Expression key, FunctionNode function, boolean computed) {
            this.key = key;
            this.functionNode = function;
            this.computed = computed;
        }
    }

    /**
     * LeftHandSideExpression :
     *      NewExpression
     *      CallExpression
     *
     * CallExpression :
     *      MemberExpression Arguments
     *      SuperCall
     *      CallExpression Arguments
     *      CallExpression [ Expression ]
     *      CallExpression . IdentifierName
     *
     * SuperCall :
     *      super Arguments
     *
     * See 11.2
     *
     * Parse left hand side expression.
     * @return Expression node.
     */
    private Expression leftHandSideExpression() {
        var callLine = line;
        var callToken = token;

        var lhs = memberExpression();

        if (type == LPAREN) {
            var arguments = optimizeList(argumentList());

            // Catch special functions.
            if (lhs instanceof IdentNode) {
                detectSpecialFunction((IdentNode)lhs);
                checkEscapedKeyword((IdentNode)lhs);
            }

            lhs = new CallNode(callLine, callToken, finish, lhs, arguments, false);
        }

        loop: for (;;) {
            // Capture token.
            callLine  = line;
            callToken = token;

            switch (type) {
                case LPAREN ->  {
                    // Get NEW or FUNCTION arguments.
                    var arguments = optimizeList(argumentList());
                    // Create call node.
                    lhs = new CallNode(callLine, callToken, finish, lhs, arguments, false);
                }
                case LBRACKET ->  {
                    next();
                    // Get array index.
                    var rhs = expression();
                    expect(RBRACKET);
                    // Create indexing node.
                    lhs = new IndexNode(callToken, finish, lhs, rhs);
                }
                case PERIOD ->  {
                    next();
                    var property = getIdentifierName();
                    // Create property access node.
                    lhs = new AccessNode(callToken, finish, lhs, property.getName());
                }
                case TEMPLATE, TEMPLATE_HEAD -> {
                    // tagged template literal
                    var arguments = templateLiteralArgumentList();
                    // Create call node.
                    lhs = new CallNode(callLine, callToken, finish, lhs, arguments, false);
                }
                default ->  {
                    break loop;
                }
            }
        }

        return lhs;
    }

    /**
     * NewExpression :
     *      MemberExpression
     *      new NewExpression
     *
     * See 11.2
     *
     * Parse new expression.
     * @return Expression node.
     */
    private Expression newExpression() {
        var newToken = token;
        // NEW is tested in caller.
        next();

        if (type == PERIOD) {
            next();
            if (type == IDENT && "target".equals(getValue())) {
                if (lc.getCurrentFunction().isProgram()) {
                    throw error(AbstractParser.message("new.target.in.function"), token);
                }
                next();
                markNewTarget(lc);
                return new IdentNode(newToken, finish, "new.target");
            } else {
                throw error(AbstractParser.message("expected.target"), token);
            }
        }

        // Get function base.
        var callLine = line;
        var constructor = memberExpression();
        if (constructor == null) {
            return null;
        }
        // Get arguments.
        ArrayList<Expression> arguments;

        // Allow for missing arguments.
        if (type == LPAREN) {
            arguments = argumentList();
        } else {
            arguments = new ArrayList<>();
        }

        // Nashorn extension: This is to support the following interface implementation
        // syntax:
        //
        //     var r = new java.lang.Runnable() {
        //         run: function() { println("run"); }
        //     };
        //
        // The object literal following the "new Constructor()" expression
        // is passed as an additional (last) argument to the constructor.
        if (!env._no_syntax_extensions && type == LBRACE) {
            arguments.add(objectLiteral());
        }

        var callNode = new CallNode(callLine, constructor.getToken(), finish, constructor, optimizeList(arguments), true);

        return new UnaryNode(newToken, callNode);
    }

    /**
     * MemberExpression :
     *      PrimaryExpression
     *        FunctionExpression
     *      MemberExpression [ Expression ]
     *      MemberExpression . IdentifierName
     *      MemberExpression TemplateLiteral
     *      SuperProperty
     *      MetaProperty
     *      new MemberExpression Arguments
     *
     * SuperProperty :
     *      super [ Expression ]
     *      super . IdentifierName
     *
     * MetaProperty :
     *      NewTarget
     *
     * Parse member expression.
     * @return Expression node.
     */
    private Expression memberExpression() {
        // Prepare to build operation.
        Expression lhs;
        boolean isSuper = false;

        lhs = switch (type) {
                case NEW -> newExpression(); // Get new expression.
                case FUNCTION -> functionExpression(false, false); // Get function expression.
                default -> primaryExpression(); // Get primary expression.
            };

        loop: for (;;) {
            // Capture token.
            final long callToken = token;

            switch (type) {
                case LBRACKET ->  {
                    next();
                    // Get array index.
                    var index = expression();
                    expect(RBRACKET);
                    // Create indexing node.
                    lhs = new IndexNode(callToken, finish, lhs, index);
                    if (isSuper) {
                        isSuper = false;
                        lhs = ((BaseNode) lhs).setIsSuper();
                    }
                }
                case PERIOD ->  {
                    if (lhs == null) {
                        throw error(AbstractParser.message("expected.operand", type.getNameOrType()));
                    }
                    next();
                    var property = getIdentifierName();
                    // Create property access node.
                    lhs = new AccessNode(callToken, finish, lhs, property.getName());
                    if (isSuper) {
                        isSuper = false;
                        lhs = ((BaseNode) lhs).setIsSuper();
                    }
                }
                case TEMPLATE, TEMPLATE_HEAD -> {
                    // tagged template literal
                    var callLine = line;
                    var arguments = templateLiteralArgumentList();
                    lhs = new CallNode(callLine, callToken, finish, lhs, arguments, false);
                }
                default ->  {
                    break loop;
                }
            }
        }

        return lhs;
    }

    /**
     * Arguments :
     *      ( )
     *      ( ArgumentList )
     *
     * ArgumentList :
     *      AssignmentExpression
     *      ... AssignmentExpression
     *      ArgumentList , AssignmentExpression
     *      ArgumentList , ... AssignmentExpression
     *
     * See 11.2
     *
     * Parse function call arguments.
     * @return Argument list.
     */
    private ArrayList<Expression> argumentList() {
        // Prepare to accumulate list of arguments.
        var nodeList = new ArrayList<Expression>();
        // LPAREN tested in caller.
        next();

        // Track commas.
        boolean first = true;

        while (type != RPAREN) {
            // Comma prior to every argument except the first.
            if (!first) {
                expect(COMMARIGHT);
            } else {
                first = false;
            }

            // Get argument expression.
            var expression = assignmentExpression(false);
            nodeList.add(expression);
        }

        expect(RPAREN);
        return nodeList;
    }

    private static <T> List<T> optimizeList(ArrayList<T> list) {
        switch(list.size()) {
            case 0 -> {
                return Collections.emptyList();
            }
            case 1 -> {
                return Collections.singletonList(list.get(0));
            }
            default -> {
                list.trimToSize();
                return list;
            }
        }
    }

    /**
     * FunctionDeclaration :
     *      function Identifier ( FormalParameterList? ) { FunctionBody }
     *
     * FunctionExpression :
     *      function Identifier? ( FormalParameterList? ) { FunctionBody }
     *
     * See 13
     *
     * Parse function declaration.
     * @param isStatement True if for is a statement.
     *
     * @return Expression node.
     */
    private Expression functionExpression(boolean isStatement, boolean topLevel) {
        var functionToken = token;
        var functionLine = line;
        // FUNCTION is tested in caller.
        assert type == FUNCTION;
        next();

        IdentNode name = null;

        if (isBindingIdentifier()) {
            name = getIdent();
            verifyIdent(name, "function name");
        } else if (isStatement) {
            // Nashorn extension: anonymous function statements.
            // Do not allow anonymous function statement if extensions are now allowed.
            // But if we are reparsing then anon function statement is possible - because it was used as function expression in surrounding code.
            if (env._no_syntax_extensions && reparsedFunction == null) {
                expect(IDENT);
            }
        }

        // name is null, generate anonymous name
        var isAnonymous = false;
        if (name == null) {
            var tmpName = getDefaultValidFunctionName(functionLine, isStatement);
            name = new IdentNode(functionToken, Token.descPosition(functionToken), tmpName);
            isAnonymous = true;
        }

        var functionKind = FunctionNode.Kind.NORMAL;
        List<IdentNode> parameters = Collections.emptyList();
        var functionNode = createParserContextFunctionNode(name, functionToken, functionKind, functionLine, parameters);
        lc.push(functionNode);

        Block functionBody = null;
        // Hide the current default name across function boundaries.
        // E.g. "x3 = function x1() { function() {}}"
        // If we didn't hide the current default name, then the innermost anonymous function would receive "x3".
        hideDefaultName();
        try {
            var parameterBlock = newBlock();
            try {
                expect(LPAREN);
                parameters = formalParameterList();
                functionNode.setParameters(parameters);
                expect(RPAREN);
            } finally {
                restoreBlock(parameterBlock);
            }

            functionBody = functionBody(functionNode);
            functionBody = maybeWrapBodyInParameterBlock(functionBody, parameterBlock);
        } finally {
            defaultNames.pop();
            lc.pop(functionNode);
        }

        if (isStatement) {
            functionNode.setFlag(FunctionNode.IS_DECLARED);
            if (isArguments(name)) {
               lc.getCurrentFunction().setFlag(FunctionNode.DEFINES_ARGUMENTS);
            }
        }

        if (isAnonymous) {
            functionNode.setFlag(FunctionNode.IS_ANONYMOUS);
        }

        verifyParameterList(parameters, functionNode);

        var function = createFunctionNode(functionNode, functionToken, name, parameters, functionKind, functionLine, functionBody);

        if (isStatement) {
            if (isAnonymous) {
                appendStatement(new ExpressionStatement(functionLine, functionToken, finish, function));
                return function;
            }

            // mark ES6 block functions as lexically scoped
            var varFlags = topLevel ? 0 : VarNode.IS_LET;
            var varNode  = new VarNode(functionLine, functionToken, finish, name, function, varFlags);
            if (topLevel) {
                functionDeclarations.add(varNode);
            } else {
                prependStatement(varNode); // Hoist to beginning of current block
            }
        }

        return function;
    }

    private void verifyParameterList(List<IdentNode> parameters, ParserContextFunctionNode functionNode) {
        var duplicateParameter = functionNode.getDuplicateParameterBinding();
        if (duplicateParameter != null) {
            if (functionNode.getKind() == FunctionNode.Kind.ARROW || !functionNode.isSimpleParameterList()) {
                throw error(AbstractParser.message("strict.param.redefinition", duplicateParameter.getName()), duplicateParameter.getToken());
            }

            var arity = parameters.size();
            var parametersSet = new HashSet<String>(arity);

            for (var i = arity - 1; i >= 0; i--) {
                var parameter = parameters.get(i);
                var parameterName = parameter.getName();

                if (parametersSet.contains(parameterName)) {
                    // redefinition of parameter name
                    parameterName = functionNode.uniqueName(parameterName);
                    var parameterToken = parameter.getToken();
                    parameters.set(i, new IdentNode(parameterToken, Token.descPosition(parameterToken), functionNode.uniqueName(parameterName)));
                }
                parametersSet.add(parameterName);
            }
        }
    }

    private static Block maybeWrapBodyInParameterBlock(Block functionBody, ParserContextBlockNode parameterBlock) {
        assert functionBody.isFunctionBody();
        if (!parameterBlock.getStatements().isEmpty()) {
            parameterBlock.appendStatement(new BlockStatement(functionBody));
            return new Block(parameterBlock.getToken(), functionBody.getFinish(), (functionBody.getFlags() | Block.IS_PARAMETER_BLOCK) & ~Block.IS_BODY, parameterBlock.getStatements());
        }
        return functionBody;
    }

    private String getDefaultValidFunctionName(int functionLine, boolean isStatement) {
        var defaultFunctionName = getDefaultFunctionName();
        if (isValidIdentifier(defaultFunctionName)) {
            if (isStatement) {
                // The name will be used as the LHS of a symbol assignment.
                // We add the anonymous function prefix to ensure that it can't clash with another variable.
                return ANON_FUNCTION_PREFIX.symbolName() + defaultFunctionName;
            }
            return defaultFunctionName;
        }
        return ANON_FUNCTION_PREFIX.symbolName() + functionLine;
    }

    private static boolean isValidIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (var i = 1; i < name.length(); ++i) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String getDefaultFunctionName() {
        if (!defaultNames.isEmpty()) {
            var nameExpr = defaultNames.peek();
            if (nameExpr instanceof PropertyKey) {
                markDefaultNameUsed();
                return ((PropertyKey)nameExpr).getPropertyName();
            } else if (nameExpr instanceof AccessNode) {
                markDefaultNameUsed();
                return ((AccessNode)nameExpr).getProperty();
            }
        }
        return null;
    }

    private void markDefaultNameUsed() {
        defaultNames.pop();
        hideDefaultName();
    }

    private void hideDefaultName() {
        // Can be any value as long as getDefaultFunctionName doesn't recognize it as something it can extract a value from.
        // Can't be null
        defaultNames.push("");
    }

    /**
     * FormalParameterList :
     *      Identifier
     *      FormalParameterList , Identifier
     *
     * See 13
     *
     * Parse function parameter list.
     * @return List of parameter nodes.
     */
    private List<IdentNode> formalParameterList() {
        return formalParameterList(RPAREN);
    }

    /**
     * Same as the other method of the same name
     * - except that the end token type expected is passed as argument to this method.
     *
     * FormalParameterList :
     *      Identifier
     *      FormalParameterList , Identifier
     *
     * See 13
     *
     * Parse function parameter list.
     * @return List of parameter nodes.
     */
    private List<IdentNode> formalParameterList(TokenType endType) {
        // Prepare to gather parameters.
        var parameters = new ArrayList<IdentNode>();
        // Track commas.
        var first = true;

        while (type != endType) {
            // Comma prior to every argument except the first.
            if (!first) {
                expect(COMMARIGHT);
            } else {
                first = false;
            }

            var paramToken = token;
            var paramLine = line;
            var contextString = "function parameter";
            IdentNode ident;
            if (isBindingIdentifier()) {
                ident = bindingIdentifier(contextString);

                if (type == ASSIGN) {
                    next();
                    ident = ident.setIsDefaultParameter();

                    // default parameter
                    var initializer = assignmentExpression(false);

                    var currentFunction = lc.getCurrentFunction();
                    if (currentFunction != null) {
                        if (env._parse_only) {
                            // keep what is seen in source "as is" and save it as parameter expression
                            var assignment = new BinaryNode(Token.recast(paramToken, ASSIGN), ident, initializer);
                            currentFunction.addParameterExpression(ident, assignment);
                        } else {
                            // desugar to: param = (param === undefined) ? initializer : param;
                            // possible alternative: if (param === undefined) param = initializer;
                            var test = new BinaryNode(Token.recast(paramToken, EQ_STRICT), ident, newUndefinedLiteral(paramToken, finish));
                            var value = new TernaryNode(Token.recast(paramToken, TERNARY), test, new JoinPredecessorExpression(initializer), new JoinPredecessorExpression(ident));
                            var assignment = new BinaryNode(Token.recast(paramToken, ASSIGN), ident, value);
                            lc.getFunctionBody(currentFunction).appendStatement(new ExpressionStatement(paramLine, assignment.getToken(), assignment.getFinish(), assignment));
                        }
                    }
                }

                var currentFunction = lc.getCurrentFunction();
                if (currentFunction != null) {
                    currentFunction.addParameterBinding(ident);
                    if (ident.isDefaultParameter()) {
                        currentFunction.setSimpleParameterList(false);
                    }
                }
            } else {
                throw error("Expected a valid binding identifier"); // lvalue
            }
            parameters.add(ident);
        }

        parameters.trimToSize();
        return parameters;
    }

    /**
     * FunctionBody :
     *      SourceElements?
     *
     * See 13
     *
     * Parse function body.
     * @return function node (body.)
     */
    private Block functionBody(ParserContextFunctionNode functionNode) {
        var lastToken = 0L;
        ParserContextBlockNode body = null;
        var bodyToken = token;
        Block functionBody;
        var bodyFinish = 0;

        boolean parseBody;
        Object endParserState = null;
        try {
            // Create a new function block.
            body = newBlock();
            if (env._debug_scopes) {
                // debug scope options forces everything to be in scope
                markEval(lc);
            }
            assert functionNode != null;
            var functionId = functionNode.getId();
            parseBody = reparsedFunction == null || functionId <= reparsedFunction.getFunctionNodeId();
            // Nashorn extension: expression closures
            if ((!env._no_syntax_extensions || functionNode.getKind() == FunctionNode.Kind.ARROW) && type != LBRACE) {
                /*
                 * Example:
                 *
                 * function square(x) x * x;
                 * print(square(3));
                 */

                // just expression as function body
                var expr = assignmentExpression(false);
                lastToken = previousToken;
                functionNode.setLastToken(previousToken);
                assert lc.getCurrentBlock() == lc.getFunctionBody(functionNode);
                // EOL uses length field to store the line number
                var lastFinish = Token.descPosition(lastToken) + (Token.descType(lastToken) == EOL ? 0 : Token.descLength(lastToken));
                // Only create the return node if we aren't skipping nested functions.
                // Note that we aren't skipping parsing of these extended functions; they're considered to be small anyway.
                // Also, they don't end with a single well known token, so it'd be very hard to get correctly (see the note below for reasoning on skipping happening before instead of after RBRACE for details).
                if (parseBody) {
                    functionNode.setFlag(FunctionNode.HAS_EXPRESSION_BODY);
                    var returnNode = new ReturnNode(functionNode.getLineNumber(), expr.getToken(), lastFinish, expr);
                    appendStatement(returnNode);
                }
                // bodyFinish = finish;
            } else {
                expectDontAdvance(LBRACE);
                if (parseBody || !skipFunctionBody(functionNode)) {
                    next();
                    // Gather the function elements.
                    var prevFunctionDecls = functionDeclarations;
                    functionDeclarations = new ArrayList<>();
                    try {
                        sourceElements(0);
                        addFunctionDeclarations(functionNode);
                    } finally {
                        functionDeclarations = prevFunctionDecls;
                    }

                    lastToken = token;
                    if (parseBody) {
                        // Since the lexer can read ahead and lexify some number of tokens in advance and have them buffered in the TokenStream, we need to produce a lexer state as it was just before it lexified RBRACE, and not whatever is its current (quite possibly well read ahead) state.
                        endParserState = new ParserState(Token.descPosition(token), line, linePosition);

                        // NOTE: you might wonder why do we capture/restore parser state before RBRACE instead of after RBRACE; after all, we could skip the below "expect(RBRACE);" if we captured the state after it.
                        // The reason is that RBRACE is a well-known token that we can expect and will never involve us getting into a weird lexer state, and as such is a great reparse point.
                        // Typical example of a weird lexer state after RBRACE would be:
                        //     function this_is_skipped() { ... } "use strict";
                        // because lexer is doing weird off-by-one maneuvers around string literal quotes.
                        // Instead of compensating for the possibility of a string literal (or similar) after RBRACE, we'll rather just restart parsing from this well-known, friendly token instead.
                    }
                }
                bodyFinish = finish;
                functionNode.setLastToken(token);
                expect(RBRACE);
            }
        } finally {
            restoreBlock(body);
        }

        // NOTE: we can only do alterations to the function node after restoreFunctionNode.

        if (parseBody) {
            functionNode.setEndParserState(endParserState);
        } else if (!body.getStatements().isEmpty()){
            // This is to ensure the body is empty when !parseBody but we couldn't skip parsing it (see skipFunctionBody() for possible reasons).
            // While it is not strictly necessary for correctness to enforce empty bodies in nested functions that were supposed to be skipped, we do assert it as an invariant in few places in the compiler pipeline, so for consistency's sake we'll throw away nested bodies early if we were supposed to skip 'em.
            body.setStatements(Collections.<Statement>emptyList());
        }

        if (reparsedFunction != null) {
            // We restore the flags stored in the function's ScriptFunctionData that we got when we first eagerly parsed the code.
            // We're doing it because some flags would be set based on the content of the function, or even content of its nested functions, most of which are normally skipped during an on-demand compilation.
            var data = reparsedFunction.getScriptFunctionData(functionNode.getId());
            if (data != null) {
                // Data can be null if when we originally parsed the file, we removed the function declaration as it was dead code.
                functionNode.setFlag(data.getFunctionFlags());
                // This compensates for missing markEval() in case the function contains an inner function that contains eval(), that now we didn't discover since we skipped the inner function.
                if (functionNode.hasNestedEval()) {
                    assert functionNode.hasScopeBlock();
                    body.setFlag(Block.NEEDS_SCOPE);
                }
            }
        }
        functionBody = new Block(bodyToken, bodyFinish, body.getFlags() | Block.IS_BODY, body.getStatements());
        return functionBody;
    }

    private boolean skipFunctionBody(ParserContextFunctionNode functionNode) {
        if (reparsedFunction == null) {
            // Not reparsing, so don't skip any function body.
            return false;
        }
        // Skip to the RBRACE of this function, and continue parsing from there.
        var data = reparsedFunction.getScriptFunctionData(functionNode.getId());
        if (data == null) {
            // Nested function is not known to the reparsed function.
            // This can happen if the FunctionNode was in dead code that was removed.
            // Both FoldConstants and Lower prune dead code.
            // In that case, the FunctionNode was dropped before a RecompilableScriptFunctionData could've been created for it.
            return false;
        }
        var parserState = (ParserState)data.getEndParserState();
        assert parserState != null;

        if (k < stream.last() && start < parserState.position && parserState.position <= Token.descPosition(stream.get(stream.last()))) {
            // RBRACE is already in the token stream, so fast forward to it
            for (; k < stream.last(); k++) {
                var nextToken = stream.get(k + 1);
                if (Token.descPosition(nextToken) == parserState.position && Token.descType(nextToken) == RBRACE) {
                    token = stream.get(k);
                    type = Token.descType(token);
                    next();
                    assert type == RBRACE && start == parserState.position;
                    return true;
                }
            }
        }

        stream.reset();
        lexer = parserState.createLexer(source, lexer, stream, scripting && !env._no_syntax_extensions);
        line = parserState.line;
        linePosition = parserState.linePosition;
        // Doesn't really matter, but it's safe to treat it as if there were a semicolon before the RBRACE.
        type = SEMICOLON;
        scanFirstToken();

        return true;
    }

    /**
     * Encapsulates part of the state of the parser, enough to reconstruct the state of both parser and lexer for resuming parsing after skipping a function body.
     */
    private static class ParserState implements Serializable {
        private final int position;
        private final int line;
        private final int linePosition;

        ParserState(int position, int line, int linePosition) {
            this.position = position;
            this.line = line;
            this.linePosition = linePosition;
        }

        Lexer createLexer(Source source, Lexer lexer, TokenStream stream, boolean scripting) {
            var newLexer = new Lexer(source, position, lexer.limit - position, stream, scripting, true);
            newLexer.restoreState(new Lexer.State(position, Integer.MAX_VALUE, line, -1, linePosition, SEMICOLON));
            return newLexer;
        }
    }

    private void addFunctionDeclarations(ParserContextFunctionNode functionNode) {
        VarNode lastDecl = null;
        for (var i = functionDeclarations.size() - 1; i >= 0; i--) {
            var decl = functionDeclarations.get(i);
            if (lastDecl == null && decl instanceof VarNode) {
                decl = lastDecl = ((VarNode)decl).setFlag(VarNode.IS_LAST_FUNCTION_DECLARATION);
                functionNode.setFlag(FunctionNode.HAS_FUNCTION_DECLARATIONS);
            }
            prependStatement(decl);
        }
    }

    private RuntimeNode referenceError(Expression lhs, Expression rhs, boolean earlyError) {
        if (env._parse_only || earlyError) {
            throw error(JSErrorType.REFERENCE_ERROR, AbstractParser.message("invalid.lvalue"), lhs.getToken());
        }
        var args = new ArrayList<Expression>();
        args.add(lhs);
        if (rhs == null) {
            args.add(LiteralNode.newInstance(lhs.getToken(), lhs.getFinish()));
        } else {
            args.add(rhs);
        }
        args.add(LiteralNode.newInstance(lhs.getToken(), lhs.getFinish(), lhs.toString()));
        return new RuntimeNode(lhs.getToken(), lhs.getFinish(), RuntimeNode.Request.REFERENCE_ERROR, args);
    }

    /**
     * PostfixExpression :
     *      LeftHandSideExpression
     *      LeftHandSideExpression ++ // [no LineTerminator here]
     *      LeftHandSideExpression -- // [no LineTerminator here]
     *
     * See 11.3
     *
     * UnaryExpression :
     *      PostfixExpression
     *      delete UnaryExpression
     *      void UnaryExpression
     *      typeof UnaryExpression
     *      ++ UnaryExpression
     *      -- UnaryExpression
     *      + UnaryExpression
     *      - UnaryExpression
     *      ~ UnaryExpression
     *      ! UnaryExpression
     *
     * See 11.4
     *
     * Parse unary expression.
     * @return Expression node.
     */
    private Expression unaryExpression() {
        final long unaryToken = token;

        switch (type) {
            // default ->  {}

            case ADD, SUB -> {
                var opType = type;
                next();
                var expr = unaryExpression();
                return new UnaryNode(Token.recast(unaryToken, (opType == TokenType.ADD) ? TokenType.POS : TokenType.NEG), expr);
            }
            case DELETE, VOID, TYPEOF, BIT_NOT, NOT ->  {
                next();
                var expr = unaryExpression();
                return new UnaryNode(unaryToken, expr);
            }
            case INCPREFIX, DECPREFIX -> {
                var opType = type;
                next();
                var lhs = leftHandSideExpression();
                // ++, -- without operand..
                if (lhs == null) {
                    throw error(AbstractParser.message("expected.lvalue", type.getNameOrType()));
                }
                return verifyIncDecExpression(unaryToken, opType, lhs, false);
            }
        }

        var expression = leftHandSideExpression();

        if (last != EOL) {
            switch (type) {
                // default -> {}

                case INCPREFIX, DECPREFIX -> {
                    var opToken = token;
                    var opType = type;
                    var lhs = expression;
                    // ++, -- without operand..
                    if (lhs == null) {
                        throw error(AbstractParser.message("expected.lvalue", type.getNameOrType()));
                    }
                    next();
                    return verifyIncDecExpression(opToken, opType, lhs, true);
                }
            }
        }

        if (expression == null) {
            throw error(AbstractParser.message("expected.operand", type.getNameOrType()));
        }

        return expression;
    }

    private Expression verifyIncDecExpression(long unaryToken, TokenType opType, Expression lhs, boolean isPostfix) {
        assert lhs != null;

        if (!(lhs instanceof AccessNode || lhs instanceof IndexNode || lhs instanceof IdentNode)) {
            return referenceError(lhs, null, env._early_lvalue_error);
        }

        if (lhs instanceof IdentNode) {
            if (!checkIdentLValue((IdentNode)lhs)) {
                return referenceError(lhs, null, false);
            }
            verifyIdent((IdentNode)lhs, "operand for " + opType.getName() + " operator");
        }

        return incDecExpression(unaryToken, opType, lhs, isPostfix);
    }

    /**
     * {@code
     * MultiplicativeExpression :
     *      UnaryExpression
     *      MultiplicativeExpression * UnaryExpression
     *      MultiplicativeExpression / UnaryExpression
     *      MultiplicativeExpression % UnaryExpression
     *
     * See 11.5
     *
     * AdditiveExpression :
     *      MultiplicativeExpression
     *      AdditiveExpression + MultiplicativeExpression
     *      AdditiveExpression - MultiplicativeExpression
     *
     * See 11.6
     *
     * ShiftExpression :
     *      AdditiveExpression
     *      ShiftExpression << AdditiveExpression
     *      ShiftExpression >> AdditiveExpression
     *      ShiftExpression >>> AdditiveExpression
     *
     * See 11.7
     *
     * RelationalExpression :
     *      ShiftExpression
     *      RelationalExpression < ShiftExpression
     *      RelationalExpression > ShiftExpression
     *      RelationalExpression <= ShiftExpression
     *      RelationalExpression >= ShiftExpression
     *      RelationalExpression instanceof ShiftExpression
     *      RelationalExpression in ShiftExpression // if !noIf
     *
     * See 11.8
     *
     *      RelationalExpression
     *      EqualityExpression == RelationalExpression
     *      EqualityExpression != RelationalExpression
     *      EqualityExpression === RelationalExpression
     *      EqualityExpression !== RelationalExpression
     *
     * See 11.9
     *
     * BitwiseANDExpression :
     *      EqualityExpression
     *      BitwiseANDExpression & EqualityExpression
     *
     * BitwiseXORExpression :
     *      BitwiseANDExpression
     *      BitwiseXORExpression ^ BitwiseANDExpression
     *
     * BitwiseORExpression :
     *      BitwiseXORExpression
     *      BitwiseORExpression | BitwiseXORExpression
     *
     * See 11.10
     *
     * LogicalANDExpression :
     *      BitwiseORExpression
     *      LogicalANDExpression && BitwiseORExpression
     *
     * LogicalORExpression :
     *      LogicalANDExpression
     *      LogicalORExpression || LogicalANDExpression
     *
     * See 11.11
     *
     * ConditionalExpression :
     *      LogicalORExpression
     *      LogicalORExpression ? AssignmentExpression : AssignmentExpression
     *
     * See 11.12
     *
     * AssignmentExpression :
     *      ConditionalExpression
     *      LeftHandSideExpression AssignmentOperator AssignmentExpression
     *
     * AssignmentOperator :
     *      = *= /= %= += -= <<= >>= >>>= &= ^= |=
     *
     * See 11.13
     *
     * Expression :
     *      AssignmentExpression
     *      Expression , AssignmentExpression
     *
     * See 11.14
     * }
     *
     * Parse expression.
     * @return Expression node.
     */
    protected Expression expression() {
        // This method is protected so that subclass can get details at expression start point!

        // Include commas in expression parsing.
        return expression(false);
    }

    private Expression expression(boolean noIn) {
        var assignmentExpression = assignmentExpression(noIn);
        while (type == COMMARIGHT) {
            var commaToken = token;
            next();
            var rhs = assignmentExpression(noIn);
            assignmentExpression = new BinaryNode(commaToken, assignmentExpression, rhs);
        }
        return assignmentExpression;
    }

    private Expression expression(int minPrecedence, boolean noIn) {
        return expression(unaryExpression(), minPrecedence, noIn);
    }

    private JoinPredecessorExpression joinPredecessorExpression() {
        return new JoinPredecessorExpression(expression());
    }

    private Expression expression(Expression exprLhs, int minPrecedence, boolean noIn) {
        // Get the precedence of the next operator.
        var precedence = type.getPrecedence();
        var lhs = exprLhs;

        // While greater precedence.
        while (type.isOperator(noIn) && precedence >= minPrecedence) {
            // Capture the operator token.
            var op = token;

            if (type == TERNARY) {
                // Skip operator.
                next();

                // Pass expression. Middle expression of a conditional expression can be a "in" expression - even in the contexts where "in" is not permitted.
                var trueExpr = expression(unaryExpression(), ASSIGN.getPrecedence(), false);

                expect(COLON);

                // Fail expression.
                var falseExpr = expression(unaryExpression(), ASSIGN.getPrecedence(), noIn);

                // Build up node.
                lhs = new TernaryNode(op, lhs, new JoinPredecessorExpression(trueExpr), new JoinPredecessorExpression(falseExpr));
            }
            else {
                // Skip operator.
                next();

                 // Get the next primary expression.
                Expression rhs;
                var isAssign = Token.descType(op) == ASSIGN;
                if (isAssign) {
                    defaultNames.push(lhs);
                }
                try {
                    rhs = unaryExpression();
                    // Get precedence of next operator.
                    var nextPrecedence = type.getPrecedence();

                    // Subtask greater precedence.
                    while (type.isOperator(noIn) && (nextPrecedence > precedence || nextPrecedence == precedence && !type.isLeftAssociative())) {
                        rhs = expression(rhs, nextPrecedence, noIn);
                        nextPrecedence = type.getPrecedence();
                    }
                } finally {
                    if (isAssign) {
                        defaultNames.pop();
                    }
                }
                lhs = verifyAssignment(op, lhs, rhs);
            }

            precedence = type.getPrecedence();
        }

        return lhs;
    }

    /**
     * AssignmentExpression.
     *
     * AssignmentExpression[In] :
     *   ConditionalExpression[?In]
     *   ArrowFunction[?In]
     *   LeftHandSideExpression[] = AssignmentExpression[?In]
     *   LeftHandSideExpression[] AssignmentOperator AssignmentExpression[?In]
     *
     * @param noIn {@code true} if IN operator should be ignored.
     * @return the assignment expression
     */
    protected Expression assignmentExpression(boolean noIn) {
        // This method is protected so that subclass can get details at assignment expression start point!

        var startToken = token;
        var startLine = line;
        var exprLhs = conditionalExpression(noIn);

        if (type == ARROW) {
            if (checkNoLineTerminator()) {
                Expression paramListExpr;
                if (exprLhs instanceof ExpressionList) {
                    paramListExpr = (((ExpressionList)exprLhs).getExpressions().isEmpty() ? null : ((ExpressionList)exprLhs).getExpressions().get(0));
                } else {
                    paramListExpr = exprLhs;
                }
                return arrowFunction(startToken, startLine, paramListExpr);
            }
        }
        assert !(exprLhs instanceof ExpressionList);

        if (isAssignmentOperator(type)) {
            var isAssign = type == ASSIGN;
            if (isAssign) {
                defaultNames.push(exprLhs);
            }
            try {
                var assignToken = token;
                next();
                var exprRhs = assignmentExpression(noIn);
                return verifyAssignment(assignToken, exprLhs, exprRhs);
            } finally {
                if (isAssign) {
                    defaultNames.pop();
                }
            }
        } else {
            return exprLhs;
        }
    }

    /**
     * Is type one of {@code = *= /= %= += -= <<= >>= >>>= &= ^= |=}?
     */
    private static boolean isAssignmentOperator(TokenType type) {
        return switch (type) {
            case ASSIGN, ASSIGN_ADD, ASSIGN_BIT_AND, ASSIGN_BIT_OR, ASSIGN_BIT_XOR, ASSIGN_DIV, ASSIGN_MOD, ASSIGN_MUL, ASSIGN_SAR, ASSIGN_SHL, ASSIGN_SHR, ASSIGN_SUB -> true;
            default -> false;
        };
    }

    /**
     * ConditionalExpression.
     */
    private Expression conditionalExpression(boolean noIn) {
        return expression(TERNARY.getPrecedence(), noIn);
    }

    /**
     * ArrowFunction.
     * @param startToken start token of the ArrowParameters expression
     * @param functionLine start line of the arrow function
     * @param paramListExpr ArrowParameters expression or {@code null} for {@code ()} (empty list)
     */
    private Expression arrowFunction(long startToken, int functionLine, Expression paramListExpr) {
        // caller needs to check that there's no LineTerminator between parameter list and arrow
        assert type != ARROW || checkNoLineTerminator();
        expect(ARROW);

        var functionToken = Token.recast(startToken, ARROW);
        var name = new IdentNode(functionToken, Token.descPosition(functionToken), NameCodec.encode("=>:") + functionLine);
        var functionNode = createParserContextFunctionNode(name, functionToken, FunctionNode.Kind.ARROW, functionLine, null);
        functionNode.setFlag(FunctionNode.IS_ANONYMOUS);

        lc.push(functionNode);
        try {
            var parameterBlock = newBlock();
            List<IdentNode> parameters;
            try {
                parameters = convertArrowFunctionParameterList(paramListExpr, functionLine);
                functionNode.setParameters(parameters);

                if (!functionNode.isSimpleParameterList()) {
                    markEvalInArrowParameterList(parameterBlock);
                }
            } finally {
                restoreBlock(parameterBlock);
            }
            var functionBody = functionBody(functionNode);
            functionBody = maybeWrapBodyInParameterBlock(functionBody, parameterBlock);

            verifyParameterList(parameters, functionNode);

            var function = createFunctionNode(functionNode, functionToken, name, parameters, FunctionNode.Kind.ARROW, functionLine, functionBody);
            return function;
        } finally {
            lc.pop(functionNode);
        }
    }

    private void markEvalInArrowParameterList(ParserContextBlockNode parameterBlock) {
        var iter = lc.getFunctions();
        var current = iter.next();
        var parent = iter.next();

        if (parent.getFlag(FunctionNode.HAS_EVAL) != 0) {
            // we might have flagged has-eval in the parent function during parsing the parameter list, if the parameter list contains eval; must tag arrow function as has-eval.
            for (var st : parameterBlock.getStatements()) {
                st.accept(new NodeVisitor<LexicalContext>(new LexicalContext()) {
                    @Override
                    public boolean enterCallNode(CallNode callNode) {
                        if (callNode.getFunction() instanceof IdentNode && ((IdentNode) callNode.getFunction()).getName().equals("eval")) {
                            current.setFlag(FunctionNode.HAS_EVAL);
                        }
                        return true;
                    }
                });
            }
            // TODO: function containing the arrow function should not be flagged has-eval
        }
    }

    private List<IdentNode> convertArrowFunctionParameterList(Expression paramListExpr, int functionLine) {
        List<IdentNode> parameters;
        if (paramListExpr == null) {
            // empty parameter list, i.e. () =>
            parameters = Collections.emptyList();
        } else if (paramListExpr instanceof IdentNode || paramListExpr.isTokenType(ASSIGN)) {
            parameters = Collections.singletonList(verifyArrowParameter(paramListExpr, 0, functionLine));
        } else if (paramListExpr instanceof BinaryNode && Token.descType(paramListExpr.getToken()) == COMMARIGHT) {
            parameters = new ArrayList<>();
            var car = paramListExpr;
            do {
                var cdr = ((BinaryNode) car).rhs();
                parameters.add(0, verifyArrowParameter(cdr, parameters.size(), functionLine));
                car = ((BinaryNode) car).lhs();
            } while (car instanceof BinaryNode && Token.descType(car.getToken()) == COMMARIGHT);
            parameters.add(0, verifyArrowParameter(car, parameters.size(), functionLine));
        } else {
            throw error(AbstractParser.message("expected.arrow.parameter"), paramListExpr.getToken());
        }
        return parameters;
    }

    private IdentNode verifyArrowParameter(Expression param, int index, int paramLine) {
        var contextString = "function parameter";
        if (param instanceof IdentNode) {
            var ident = (IdentNode)param;
            verifyIdent(ident, contextString);
            var currentFunction = lc.getCurrentFunction();
            if (currentFunction != null) {
                currentFunction.addParameterBinding(ident);
            }
            return ident;
        }

        if (param.isTokenType(ASSIGN)) {
            var lhs = ((BinaryNode) param).lhs();
            var paramToken = lhs.getToken();
            var initializer = ((BinaryNode) param).rhs();
            if (lhs instanceof IdentNode) {
                // default parameter
                var ident = (IdentNode) lhs;

                var currentFunction = lc.getCurrentFunction();
                if (currentFunction != null) {
                    if (env._parse_only) {
                        currentFunction.addParameterExpression(ident, param);
                    } else {
                        var test = new BinaryNode(Token.recast(paramToken, EQ_STRICT), ident, newUndefinedLiteral(paramToken, finish));
                        var value = new TernaryNode(Token.recast(paramToken, TERNARY), test, new JoinPredecessorExpression(initializer), new JoinPredecessorExpression(ident));
                        var assignment = new BinaryNode(Token.recast(paramToken, ASSIGN), ident, value);
                        lc.getFunctionBody(currentFunction).appendStatement(new ExpressionStatement(paramLine, assignment.getToken(), assignment.getFinish(), assignment));
                    }

                    currentFunction.addParameterBinding(ident);
                    currentFunction.setSimpleParameterList(false);
                }
                return ident;
            }
        }
        throw error(AbstractParser.message("invalid.arrow.parameter"), param.getToken());
    }

    private boolean checkNoLineTerminator() {
        assert type == ARROW;
        if (last == RPAREN) {
            return true;
        } else if (last == IDENT) {
            return true;
        }
        for (var i = k - 1; i >= 0; i--) {
            var t = T(i);
            switch (t) {
                case RPAREN, IDENT -> {
                    return true;
                }
                case EOL -> {
                    return false;
                }
                case COMMENT -> {
                    continue;
                }
                default -> {
                    return (t.getKind() == TokenKind.FUTURE);
                }
            }
        }
        return false;
    }

    /**
     * Parse an end of line.
     */
    private void endOfLine() {
        switch (type) {
            case SEMICOLON, EOL -> {
                next();
            }
            case RPAREN, RBRACKET, RBRACE, EOF -> {
                /*no-op*/
            }
            default -> {
                if (last != EOL) {
                    expect(SEMICOLON);
                }
            }
        }
    }

    /**
     * Parse untagged template literal as string concatenation.
     */
    private Expression templateLiteral() {
        assert type == TEMPLATE || type == TEMPLATE_HEAD;
        var noSubstitutionTemplate = type == TEMPLATE;
        var lastLiteralToken = token;
        var literal = getLiteral();
        if (noSubstitutionTemplate) {
            return literal;
        }

        if (env._parse_only) {
            var exprs = new ArrayList<Expression>();
            exprs.add(literal);
            TokenType lastLiteralType;
            do {
                var expression = expression();
                if (type != TEMPLATE_MIDDLE && type != TEMPLATE_TAIL) {
                    throw error(AbstractParser.message("unterminated.template.expression"), token);
                }
                exprs.add(expression);
                lastLiteralType = type;
                literal = getLiteral();
                exprs.add(literal);
            } while (lastLiteralType == TEMPLATE_MIDDLE);
            return new TemplateLiteral(exprs);
        } else {
            Expression concat = literal;
            TokenType lastLiteralType;
            do {
                var expression = expression();
                if (type != TEMPLATE_MIDDLE && type != TEMPLATE_TAIL) {
                    throw error(AbstractParser.message("unterminated.template.expression"), token);
                }
                concat = new BinaryNode(Token.recast(lastLiteralToken, TokenType.ADD), concat, expression);
                lastLiteralType = type;
                lastLiteralToken = token;
                literal = getLiteral();
                concat = new BinaryNode(Token.recast(lastLiteralToken, TokenType.ADD), concat, literal);
            } while (lastLiteralType == TEMPLATE_MIDDLE);
            return concat;
        }
    }

    /**
     * Parse tagged template literal as argument list.
     * @return argument list for a tag function call (template object, ...substitutions)
     */
    private List<Expression> templateLiteralArgumentList() {
        assert type == TEMPLATE || type == TEMPLATE_HEAD;
        var argumentList = new ArrayList<Expression>();
        var rawStrings = new ArrayList<Expression>();
        var cookedStrings = new ArrayList<Expression>();
        argumentList.add(null); // filled at the end

        var templateToken = token;
        var hasSubstitutions = type == TEMPLATE_HEAD;
        addTemplateLiteralString(rawStrings, cookedStrings);

        if (hasSubstitutions) {
            TokenType lastLiteralType;
            do {
                var expression = expression();
                if (type != TEMPLATE_MIDDLE && type != TEMPLATE_TAIL) {
                    throw error(AbstractParser.message("unterminated.template.expression"), token);
                }
                argumentList.add(expression);

                lastLiteralType = type;
                addTemplateLiteralString(rawStrings, cookedStrings);
            } while (lastLiteralType == TEMPLATE_MIDDLE);
        }

        var rawStringArray = LiteralNode.newInstance(templateToken, finish, rawStrings);
        var cookedStringArray = LiteralNode.newInstance(templateToken, finish, cookedStrings);

        if (!env._parse_only) {
            var templateObject = new RuntimeNode(templateToken, finish, RuntimeNode.Request.GET_TEMPLATE_OBJECT, rawStringArray, cookedStringArray);
            argumentList.set(0, templateObject);
        } else {
            argumentList.set(0, rawStringArray);
        }
        return optimizeList(argumentList);
    }

    private void addTemplateLiteralString(ArrayList<Expression> rawStrings, ArrayList<Expression> cookedStrings) {
        var stringToken = token;
        var rawString = lexer.valueOfRawString(stringToken);
        var cookedString = (String) getValue();
        next();
        rawStrings.add(LiteralNode.newInstance(stringToken, finish, rawString));
        cookedStrings.add(LiteralNode.newInstance(stringToken, finish, cookedString));
    }


    @Override
    public String toString() {
        return "'JavaScript Parsing'";
    }

    private static void markEval(ParserContext lc) {
        var iter = lc.getFunctions();
        var flaggedCurrentFn = false;
        while (iter.hasNext()) {
            var fn = iter.next();
            if (!flaggedCurrentFn) {
                fn.setFlag(FunctionNode.HAS_EVAL);
                flaggedCurrentFn = true;
                if (fn.getKind() == FunctionNode.Kind.ARROW) {
                    // possible use of this in an eval that's nested in an arrow function, e.g.:
                    // function fun(){ return (() => eval("this"))(); };
                    markThis(lc);
                    markNewTarget(lc);
                }
            } else {
                fn.setFlag(FunctionNode.HAS_NESTED_EVAL);
            }
            var body = lc.getFunctionBody(fn);
            // NOTE: it is crucial to mark the body of the outer function as needing scope even when we skip parsing a nested function.
            // functionBody() contains code to compensate for the lack of invoking this method when the parser skips a nested function.
            body.setFlag(Block.NEEDS_SCOPE);
            fn.setFlag(FunctionNode.HAS_SCOPE_BLOCK);
        }
    }

    private void prependStatement(Statement statement) {
        lc.prependStatementToCurrentNode(statement);
    }

    private void appendStatement(Statement statement) {
        lc.appendStatementToCurrentNode(statement);
    }

    private ParserContextFunctionNode getCurrentNonArrowFunction() {
        var iter = lc.getFunctions();
        while (iter.hasNext()) {
            var fn = iter.next();
            if (fn.getKind() != FunctionNode.Kind.ARROW) {
                return fn;
            }
        }
        return null;
    }

    private static void markThis(ParserContext lc) {
        var iter = lc.getFunctions();
        while (iter.hasNext()) {
            var fn = iter.next();
            fn.setFlag(FunctionNode.USES_THIS);
            if (fn.getKind() != FunctionNode.Kind.ARROW) {
                break;
            }
        }
    }

    private static void markNewTarget(ParserContext lc) {
        var iter = lc.getFunctions();
        while (iter.hasNext()) {
            var fn = iter.next();
            if (fn.getKind() != FunctionNode.Kind.ARROW) {
                if (!fn.isProgram()) {
                    fn.setFlag(FunctionNode.ES6_USES_NEW_TARGET);
                }
                break;
            }
        }
    }

}
