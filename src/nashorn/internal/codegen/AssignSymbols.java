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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

import nashorn.internal.ir.AccessNode;
import nashorn.internal.ir.BinaryNode;
import nashorn.internal.ir.Block;
import nashorn.internal.ir.CatchNode;
import nashorn.internal.ir.Expression;
import nashorn.internal.ir.ForNode;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.LexicalContextNode;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.RuntimeNode;
import nashorn.internal.ir.RuntimeNode.Request;
import nashorn.internal.ir.Splittable;
import nashorn.internal.ir.Statement;
import nashorn.internal.ir.SwitchNode;
import nashorn.internal.ir.Symbol;
import nashorn.internal.ir.TryNode;
import nashorn.internal.ir.UnaryNode;
import nashorn.internal.ir.VarNode;
import nashorn.internal.ir.visitor.SimpleNodeVisitor;
import nashorn.internal.parser.TokenType;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.ECMAErrors;
import nashorn.internal.runtime.ErrorManager;
import nashorn.internal.runtime.JSErrorType;
import nashorn.internal.runtime.ParserException;
import nashorn.internal.runtime.Source;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.logging.Loggable;
import nashorn.internal.runtime.logging.Logger;
import static nashorn.internal.codegen.CompilerConstants.ARGUMENTS;
import static nashorn.internal.codegen.CompilerConstants.ARGUMENTS_VAR;
import static nashorn.internal.codegen.CompilerConstants.CALLEE;
import static nashorn.internal.codegen.CompilerConstants.EXCEPTION_PREFIX;
import static nashorn.internal.codegen.CompilerConstants.ITERATOR_PREFIX;
import static nashorn.internal.codegen.CompilerConstants.RETURN;
import static nashorn.internal.codegen.CompilerConstants.SCOPE;
import static nashorn.internal.codegen.CompilerConstants.SWITCH_TAG_PREFIX;
import static nashorn.internal.codegen.CompilerConstants.THIS;
import static nashorn.internal.codegen.CompilerConstants.VARARGS;
import static nashorn.internal.ir.Symbol.HAS_OBJECT_VALUE;
import static nashorn.internal.ir.Symbol.IS_CONST;
import static nashorn.internal.ir.Symbol.IS_FUNCTION_SELF;
import static nashorn.internal.ir.Symbol.IS_GLOBAL;
import static nashorn.internal.ir.Symbol.IS_INTERNAL;
import static nashorn.internal.ir.Symbol.IS_LET;
import static nashorn.internal.ir.Symbol.IS_PARAM;
import static nashorn.internal.ir.Symbol.IS_PROGRAM_LEVEL;
import static nashorn.internal.ir.Symbol.IS_SCOPE;
import static nashorn.internal.ir.Symbol.IS_THIS;
import static nashorn.internal.ir.Symbol.IS_VAR;
import static nashorn.internal.ir.Symbol.KINDMASK;

/**
 * This visitor assigns symbols to identifiers denoting variables.
 *
 * It does few more minor calculations that are only possible after symbols have been assigned; such is the transformation of "delete" and "typeof" operators into runtime nodes and counting of number of properties assigned to "this" in constructor functions.
 * This visitor is also notable for what it doesn't do, most significantly it does no type calculations as in JavaScript variables can change types during runtime and as such symbols don't have types.
 * Calculation of expression types is performed by a separate visitor.
 */
@Logger(name="symbols")
final class AssignSymbols extends SimpleNodeVisitor implements Loggable {

    private final DebugLogger log;
    private final boolean debug;

    private static boolean isParamOrVar(IdentNode identNode) {
        var symbol = identNode.getSymbol();
        return symbol.isParam() || symbol.isVar();
    }

    private static String name(Node node) {
        var cn = node.getClass().getName();
        var lastDot = cn.lastIndexOf('.');
        if (lastDot == -1) {
            return cn;
        }
        return cn.substring(lastDot + 1);
    }

    /**
     * Checks if various symbols that were provisionally marked as needing a slot ended up unused, and marks them as not needing a slot after all.
     */
    private static FunctionNode removeUnusedSlots(FunctionNode functionNode) {
        if (!functionNode.needsCallee()) {
            functionNode.compilerConstant(CALLEE).setNeedsSlot(false);
        }
        if (!(functionNode.hasScopeBlock() || functionNode.needsParentScope())) {
            functionNode.compilerConstant(SCOPE).setNeedsSlot(false);
        }
        // Named function expressions that end up not referencing themselves won't need a local slot for the self symbol.
        if (functionNode.isNamedFunctionExpression() && !functionNode.usesSelfSymbol()) {
            var selfSymbol = functionNode.getBody().getExistingSymbol(functionNode.getIdent().getName());
            if (selfSymbol != null && selfSymbol.isFunctionSelf()) {
                selfSymbol.setNeedsSlot(false);
                selfSymbol.clearFlag(Symbol.IS_VAR);
            }
        }
        return functionNode;
    }

    private final Deque<Set<String>> thisProperties = new ArrayDeque<>();
    private final Map<String, Symbol> globalSymbols = new HashMap<>(); //reuse the same global symbol
    private final Compiler compiler;
    private final boolean isOnDemand;

    public AssignSymbols(Compiler compiler) {
        this.compiler = compiler;
        this.log = initLogger(compiler.getContext());
        this.debug = log.isEnabled();
        this.isOnDemand = compiler.isOnDemandCompilation();
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
     * Define symbols for all variable declarations at the top of the function scope.
     * This way we can get around problems like
     * <pre>
     * while (true) {
     *   break;
     *   if (true) {
     *     var s;
     *   }
     * }
     * </pre>
     * to an arbitrary nesting depth.
     * <p>
     * see NASHORN-73
     *
     * @param functionNode the FunctionNode we are entering
     * @param body the body of the FunctionNode we are entering
     */
    private void acceptDeclarations(FunctionNode functionNode, Block body) {
        // This visitor will assign symbol to all declared variables.
        body.accept(new SimpleNodeVisitor() {
            @Override
            protected boolean enterDefault(Node node) {
                // Don't bother visiting expressions; var is a statement, it can't be inside an expression.
                // This will also prevent visiting nested functions (as FunctionNode is an expression).
                return !(node instanceof Expression);
            }

            @Override
            public Node leaveVarNode(VarNode varNode) {
                var ident  = varNode.getName();
                var blockScoped = varNode.isBlockScoped();
                if (blockScoped && lc.inUnprotectedSwitchContext()) {
                    throwUnprotectedSwitchError(varNode);
                }
                var block = blockScoped ? lc.getCurrentBlock() : body;
                var symbol = defineSymbol(block, ident.getName(), ident, varNode.getSymbolFlags());
                if (varNode.isFunctionDeclaration()) {
                    symbol.setIsFunctionDeclaration();
                }
                return varNode.setName(ident.setSymbol(symbol));
            }
        });
    }

    private IdentNode compilerConstantIdentifier(CompilerConstants cc) {
        return createImplicitIdentifier(cc.symbolName()).setSymbol(lc.getCurrentFunction().compilerConstant(cc));
    }

    /**
     * Creates an ident node for an implicit identifier within the function (one not declared in the script source code).
     * These identifiers are defined with function's token and finish.
     */
    private IdentNode createImplicitIdentifier(String name) {
        var fn = lc.getCurrentFunction();
        return new IdentNode(fn.getToken(), fn.getFinish(), name);
    }

    private Symbol createSymbol(String name, int flags) {
        if ((flags & Symbol.KINDMASK) == IS_GLOBAL) {
            // reuse global symbols so they can be hashed
            var global = globalSymbols.get(name);
            if (global == null) {
                global = new Symbol(name, flags);
                globalSymbols.put(name, global);
            }
            return global;
        }
        return new Symbol(name, flags);
    }

    /**
     * Creates a synthetic initializer for a variable (a var statement that doesn't occur in the source code).
     * Typically used to create assignment of {@code :callee} to the function name symbol in self-referential function expressions as well as for assignment of {@code :arguments} to {@code arguments}.
     * @param name the ident node identifying the variable to initialize
     * @param initConstant the compiler constant it is initialized to
     * @param fn the function node the assignment is for
     * @return a var node with the appropriate assignment
     */
    private VarNode createSyntheticInitializer(IdentNode name, CompilerConstants initConstant, FunctionNode fn) {
        var init = compilerConstantIdentifier(initConstant);
        assert init.getSymbol() != null && init.getSymbol().isBytecodeLocal();

        var synthVar = new VarNode(fn.getLineNumber(), fn.getToken(), fn.getFinish(), name, init);

        var nameSymbol = fn.getBody().getExistingSymbol(name.getName());
        assert nameSymbol != null;

        return (VarNode)synthVar.setName(name.setSymbol(nameSymbol)).accept(this);
    }

    private FunctionNode createSyntheticInitializers(FunctionNode functionNode) {
        var syntheticInitializers = new ArrayList<VarNode>(2);

        // Must visit the new var nodes in the context of the body.
        // We could also just set the new statements into the block and then revisit the entire block, but that seems to be too much double work.
        var body = functionNode.getBody();
        lc.push(body);
        try {
            if (functionNode.usesSelfSymbol()) {
                // "var fn = :callee"
                syntheticInitializers.add(createSyntheticInitializer(functionNode.getIdent(), CALLEE, functionNode));
            }

            if (functionNode.needsArguments()) {
                // "var arguments = :arguments"
                syntheticInitializers.add(createSyntheticInitializer(createImplicitIdentifier(ARGUMENTS_VAR.symbolName()), ARGUMENTS, functionNode));
            }

            if (syntheticInitializers.isEmpty()) {
                return functionNode;
            }

            for (var it = syntheticInitializers.listIterator(); it.hasNext();) {
                it.set((VarNode)it.next().accept(this));
            }
        } finally {
            lc.pop(body);
        }

        var stmts = body.getStatements();
        var newStatements = new ArrayList<Statement>(stmts.size() + syntheticInitializers.size());
        newStatements.addAll(syntheticInitializers);
        newStatements.addAll(stmts);
        return functionNode.setBody(lc, body.setStatements(lc, newStatements));
    }

    /**
     * Defines a new symbol in the given block.
     * @param block        the block in which to define the symbol
     * @param name         name of symbol.
     * @param origin       origin node
     * @param symbolFlags  Symbol flags.
     * @return Symbol for given name or null for redefinition.
     */
    private Symbol defineSymbol(Block block, String name, Node origin, int symbolFlags) {
        var flags  = symbolFlags;
        var isBlockScope = (flags & IS_LET) != 0 || (flags & IS_CONST) != 0;
        var isGlobal = (flags & KINDMASK) == IS_GLOBAL;

        Symbol symbol;
        FunctionNode function;
        if (isBlockScope) {
            // block scoped variables always live in current block, no need to look for existing symbols in parent blocks.
            symbol = block.getExistingSymbol(name);
            function = lc.getCurrentFunction();
        } else {
            symbol = findSymbol(block, name);
            function = lc.getFunction(block);
        }

        // Global variables are implicitly always scope variables too.
        if (isGlobal) {
            flags |= IS_SCOPE;
        }

        if (lc.getCurrentFunction().isProgram()) {
            flags |= IS_PROGRAM_LEVEL;
        }

        var isParam = (flags & KINDMASK) == IS_PARAM;
        var isVar = (flags & KINDMASK) == IS_VAR;

        if (symbol != null) {
            // Symbol was already defined. Check if it needs to be redefined.
            if (isParam) {
                if (!isLocal(function, symbol)) {
                    // Not defined in this function. Create a new definition.
                    symbol = null;
                } else if (symbol.isParam()) {
                    // Duplicate parameter. Null return will force an error.
                    throwParserException(ECMAErrors.getMessage("syntax.error.duplicate.parameter", name), origin);
                }
            } else if (isVar) {
                if (isBlockScope) {
                    // Check redeclaration in same block
                    if (symbol.hasBeenDeclared()) {
                        throwParserException(ECMAErrors.getMessage("syntax.error.redeclare.variable", name), origin);
                    } else {
                        symbol.setHasBeenDeclared();
                        // Set scope flag on top-level block scoped symbols
                        if (function.isProgram() && function.getBody() == block) {
                            symbol.setIsScope();
                        }
                    }
                } else if ((flags & IS_INTERNAL) != 0) {
                    // Always create a new definition.
                    symbol = null;
                } else {
                    // Found LET or CONST in parent scope of same function - s SyntaxError
                    if (symbol.isBlockScoped() && isLocal(lc.getCurrentFunction(), symbol)) {
                        throwParserException(ECMAErrors.getMessage("syntax.error.redeclare.variable", name), origin);
                    }
                    // Not defined in this function. Create a new definition.
                    if (!isLocal(function, symbol) || symbol.less(IS_VAR)) {
                        symbol = null;
                    }
                }
            }
        }

        if (symbol == null) {
            // If not found, then create a new one.
            Block symbolBlock;

            // Determine where to create it.
            if (isVar && ((flags & IS_INTERNAL) != 0 || isBlockScope)) {
                symbolBlock = block; //internal vars are always defined in the block closest to them
            } else if (isGlobal) {
                symbolBlock = lc.getOutermostFunction().getBody();
            } else {
                symbolBlock = lc.getFunctionBody(function);
            }

            // Create and add to appropriate block.
            symbol = createSymbol(name, flags);
            symbolBlock.putSymbol(symbol);

            if ((flags & IS_SCOPE) == 0) {
                // Initial assumption; symbol can lose its slot later
                symbol.setNeedsSlot(true);
            }
        } else if (symbol.less(flags)) {
            symbol.setFlags(flags);
        }

        return symbol;
    }

    private <T extends Node> T end(T node) {
        return end(node, true);
    }

    private <T extends Node> T end(T node, boolean printNode) {
        if (debug) {
            var sb = new StringBuilder();

            sb.append("[LEAVE ").
               append(name(node)).
               append("] ").
               append(printNode ? node.toString() : "").
               append(" in '").
               append(lc.getCurrentFunction().getName()).
               append('\'');

            if (node instanceof IdentNode) {
                var symbol = ((IdentNode)node).getSymbol();
                if (symbol == null) {
                    sb.append(" <NO SYMBOL>");
                } else {
                    sb.append(" <symbol=").append(symbol).append('>');
                }
            }

            log.unindent();
            log.info(sb);
        }

        return node;
    }

    @Override
    public boolean enterBlock(Block block) {
        start(block);

        if (lc.isFunctionBody()) {
            assert !block.hasSymbols();
            var fn = lc.getCurrentFunction();
            if (isUnparsedFunction(fn)) {
                // It's a skipped nested function. Just mark the symbols being used by it as being in use.
                for (var name: compiler.getScriptFunctionData(fn.getId()).getExternalSymbolNames()) {
                    nameIsUsed(name, null);
                }
                // Don't bother descending into it, it must be empty anyway.
                assert block.getStatements().isEmpty();
                return false;
            }

            enterFunctionBody();
        }

        return true;
    }

    private boolean isUnparsedFunction(FunctionNode fn) {
        return isOnDemand && fn != lc.getOutermostFunction();
    }

    @Override
    public boolean enterCatchNode(CatchNode catchNode) {
        var exception = catchNode.getExceptionIdentifier();
        var block = lc.getCurrentBlock();

        start(catchNode);

        // define block-local exception variable
        var exname = exception.getName();
        // If the name of the exception starts with ":e", this is a synthetic catch block, likely a catch-all.
        // Its symbol is naturally internal, and should be treated as such.
        var isInternal = exname.startsWith(EXCEPTION_PREFIX.symbolName());
        // IS_LET flag is required to make sure symbol is not visible outside catch block. However, we need to clear the IS_LET flag after creation to allow redefinition of symbol inside the catch block.
        var symbol = defineSymbol(block, exname, catchNode, IS_VAR | IS_LET | (isInternal ? IS_INTERNAL : 0) | HAS_OBJECT_VALUE);
        symbol.clearFlag(IS_LET);

        return true;
    }

    private void enterFunctionBody() {
        var functionNode = lc.getCurrentFunction();
        var body = lc.getCurrentBlock();

        initFunctionWideVariables(functionNode, body);
        acceptDeclarations(functionNode, body);
        defineFunctionSelfSymbol(functionNode, body);
    }

    private void defineFunctionSelfSymbol(FunctionNode functionNode, Block body) {
        // Function self-symbol is only declared as a local variable for named function expressions.
        // Declared functions don't need it as they are local variables in their declaring scope.
        if (!functionNode.isNamedFunctionExpression()) {
            return;
        }

        var name = functionNode.getIdent().getName();
        assert name != null; // As it's a named function expression.

        if (body.getExistingSymbol(name) != null) {
            // Body already has a declaration for the name.
            // It's either a parameter "function x(x)" or a top-level variable "function x() { ... var x; ... }".
            return;
        }

        defineSymbol(body, name, functionNode, IS_VAR | IS_FUNCTION_SELF | HAS_OBJECT_VALUE);
        if (functionNode.allVarsInScope()) { // basically, has deep eval
            // We must conservatively presume that eval'd code can dynamically use the function symbol.
            lc.setFlag(functionNode, FunctionNode.USES_SELF_SYMBOL);
        }
    }

    @Override
    public boolean enterFunctionNode(FunctionNode functionNode) {
        start(functionNode, false);

        thisProperties.push(new HashSet<String>());

        // Every function has a body, even the ones skipped on reparse (they have an empty one).
        // We're asserting this as even for those, enterBlock() must be invoked to correctly process symbols that are used in them.
        assert functionNode.getBody() != null;

        return true;
    }

    @Override
    public boolean enterVarNode(VarNode varNode) {
        start(varNode);
        // Normally, a symbol assigned in a var statement is not live for its RHS.
        // Since we also represent function declarations as VarNodes, they are exception to the rule, as they need to have the symbol visible to the body of the declared function for self-reference.
        if (varNode.isFunctionDeclaration()) {
            defineVarIdent(varNode);
        }
        return true;
    }

    @Override
    public Node leaveVarNode(VarNode varNode) {
        if (!varNode.isFunctionDeclaration()) {
            defineVarIdent(varNode);
        }
        return super.leaveVarNode(varNode);
    }

    private void defineVarIdent(VarNode varNode) {
        var ident = varNode.getName();
        int flags;
        if (!varNode.isBlockScoped() && lc.getCurrentFunction().isProgram()) {
            flags = IS_SCOPE;
        } else {
            flags = 0;
        }
        defineSymbol(lc.getCurrentBlock(), ident.getName(), ident, varNode.getSymbolFlags() | flags);
    }

    private Symbol exceptionSymbol() {
        return newObjectInternal(EXCEPTION_PREFIX);
    }

    /**
     * This has to run before fix assignment types, store any type specializations for parameters, then turn them into objects for the generic version of this method.
     */
    private FunctionNode finalizeParameters(FunctionNode functionNode) {
        var newParams = new ArrayList<IdentNode>();
        var isVarArg = functionNode.isVarArg();

        var body = functionNode.getBody();
        for (var param : functionNode.getParameters()) {
            var paramSymbol = body.getExistingSymbol(param.getName());
            assert paramSymbol != null;
            assert paramSymbol.isParam() : paramSymbol + " " + paramSymbol.getFlags();
            newParams.add(param.setSymbol(paramSymbol));

            // parameters should not be slots for a function that uses variable arity signature
            if (isVarArg) {
                paramSymbol.setNeedsSlot(false);
            }
        }

        return functionNode.setParameters(lc, newParams);
    }

    /**
     * Search for symbol in the lexical context starting from the given block.
     */
    private Symbol findSymbol(Block block, String name) {
        for (var blocks = lc.getBlocks(block); blocks.hasNext();) {
            var symbol = blocks.next().getExistingSymbol(name);
            if (symbol != null) {
                return symbol;
            }
        }
        return null;
    }

    /**
     * Marks the current function as one using any global symbol.
     * The function and all its parent functions will all be marked as needing parent scope.
     * @see FunctionNode#needsParentScope()
     */
    private void functionUsesGlobalSymbol() {
        for (var fns = lc.getFunctions(); fns.hasNext();) {
            lc.setFlag(fns.next(), FunctionNode.USES_ANCESTOR_SCOPE);
        }
    }

    /**
     * Marks the current function as one using a scoped symbol.
     * The block defining the symbol will be marked as needing its own scope to hold the variable.
     * If the symbol is defined outside of the current function, it and all functions up to (but not including) the function containing the defining block will be marked as needing parent function scope.
     * @see FunctionNode#needsParentScope()
     */
    private void functionUsesScopeSymbol(Symbol symbol) {
        var name = symbol.getName();
        for (var contextNodeIter = lc.getAllNodes(); contextNodeIter.hasNext(); ) {
            var node = contextNodeIter.next();
            if (node instanceof Block) {
                var block = (Block)node;
                if (block.getExistingSymbol(name) != null) {
                    assert lc.contains(block);
                    lc.setBlockNeedsScope(block);
                    break;
                }
            } else if (node instanceof FunctionNode) {
                lc.setFlag(node, FunctionNode.USES_ANCESTOR_SCOPE);
            }
        }
    }

    /**
     * Declares that the current function is using the symbol.
     */
    private void functionUsesSymbol(Symbol symbol) {
        assert symbol != null;
        if (symbol.isScope()) {
            if (symbol.isGlobal()) {
                functionUsesGlobalSymbol();
            } else {
                functionUsesScopeSymbol(symbol);
            }
        } else {
            assert !symbol.isGlobal(); // Every global is also scope
        }
    }

    private void initCompileConstant(CompilerConstants cc, Block block, int flags) {
        defineSymbol(block, cc.symbolName(), null, flags).setNeedsSlot(true);
    }

    private void initFunctionWideVariables(FunctionNode functionNode, Block body) {
        initCompileConstant(CALLEE, body, IS_PARAM | IS_INTERNAL | HAS_OBJECT_VALUE);
        initCompileConstant(THIS, body, IS_PARAM | IS_THIS | HAS_OBJECT_VALUE);

        if (functionNode.isVarArg()) {
            initCompileConstant(VARARGS, body, IS_PARAM | IS_INTERNAL | HAS_OBJECT_VALUE);
            if (functionNode.needsArguments()) {
                initCompileConstant(ARGUMENTS, body, IS_VAR | IS_INTERNAL | HAS_OBJECT_VALUE);
                defineSymbol(body, ARGUMENTS_VAR.symbolName(), null, IS_VAR | HAS_OBJECT_VALUE);
            }
        }

        initParameters(functionNode, body);
        initCompileConstant(SCOPE, body, IS_VAR | IS_INTERNAL | HAS_OBJECT_VALUE);
        initCompileConstant(RETURN, body, IS_VAR | IS_INTERNAL);
    }

    /**
     * Initialize parameters for function node.
     */
    private void initParameters(FunctionNode functionNode, Block body) {
        var isVarArg = functionNode.isVarArg();
        var scopeParams = functionNode.allVarsInScope() || isVarArg;
        for (var param : functionNode.getParameters()) {
            var symbol = defineSymbol(body, param.getName(), param, IS_PARAM);
            if (scopeParams) {
                // NOTE: this "set is scope" is a poor substitute for clear expression of where the symbol is stored.
                // It will force creation of scopes where they would otherwise not necessarily be needed (functions using arguments object and other variable arity functions).
                // Tracked by JDK-8038942.
                symbol.setIsScope();
                assert symbol.hasSlot();
                if (isVarArg) {
                    symbol.setNeedsSlot(false);
                }
            }
        }
    }

    /**
     * Is the symbol local to (that is, defined in) the specified function?
     */
    private boolean isLocal(FunctionNode function, Symbol symbol) {
        var definingFn = lc.getDefiningFunction(symbol);
        assert definingFn != null;
        return definingFn == function;
    }

    @Override
    public Node leaveBinaryNode(BinaryNode binaryNode) {
        if (binaryNode.isTokenType(TokenType.ASSIGN)) {
            return leaveASSIGN(binaryNode);
        }
        return super.leaveBinaryNode(binaryNode);
    }

    private Node leaveASSIGN(BinaryNode binaryNode) {
        // If we're assigning a property of the this object ("this.foo = ..."), record it.
        var lhs = binaryNode.lhs();
        if (lhs instanceof AccessNode) {
            var accessNode = (AccessNode) lhs;
            var base = accessNode.getBase();
            if (base instanceof IdentNode) {
                var symbol = ((IdentNode)base).getSymbol();
                if (symbol.isThis()) {
                    thisProperties.peek().add(accessNode.getProperty());
                }
            }
        }
        return binaryNode;
    }

    @Override
    public Node leaveUnaryNode(UnaryNode unaryNode) {
        if (unaryNode.tokenType() == TokenType.TYPEOF) {
            return leaveTYPEOF(unaryNode);
        } else {
            return super.leaveUnaryNode(unaryNode);
        }
    }

    @Override
    public Node leaveForNode(ForNode forNode) {
        if (forNode.isForInOrOf()) {
            return forNode.setIterator(lc, newObjectInternal(ITERATOR_PREFIX)); //NASHORN-73
        }
        return end(forNode);
    }

    @Override
    public Node leaveFunctionNode(FunctionNode functionNode) {
        FunctionNode finalizedFunction;
        if (isUnparsedFunction(functionNode)) {
            finalizedFunction = functionNode;
        } else {
            finalizedFunction = markProgramBlock(removeUnusedSlots(createSyntheticInitializers(finalizeParameters(lc.applyTopFlags(functionNode)))).setThisProperties(lc, thisProperties.pop().size()));
        }
        return finalizedFunction;
    }

    @Override
    public Node leaveIdentNode(IdentNode identNode) {
        if (identNode.isPropertyName()) {
            return identNode;
        }

        var symbol = nameIsUsed(identNode.getName(), identNode);

        if (!identNode.isInitializedHere()) {
            symbol.increaseUseCount();
        }

        IdentNode newIdentNode = identNode.setSymbol(symbol);

        // If a block-scoped var is used before its declaration mark it as dead.
        // We can only statically detect this for local vars, cross-function symbols require runtime checks.
        if (symbol.isBlockScoped() && !symbol.hasBeenDeclared() && !identNode.isDeclaredHere() && isLocal(lc.getCurrentFunction(), symbol)) {
            newIdentNode = newIdentNode.markDead();
        }

        return end(newIdentNode);
    }

    private Symbol nameIsUsed(String name, IdentNode origin) {
        var block = lc.getCurrentBlock();

        var symbol = findSymbol(block, name);

        //If an existing symbol with the name is found, use that otherwise, declare a new one
        if (symbol != null) {
            log.info("Existing symbol = ", symbol);
            if (symbol.isFunctionSelf()) {
                var functionNode = lc.getDefiningFunction(symbol);
                assert functionNode != null;
                assert lc.getFunctionBody(functionNode).getExistingSymbol(CALLEE.symbolName()) != null;
                lc.setFlag(functionNode, FunctionNode.USES_SELF_SYMBOL);
            }

            // if symbol is non-local or we're in a with block, we need to put symbol in scope (if it isn't already)
            maybeForceScope(symbol);
        } else {
            log.info("No symbol exists. Declare as global: ", name);
            symbol = defineSymbol(block, name, origin, IS_GLOBAL | IS_SCOPE);
        }

        functionUsesSymbol(symbol);
        return symbol;
    }

    @Override
    public Node leaveSwitchNode(SwitchNode switchNode) {
        // We only need a symbol for the tag if it's not an integer switch node
        if (!switchNode.isUniqueInteger()) {
            return switchNode.setTag(lc, newObjectInternal(SWITCH_TAG_PREFIX));
        }
        return switchNode;
    }

    @Override
    public Node leaveTryNode(TryNode tryNode) {
        assert tryNode.getFinallyBody() == null;

        end(tryNode);

        return tryNode.setException(lc, exceptionSymbol());
    }

    private Node leaveTYPEOF(UnaryNode unaryNode) {
        var rhs = unaryNode.getExpression();

        var args = new ArrayList<Expression>();
        if (rhs instanceof IdentNode && !isParamOrVar((IdentNode)rhs)) {
            args.add(compilerConstantIdentifier(SCOPE));
            args.add(LiteralNode.newInstance(rhs, ((IdentNode)rhs).getName())); //null
        } else {
            args.add(rhs);
            args.add(LiteralNode.newInstance(unaryNode)); //null, do not reuse token of identifier rhs, it can be e.g. 'this'
        }

        var runtimeNode = new RuntimeNode(unaryNode, Request.TYPEOF, args);

        end(unaryNode);

        return runtimeNode;
    }

    private FunctionNode markProgramBlock(FunctionNode functionNode) {
        if (isOnDemand || !functionNode.isProgram()) {
            return functionNode;
        }

        return functionNode.setBody(lc, functionNode.getBody().setFlag(lc, Block.IS_GLOBAL_SCOPE));
    }

    /**
     * If the symbol isn't already a scope symbol, but it needs to be (see {@link #symbolNeedsToBeScope(Symbol)}, it is promoted to a scope symbol and its block marked as needing a scope.
     */
    private void maybeForceScope(Symbol symbol) {
        if (!symbol.isScope() && symbolNeedsToBeScope(symbol)) {
            Symbol.setSymbolIsScope(lc, symbol);
        }
    }

    private Symbol newInternal(CompilerConstants cc, int flags) {
        return defineSymbol(lc.getCurrentBlock(), lc.getCurrentFunction().uniqueName(cc.symbolName()), null, IS_VAR | IS_INTERNAL | flags); //NASHORN-73
    }

    private Symbol newObjectInternal(CompilerConstants cc) {
        return newInternal(cc, HAS_OBJECT_VALUE);
    }

    private boolean start(Node node) {
        return start(node, true);
    }

    private boolean start(Node node, boolean printNode) {
        if (debug) {
            var sb = new StringBuilder();

            sb.append("[ENTER ").
               append(name(node)).
               append("] ").
               append(printNode ? node.toString() : "").
               append(" in '").
               append(lc.getCurrentFunction().getName()).
               append("'");
            log.info(sb);
            log.indent();
        }

        return true;
    }

    /**
     * Determines if the symbol has to be a scope symbol.
     * In general terms, it has to be a scope symbol if it can only be reached from the current block by traversing a function node, a split node, or a with node.
     */
    private boolean symbolNeedsToBeScope(Symbol symbol) {
        if (symbol.isThis() || symbol.isInternal()) {
            return false;
        }

        var func = lc.getCurrentFunction();
        if (func.allVarsInScope() || (!symbol.isBlockScoped() && func.isProgram())) {
            return true;
        }

        var previousWasBlock = false;
        for (var it = lc.getAllNodes(); it.hasNext();) {
            var node = it.next();
            if (node instanceof FunctionNode || isSplitLiteral(node)) {
                // We reached the function boundary or a splitting boundary without seeing a definition for the symbol.
                // It needs to be in scope.
                return true;
            } else if (node instanceof Block) {
                if (((Block)node).getExistingSymbol(symbol.getName()) == symbol) {
                    // We reached the block that defines the symbol without reaching either the function boundary, or a WithNode.
                    // The symbol need not be scoped.
                    return false;
                }
                previousWasBlock = true;
            } else {
                previousWasBlock = false;
            }
        }
        throw new AssertionError();
    }

    private static boolean isSplitLiteral(LexicalContextNode expr) {
        return expr instanceof Splittable && ((Splittable) expr).getSplitRanges() != null;
    }

    private void throwUnprotectedSwitchError(VarNode varNode) {
        // Block scoped declarations in switch statements without explicit blocks should be declared in a common block that contains all the case clauses.
        // We cannot support this without a fundamental rewrite of how switch statements are handled (case nodes contain blocks and are directly contained by switch node).
        // As a temporary solution we throw a reference error here.
        var msg = ECMAErrors.getMessage("syntax.error.unprotected.switch.declaration", varNode.isLet() ? "let" : "const");
        throwParserException(msg, varNode);
    }

    private void throwParserException(String message, Node origin) {
        if (origin == null) {
            throw new ParserException(message);
        }
        var source = compiler.getSource();
        var token = origin.getToken();
        var line = source.getLine(origin.getStart());
        var column = source.getColumn(origin.getStart());
        var formatted = ErrorManager.format(message, source, line, column, token);
        throw new ParserException(JSErrorType.SYNTAX_ERROR, formatted, source, line, column, token);
    }

}
