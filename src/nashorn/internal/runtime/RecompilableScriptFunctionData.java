/*
 * Copyright (c) 2010, 2019, Oracle and/or its affiliates. All rights reserved.
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

package nashorn.internal.runtime;

import static nashorn.internal.lookup.Lookup.MH;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import nashorn.internal.codegen.Compiler;
import nashorn.internal.codegen.Compiler.CompilationPhases;
import nashorn.internal.codegen.CompilerConstants;
import nashorn.internal.codegen.FunctionSignature;
import nashorn.internal.codegen.Namespace;
import nashorn.internal.codegen.OptimisticTypesPersistence;
import nashorn.internal.codegen.TypeMap;
import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.Block;
import nashorn.internal.ir.ForNode;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.LexicalContext;
import nashorn.internal.ir.Node;
import nashorn.internal.ir.SwitchNode;
import nashorn.internal.ir.Symbol;
import nashorn.internal.ir.TryNode;
import nashorn.internal.ir.visitor.SimpleNodeVisitor;
import nashorn.internal.objects.Global;
import nashorn.internal.parser.Parser;
import nashorn.internal.parser.Token;
import nashorn.internal.parser.TokenType;
import nashorn.internal.runtime.linker.NameCodec;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.logging.Loggable;
import nashorn.internal.runtime.logging.Logger;
import nashorn.internal.runtime.options.Options;
/**
 * This is a subclass that represents a script function that may be regenerated, for example with specialization based on call site types, or lazily generated.
 * The common denominator is that it can get new invokers during its lifespan, unlike {@code FinalScriptFunctionData}
 */
@Logger(name="recompile")
public final class RecompilableScriptFunctionData extends ScriptFunctionData implements Loggable {

    /** Prefix used for all recompiled script classes */
    public static final String RECOMPILATION_PREFIX = "Recompilation$";

    private static final ExecutorService astSerializerExecutorService = createAstSerializerExecutorService();

    /** Unique function node id for this function node */
    private final int functionNodeId;

    private final String functionName;

    /** The line number where this function begins. */
    private final int lineNumber;

    /** Source from which FunctionNode was parsed. */
    private transient Source source;

    /**
     * Cached form of the AST.
     * Either a {@code SerializedAst} object used by split functions as they can't be reparsed from source, or a soft reference to a {@code FunctionNode} for other functions (it is safe to be cleared as they can be reparsed).
     */
    private volatile transient Object cachedAst;

    /** Token of this function within the source. */
    private final long token;

    /**
     * Represents the allocation strategy (property map, script object class, and method handle) for when this function is used as a constructor.
     * Note that majority of functions (those not setting any this.* properties) will share a single canonical "default strategy" instance.
     */
    private final AllocationStrategy allocationStrategy;

    /**
     * Opaque object representing parser state at the end of the function.
     * Used when reparsing outer function to help with skipping parsing inner functions.
     */
    @SuppressWarnings("serial") // Not statically typed as Serializable
    private final Object endParserState;

    /** Code installer used for all further recompilation/specialization of this ScriptFunction */
    private transient CodeInstaller installer;

    @SuppressWarnings("serial") // Not statically typed as Serializable
    private final Map<Integer, RecompilableScriptFunctionData> nestedFunctions;

    /** Id to parent function if one exists */
    private RecompilableScriptFunctionData parent;

    /** Copy of the {@link FunctionNode} flags. */
    private final int functionFlags;

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private transient DebugLogger log;

    @SuppressWarnings("serial") // Not statically typed as Serializable
    private final Map<String, Integer> externalScopeDepths;

    @SuppressWarnings("serial") // Not statically typed as Serializable
    private final Set<String> internalSymbols;

    private static final int GET_SET_PREFIX_LENGTH = "*et ".length();

    /**
     * Constructor - public as scripts use it
     * @param functionNode        functionNode that represents this function code
     * @param installer           installer for code regeneration versions of this function
     * @param allocationStrategy  strategy for the allocation behavior when this function is used as a constructor
     * @param nestedFunctions     nested function map
     * @param externalScopeDepths external scope depths
     * @param internalSymbols     internal symbols to method, defined in its scope
     */
    public RecompilableScriptFunctionData(FunctionNode functionNode, CodeInstaller installer, AllocationStrategy allocationStrategy, Map<Integer, RecompilableScriptFunctionData> nestedFunctions, Map<String, Integer> externalScopeDepths, Set<String> internalSymbols) {
        super(functionName(functionNode), Math.min(functionNode.getParameters().size(), MAX_ARITY), getDataFlags(functionNode));

        this.functionName = functionNode.getName();
        this.lineNumber = functionNode.getLineNumber();
        this.functionFlags = functionNode.getFlags() | (functionNode.needsCallee() ? FunctionNode.NEEDS_CALLEE : 0);
        this.functionNodeId = functionNode.getId();
        this.source = functionNode.getSource();
        this.endParserState = functionNode.getEndParserState();
        this.token = tokenFor(functionNode);
        this.installer = installer;
        this.allocationStrategy = allocationStrategy;
        this.nestedFunctions = smallMap(nestedFunctions);
        this.externalScopeDepths = smallMap(externalScopeDepths);
        this.internalSymbols = smallSet(new HashSet<>(internalSymbols));

        for (var nfn : nestedFunctions.values()) {
            assert nfn.getParent() == null;
            nfn.setParent(this);
        }

        createLogger();
    }

    private static <K, V> Map<K, V> smallMap(Map<K, V> map) {
        if (map == null || map.isEmpty()) {
            return Collections.emptyMap();
        } else if (map.size() == 1) {
            var entry = map.entrySet().iterator().next();
            return Collections.singletonMap(entry.getKey(), entry.getValue());
        } else {
            return map;
        }
    }

    private static <T> Set<T> smallSet(Set<T> set) {
        if (set == null || set.isEmpty()) {
            return Collections.emptySet();
        } else if (set.size() == 1) {
            return Collections.singleton(set.iterator().next());
        } else {
            return set;
        }
    }

    @Override
    public DebugLogger getLogger() {
        return log;
    }

    @Override
    public DebugLogger initLogger(Context ctxt) {
        return ctxt.getLogger(this.getClass());
    }

    /**
     * Check if a symbol is internally defined in a function.
     * For example if "undefined" is internally defined in the outermost program function, it has not been reassigned or overridden and can be optimized
     * @param symbolName symbol name
     * @return true if symbol is internal to this ScriptFunction
     */

    public boolean hasInternalSymbol(String symbolName) {
        return internalSymbols.contains(symbolName);
    }

    /**
     * Return the external symbol table
     * @param symbolName symbol name
     * @return the external symbol table with proto depths
     */
    public int getExternalSymbolDepth(String symbolName) {
        var depth = externalScopeDepths.get(symbolName);
        return depth == null ? -1 : depth;
    }

    /**
     * Returns the names of all external symbols this function uses.
     */
    public Set<String> getExternalSymbolNames() {
        return Collections.unmodifiableSet(externalScopeDepths.keySet());
    }

    /**
     * Returns the opaque object representing the parser state at the end of this function's body, used to skip parsing this function when reparsing its containing outer function.
     */
    public Object getEndParserState() {
        return endParserState;
    }

    /**
     * Get the parent of this RecompilableScriptFunctionData.
     * If we are nested function, we have a parent.
     * Note that "null" return value can also mean that we have a parent but it is unknown, so this can only be used for conservative assumptions.
     * @return parent data, or null if non exists and also null IF UNKNOWN.
     */
    public RecompilableScriptFunctionData getParent() {
        return parent;
    }

    void setParent(RecompilableScriptFunctionData parent) {
        this.parent = parent;
    }

    @Override
    String toSource() {
        if (source != null && token != 0) {
            return source.getString(Token.descPosition(token), Token.descLength(token));
        }

        return "function " + (name == null ? "" : name) + "() { [native code] }";
    }

    /**
     * Initialize transient fields on deserialized instances
     * @param src source
     * @param inst code installer
     */
    public void initTransients(Source src, CodeInstaller inst) {
        if (this.source == null && this.installer == null) {
            this.source = src;
            this.installer = inst;
            for (var nested : nestedFunctions.values()) {
                nested.initTransients(src, inst);
            }
        } else if (this.source != src || !this.installer.isCompatibleWith(inst)) {
            // Existing values must be same as those passed as parameters
            throw new IllegalArgumentException();
        }
    }

    @Override
    public String toString() {
        return super.toString() + '@' + functionNodeId;
    }

    @Override
    public String toStringVerbose() {
        var sb = new StringBuilder();

        sb.append("fnId=").append(functionNodeId).append(' ');

        if (source != null) {
            sb.append(source.getName())
              .append(':')
              .append(lineNumber)
              .append(' ');
        }

        return sb.toString() + super.toString();
    }

    @Override
    public String getFunctionName() {
        return functionName;
    }

    @Override
    public boolean inDynamicContext() {
        return getFunctionFlag(FunctionNode.IN_DYNAMIC_CONTEXT);
    }

    private static String functionName(FunctionNode fn) {
        if (fn.isAnonymous()) {
            return "";
        }
        var kind = fn.getKind();
        if (kind == FunctionNode.Kind.GETTER || kind == FunctionNode.Kind.SETTER) {
            var name = NameCodec.decode(fn.getIdent().getName());
            return name.substring(GET_SET_PREFIX_LENGTH);
        }
        return fn.getIdent().getName();
    }

    private static long tokenFor(FunctionNode fn) {
        var position = Token.descPosition(fn.getFirstToken());
        var lastToken = Token.withDelimiter(fn.getLastToken());
        // EOL uses length field to store the line number
        var length = Token.descPosition(lastToken) - position + (Token.descType(lastToken) == TokenType.EOL ? 0 : Token.descLength(lastToken));

        return Token.toDesc(TokenType.FUNCTION, position, length);
    }

    private static int getDataFlags(FunctionNode functionNode) {
        var flags = IS_CONSTRUCTOR;
        if (functionNode.needsCallee()) {
            flags |= NEEDS_CALLEE;
        }
        if (functionNode.usesThis() || functionNode.hasEval()) {
            flags |= USES_THIS;
        }
        if (functionNode.isVarArg()) {
            flags |= IS_VARIABLE_ARITY;
        }
        if (functionNode.getKind() == FunctionNode.Kind.GETTER || functionNode.getKind() == FunctionNode.Kind.SETTER) {
            flags |= IS_PROPERTY_ACCESSOR;
        }
        if (functionNode.isMethod() || functionNode.isClassConstructor()) {
            flags |= IS_ES6_METHOD;
        }
        return flags;
    }

    @Override
    PropertyMap getAllocatorMap(ScriptObject prototype) {
        return allocationStrategy.getAllocatorMap(prototype);
    }

    @Override
    ScriptObject allocate(PropertyMap map) {
        return allocationStrategy.allocate(map);
    }

    FunctionNode reparse() {
        var cachedFunction = getCachedAst();
        if (cachedFunction != null) {
            assert cachedFunction.isCached();
            return cachedFunction;
        }

        var descPosition = Token.descPosition(token);
        var context = Context.getContextTrusted();
        var parser = new Parser(
            context.getEnv(),
            source,
            new Context.ThrowErrorManager(),
            // source starts at line 0, so even though lineNumber is the correct declaration line, back off one to make it exclusive
            lineNumber - 1,
            context.getLogger(Parser.class));

        if (getFunctionFlag(FunctionNode.IS_ANONYMOUS)) {
            parser.setFunctionName(functionName);
        }
        parser.setReparsedFunction(this);

        var program = parser.parse(CompilerConstants.PROGRAM.symbolName(), descPosition, Token.descLength(token), flags);
        // Parser generates a program AST even if we're recompiling a single function, so when we are only recompiling a single function, extract it from the program.
        return (isProgram() ? program : extractFunctionFromScript(program)).setName(null, functionName);
    }

    private FunctionNode getCachedAst() {
        var lCachedAst = cachedAst;
        // Are we softly caching the AST?
        if (lCachedAst instanceof Reference<?>) {
            var fn = (FunctionNode)((Reference<?>)lCachedAst).get();
            if (fn != null) {
                // Yes we are - this is fast
                return cloneSymbols(fn);
            }
        // Are we strongly caching a serialized AST (for split functions only)?
        } else if (lCachedAst instanceof SerializedAst) {
            var serializedAst = (SerializedAst)lCachedAst;
            // Even so, are we also softly caching the AST?
            var cachedFn = serializedAst.cachedAst == null ? null : serializedAst.cachedAst.get();
            if (cachedFn != null) {
                // Yes we are - this is fast
                return cloneSymbols(cachedFn);
            }
            var deserializedFn = deserialize(serializedAst.serializedAst);
            // Softly cache after deserialization, maybe next time we won't need to deserialize
            serializedAst.cachedAst = new SoftReference<>(deserializedFn);
            return deserializedFn;
        }
        // No cached representation; return null for reparsing
        return null;
    }

    /**
     * Sets the AST to cache in this function
     * @param astToCache the new AST to cache
     */
    public void setCachedAst(FunctionNode astToCache) {
        assert astToCache.getId() == functionNodeId; // same function
        assert !(cachedAst instanceof SerializedAst); // Can't overwrite serialized AST

        var isSplit = astToCache.isSplit();
        // If we're caching a split function, we're doing it in the eager pass, hence there can be no other
        // cached representation already. In other words, isSplit implies cachedAst == null.
        assert !isSplit || cachedAst == null; //

        var symbolClonedAst = cloneSymbols(astToCache);
        var ref = new SoftReference<>(symbolClonedAst);
        cachedAst = ref;

        // Asynchronously serialize split functions.
        if (isSplit) {
            astSerializerExecutorService.execute(() -> {
                cachedAst = new SerializedAst(symbolClonedAst, ref);
            });
        }
    }

    /**
     * Creates the AST serializer executor service used for in-memory serialization of split functions' ASTs.
     * It is created with an unbounded queue (so it can queue any number of pending tasks).
     * Its core and max threads is the same, but they are all allowed to time out so when there's no work, they can all go away.
     * The threads will be daemons, and they will time out if idle for a minute.
     * Their priority is also slightly lower than normal priority as we'd prefer the CPU to keep running the program; serializing split function is a memory conservation measure (it allows us to release the AST), it can wait a bit.
     * @return an executor service with above described characteristics.
     */
    private static ExecutorService createAstSerializerExecutorService() {
        var threads = Math.max(1, Options.getIntProperty("nashorn.serialize.threads", Runtime.getRuntime().availableProcessors() / 2));
        var service = new ThreadPoolExecutor(threads, threads, 1, TimeUnit.MINUTES, new LinkedBlockingDeque<>(),
            (r) -> {
                var t = new Thread(r, "Nashorn AST Serializer");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        service.allowCoreThreadTimeOut(true);
        return service;
    }

    /**
     * A tuple of a serialized AST and a soft reference to a deserialized AST.
     * This is used to cache split functions.
     * Since split functions are altered from their source form, they can't be reparsed from source.
     * While we could just use the {@code byte[]} representation in {@link RecompilableScriptFunctionData#cachedAst} we're using this tuple instead to also keep a deserialized AST around in memory to cut down on deserialization costs.
     */
    private static class SerializedAst implements Serializable {
        private final byte[] serializedAst;
        private volatile transient Reference<FunctionNode> cachedAst;

        SerializedAst(FunctionNode fn, Reference<FunctionNode> cachedAst) {
            this.serializedAst = AstSerializer.serialize(fn);
            this.cachedAst = cachedAst;
        }
    }

    private FunctionNode deserialize(byte[] serializedAst) {
        var env = installer.getContext().getEnv();
        var timing = env._timing;
        var t1 = System.nanoTime();
        try {
            return AstDeserializer.deserialize(serializedAst).initializeDeserialized(source, new Namespace(env.getNamespace()));
        } finally {
            timing.accumulateTime("'Deserialize'", System.nanoTime() - t1);
        }
    }

    private FunctionNode cloneSymbols(FunctionNode fn) {
        var symbolReplacements = new IdentityHashMap<Symbol, Symbol>();
        var cached = fn.isCached();
        // blockDefinedSymbols is used to re-mark symbols defined outside the function as global.
        // We only need to do this when we cache an eagerly parsed function (which currently means a split one, as we don't cache non-split functions from the eager pass); those already cached, or those not split don't need this step.
        var blockDefinedSymbols = fn.isSplit() && !cached ? Collections.newSetFromMap(new IdentityHashMap<>()) : null;
        var newFn = (FunctionNode)fn.accept(new SimpleNodeVisitor() {
            private Symbol getReplacement(Symbol original) {
                if (original == null) {
                    return null;
                }
                var existingReplacement = symbolReplacements.get(original);
                if (existingReplacement != null) {
                    return existingReplacement;
                }
                var newReplacement = original.clone();
                symbolReplacements.put(original, newReplacement);
                return newReplacement;
            }

            @Override
            public Node leaveIdentNode(IdentNode identNode) {
                var oldSymbol = identNode.getSymbol();
                if (oldSymbol != null) {
                    var replacement = getReplacement(oldSymbol);
                    return identNode.setSymbol(replacement);
                }
                return identNode;
            }

            @Override
            public Node leaveForNode(ForNode forNode) {
                return ensureUniqueLabels(forNode.setIterator(lc, getReplacement(forNode.getIterator())));
            }

            @Override
            public Node leaveSwitchNode(SwitchNode switchNode) {
                return ensureUniqueLabels(switchNode.setTag(lc, getReplacement(switchNode.getTag())));
            }

            @Override
            public Node leaveTryNode(TryNode tryNode) {
                return ensureUniqueLabels(tryNode.setException(lc, getReplacement(tryNode.getException())));
            }

            @Override
            public boolean enterBlock(Block block) {
                for (var symbol: block.getSymbols()) {
                    var replacement = getReplacement(symbol);
                    if (blockDefinedSymbols != null) {
                        blockDefinedSymbols.add(replacement);
                    }
                }
                return true;
            }

            @Override
            public Node leaveBlock(Block block) {
                return ensureUniqueLabels(block.replaceSymbols(lc, symbolReplacements));
            }

            @Override
            public Node leaveFunctionNode(FunctionNode functionNode) {
                return functionNode.setParameters(lc, functionNode.visitParameters(this));
            }

            @Override
            protected Node leaveDefault(Node node) {
                return ensureUniqueLabels(node);
            };

            private Node ensureUniqueLabels(Node node) {
                // If we're returning a cached AST, we must also ensure unique labels
                return cached ? node.ensureUniqueLabels(lc) : node;
            }
        });

        if (blockDefinedSymbols != null) {
            // Mark all symbols not defined in blocks as globals
            Block newBody = null;
            for (var symbol: symbolReplacements.values()) {
                if (!blockDefinedSymbols.contains(symbol)) {
                    assert symbol.isScope(); // must be scope
                    assert externalScopeDepths.containsKey(symbol.getName()); // must be known to us as an external
                    // Register it in the function body symbol table as a new global symbol
                    symbol.setFlags((symbol.getFlags() & ~Symbol.KINDMASK) | Symbol.IS_GLOBAL);
                    if (newBody == null) {
                        newBody = newFn.getBody().copyWithNewSymbols();
                        newFn = newFn.setBody(null, newBody);
                    }
                    assert newBody.getExistingSymbol(symbol.getName()) == null; // must not be defined in the body already
                    newBody.putSymbol(symbol);
                }
            }
        }
        return newFn.setCached(null);
    }

    private boolean getFunctionFlag(int flag) {
        return (functionFlags & flag) != 0;
    }

    private boolean isProgram() {
        return getFunctionFlag(FunctionNode.IS_PROGRAM);
    }

    TypeMap typeMap(MethodType fnCallSiteType) {
        if (fnCallSiteType == null) {
            return null;
        }

        if (CompiledFunction.isVarArgsType(fnCallSiteType)) {
            return null;
        }

        return new TypeMap(functionNodeId, explicitParams(fnCallSiteType), needsCallee());
    }

    private static ScriptObject newLocals(ScriptObject runtimeScope) {
        var locals = Global.newEmptyInstance();
        locals.setProto(runtimeScope);
        return locals;
    }

    private Compiler getCompiler(FunctionNode fn, MethodType actualCallSiteType, ScriptObject runtimeScope) {
        return getCompiler(fn, actualCallSiteType, newLocals(runtimeScope), null, null);
    }

    /**
     * Returns a code installer for installing new code.
     * If we're using either optimistic typing or loader-per-compile, then asks for a code installer with a new class loader; otherwise just uses the current installer.
     * We use a new class loader with optimistic typing so that deoptimized code can get reclaimed by GC.
     * @return a code installer for installing new code.
     */
    private CodeInstaller getInstallerForNewCode() {
        var env = installer.getContext().getEnv();
        return env._optimistic_types || env._loader_per_compile ? installer.getOnDemandCompilationInstaller() : installer;
    }

    Compiler getCompiler(FunctionNode functionNode, MethodType actualCallSiteType, ScriptObject runtimeScope, Map<Integer, Type> invalidatedProgramPoints, int[] continuationEntryPoints) {
        var typeMap = typeMap(actualCallSiteType);
        var paramTypes = typeMap == null ? null : typeMap.getParameterTypes(functionNodeId);
        var typeInformationFile = OptimisticTypesPersistence.getLocationDescriptor(source, functionNodeId, paramTypes);
        return Compiler.forOnDemandCompilation(
            getInstallerForNewCode(),
            functionNode.getSource(),  // source
            this,       // compiledFunction, i.e. this RecompilableScriptFunctionData
            typeMap,    // type map
            getEffectiveInvalidatedProgramPoints(invalidatedProgramPoints, typeInformationFile), // invalidated program points
            typeInformationFile,
            continuationEntryPoints, // continuation entry points
            runtimeScope); // runtime scope
    }

    /**
     * If the function being compiled already has its own invalidated program points map, use it.
     * Otherwise, attempt to load invalidated program points map from the persistent type info cache.
     * @param invalidatedProgramPoints the function's current invalidated program points map. Null if the function doesn't have it.
     * @param typeInformationFile the object describing the location of the persisted type information.
     * @return either the existing map, or a loaded map from the persistent type info cache, or a new empty map if neither an existing map or a persistent cached type info is available.
     */
    @SuppressWarnings("unused")
    private static Map<Integer, Type> getEffectiveInvalidatedProgramPoints(Map<Integer, Type> invalidatedProgramPoints, Object typeInformationFile) {
        if (invalidatedProgramPoints != null) {
            return invalidatedProgramPoints;
        }
        var loadedProgramPoints = OptimisticTypesPersistence.load(typeInformationFile);
        return loadedProgramPoints != null ? loadedProgramPoints : new TreeMap<Integer, Type>();
    }

    private FunctionInitializer compileTypeSpecialization(MethodType actualCallSiteType, ScriptObject runtimeScope, boolean persist) {
        // We're creating an empty script object for holding local variables. AssignSymbols will populate it with explicit Undefined values for undefined local variables (see AssignSymbols#defineSymbol() and CompilationEnvironment#declareLocalSymbol()).

        if (log.isEnabled()) {
            log.info("Parameter type specialization of '", functionName, "' signature: ", actualCallSiteType);
        }

        var persistentCache = persist && usePersistentCodeCache();
        String cacheKey = null;
        if (persistentCache) {
            var typeMap = typeMap(actualCallSiteType);
            var paramTypes = typeMap == null ? null : typeMap.getParameterTypes(functionNodeId);
            cacheKey = CodeStore.getCacheKey(functionNodeId, paramTypes);
            var newInstaller = getInstallerForNewCode();
            var script = newInstaller.loadScript(source, cacheKey);

            if (script != null) {
                Compiler.updateCompilationId(script.getCompilationId());
                return script.installFunction(this, newInstaller);
            }
        }

        var fn = reparse();
        var compiler = getCompiler(fn, actualCallSiteType, runtimeScope);
        var compiledFn = compiler.compile(fn, fn.isCached() ? CompilationPhases.COMPILE_ALL_CACHED : CompilationPhases.COMPILE_ALL);

        if (persist && !compiledFn.hasApplyToCallSpecialization()) {
            compiler.persistClassInfo(cacheKey, compiledFn);
        }
        return new FunctionInitializer(compiledFn, compiler.getInvalidatedProgramPoints());
    }

    boolean usePersistentCodeCache() {
        return installer != null && installer.getContext().getEnv()._persistent_cache;
    }

    private MethodType explicitParams(MethodType callSiteType) {
        if (CompiledFunction.isVarArgsType(callSiteType)) {
            return null;
        }

        var noCalleeThisType = callSiteType.dropParameterTypes(0, 2); // (callee, this) is always in call site type
        var callSiteParamCount = noCalleeThisType.parameterCount();

        // Widen parameters of reference types to Object as we currently don't care for specialization among reference types.
        // E.g. call site saying (ScriptFunction, Object, String) should still link to (ScriptFunction, Object, Object)
        var paramTypes = noCalleeThisType.parameterArray();
        var changed = false;
        for (var i = 0; i < paramTypes.length; ++i) {
            var paramType = paramTypes[i];
            if (!(paramType.isPrimitive() || paramType == Object.class)) {
                paramTypes[i] = Object.class;
                changed = true;
            }
        }
        var generalized = changed ? MethodType.methodType(noCalleeThisType.returnType(), paramTypes) : noCalleeThisType;

        if (callSiteParamCount < getArity()) {
            return generalized.appendParameterTypes(Collections.<Class<?>>nCopies(getArity() - callSiteParamCount, Object.class));
        }
        return generalized;
    }

    private FunctionNode extractFunctionFromScript(FunctionNode script) {
        var fns = new HashSet<FunctionNode>();
        script.getBody().accept(new SimpleNodeVisitor() {
            @Override
            public boolean enterFunctionNode(FunctionNode fn) {
                fns.add(fn);
                return false;
            }
        });
        assert fns.size() == 1 : "got back more than one method in recompilation";
        var f = fns.iterator().next();
        assert f.getId() == functionNodeId;
        if (!getFunctionFlag(FunctionNode.IS_DECLARED) && f.isDeclared()) {
            return f.clearFlag(null, FunctionNode.IS_DECLARED);
        }
        return f;
    }

    private void logLookup(boolean shouldLog, MethodType targetType) {
        if (shouldLog && log.isEnabled()) {
            log.info("Looking up ", DebugLogger.quote(functionName), " type=", targetType);
        }
    }

    private MethodHandle lookup(FunctionInitializer fnInit, boolean shouldLog) {
        var type = fnInit.getMethodType();
        logLookup(shouldLog, type);
        return lookupCodeMethod(fnInit.getCode(), type);
    }

    MethodHandle lookup(FunctionNode fn) {
        var type = new FunctionSignature(fn).getMethodType();
        logLookup(true, type);
        return lookupCodeMethod(fn.getCompileUnit().getCode(), type);
    }

    MethodHandle lookupCodeMethod(Class<?> codeClass, MethodType targetType) {
        return MH.findStatic(LOOKUP, codeClass, functionName, targetType);
    }

    /**
     * Initializes this function data with the eagerly generated version of the code.
     * This method can only be invoked by the compiler internals in Nashorn and is public for implementation reasons only.
     * Attempting to invoke it externally will result in an exception.
     * @param functionNode FunctionNode for this data
     */
    public void initializeCode(FunctionNode functionNode) {
        // Since the method is public, we double-check that we aren't invoked with an inappropriate compile unit.
        if (!code.isEmpty() || functionNode.getId() != functionNodeId || !functionNode.getCompileUnit().isInitializing(this, functionNode)) {
            throw new IllegalStateException(name);
        }
        addCode(lookup(functionNode), null, null, functionNode.getFlags());
    }

    /**
     * Initializes this function with the given function code initializer.
     */
    void initializeCode(FunctionInitializer initializer) {
        addCode(lookup(initializer, true), null, null, initializer.getFlags());
    }

    private CompiledFunction addCode(MethodHandle target, Map<Integer, Type> invalidatedProgramPoints, MethodType callSiteType, int fnFlags) {
        var cfn = new CompiledFunction(target, this, invalidatedProgramPoints, callSiteType, fnFlags);
        assert noDuplicateCode(cfn) : "duplicate code";
        code.add(cfn);
        return cfn;
    }

    /**
     * Add code with specific call site type.
     * It will adapt the type of the looked up method handle to fit the call site type.
     * This is necessary because even if we request a specialization that takes an "int" parameter, we might end up getting one that takes a "double" etc. because of internal function logic causes widening (e.g. assignment of a wider value to the parameter variable).
     * However, we use the method handle type for matching subsequent lookups for the same specialization, so we must adapt the handle to the expected type.
     * @param fnInit the function
     * @param callSiteType the call site type
     * @return the compiled function object, with its type matching that of the call site type.
     */
    private CompiledFunction addCode(FunctionInitializer fnInit, MethodType callSiteType) {
        if (isVariableArity()) {
            return addCode(lookup(fnInit, true), fnInit.getInvalidatedProgramPoints(), callSiteType, fnInit.getFlags());
        }

        var handle = lookup(fnInit, true);
        var fromType = handle.type();
        var toType = needsCallee(fromType) ? callSiteType.changeParameterType(0, ScriptFunction.class) : callSiteType.dropParameterTypes(0, 1);
        toType = toType.changeReturnType(fromType.returnType());

        var toCount = toType.parameterCount();
        var fromCount = fromType.parameterCount();
        var minCount = Math.min(fromCount, toCount);
        for (var i = 0; i < minCount; ++i) {
            var fromParam = fromType.parameterType(i);
            var toParam = toType.parameterType(i);
            // If method has an Object parameter, but call site had String, preserve it as Object.
            // No need to narrow it artificially.
            // Note that this is related to how CompiledFunction.matchesCallSite() works, specifically the fact that various reference types compare to equal (see "fnType.isEquivalentTo(csType)" there).
            if (fromParam != toParam && !fromParam.isPrimitive() && !toParam.isPrimitive()) {
                assert fromParam.isAssignableFrom(toParam);
                toType = toType.changeParameterType(i, fromParam);
            }
        }
        if (fromCount > toCount) {
            toType = toType.appendParameterTypes(fromType.parameterList().subList(toCount, fromCount));
        } else if (fromCount < toCount) {
            toType = toType.dropParameterTypes(fromCount, toCount);
        }

        return addCode(lookup(fnInit, false).asType(toType), fnInit.getInvalidatedProgramPoints(), callSiteType, fnInit.getFlags());
    }

    /**
     * Returns the return type of a function specialization for particular parameter types.
     * Be aware that the way this is implemented, it forces full materialization (compilation and installation) of code for that specialization.
     * @param callSiteType the parameter types at the call site. It must include the mandatory {@code callee} and {@code this} parameters, so it needs to start with at least {@code ScriptFunction.class} and {@code Object.class} class. Since the return type of the function is calculated from the code itself, it is irrelevant and should be set to {@code Object.class}.
     * @param runtimeScope a current runtime scope. Can be null but when it's present it will be used as a source of current runtime values that can improve the compiler's type speculations (and thus reduce the need for later recompilations) if the specialization is not already present and thus needs to be freshly compiled.
     * @return the return type of the function specialization.
     */
    public Class<?> getReturnType(MethodType callSiteType, ScriptObject runtimeScope) {
        return getBest(callSiteType, runtimeScope, CompiledFunction.NO_FUNCTIONS).type().returnType();
    }

    @Override
    synchronized CompiledFunction getBest(MethodType callSiteType, ScriptObject runtimeScope, Collection<CompiledFunction> forbidden, boolean linkLogicOkay) {
        assert isValidCallSite(callSiteType) : callSiteType;

        var existingBest = pickFunction(callSiteType, false);
        if (existingBest == null) {
            existingBest = pickFunction(callSiteType, true); // try vararg last
        }
        if (existingBest == null) {
            existingBest = addCode(compileTypeSpecialization(callSiteType, runtimeScope, true), callSiteType);
        }

        assert existingBest != null;

        // if the best one is an apply to call, it has to match the callsite exactly or we need to regenerate
        if (existingBest.isApplyToCall()) {
            var best = lookupExactApplyToCall(callSiteType);
            if (best != null) {
                return best;
            }

            // Special case: we had an apply to call, but we failed to make it fit.
            // Try to generate a specialized one for this callsite.
            // It may be another apply to call specialization, or it may not, but whatever it is, it is a specialization that is guaranteed to fit
            existingBest = addCode(compileTypeSpecialization(callSiteType, runtimeScope, false), callSiteType);
        }

        return existingBest;
    }

    @Override
    public boolean needsCallee() {
        return getFunctionFlag(FunctionNode.NEEDS_CALLEE);
    }

    /**
     * Returns the {@link FunctionNode} flags associated with this function data.
     */
    public int getFunctionFlags() {
        return functionFlags;
    }

    @Override
    MethodType getGenericType() {
        // 2 is for (callee, this)
        if (isVariableArity()) {
            return MethodType.genericMethodType(2, true);
        }
        return MethodType.genericMethodType(2 + getArity());
    }

    /**
     * Return the function node id.
     */
    public int getFunctionNodeId() {
        return functionNodeId;
    }

    /**
     * Get the source for the script
     */
    public Source getSource() {
        return source;
    }

    /**
     * Return a script function data based on a function id, either this function if the id matches or a nested function based on functionId.
     * This goes down into nested functions until all leaves are exhausted.
     * @param functionId function id
     * @return script function data or null if invalid id
     */
    public RecompilableScriptFunctionData getScriptFunctionData(int functionId) {
        if (functionId == functionNodeId) {
            return this;
        }
        RecompilableScriptFunctionData data;

        data = nestedFunctions == null ? null : nestedFunctions.get(functionId);
        if (data != null) {
            return data;
        }
        for (var ndata : nestedFunctions.values()) {
            data = ndata.getScriptFunctionData(functionId);
            if (data != null) {
                return data;
            }
        }
        return null;
    }

    /**
     * Check whether a certain name is a global symbol, i.e. only exists as defined in outermost scope and not shadowed by being parameter or assignment in inner scopes
     * @param functionNode function node to check
     * @param symbolName symbol name
     * @return true if global symbol
     */
    public boolean isGlobalSymbol(FunctionNode functionNode, String symbolName) {
        var data = getScriptFunctionData(functionNode.getId());
        assert data != null;

        do {
            if (data.hasInternalSymbol(symbolName)) {
                return false;
            }
            data = data.getParent();
        } while (data != null);

        return true;
    }

    /**
     * Restores the {@link #getFunctionFlags()} flags to a function node.
     * During on-demand compilation, we might need to restore flags to a function node that was otherwise not subjected to a full compile pipeline (e.g. its parse was skipped, or it's a nested function of a deserialized function.
     * @param lc current lexical context
     * @param fn the function node to restore flags onto
     * @return the transformed function node
     */
    public FunctionNode restoreFlags(LexicalContext lc, FunctionNode fn) {
        assert fn.getId() == functionNodeId;
        var newFn = fn.setFlags(lc, functionFlags);
        // This compensates for missing markEval() in case the function contains an inner function that contains eval(), that now we didn't discover since we skipped the inner function.
        if (newFn.hasNestedEval()) {
            assert newFn.hasScopeBlock();
            newFn = newFn.setBody(lc, newFn.getBody().setNeedsScope(null));
        }
        return newFn;
    }

    // Make sure code does not contain a compiled function with the same signature as compiledFunction
    private boolean noDuplicateCode(CompiledFunction compiledFunction) {
        for (var cf : code) {
            if (cf.type().equals(compiledFunction.type())) {
                return false;
            }
        }
        return true;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
        var localCachedAst = cachedAst;
        out.defaultWriteObject();
        // We need to persist SerializedAst for split functions as they can't reparse the source code.
        if (localCachedAst instanceof SerializedAst) {
            out.writeObject(localCachedAst);
        } else {
            out.writeObject(null);
        }
    }

    private void readObject(java.io.ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        cachedAst = in.readObject();
        createLogger();
    }

    private void createLogger() {
        log = initLogger(Context.getContextTrusted());
    }

}
