/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import nashorn.internal.ir.Block;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.LexicalContext;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.Symbol;
import nashorn.internal.ir.visitor.SimpleNodeVisitor;
import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.RecompilableScriptFunctionData;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.logging.Loggable;
import nashorn.internal.runtime.logging.Logger;
import static nashorn.internal.runtime.logging.DebugLogger.quote;

/**
 * Establishes depth of scope for non local symbols at the start of method.
 *
 * If this is a recompilation, the previous data from eager compilation is stored in the RecompilableScriptFunctionData and is transferred to the FunctionNode being compiled.
 */
@Logger(name="scopedepths")
final class FindScopeDepths extends SimpleNodeVisitor implements Loggable {

    private final Compiler compiler;
    private final Map<Integer, Map<Integer, RecompilableScriptFunctionData>> fnIdToNestedFunctions = new HashMap<>();
    private final Map<Integer, Map<String, Integer>> externalSymbolDepths = new HashMap<>();
    private final Map<Integer, Set<String>> internalSymbols = new HashMap<>();
    private final Set<Block> withBodies = new HashSet<>();

    private final DebugLogger log;

    private int dynamicScopeCount;

    FindScopeDepths(Compiler compiler) {
        this.compiler = compiler;
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

    static int findScopesToStart(LexicalContext lc, FunctionNode fn, Block block) {
        var bodyBlock = findBodyBlock(lc, fn, block);
        var iter = lc.getBlocks(block);
        var b = iter.next();
        var scopesToStart = 0;
        for (;;) {
            if (b.needsScope()) {
                scopesToStart++;
            }
            if (b == bodyBlock) {
                break;
            }
            b = iter.next();
        }
        return scopesToStart;
    }

    static int findInternalDepth(LexicalContext lc, FunctionNode fn, Block block, Symbol symbol) {
        var bodyBlock = findBodyBlock(lc, fn, block);
        var iter = lc.getBlocks(block);
        var b = iter.next();
        var scopesToStart = 0;
        for (;;) {
            if (definedInBlock(b, symbol)) {
                return scopesToStart;
            }
            if (b.needsScope()) {
                scopesToStart++;
            }
            if (b == bodyBlock) {
                break; //don't go past body block, but process it
            }
            b = iter.next();
        }
        return -1;
    }

    private static boolean definedInBlock(Block block, Symbol symbol) {
        if (symbol.isGlobal()) {
            //globals cannot be defined anywhere else

            return block.isGlobalScope();
        }
        return block.getExistingSymbol(symbol.getName()) == symbol;
    }

    static Block findBodyBlock(LexicalContext lc, FunctionNode fn, Block block) {
        var iter = lc.getBlocks(block);
        while (iter.hasNext()) {
            var next = iter.next();
            if (fn.getBody() == next) {
                return next;
            }
        }
        return null;
    }

    private static Block findGlobalBlock(LexicalContext lc, Block block) {
        var iter = lc.getBlocks(block);
        Block globalBlock = null;
        while (iter.hasNext()) {
            globalBlock = iter.next();
        }
        return globalBlock;
    }

    private boolean isDynamicScopeBoundary(Block block) {
        return withBodies.contains(block);
    }

    @Override
    public boolean enterFunctionNode(FunctionNode functionNode) {
        if (compiler.isOnDemandCompilation()) {
            return true;
        }

        var fnId = functionNode.getId();
        var nestedFunctions = fnIdToNestedFunctions.get(fnId);
        if (nestedFunctions == null) {
            nestedFunctions = new HashMap<>();
            fnIdToNestedFunctions.put(fnId, nestedFunctions);
        }

        return true;
    }

    //external symbols hold the scope depth of sc11 from global at the start of the method
    @Override
    public Node leaveFunctionNode(FunctionNode functionNode) {
        var name = functionNode.getName();
        var newFunctionNode = functionNode;
        if (compiler.isOnDemandCompilation()) {
            var data = compiler.getScriptFunctionData(newFunctionNode.getId());
            if (data.inDynamicContext()) {
                log.fine("Reviving scriptfunction ", quote(name), " as defined in previous (now lost) dynamic scope.");
                newFunctionNode = newFunctionNode.setInDynamicContext(lc);
            }
            if (newFunctionNode == lc.getOutermostFunction() && !newFunctionNode.hasApplyToCallSpecialization()) {
                data.setCachedAst(newFunctionNode);
            }
            return newFunctionNode;
        }

        if (inDynamicScope()) {
            log.fine("Tagging ", quote(name), " as defined in dynamic scope");
            newFunctionNode = newFunctionNode.setInDynamicContext(lc);
        }

        // create recompilable scriptfunctiondata
        var fnId = newFunctionNode.getId();
        var nestedFunctions = fnIdToNestedFunctions.remove(fnId);

        assert nestedFunctions != null;
        // Generate the object class and property map in case this function is ever used as constructor
        var data = new RecompilableScriptFunctionData(newFunctionNode, compiler.getCodeInstaller(), ObjectClassGenerator.createAllocationStrategy(newFunctionNode.getThisProperties(), compiler.getContext().useDualFields()), nestedFunctions, externalSymbolDepths.get(fnId), internalSymbols.get(fnId));

        if (lc.getOutermostFunction() != newFunctionNode) {
            var parentFn = lc.getParentFunction(newFunctionNode);
            if (parentFn != null) {
                fnIdToNestedFunctions.get(parentFn.getId()).put(fnId, data);
            }
        } else {
            compiler.setData(data);
        }

        return newFunctionNode;
    }

    private boolean inDynamicScope() {
        return dynamicScopeCount > 0;
    }

    private void increaseDynamicScopeCount(Node node) {
        assert dynamicScopeCount >= 0;
        ++dynamicScopeCount;
        if (log.isEnabled()) {
            log.finest(quote(lc.getCurrentFunction().getName()), " ++dynamicScopeCount = ", dynamicScopeCount, " at: ", node, node.getClass());
        }
    }

    private void decreaseDynamicScopeCount(Node node) {
        --dynamicScopeCount;
        assert dynamicScopeCount >= 0;
        if (log.isEnabled()) {
            log.finest(quote(lc.getCurrentFunction().getName()), " --dynamicScopeCount = ", dynamicScopeCount, " at: ", node, node.getClass());
        }
    }

    @Override
    public boolean enterBlock(Block block) {
        if (compiler.isOnDemandCompilation()) {
            return true;
        }

        if (isDynamicScopeBoundary(block)) {
            increaseDynamicScopeCount(block);
        }

        if (!lc.isFunctionBody()) {
            return true;
        }

        // The below part only happens on eager compilation when we have the entire hierarchy block is a function body
        var fn = lc.getCurrentFunction();

        // Get all symbols that are referenced inside this function body
        var symbols = new HashSet<Symbol>();
        block.accept(new SimpleNodeVisitor() {
            @Override
            public boolean enterIdentNode(IdentNode identNode) {
                var symbol = identNode.getSymbol();
                if (symbol != null && symbol.isScope()) {
                    //if this is an internal symbol, skip it.
                    symbols.add(symbol);
                }
                return true;
            }
        });

        var internals = new HashMap<String, Integer>();

        var globalBlock = findGlobalBlock(lc, block);
        var bodyBlock   = findBodyBlock(lc, fn, block);

        assert globalBlock != null;
        assert bodyBlock   != null;

        for (var symbol : symbols) {
            Iterator<Block> iter;

            var internalDepth = findInternalDepth(lc, fn, block, symbol);
            var internal = internalDepth >= 0;
            if (internal) {
                internals.put(symbol.getName(), internalDepth);
            }

            // If not internal, we have to continue walking until we reach the top.
            // We start outside the body and each new scope adds a depth count.
            // When we find the symbol, we store its depth count.
            if (!internal) {
                var depthAtStart = 0;
                // not internal - keep looking.
                iter = lc.getAncestorBlocks(bodyBlock);
                while (iter.hasNext()) {
                    var b2 = iter.next();
                    if (definedInBlock(b2, symbol)) {
                        addExternalSymbol(fn, symbol, depthAtStart);
                        break;
                    }
                    if (b2.needsScope()) {
                        depthAtStart++;
                    }
                }
            }
        }

        addInternalSymbols(fn, internals.keySet());

        if (log.isEnabled()) {
            log.info(fn.getName() + " internals=" + internals + " externals=" + externalSymbolDepths.get(fn.getId()));
        }

        return true;
    }

    @Override
    public Node leaveBlock(Block block) {
        if (compiler.isOnDemandCompilation()) {
            return block;
        }
        if (isDynamicScopeBoundary(block)) {
            decreaseDynamicScopeCount(block);
        }
        return block;
    }

    private void addInternalSymbols(FunctionNode functionNode, Set<String> symbols) {
        var fnId = functionNode.getId();
        assert internalSymbols.get(fnId) == null || internalSymbols.get(fnId).equals(symbols); //e.g. cloned finally block
        internalSymbols.put(fnId, symbols);
    }

    private void addExternalSymbol(FunctionNode functionNode, Symbol symbol, int depthAtStart) {
        var fnId = functionNode.getId();
        var depths = externalSymbolDepths.get(fnId);
        if (depths == null) {
            depths = new HashMap<>();
            externalSymbolDepths.put(fnId, depths);
        }
        depths.put(symbol.getName(), depthAtStart);
    }

}
