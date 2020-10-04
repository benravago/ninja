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

import java.io.PrintStream;

import java.lang.reflect.Array;

import java.util.Collection;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.ATHROW;
import static org.objectweb.asm.Opcodes.CHECKCAST;
import static org.objectweb.asm.Opcodes.DUP2;
import static org.objectweb.asm.Opcodes.GETFIELD;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.GOTO;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.H_INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.IFEQ;
import static org.objectweb.asm.Opcodes.IFGE;
import static org.objectweb.asm.Opcodes.IFGT;
import static org.objectweb.asm.Opcodes.IFLE;
import static org.objectweb.asm.Opcodes.IFLT;
import static org.objectweb.asm.Opcodes.IFNE;
import static org.objectweb.asm.Opcodes.IFNONNULL;
import static org.objectweb.asm.Opcodes.IFNULL;
import static org.objectweb.asm.Opcodes.IF_ACMPEQ;
import static org.objectweb.asm.Opcodes.IF_ACMPNE;
import static org.objectweb.asm.Opcodes.IF_ICMPEQ;
import static org.objectweb.asm.Opcodes.IF_ICMPGE;
import static org.objectweb.asm.Opcodes.IF_ICMPGT;
import static org.objectweb.asm.Opcodes.IF_ICMPLE;
import static org.objectweb.asm.Opcodes.IF_ICMPLT;
import static org.objectweb.asm.Opcodes.IF_ICMPNE;
import static org.objectweb.asm.Opcodes.INSTANCEOF;
import static org.objectweb.asm.Opcodes.INVOKEINTERFACE;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;
import static org.objectweb.asm.Opcodes.NEW;
import static org.objectweb.asm.Opcodes.PUTFIELD;
import static org.objectweb.asm.Opcodes.PUTSTATIC;
import static org.objectweb.asm.Opcodes.RETURN;

import nashorn.internal.codegen.ClassEmitter.Flag;
import nashorn.internal.codegen.CompilerConstants.Call;
import nashorn.internal.codegen.CompilerConstants.FieldAccess;
import nashorn.internal.codegen.types.ArrayType;
import nashorn.internal.codegen.types.BitwiseType;
import nashorn.internal.codegen.types.NumericType;
import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.FunctionNode;
import nashorn.internal.ir.IdentNode;
import nashorn.internal.ir.JoinPredecessor;
import nashorn.internal.ir.LiteralNode;
import nashorn.internal.ir.LocalVariableConversion;
import nashorn.internal.ir.Symbol;
import nashorn.internal.ir.TryNode;
import nashorn.internal.objects.NativeArray;
import nashorn.internal.runtime.ArgumentSetter;
import nashorn.internal.runtime.Context;
import nashorn.internal.Util;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.RewriteException;
import nashorn.internal.runtime.Scope;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.UnwarrantedOptimismException;
import nashorn.internal.runtime.linker.Bootstrap;
import nashorn.internal.runtime.linker.NameCodec;
import nashorn.internal.runtime.linker.NashornCallSiteDescriptor;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.options.Options;
import static nashorn.internal.codegen.CompilerConstants.ARGUMENTS;
import static nashorn.internal.codegen.CompilerConstants.CONSTANTS;
import static nashorn.internal.codegen.CompilerConstants.SCOPE;
import static nashorn.internal.codegen.CompilerConstants.THIS;
import static nashorn.internal.codegen.CompilerConstants.THIS_DEBUGGER;
import static nashorn.internal.codegen.CompilerConstants.VARARGS;
import static nashorn.internal.codegen.CompilerConstants.className;
import static nashorn.internal.codegen.CompilerConstants.constructorNoLookup;
import static nashorn.internal.codegen.CompilerConstants.methodDescriptor;
import static nashorn.internal.codegen.CompilerConstants.staticField;
import static nashorn.internal.codegen.CompilerConstants.virtualCallNoLookup;
import static nashorn.internal.codegen.ObjectClassGenerator.PRIMITIVE_FIELD_TYPE;
import static nashorn.internal.runtime.linker.NameCodec.EMPTY_NAME;
import static nashorn.internal.runtime.linker.NashornCallSiteDescriptor.CALLSITE_OPTIMISTIC;
import static nashorn.internal.runtime.linker.NashornCallSiteDescriptor.CALLSITE_PROGRAM_POINT_SHIFT;

/**
 * This is the main function responsible for emitting method code in a class.
 *
 * It maintains a type stack and keeps track of control flow to make sure that the registered instructions don't violate byte code verification.
 * Running Nashorn with -ea will assert as soon as a type stack becomes corrupt, for easier debugging
 */
public class MethodEmitter {

    /** The ASM MethodVisitor we are plugged into */
    private final MethodVisitor method;

    /** Parent classEmitter representing the class of this method */
    private final ClassEmitter classEmitter;

    /** FunctionNode representing this method, or null if none exists */
    protected FunctionNode functionNode;

    /** Current type stack for current evaluation */
    private Label.Stack stack;

    private boolean preventUndefinedLoad;

    /**
     * Map of live local variable definitions.
     */
    private final Map<Symbol, LocalVariableDef> localVariableDefs = new IdentityHashMap<>();

    /** The context */
    private final Context context;

    /** Threshold in chars for when string constants should be split */
    static final int LARGE_STRING_THRESHOLD = 32 * 1024;

    /** Debug flag, should we dump all generated bytecode along with stacks? */
    private final DebugLogger log;
    private final boolean     debug;

    /** dump stack on a particular line, or -1 if disabled */
    private static final int DEBUG_TRACE_LINE;

    static {
        var tl = Options.getStringProperty("nashorn.codegen.debug.trace", "-1");
        var line = -1;
        try {
            line = Integer.parseInt(tl);
        } catch (NumberFormatException e) {
            // fallthru
        }
        DEBUG_TRACE_LINE = line;
    }

    /** Bootstrap for normal indy:s */
    private static final Handle LINKERBOOTSTRAP  = new Handle(H_INVOKESTATIC, Bootstrap.BOOTSTRAP.className(), Bootstrap.BOOTSTRAP.name(), Bootstrap.BOOTSTRAP.descriptor(), false);

    /** Bootstrap for array populators */
    private static final Handle POPULATE_ARRAY_BOOTSTRAP = new Handle(H_INVOKESTATIC, RewriteException.BOOTSTRAP.className(), RewriteException.BOOTSTRAP.name(), RewriteException.BOOTSTRAP.descriptor(), false);

    /**
     * Constructor - internal use from ClassEmitter only
     * @see ClassEmitter#method
     * @param classEmitter the class emitter weaving the class this method is in
     * @param method       a method visitor
     */
    MethodEmitter(ClassEmitter classEmitter, MethodVisitor method) {
        this(classEmitter, method, null);
    }

    /**
     * Constructor - internal use from ClassEmitter only
     * @see ClassEmitter#method
     * @param classEmitter the class emitter weaving the class this method is in
     * @param method       a method visitor
     * @param functionNode a function node representing this method
     */
    MethodEmitter(ClassEmitter classEmitter, MethodVisitor method, FunctionNode functionNode) {
        this.context = classEmitter.getContext();
        this.classEmitter = classEmitter;
        this.method = method;
        this.functionNode = functionNode;
        this.stack = null;
        this.log = context.getLogger(CodeGenerator.class);
        this.debug = log.isEnabled();
    }

    /**
     * Begin a method
     */
    public void begin() {
        classEmitter.beginMethod(this);
        newStack();
        method.visitCode();
    }

    /**
     * End a method
     */
    public void end() {
        method.visitMaxs(0, 0);
        method.visitEnd();
        classEmitter.endMethod(this);
    }

    boolean isReachable() {
        return stack != null;
    }

    private void doesNotContinueSequentially() {
        stack = null;
    }

    private void newStack() {
        stack = new Label.Stack();
    }

    @Override
    public String toString() {
        return "methodEmitter: " + (functionNode == null ? method : functionNode.getName()).toString() + ' ' + Util.id(this);
    }

    /**
     * Push a type to the existing stack
     */
    void pushType(Type type) {
        if (type != null) {
            stack.push(type);
        }
    }

    /**
     * Pop a type from the existing stack
     * @param expected expected type - will assert if wrong
     * @return the type that was retrieved
     */
    private Type popType(Type expected) {
        var type = popType();
        assert type.isEquivalentTo(expected) : type + " is not compatible with " + expected;
        return type;
    }

    /**
     * Pop a type from the existing stack, no matter what it is.
     */
    private Type popType() {
        return stack.pop();
    }

    /**
     * Pop a type from the existing stack, ensuring that it is numeric. Boolean type is popped as int type.
     */
    private NumericType popNumeric() {
        var type = popType();
        if (type.isBoolean()) {
            // Booleans are treated as int for purposes of arithmetic operations
            return Type.INT;
        }
        assert type.isNumeric();
        return (NumericType)type;
    }

    /**
     * Pop a type from the existing stack, ensuring that it is an integer type (integer or long).
     * Boolean type is popped as int type.
     */
    private BitwiseType popBitwise() {
        var type = popType();
        if (type == Type.BOOLEAN) {
            return Type.INT;
        }
        return (BitwiseType)type;
    }

    private BitwiseType popInteger() {
        var type = popType();
        if (type == Type.BOOLEAN) {
            return Type.INT;
        }
        assert type == Type.INT;
        return (BitwiseType)type;
    }

    /**
     * Pop a type from the existing stack, ensuring that it is an array type, assert if not
     */
    private ArrayType popArray() {
        var type = popType();
        assert type.isArray() : type;
        return (ArrayType)type;
    }

    /**
     * Peek a given number of slots from the top of the stack and return the type in that slot
     * @param pos the number of positions from the top, 0 is the top element
     * @return the type at position "pos" on the stack
     */
    final Type peekType(int pos) {
        return stack.peek(pos);
    }

    /**
     * Peek at the type at the top of the stack
     */
    final Type peekType() {
        return stack.peek();
    }

    /**
     * Generate code a for instantiating a new object and push the object type on the stack
     * @param classDescriptor class descriptor for the object type
     * @param type the type of the new object
     */
    MethodEmitter _new(String classDescriptor, Type type) {
        debug("new", classDescriptor);
        method.visitTypeInsn(NEW, classDescriptor);
        pushType(type);
        return this;
    }

    /**
     * Generate code a for instantiating a new object and push the object type on the stack
     * @param clazz class type to instatiate
     */
    MethodEmitter _new(Class<?> clazz) { // newInstance()
        return _new(className(clazz), Type.typeFor(clazz));
    }

    /**
     * Generate code to call the empty constructor for a class
     * @param clazz class type to instatiate
     */
    MethodEmitter newObject(Class<?> clazz) { // constructor()
        return invoke(constructorNoLookup(clazz));
    }

    /**
     * Perform a dup, that is, duplicate the top element and push the duplicate down a given number of positions on the stack.
     * This is totally type agnostic.
     * @param depth the depth on which to put the copy
     * @return the method emitter, or null if depth is illegal and  has no instruction equivalent.
     */
    MethodEmitter dup(int depth) {
        if (peekType().dup(method, depth) == null) {
            return null;
        }

        debug("dup", depth);

        switch (depth) {
            case 0 ->  {
                var l0 = stack.getTopLocalLoad();
                pushType(peekType());
                stack.markLocalLoad(l0);
            }
            case 1 ->  {
                var l0 = stack.getTopLocalLoad();
                var p0 = popType();
                var l1 = stack.getTopLocalLoad();
                var p1 = popType();
                pushType(p0);
                stack.markLocalLoad(l0);
                pushType(p1);
                stack.markLocalLoad(l1);
                pushType(p0);
                stack.markLocalLoad(l0);
            }
            case 2 ->  {
                var l0 = stack.getTopLocalLoad();
                var p0 = popType();
                var l1 = stack.getTopLocalLoad();
                var p1 = popType();
                var l2 = stack.getTopLocalLoad();
                var p2 = popType();
                pushType(p0);
                stack.markLocalLoad(l0);
                pushType(p2);
                stack.markLocalLoad(l2);
                pushType(p1);
                stack.markLocalLoad(l1);
                pushType(p0);
                stack.markLocalLoad(l0);
            }
            default -> {
                assert false : "illegal dup depth = " + depth;
                return null;
            }
        }

        return this;
    }

    /**
     * Perform a dup2, that is, duplicate the top element if it is a category 2 type, or two top elements if they are category 1 types, and push them on top of the stack
     */
    MethodEmitter dup2() {
        debug("dup2");

        if (peekType().isCategory2()) {
            var l0 = stack.getTopLocalLoad();
            pushType(peekType());
            stack.markLocalLoad(l0);
        } else {
            var l0 = stack.getTopLocalLoad();
            var p0 = popType();
            var l1 = stack.getTopLocalLoad();
            var p1 = popType();
            pushType(p0);
            stack.markLocalLoad(l0);
            pushType(p1);
            stack.markLocalLoad(l1);
            pushType(p0);
            stack.markLocalLoad(l0);
            pushType(p1);
            stack.markLocalLoad(l1);
        }
        method.visitInsn(DUP2);
        return this;
    }

    /**
     * Duplicate the top element on the stack and push it
     */
    MethodEmitter dup() {
        return dup(0);
    }

    /**
     * Pop the top element of the stack and throw it away
     */
    MethodEmitter pop() {
        debug("pop", peekType());
        popType().pop(method);
        return this;
    }

    /**
     * Pop the top element of the stack if category 2 type, or the two top elements of the stack if category 1 types
     */
    MethodEmitter pop2() {
        if (peekType().isCategory2()) {
            popType();
        } else {
            get2n();
        }
        return this;
    }

    /**
     * Swap the top two elements of the stack.
     * This is totally type agnostic and works for all types
     */
    MethodEmitter swap() {
        debug("swap");

        var l0 = stack.getTopLocalLoad();
        var p0 = popType();
        var l1 = stack.getTopLocalLoad();
        var p1 = popType();
        p0.swap(method, p1);

        pushType(p0);
        stack.markLocalLoad(l0);
        pushType(p1);
        stack.markLocalLoad(l1);
        return this;
    }

    void pack() {
        var type = peekType();
        if (type.isInteger()) {
            convert(PRIMITIVE_FIELD_TYPE);
        } else if (type.isLong()) {
            //nop
        } else if (type.isNumber()) {
            invokestatic("java/lang/Double", "doubleToRawLongBits", "(D)J");
        } else {
            assert false : type + " cannot be packed!";
        }
    }

    /**
     * Initializes a bytecode method parameter
     * @param symbol the symbol for the parameter
     * @param type the type of the parameter
     * @param start the label for the start of the method
     */
    void initializeMethodParameter(Symbol symbol, Type type, Label start) {
        assert symbol.isBytecodeLocal();
        localVariableDefs.put(symbol, new LocalVariableDef(start.getLabel(), type));
    }

    /**
     * Create a new string builder, call the constructor and push the instance to the stack.
     */
    MethodEmitter newStringBuilder() {
        return invoke(constructorNoLookup(StringBuilder.class)).dup();
    }

    /**
     * Pop a string and a StringBuilder from the top of the stack and call the append function of the StringBuilder, appending the string.
     * Pushes the StringBuilder to the stack when finished.
     */
    MethodEmitter stringBuilderAppend() {
        convert(Type.STRING);
        return invoke(virtualCallNoLookup(StringBuilder.class, "append", StringBuilder.class, String.class));
    }

    /**
     * Pops two integer types from the stack, performs a bitwise and and pushes the result
     */
    MethodEmitter and() {
        debug("and");
        pushType(get2i().and(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise or and pushes the result
     */
    MethodEmitter or() {
        debug("or");
        pushType(get2i().or(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise xor and pushes the result
     */
    MethodEmitter xor() {
        debug("xor");
        pushType(get2i().xor(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise logic shift right and pushes the result.
     * The shift count, the first element, must be INT.
     */
    MethodEmitter shr() {
        debug("shr");
        popInteger();
        pushType(popBitwise().shr(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise shift left and and pushes the result.
     * The shift count, the first element, must be INT.
     */
    MethodEmitter shl() {
        debug("shl");
        popInteger();
        pushType(popBitwise().shl(method));
        return this;
    }

    /**
     * Pops two integer types from the stack, performs a bitwise arithmetic shift right and pushes the result.
     * The shift count, the first element, must be INT.
     */
    MethodEmitter sar() {
        debug("sar");
        popInteger();
        pushType(popBitwise().sar(method));
        return this;
    }

    /**
     * Pops a numeric type from the stack, negates it and pushes the result
     */
    MethodEmitter neg(int programPoint) {
        debug("neg");
        pushType(popNumeric().neg(method, programPoint));
        return this;
    }

    /**
     * Add label for the start of a catch block and push the exception to the stack
     * @param recovery label pointing to start of catch block
     */
    void _catch(Label recovery) {
        // While in JVM a catch block can be reached through normal control flow, our code generator never does this, so we might as well presume there's no stack on entry.
        assert stack == null;
        recovery.onCatch();
        label(recovery);
        beginCatchBlock();
    }

    /**
     * Add any number of labels for the start of a catch block and push the exception to the stack
     */
    void _catch(Collection<Label> recoveries) {
        assert stack == null;
        for (var l : recoveries) {
            label(l);
        }
        beginCatchBlock();
    }

    private void beginCatchBlock() {
        // It can happen that the catch label wasn't marked as reachable.
        // They are marked as reachable if there's an assignment in the try block, but it's possible that there was none.
        if (!isReachable()) {
            newStack();
        }
        pushType(Type.typeFor(Throwable.class));
    }

    /**
     * Start a try/catch block.
     * @param entry    start label for try
     * @param exit     end label for try
     * @param recovery start label for catch
     * @param clazz    exception class or null for any Throwable
     * @param isOptimismHandler true if this is a hander for {@code UnwarrantedOptimismException}. Normally joining on a catch handler kills temporary variables, but optimism handlers are an exception, as they need to capture temporaries as well, so they must remain live.
     */
    void _try(Label entry, Label exit, Label recovery, Class<?> clazz, boolean isOptimismHandler) {
        recovery.joinFromTry(entry.getStack(), isOptimismHandler);
        var typeDescriptor = clazz == null ? null : CompilerConstants.className(clazz);
        method.visitTryCatchBlock(entry.getLabel(), exit.getLabel(), recovery.getLabel(), typeDescriptor);
    }

    /**
     * Start a try/catch block.
     */
    void _try(Label entry, Label exit, Label recovery, Class<?> clazz) {
        _try(entry, exit, recovery, clazz, clazz == UnwarrantedOptimismException.class);
    }

    /**
     * Start a try/catch block. The catch is "Throwable" - i.e. catch-all
     */
    void _try(Label entry, Label exit, Label recovery) {
        _try(entry, exit, recovery, null, false);
    }

    void markLabelAsOptimisticCatchHandler(Label label, int liveLocalCount) {
        label.markAsOptimisticCatchHandler(stack, liveLocalCount);
    }

    /**
     * Load the constants array
     */
    MethodEmitter loadConstants() {
        getStatic(classEmitter.getUnitClassName(), CONSTANTS.symbolName(), CONSTANTS.descriptor());
        assert peekType().isArray() : peekType();
        return this;
    }

    /**
     * Push the undefined value for the given type, i.e. UNDEFINED or UNDEFINEDNUMBER.
     * Currently we have no way of representing UNDEFINED for INTs and LONGs, so they are not  allowed to be local variables (yet)
     */
    MethodEmitter loadUndefined(Type type) {
        debug("load undefined ", type);
        pushType(type.loadUndefined(method));
        return this;
    }

    MethodEmitter loadForcedInitializer(Type type) {
        debug("load forced initializer ", type);
        pushType(type.loadForcedInitializer(method));
        return this;
    }

    /**
     * Push the empty value for the given type, i.e. EMPTY.
     */
    MethodEmitter loadEmpty(Type type) {
        debug("load empty ", type);
        pushType(type.loadEmpty(method));
        return this;
    }

    /**
     * Push null to stack
     */
    MethodEmitter loadNull() {
        debug("aconst_null");
        pushType(Type.OBJECT.ldc(method, null));
        return this;
    }

    /**
     * Push a handle representing this class top stack
     */
    MethodEmitter loadType(String className) {
        debug("load type", className);
        method.visitLdcInsn(org.objectweb.asm.Type.getObjectType(className));
        pushType(Type.OBJECT);
        return this;
    }

    /**
     * Push a boolean constant to the stack.
     */
    MethodEmitter load(boolean b) {
        debug("load boolean", b);
        pushType(Type.BOOLEAN.ldc(method, b));
        return this;
    }

    /**
     * Push an int constant to the stack
     */
    MethodEmitter load(int i) {
        debug("load int", i);
        pushType(Type.INT.ldc(method, i));
        return this;
    }

    /**
     * Push a double constant to the stack
     */
    MethodEmitter load(double d) {
        debug("load double", d);
        pushType(Type.NUMBER.ldc(method, d));
        return this;
    }

    /**
     * Push an long constant to the stack
     */
    MethodEmitter load(long l) {
        debug("load long", l);
        pushType(Type.LONG.ldc(method, l));
        return this;
    }

    /**
     * Fetch the length of an array.
     */
    MethodEmitter arraylength() {
        debug("arraylength");
        popType(Type.OBJECT);
        pushType(Type.OBJECT_ARRAY.arraylength(method));
        return this;
    }

    /**
     * Push a String constant to the stack
     */
    MethodEmitter load(String s) {
        debug("load string", s);

        if (s == null) {
            loadNull();
            return this;
        }

        // NASHORN-142 - split too large string
        var length = s.length();
        if (length > LARGE_STRING_THRESHOLD) {

            _new(StringBuilder.class);
            dup();
            load(length);
            invoke(constructorNoLookup(StringBuilder.class, int.class));

            for (var n = 0; n < length; n += LARGE_STRING_THRESHOLD) {
                var part = s.substring(n, Math.min(n + LARGE_STRING_THRESHOLD, length));
                load(part);
                stringBuilderAppend();
            }

            invoke(virtualCallNoLookup(StringBuilder.class, "toString", String.class));

            return this;
        }

        pushType(Type.OBJECT.ldc(method, s));
        return this;
    }

    /**
     * Pushes the value of an identifier to the stack.
     * If the identifier does not represent a local variable or a parameter, this will be a no-op.
     */
    MethodEmitter load(IdentNode ident) {
        return load(ident.getSymbol(), ident.getType());
    }

    /**
     * Pushes the value of the symbol to the stack with the specified type.
     * No type conversion is being performed, and the type is only being used if the symbol addresses a local variable slot.
     * The value of the symbol is loaded if it addresses a local variable slot, or it is a parameter (in which case it can also be loaded from a vararg array or the arguments object). If it is neither, the operation is a no-op.
     * @param symbol the symbol addressing the value being loaded
     * @param type the presumed type of the value when it is loaded from a local variable slot
     */
    MethodEmitter load(Symbol symbol, Type type) {
        assert symbol != null;
        if (symbol.hasSlot()) {
            var slot = symbol.getSlot(type);
            debug("load symbol", symbol.getName(), " slot=", slot, "type=", type);
            load(type, slot);
            // _try(new Label("dummy"), new Label("dummy2"), recovery);
            // method.visitTryCatchBlock(new Label(), arg1, arg2, arg3);
        } else if (symbol.isParam()) {
            assert functionNode.isVarArg() : "Non-vararg functions have slotted parameters";
            var index = symbol.getFieldIndex();
            if (functionNode.needsArguments()) {
                // ScriptObject.getArgument(int) on arguments
                debug("load symbol", symbol.getName(), " arguments index=", index);
                loadCompilerConstant(ARGUMENTS);
                load(index);
                ScriptObject.GET_ARGUMENT.invoke(this);
            } else {
                // array load from __varargs__
                debug("load symbol", symbol.getName(), " array index=", index);
                loadCompilerConstant(VARARGS);
                load(symbol.getFieldIndex());
                arrayload();
            }
        }
        return this;
    }

    /**
     * Push a local variable to the stack, given an explicit bytecode slot.
     * This is used e.g. for stub generation where we know where items like "this" and "scope" reside.
     * @param type  the type of the variable
     * @param slot  the slot the variable is in
     */
    MethodEmitter load(Type type, int slot) {
        debug("explicit load", type, slot);
        var loadType = type.load(method, slot);
        assert loadType != null;
        pushType(loadType == Type.OBJECT && isThisSlot(slot) ? Type.THIS : loadType);
        assert !preventUndefinedLoad || (slot < stack.localVariableTypes.size() && stack.localVariableTypes.get(slot) != Type.UNKNOWN)
           : "Attempted load of uninitialized slot " + slot + " (as type " + type + ")";
        stack.markLocalLoad(slot);
        return this;
    }

    private boolean isThisSlot(int slot) {
        if (functionNode == null) {
            return slot == CompilerConstants.JAVA_THIS.slot();
        }
        var thisSlot = getCompilerConstantSymbol(THIS).getSlot(Type.OBJECT);
        assert !functionNode.needsCallee() || thisSlot == 1; // needsCallee -> thisSlot == 1
        assert functionNode.needsCallee() || thisSlot == 0; // !needsCallee -> thisSlot == 0
        return slot == thisSlot;
    }

    /**
     * Push a method handle to the stack
     * @param className  class name
     * @param methodName method name
     * @param descName   descriptor
     * @param flags      flags that describe this handle, e.g. invokespecial new, or invoke virtual
     */
    MethodEmitter loadHandle(String className, String methodName, String descName, EnumSet<Flag> flags) {
        var flag = Flag.getValue(flags);
        debug("load handle ");
        pushType(Type.OBJECT.ldc(method, new Handle(flag, className, methodName, descName, flag == H_INVOKEINTERFACE)));
        return this;
    }

    private Symbol getCompilerConstantSymbol(CompilerConstants cc) {
        return functionNode.getBody().getExistingSymbol(cc.symbolName());
    }

    /**
     * True if this method has a slot allocated for the scope variable (meaning, something in the method actually needs the scope).
     */
    boolean hasScope() {
        return getCompilerConstantSymbol(SCOPE).hasSlot();
    }

    MethodEmitter loadCompilerConstant(CompilerConstants cc) {
        return loadCompilerConstant(cc, null);
    }

    MethodEmitter loadCompilerConstant(CompilerConstants cc, Type type) {
        if (cc == SCOPE && peekType() == Type.SCOPE) {
            dup();
            return this;
        }
        return load(getCompilerConstantSymbol(cc), type != null ? type : getCompilerConstantType(cc));
    }

    MethodEmitter loadScope() {
        return loadCompilerConstant(SCOPE).checkcast(Scope.class);
    }

    MethodEmitter setSplitState(int state) {
        return loadScope().load(state).invoke(Scope.SET_SPLIT_STATE);
    }

    void storeCompilerConstant(CompilerConstants cc) {
        storeCompilerConstant(cc, null);
    }

    void storeCompilerConstant(CompilerConstants cc, Type type) {
        var symbol = getCompilerConstantSymbol(cc);
        if (!symbol.hasSlot()) {
            return;
        }
        debug("store compiler constant ", symbol);
        store(symbol, type != null ? type : getCompilerConstantType(cc));
    }

    private static Type getCompilerConstantType(CompilerConstants cc) {
        var constantType = cc.type();
        assert constantType != null;
        return Type.typeFor(constantType);
    }

    /**
     * Load an element from an array, determining type automatically
     */
    MethodEmitter arrayload() {
        debug("Xaload");
        popType(Type.INT);
        pushType(popArray().aload(method));
        return this;
    }

    /**
     * Pop a value, an index and an array from the stack and store the value at the given index in the array.
     */
    void arraystore() {
        debug("Xastore");
        var value = popType();
        var index = popType(Type.INT);
        assert index.isInteger() : "array index is not integer, but " + index;
        var array = popArray();

        assert value.isEquivalentTo(array.getElementType()) : "Storing "+value+" into "+array;
        assert array.isObject();
        array.astore(method);
    }

    /**
     * Pop a value from the stack and store it in a local variable represented by the given identifier.
     * If the symbol has no slot, this is a NOP.
     */
    void store(IdentNode ident) {
        var type = ident.getType();
        var symbol = ident.getSymbol();
        if (type == Type.UNDEFINED) {
            assert peekType() == Type.UNDEFINED;
            store(symbol, Type.OBJECT);
        } else {
            store(symbol, type);
        }
    }

    /**
     * Represents a definition of a local variable with a type. Used for local variable table building.
     */
    private static class LocalVariableDef {
        // The start label from where this definition lives.
        private final org.objectweb.asm.Label label;
        // The currently live type of the local variable.
        private final Type type;

        LocalVariableDef(org.objectweb.asm.Label label, Type type) {
            this.label = label;
            this.type = type;
        }

    }

    void closeLocalVariable(Symbol symbol, Label label) {
        var def = localVariableDefs.get(symbol);
        if (def != null) {
            endLocalValueDef(symbol, def, label.getLabel());
        }
        if (isReachable()) {
            markDeadLocalVariable(symbol);
        }
    }

    void markDeadLocalVariable(Symbol symbol) {
        if (!symbol.isDead()) {
            markDeadSlots(symbol.getFirstSlot(), symbol.slotCount());
        }
    }

    void markDeadSlots(int firstSlot, int slotCount) {
        stack.markDeadLocalVariables(firstSlot, slotCount);
    }

    private void endLocalValueDef(Symbol symbol, LocalVariableDef def, org.objectweb.asm.Label label) {
        var name = symbol.getName();
        if (name.equals(THIS.symbolName())) {
            name = THIS_DEBUGGER.symbolName();
        }
        method.visitLocalVariable(name, def.type.getDescriptor(), null, def.label, label, symbol.getSlot(def.type));
    }

    void store(Symbol symbol, Type type) {
        store(symbol, type, true);
    }

    /**
     * Pop a value from the stack and store it in a variable denoted by the given symbol.
     * The variable should be either a local variable, or a function parameter (and not a scoped variable).
     * For local variables, this method will also do the bookkeeping of the local variable table as well as mark values in all alternative slots for the symbol as dead.
     * In this regard it differs from {@link #storeHidden(Type, int)}.
     * @param symbol the symbol to store into.
     * @param type the type to store
     * @param onlySymbolLiveValue if true, this is the sole live value for the symbol. If false, currently live values should be kept live.
     */
    void store(Symbol symbol, Type type, boolean onlySymbolLiveValue) {
        assert symbol != null : "No symbol to store";
        if (symbol.hasSlot()) {
            var isLiveType = symbol.hasSlotFor(type);
            var existingDef = localVariableDefs.get(symbol);
            if (existingDef == null || existingDef.type != type) {
                var here = new org.objectweb.asm.Label();
                if (isLiveType) {
                    var newDef = new LocalVariableDef(here, type);
                    localVariableDefs.put(symbol, newDef);
                }
                method.visitLabel(here);
                if (existingDef != null) {
                    endLocalValueDef(symbol, existingDef, here);
                }
            }
            if (isLiveType) {
                var slot = symbol.getSlot(type);
                debug("store symbol", symbol.getName(), " type=", type, " slot=", slot);
                storeHidden(type, slot, onlySymbolLiveValue);
            } else {
                if (onlySymbolLiveValue) {
                    markDeadLocalVariable(symbol);
                }
                debug("dead store symbol ", symbol.getName(), " type=", type);
                pop();
            }
        } else if (symbol.isParam()) {
            assert !symbol.isScope();
            assert functionNode.isVarArg() : "Non-vararg functions have slotted parameters";
            var index = symbol.getFieldIndex();
            if (functionNode.needsArguments()) {
                convert(Type.OBJECT);
                debug("store symbol", symbol.getName(), " arguments index=", index);
                loadCompilerConstant(ARGUMENTS);
                load(index);
                ArgumentSetter.SET_ARGUMENT.invoke(this);
            } else {
                convert(Type.OBJECT);
                // varargs without arguments object - just do array store to __varargs__
                debug("store symbol", symbol.getName(), " array index=", index);
                loadCompilerConstant(VARARGS);
                load(index);
                ArgumentSetter.SET_ARRAY_ELEMENT.invoke(this);
            }
        } else {
            debug("dead store symbol ", symbol.getName(), " type=", type);
            pop();
        }
    }

    /**
     * Pop a value from the stack and store it in a local variable slot.
     * Note that in contrast with {@link #store(Symbol, Type)}, this method does not adjust the local variable table, nor marks slots for alternative value types for the symbol as being dead.
     * For that reason, this method is usually not called directly.
     * Notable exceptions are temporary internal locals (e.g. quick store, last-catch-condition, etc.) that are not desired to show up in the local variable table.
     */
    void storeHidden(Type type, int slot) {
        storeHidden(type, slot, true);
    }

    void storeHidden(Type type, int slot, boolean onlyLiveSymbolValue) {
        explicitStore(type, slot);
        stack.onLocalStore(type, slot, onlyLiveSymbolValue);
    }

    void storeTemp(Type type, int slot) {
        explicitStore(type, slot);
        defineTemporaryLocalVariable(slot, slot + type.getSlots());
        onLocalStore(type, slot);
    }

    void onLocalStore(Type type, int slot) {
        stack.onLocalStore(type, slot, true);
    }

    private void explicitStore(Type type, int slot) {
        assert slot != -1;
        debug("explicit store", type, slot);
        popType(type);
        type.store(method, slot);
    }

    /**
     * Marks a range of slots as belonging to a defined local variable.
     * The slots will start out with no live value in them.
     */
    void defineBlockLocalVariable(int fromSlot, int toSlot) {
        stack.defineBlockLocalVariable(fromSlot, toSlot);
    }

    /**
     * Marks a range of slots as belonging to a defined temporary local variable.
     * The slots will start out with no live value in them.
     */
    void defineTemporaryLocalVariable(int fromSlot, int toSlot) {
        stack.defineTemporaryLocalVariable(fromSlot, toSlot);
    }

    /**
     * Defines a new temporary local variable and returns its allocated index.
     * @param width the required width (in slots) for the new variable.
     * @return the bytecode slot index where the newly allocated local begins.
     */
    int defineTemporaryLocalVariable(int width) {
        return stack.defineTemporaryLocalVariable(width);
    }

    void undefineLocalVariables(int fromSlot, boolean canTruncateSymbol) {
        if (isReachable()) {
            stack.undefineLocalVariables(fromSlot, canTruncateSymbol);
        }
    }

    List<Type> getLocalVariableTypes() {
        return stack.localVariableTypes;
    }

    List<Type> getWidestLiveLocals(List<Type> localTypes) {
        return stack.getWidestLiveLocals(localTypes);
    }

    String markSymbolBoundariesInLvarTypesDescriptor(String lvarDescriptor) {
        return stack.markSymbolBoundariesInLvarTypesDescriptor(lvarDescriptor);
    }

    /**
     * Increment/Decrement a local integer by the given value.
     */
    void iinc(int slot, int increment) {
        debug("iinc");
        method.visitIincInsn(slot, increment);
    }

    /**
     * Pop an exception object from the stack and generate code for throwing it
     */
    public void athrow() {
        debug("athrow");
        var receiver = popType(Type.OBJECT);
        assert Throwable.class.isAssignableFrom(receiver.getTypeClass()) : receiver.getTypeClass();
        method.visitInsn(ATHROW);
        doesNotContinueSequentially();
    }

    /**
     * Pop an object from the stack and perform an instanceof operation, given a classDescriptor to compare it to.
     * Push the boolean result 1/0 as an int to the stack.
     */
    MethodEmitter _instanceof(String classDescriptor) {
        debug("instanceof", classDescriptor);
        popType(Type.OBJECT);
        method.visitTypeInsn(INSTANCEOF, classDescriptor);
        pushType(Type.INT);
        return this;
    }

    /**
     * Pop an object from the stack and perform an instanceof operation, given a classDescriptor to compare it to.
     * Push the boolean result 1/0 as an int to the stack
     */
    MethodEmitter _instanceof(Class<?> clazz) {
        return _instanceof(CompilerConstants.className(clazz));
    }

    /**
     * Perform a checkcast operation on the object at the top of the stack.
     */
    MethodEmitter checkcast(String classDescriptor) {
        debug("checkcast", classDescriptor);
        assert peekType().isObject();
        method.visitTypeInsn(CHECKCAST, classDescriptor);
        return this;
    }

    /**
     * Perform a checkcast operation on the object at the top of the stack.
     */
    MethodEmitter checkcast(Class<?> clazz) {
        return checkcast(CompilerConstants.className(clazz));
    }

    /**
     * Instantiate a new array given a length that is popped from the stack and the array type
     */
    MethodEmitter newarray(ArrayType arrayType) {
        debug("newarray ", "arrayType=", arrayType);
        popType(Type.INT); // LENGTH
        pushType(arrayType.newarray(method));
        return this;
    }

    /**
     * Instantiate a multidimensional array with a given number of dimensions.
     * On the stack are dim lengths of the sub arrays.
     */
    MethodEmitter multinewarray(ArrayType arrayType, int dims) {
        debug("multianewarray ", arrayType, dims);
        for (var i = 0; i < dims; i++) {
            popType(Type.INT); //LENGTH
        }
        pushType(arrayType.newarray(method, dims));
        return this;
    }

    /**
     * Helper function to pop and type check the appropriate arguments from the stack given a method signature
     */
    private Type fixParamStack(String signature) {
        var params = Type.getMethodArguments(signature);
        for (var i = params.length - 1; i >= 0; i--) {
            popType(params[i]);
        }
        var returnType = Type.getMethodReturnType(signature);
        return returnType;
    }

    /**
     * Generate an invocation to a Call structure
     * @see CompilerConstants
     */
    MethodEmitter invoke(Call call) {
        return call.invoke(this);
    }

    private MethodEmitter invoke(int opcode, String className, String methodName, String methodDescriptor, boolean hasReceiver) {
        var returnType = fixParamStack(methodDescriptor);

        if (hasReceiver) {
            popType(Type.OBJECT);
        }

        method.visitMethodInsn(opcode, className, methodName, methodDescriptor, opcode == INVOKEINTERFACE);

        if (returnType != null) {
            pushType(returnType);
        }

        return this;
    }

    /**
     * Pop receiver from stack, perform an invoke special
     */
    MethodEmitter invokespecial(String className, String methodName, String methodDescriptor) {
        debug("invokespecial", className, ".", methodName, methodDescriptor);
        return invoke(INVOKESPECIAL, className, methodName, methodDescriptor, true);
    }

    /**
     * Pop receiver from stack, perform an invoke virtual, push return value if any
     */
    MethodEmitter invokevirtual(String className, String methodName, String methodDescriptor) {
        debug("invokevirtual", className, ".", methodName, methodDescriptor, " ", stack);
        return invoke(INVOKEVIRTUAL, className, methodName, methodDescriptor, true);
    }

    /**
     * Perform an invoke static and push the return value if any
     */
    MethodEmitter invokestatic(String className, String methodName, String methodDescriptor) {
        debug("invokestatic", className, ".", methodName, methodDescriptor);
        invoke(INVOKESTATIC, className, methodName, methodDescriptor, false);
        return this;
    }

    /**
     * Perform an invoke static and replace the return type if we know better, e.g. Global.allocate that allocates an array should return an ObjectArray type as a NativeArray counts as that
     */
    MethodEmitter invokestatic(String className, String methodName, String methodDescriptor, Type returnType) {
        invokestatic(className, methodName, methodDescriptor);
        popType();
        pushType(returnType);
        return this;
    }

    /**
     * Pop receiver from stack, perform an invoke interface and push return value if any
     */
    MethodEmitter invokeinterface(String className, String methodName, String methodDescriptor) {
        debug("invokeinterface", className, ".", methodName, methodDescriptor);
        return invoke(INVOKEINTERFACE, className, methodName, methodDescriptor, true);
    }

    static org.objectweb.asm.Label[] getLabels(Label... table) {
        var internalLabels = new org.objectweb.asm.Label[table.length];
        for (var i = 0; i < table.length; i++) {
            internalLabels[i] = table[i].getLabel();
        }
        return internalLabels;
    }

    /**
     * Generate a lookup switch, popping the switch value from the stack
     */
    void lookupswitch(Label defaultLabel, int[] values, Label... table) {//Collection<Label> table) {
        debug("lookupswitch", peekType());
        adjustStackForSwitch(defaultLabel, table);
        method.visitLookupSwitchInsn(defaultLabel.getLabel(), values, getLabels(table));
        doesNotContinueSequentially();
    }

    /**
     * Generate a table switch
     */
    void tableswitch(int lo, int hi, Label defaultLabel, Label... table) {
        debug("tableswitch", peekType());
        adjustStackForSwitch(defaultLabel, table);
        method.visitTableSwitchInsn(lo, hi, defaultLabel.getLabel(), getLabels(table));
        doesNotContinueSequentially();
    }

    private void adjustStackForSwitch(Label defaultLabel, Label... table) {
        popType(Type.INT);
        joinTo(defaultLabel);
        for (var label: table) {
            joinTo(label);
        }
    }

    /**
     * Abstraction for performing a conditional jump of any type
     * @see Condition
     */
    void conditionalJump(Condition cond, Label trueLabel) {
        conditionalJump(cond, cond != Condition.GT && cond != Condition.GE, trueLabel);
    }

    /**
     * Abstraction for performing a conditional jump of any type, including a dcmpg/dcmpl semantic for doubles.
     * @param cond      the condition to test
     * @param isCmpG    is this a dcmpg for numbers, false if it's a dcmpl
     * @param trueLabel the destination label if condition is true
     */
    void conditionalJump(Condition cond, boolean isCmpG, Label trueLabel) {
        if (peekType().isCategory2()) {
            debug("[ld]cmp isCmpG=", isCmpG);
            pushType(get2n().cmp(method, isCmpG));
            jump(Condition.toUnary(cond), trueLabel, 1);
        } else {
            debug("if", cond);
            jump(Condition.toBinary(cond, peekType().isObject()), trueLabel, 2);
        }
    }

    /**
     * Perform a non void return, popping the type from the stack
     */
    void _return(Type type) { // returnValue()
        debug("return", type);
        assert stack.size() == 1 : "Only return value on stack allowed at return point - depth=" + stack.size() + " stack = " + stack;
        var stackType = peekType();
        if (!Type.areEquivalent(type, stackType)) {
            convert(type);
        }
        popType(type).doReturn(method);
        doesNotContinueSequentially();
    }

    /**
     * Perform a return using the stack top value as the guide for the type
     */
    void _return() {
        _return(peekType());
    }

    /**
     * Perform a void return.
     */
    void returnVoid() {
        debug("return [void]");
        assert stack.isEmpty() : stack;
        method.visitInsn(RETURN);
        doesNotContinueSequentially();
    }

    /**
     * Perform a comparison of two number types that are popped from the stack
     * @param isCmpG is this a dcmpg semantic, false if it's a dcmpl semantic
     */
    MethodEmitter cmp(boolean isCmpG) {
        pushType(get2n().cmp(method, isCmpG));
        return this;
    }

    /**
     * Helper function for jumps, conditional or not
     * @param opcode  opcode for jump
     * @param label   destination
     * @param n       elements on stack to compare, 0-2
     */
    private void jump(int opcode, Label label, int n) {
        for (var i = 0; i < n; i++) {
            assert peekType().isInteger() || peekType().isBoolean() || peekType().isObject() : "expecting integer type or object for jump, but found " + peekType();
            popType();
        }
        joinTo(label);
        method.visitJumpInsn(opcode, label.getLabel());
    }

    /**
     * Generate an if_acmpeq
     */
    void if_acmpeq(Label label) {
        debug("if_acmpeq", label);
        jump(IF_ACMPEQ, label, 2);
    }

    /**
     * Generate an if_acmpne
     */
    void if_acmpne(Label label) {
        debug("if_acmpne", label);
        jump(IF_ACMPNE, label, 2);
    }

    /**
     * Generate an ifnull
     */
    void ifnull(Label label) {
        debug("ifnull", label);
        jump(IFNULL, label, 1);
    }

    /**
     * Generate an ifnonnull
     */
    void ifnonnull(Label label) {
        debug("ifnonnull", label);
        jump(IFNONNULL, label, 1);
    }

    /**
     * Generate an ifeq
     */
    void ifeq(Label label) {
        debug("ifeq ", label);
        jump(IFEQ, label, 1);
    }

    /**
     * Generate an if_icmpeq
     */
    void if_icmpeq(Label label) {
        debug("if_icmpeq", label);
        jump(IF_ICMPEQ, label, 2);
    }

    /**
     * Generate an if_ne
     */
    void ifne(Label label) {
        debug("ifne", label);
        jump(IFNE, label, 1);
    }

    /**
     * Generate an if_icmpne
     */
    void if_icmpne(Label label) {
        debug("if_icmpne", label);
        jump(IF_ICMPNE, label, 2);
    }

    /**
     * Generate an iflt
     */
    void iflt(Label label) {
        debug("iflt", label);
        jump(IFLT, label, 1);
    }

    /**
     * Generate an if_icmplt
     */
    void if_icmplt(Label label) {
        debug("if_icmplt", label);
        jump(IF_ICMPLT, label, 2);
    }

    /**
     * Generate an ifle
     */
    void ifle(Label label) {
        debug("ifle", label);
        jump(IFLE, label, 1);
    }

    /**
     * Generate an if_icmple
     */
    void if_icmple(Label label) {
        debug("if_icmple", label);
        jump(IF_ICMPLE, label, 2);
    }

    /**
     * Generate an ifgt
     */
    void ifgt(Label label) {
        debug("ifgt", label);
        jump(IFGT, label, 1);
    }

    /**
     * Generate an if_icmpgt
     */
    void if_icmpgt(Label label) {
        debug("if_icmpgt", label);
        jump(IF_ICMPGT, label, 2);
    }

    /**
     * Generate an ifge
     */
    void ifge(Label label) {
        debug("ifge", label);
        jump(IFGE, label, 1);
    }

    /**
     * Generate an if_icmpge
     */
    void if_icmpge(Label label) {
        debug("if_icmpge", label);
        jump(IF_ICMPGE, label, 2);
    }

    /**
     * Unconditional jump to a label
     */
    void _goto(Label label) {
        debug("goto", label);
        jump(GOTO, label, 0);
        doesNotContinueSequentially(); //whoever reaches the point after us provides the stack, because we don't
    }

    /**
     * Unconditional jump to the start label of a loop.
     * It differs from ordinary {@link #_goto(Label)} in that it will preserve the current label stack, as the next instruction after the goto is loop body that the loop will come back to.
     * Also used to jump at the start label of the continuation handler, as it behaves much like a loop test in the sense that after it is evaluated, it also jumps backwards.
     */
    void gotoLoopStart(Label loopStart) {
        debug("goto (loop)", loopStart);
        jump(GOTO, loopStart, 0);
    }

    /**
     * Unconditional jump without any control flow and data flow testing.
     * You should not normally use this method when generating code, except if you're very sure that you know what you're doing.
     * Normally only used for the admittedly torturous control flow of continuation handler plumbing.
     */
    void uncheckedGoto(Label target) {
        method.visitJumpInsn(GOTO, target.getLabel());
    }

    /**
     * Potential transfer of control to a catch block.
     */
    void canThrow(Label catchLabel) {
        catchLabel.joinFromTry(stack, false);
    }

    /**
     * A join in control flow - helper function that makes sure all entry stacks discovered for the join point so far are equivalent.
     * MergeStack: we are about to enter a label.
     * If its stack, label.getStack() is null we have never been here before.
     * Then we are expected to carry a stack with us.
     */
    private void joinTo(Label label) {
        assert isReachable();
        label.joinFrom(stack);
    }

    /**
     * Register a new label, enter it here.
     */
    void label(Label label) {
        breakLabel(label, -1);
    }

    /**
     * Register a new break target label, enter it here.
     * @param label the label
     * @param liveLocals the number of live locals at this label
     */
    void breakLabel(Label label, int liveLocals) {
        if (!isReachable()) {
            // If we emit a label, and the label's stack is null, it must not be reachable.
            assert (label.getStack() == null) != label.isReachable();
        } else {
            joinTo(label);
        }
        // Use label's stack as we might have no stack.
        var labelStack = label.getStack();
        stack = labelStack == null ? null : labelStack.clone();
        if (stack != null && label.isBreakTarget() && liveLocals != -1) {
            // This has to be done because we might not have another frame to provide us with its firstTemp if the label is only reachable through a break or continue statement; also in this case, the frame can actually give us a higher number of live locals, e.g. if it comes from a catch.
            // Typical example: for(;;) { try{ throw 0; } catch(e) { break; } }.
            // Since the for loop can only be exited through the break in the catch block, it'll bring with it the "e" as a live local, and we need to trim it off here.
            assert stack.firstTemp >= liveLocals;
            stack.firstTemp = liveLocals;
        }
        debug_label(label);
        method.visitLabel(label.getLabel());
    }

    /**
     * Pop element from stack, convert to given type
     */
    MethodEmitter convert(Type to) {
        var from = peekType();
        var type = from.convert(method, to);
        if (type != null) {
            if (!from.isEquivalentTo(to)) {
                debug("convert", from, "->", to);
            }
            if (type != from) {
                var l0 = stack.getTopLocalLoad();
                popType();
                pushType(type);
                // NOTE: conversions from a primitive type are considered to preserve the "load" property of the value on the stack.
                // Otherwise we could introduce temporary locals in a deoptimized rest-of (e.g. doing an "i < x.length" where "i" is int and ".length" gets deoptimized to long would end up converting i to long with "ILOAD i; I2L; LSTORE tmp; LLOAD tmp;").
                // Such additional temporary would cause an error when restoring the state of the function for rest-of execution, as the not-yet deoptimized variant would have the (now invalidated) assumption that "x.length" is an int, so it wouldn't have the I2L, and therefore neither the subsequent LSTORE tmp; LLOAD tmp;.
                // By making sure conversions from a primitive type don't erase the "load" information, we don't introduce temporaries in the deoptimized rest-of that didn't exist in the more optimistic version that triggered the deoptimization.
                // NOTE: as a more general observation, we could theoretically track the operations required to reproduce any stack value as long as they are all local loads, constant loads, and stack operations.
                // We won't go there in the current system
                if (!from.isObject()) {
                    stack.markLocalLoad(l0);
                }
            }
        }
        return this;
    }

    /**
     * Helper function - expect two types that are equivalent
     */
    private Type get2() {
        var p0 = popType();
        var p1 = popType();
        assert p0.isEquivalentTo(p1) : "expecting equivalent types on stack but got " + p0 + " and " + p1;
        return p0;
    }

    /**
     * Helper function - expect two types that are integer types and equivalent
     */
    private BitwiseType get2i() {
        var p0 = popBitwise();
        var p1 = popBitwise();
        assert p0.isEquivalentTo(p1) : "expecting equivalent types on stack but got " + p0 + " and " + p1;
        return p0;
    }

    /**
     * Helper function - expect two types that are numbers and equivalent
     */
    private NumericType get2n() {
        var p0 = popNumeric();
        var p1 = popNumeric();
        assert p0.isEquivalentTo(p1) : "expecting equivalent types on stack but got " + p0 + " and " + p1;
        return p0;
    }

    /**
     * Pop two numbers, perform addition and push result
     *
     */
    MethodEmitter add(int programPoint) {
        debug("add");
        pushType(get2().add(method, programPoint));
        return this;
    }

    /**
     * Pop two numbers, perform subtraction and push result
     */
    MethodEmitter sub(int programPoint) {
        debug("sub");
        pushType(get2n().sub(method, programPoint));
        return this;
    }

    /**
     * Pop two numbers, perform multiplication and push result
     */
    MethodEmitter mul(int programPoint) {
        debug("mul ");
        pushType(get2n().mul(method, programPoint));
        return this;
    }

    /**
     * Pop two numbers, perform division and push result
     */
    MethodEmitter div(int programPoint) {
        debug("div");
        pushType(get2n().div(method, programPoint));
        return this;
    }

    /**
     * Pop two numbers, calculate remainder and push result
     */
    MethodEmitter rem(int programPoint) {
        debug("rem");
        pushType(get2n().rem(method, programPoint));
        return this;
    }

    /**
     * Retrieve the top <code>count</code> types on the stack without modifying it.
     * @param count number of types to return
     * @return array of Types
     */
    protected Type[] getTypesFromStack(int count) {
        return stack.getTopTypes(count);
    }

    int[] getLocalLoadsOnStack(int from, int to) {
        return stack.getLocalLoads(from, to);
    }

    int getStackSize() {
        return stack.size();
    }

    int getFirstTemp() {
        return stack.firstTemp;
    }

    int getUsedSlotsWithLiveTemporaries() {
        return stack.getUsedSlotsWithLiveTemporaries();
    }

    /**
     * Helper function to generate a function signature based on stack contents and argument count and return type
     */
    private String getDynamicSignature(Type returnType, int argCount) {
        var paramTypes = new Type[argCount];

        var pos = 0;
        for (var i = argCount - 1; i >= 0; i--) {
            var pt = stack.peek(pos++);
            // "erase" specific ScriptObject subtype info - except for NativeArray.
            // NativeArray is used for array/List/Deque conversion for Java calls.
            if (ScriptObject.class.isAssignableFrom(pt.getTypeClass()) && !NativeArray.class.isAssignableFrom(pt.getTypeClass())) {
                pt = Type.SCRIPT_OBJECT;
            }
            paramTypes[i] = pt;
        }
        var descriptor = Type.getMethodDescriptor(returnType, paramTypes);
        for (var i = 0; i < argCount; i++) {
            popType(paramTypes[argCount - i - 1]);
        }

        return descriptor;
    }

    MethodEmitter invalidateSpecialName(String name) {
        switch (name) {
            // default ->  {}
            case "apply", "call" -> {
                debug("invalidate_name", "name=", name);
                load("Function");
                invoke(ScriptRuntime.INVALIDATE_RESERVED_BUILTIN_NAME);
            }
        }
        return this;
    }

    /**
     * Generate a dynamic new
     * @param argCount  number of arguments
     * @param flags     callsite flags
     */
    MethodEmitter dynamicNew(int argCount, int flags) {
        return dynamicNew(argCount, flags, null);
    }

    /**
     * Generate a dynamic new
     * @param argCount  number of arguments
     * @param flags     callsite flags
     * @param msg        additional message to be used when reporting error
     */
    MethodEmitter dynamicNew(int argCount, int flags, String msg) {
        assert !isOptimistic(flags);
        debug("dynamic_new", "argcount=", argCount);
        var signature = getDynamicSignature(Type.OBJECT, argCount);
        method.visitInvokeDynamicInsn(msg != null && msg.length() < LARGE_STRING_THRESHOLD ? NameCodec.encode(msg) : EMPTY_NAME, signature, LINKERBOOTSTRAP, flags | NashornCallSiteDescriptor.NEW);
        pushType(Type.OBJECT); //TODO fix result type
        return this;
    }

    /**
     * Generate a dynamic call
     * @param returnType return type
     * @param argCount   number of arguments
     * @param flags      callsite flags
     */
    MethodEmitter dynamicCall(Type returnType, int argCount, int flags) {
        return dynamicCall(returnType, argCount, flags, null);
    }

    /**
     * Generate a dynamic call
     * @param returnType return type
     * @param argCount   number of arguments
     * @param flags      callsite flags
     * @param msg        additional message to be used when reporting error
     */
    MethodEmitter dynamicCall(Type returnType, int argCount, int flags, String msg) {
        debug("dynamic_call", "args=", argCount, "returnType=", returnType);
        var signature = getDynamicSignature(returnType, argCount); // +1 because the function itself is the 1st parameter for dynamic calls (what you call - call target)
        debug("   signature", signature);
        method.visitInvokeDynamicInsn(msg != null && msg.length() < LARGE_STRING_THRESHOLD? NameCodec.encode(msg) : EMPTY_NAME, signature, LINKERBOOTSTRAP, flags | NashornCallSiteDescriptor.CALL);
        pushType(returnType);
        return this;
    }

    MethodEmitter dynamicArrayPopulatorCall(int argCount, int startIndex) {
        debug("populate_array", "args=", argCount, "startIndex=", startIndex);
        final String signature = getDynamicSignature(Type.OBJECT_ARRAY, argCount);
        method.visitInvokeDynamicInsn("populateArray", signature, POPULATE_ARRAY_BOOTSTRAP, startIndex);
        pushType(Type.OBJECT_ARRAY);
        return this;
    }

    /**
     * Generate dynamic getter. Pop object from stack. Push result.
     * @param valueType type of the value to set
     * @param name      name of property
     * @param flags     call site flags
     * @param isMethod  should it prefer retrieving methods
     * @param isIndex   is this an index operation?
     */
    MethodEmitter dynamicGet(Type valueType, String name, int flags, boolean isMethod, boolean isIndex) {
        if (name.length() > LARGE_STRING_THRESHOLD) { // use getIndex for extremely long names
            return load(name).dynamicGetIndex(valueType, flags, isMethod);
        }

        debug("dynamic_get", name, valueType, getProgramPoint(flags));

        var type = valueType;
        if (type.isObject() || type.isBoolean()) {
            type = Type.OBJECT; //promote e.g strings to object generic setter
        }

        popType(Type.OBJECT);
        method.visitInvokeDynamicInsn(NameCodec.encode(name), Type.getMethodDescriptor(type, Type.OBJECT), LINKERBOOTSTRAP, flags | dynGetOperation(isMethod, isIndex));

        pushType(type);
        convert(valueType); // most probably a nop

        return this;
    }

    /**
     * Generate dynamic setter. Pop receiver and property from stack.
     * @param name  name of property
     * @param flags call site flags
     * @param isIndex is this an index operation?
     */
    void dynamicSet(String name, int flags, boolean isIndex) {
        if (name.length() > LARGE_STRING_THRESHOLD) { // use setIndex for extremely long names
            load(name).swap().dynamicSetIndex(flags);
            return;
        }

        assert !isOptimistic(flags);
        debug("dynamic_set", name, peekType());

        var type = peekType();
        if (type.isObject() || type.isBoolean()) { //promote strings to objects etc
            type = Type.OBJECT;
            convert(Type.OBJECT); //TODO bad- until we specialize boolean setters,
        }
        popType(type);
        popType(Type.OBJECT);

        method.visitInvokeDynamicInsn(NameCodec.encode(name), methodDescriptor(void.class, Object.class, type.getTypeClass()), LINKERBOOTSTRAP, flags | dynSetOperation(isIndex));
    }

    /**
     * Generate dynamic remover.
     * Pop object from stack.
     * Push result.
     * @param name      name of property
     * @param flags     call site flags
     */
    MethodEmitter dynamicRemove(String name, int flags, boolean isIndex) {
        if (name.length() > LARGE_STRING_THRESHOLD) { // use removeIndex for extremely long names
            return load(name).dynamicRemoveIndex(flags);
        }

        debug("dynamic_remove", name, Type.BOOLEAN, getProgramPoint(flags));

        popType(Type.OBJECT);
        // Type is widened to OBJECT then coerced back to BOOLEAN
        method.visitInvokeDynamicInsn(NameCodec.encode(name), Type.getMethodDescriptor(Type.OBJECT, Type.OBJECT), LINKERBOOTSTRAP, flags | dynRemoveOperation(isIndex));

        pushType(Type.OBJECT);
        convert(Type.BOOLEAN); // most probably a nop

        return this;
    }

    /**
     * Dynamic getter for indexed structures.
     * Pop index and receiver from stack, generate appropriate signatures based on types
     * @param result result type for getter
     * @param flags call site flags for getter
     * @param isMethod should it prefer retrieving methods
     */
    MethodEmitter dynamicGetIndex(Type result, int flags, boolean isMethod) {
        assert result.getTypeClass().isPrimitive() || result.getTypeClass() == Object.class;
        debug("dynamic_get_index", peekType(1), "[", peekType(), "]", getProgramPoint(flags));

        var resultType = result;
        if (result.isBoolean()) {
            resultType = Type.OBJECT; // INT->OBJECT to avoid another dimension of cross products in the getters. TODO
        }

        var index = peekType();
        if (index.isObject() || index.isBoolean()) {
            index = Type.OBJECT; //e.g. string->object
            convert(Type.OBJECT);
        }
        popType();

        popType(Type.OBJECT);

        var signature = Type.getMethodDescriptor(resultType, Type.OBJECT /*e.g STRING->OBJECT*/, index);

        method.visitInvokeDynamicInsn(EMPTY_NAME, signature, LINKERBOOTSTRAP, flags | dynGetOperation(isMethod, true));
        pushType(resultType);

        if (result.isBoolean()) {
            convert(Type.BOOLEAN);
        }

        return this;
    }

    private static String getProgramPoint(int flags) {
        if ((flags & CALLSITE_OPTIMISTIC) == 0) {
            return "";
        }
        return "pp=" + String.valueOf((flags & (-1 << CALLSITE_PROGRAM_POINT_SHIFT)) >> CALLSITE_PROGRAM_POINT_SHIFT);
    }

    /**
     * Dynamic setter for indexed structures.
     * Pop value, index and receiver from stack, generate appropriate signature based on types
     * @param flags call site flags for setter
     */
    void dynamicSetIndex(int flags) {
        assert !isOptimistic(flags);
        debug("dynamic_set_index", peekType(2), "[", peekType(1), "] =", peekType());

        var value = peekType();
        if (value.isObject() || value.isBoolean()) {
            value = Type.OBJECT; //e.g. STRING->OBJECT - one descriptor for all object types
            convert(Type.OBJECT);
        }
        popType();

        var index = peekType();
        if (index.isObject() || index.isBoolean()) {
            index = Type.OBJECT; //e.g. string->object
            convert(Type.OBJECT);
        }
        popType(index);

        var receiver = popType(Type.OBJECT);
        assert receiver.isObject();

        method.visitInvokeDynamicInsn(EMPTY_NAME, methodDescriptor(void.class, receiver.getTypeClass(), index.getTypeClass(), value.getTypeClass()), LINKERBOOTSTRAP, flags | NashornCallSiteDescriptor.SET_ELEMENT);
    }

    /**
     * Dynamic remover for indexed structures.
     * Pop index and receiver from stack, generate appropriate signatures based on types
     * @param flags call site flags for getter
     */
    MethodEmitter dynamicRemoveIndex(int flags) {
        debug("dynamic_remove_index", peekType(1), "[", peekType(), "]", getProgramPoint(flags));

        var index = peekType();
        if (index.isObject() || index.isBoolean()) {
            index = Type.OBJECT; //e.g. string->object
            convert(Type.OBJECT);
        }
        popType();

        popType(Type.OBJECT);

        var signature = Type.getMethodDescriptor(Type.OBJECT, Type.OBJECT /*e.g STRING->OBJECT*/, index);

        method.visitInvokeDynamicInsn(EMPTY_NAME, signature, LINKERBOOTSTRAP, flags | dynRemoveOperation(true));
        pushType(Type.OBJECT);
        convert(Type.BOOLEAN);

        return this;
    }

    /**
     * Load a key value in the proper form.
     */
    // TODO move this and break it apart
    MethodEmitter loadKey(Object key) {
        if (key instanceof IdentNode) {
            method.visitLdcInsn(((IdentNode) key).getName());
        } else if (key instanceof LiteralNode) {
            method.visitLdcInsn(((LiteralNode<?>)key).getString());
        } else {
            method.visitLdcInsn(JSType.toString(key));
        }
        pushType(Type.OBJECT); // STRING
        return this;
    }

     @SuppressWarnings("fallthrough")
     private static Type fieldType(String desc) {
         switch (desc) {
             case "Z", "B", "C", "S", "I":
                 return Type.INT;
             case "F":
                 assert false;
                 // FALL-THROUGH
             case "D":
                 return Type.NUMBER;
             case "J":
                 return Type.LONG;
             default: {
                 assert desc.startsWith("[") || desc.startsWith("L") : desc + " is not an object type";
                 switch (desc.charAt(0)) {
                    case 'L': return Type.OBJECT;
                    case '[': return Type.typeFor(Array.newInstance(fieldType(desc.substring(1)).getTypeClass(), 0).getClass());
                    default:  assert false;
                 }
                 return Type.OBJECT;
             }
         }
     }

     /**
      * Generate get for a field access
      */
    MethodEmitter getField(FieldAccess fa) {
        return fa.get(this);
    }

     /**
      * Generate set for a field access
      */
    void putField(FieldAccess fa) {
        fa.put(this);
    }

    /**
     * Get the value of a non-static field, pop the receiver from the stack, push value to the stack
     */
    MethodEmitter getField(String className, String fieldName, String fieldDescriptor) {
        debug("getfield", "receiver=", peekType(), className, ".", fieldName, fieldDescriptor);
        var receiver = popType();
        assert receiver.isObject();
        method.visitFieldInsn(GETFIELD, className, fieldName, fieldDescriptor);
        pushType(fieldType(fieldDescriptor));
        return this;
    }

    /**
     * Get the value of a static field, push it to the stack
     */
    MethodEmitter getStatic(String className, String fieldName, String fieldDescriptor) {
        debug("getstatic", className, ".", fieldName, ".", fieldDescriptor);
        method.visitFieldInsn(GETSTATIC, className, fieldName, fieldDescriptor);
        pushType(fieldType(fieldDescriptor));
        return this;
    }

    /**
     * Pop value and field from stack and write to a non-static field
     */
    void putField(String className, String fieldName, String fieldDescriptor) {
        debug("putfield", "receiver=", peekType(1), "value=", peekType());
        popType(fieldType(fieldDescriptor));
        popType(Type.OBJECT);
        method.visitFieldInsn(PUTFIELD, className, fieldName, fieldDescriptor);
    }

    /**
     * Pop value from stack and write to a static field
     */
    void putStatic(String className, String fieldName, String fieldDescriptor) {
        debug("putfield", "value=", peekType());
        popType(fieldType(fieldDescriptor));
        method.visitFieldInsn(PUTSTATIC, className, fieldName, fieldDescriptor);
    }

    /**
     * Register line number at a label
     */
    void lineNumber(int line) {
        if (context.getEnv()._debug_lines) {
            debug_label("[LINE]", line);
            var l = new org.objectweb.asm.Label();
            method.visitLabel(l);
            method.visitLineNumber(line, l);
        }
    }

    void beforeJoinPoint(JoinPredecessor joinPredecessor) {
        var next = joinPredecessor.getLocalVariableConversion();
        while (next != null) {
            var symbol = next.getSymbol();
            if (next.isLive()) {
                emitLocalVariableConversion(next, true);
            } else {
                markDeadLocalVariable(symbol);
            }
            next = next.getNext();
        }
    }

    void beforeTry(TryNode tryNode, Label recovery) {
        var next = tryNode.getLocalVariableConversion();
        while (next != null) {
            if (next.isLive()) {
                var to = emitLocalVariableConversion(next, false);
                recovery.getStack().onLocalStore(to, next.getSymbol().getSlot(to), true);
            }
            next = next.getNext();
        }
    }

    private static int dynGetOperation(boolean isMethod, boolean isIndex) {
        if (isMethod) {
            return isIndex ? NashornCallSiteDescriptor.GET_METHOD_ELEMENT : NashornCallSiteDescriptor.GET_METHOD_PROPERTY;
        } else {
            return isIndex ? NashornCallSiteDescriptor.GET_ELEMENT : NashornCallSiteDescriptor.GET_PROPERTY;
        }
    }

    private static int dynSetOperation(boolean isIndex) {
        return isIndex ? NashornCallSiteDescriptor.SET_ELEMENT : NashornCallSiteDescriptor.SET_PROPERTY;
    }

    private static int dynRemoveOperation(boolean isIndex) {
        return isIndex ? NashornCallSiteDescriptor.REMOVE_ELEMENT : NashornCallSiteDescriptor.REMOVE_PROPERTY;
    }

    private Type emitLocalVariableConversion(LocalVariableConversion conversion, boolean onlySymbolLiveValue) {
        var from = conversion.getFrom();
        var to = conversion.getTo();
        var symbol = conversion.getSymbol();
        assert symbol.isBytecodeLocal();
        if (from == Type.UNDEFINED) {
            loadUndefined(to);
        } else {
            load(symbol, from).convert(to);
        }
        store(symbol, to, onlySymbolLiveValue);
        return to;
    }

    /*
     * Debugging below
     */

    private final FieldAccess ERR_STREAM       = staticField(System.class, "err", PrintStream.class);
    private final Call        PRINT            = virtualCallNoLookup(PrintStream.class, "print", void.class, Object.class);
    private final Call        PRINTLN          = virtualCallNoLookup(PrintStream.class, "println", void.class, Object.class);
    private final Call        PRINT_STACKTRACE = virtualCallNoLookup(Throwable.class, "printStackTrace", void.class);

    /**
     * Emit a System.err.print statement of whatever is on top of the bytecode stack
     */
     void print() {
         getField(ERR_STREAM);
         swap();
         convert(Type.OBJECT);
         invoke(PRINT);
     }

    /**
     * Emit a System.err.println statement of whatever is on top of the bytecode stack
     */
     void println() {
         getField(ERR_STREAM);
         swap();
         convert(Type.OBJECT);
         invoke(PRINTLN);
     }

     /**
      * Emit a System.err.print statement
      */
     void print(String string) {
         getField(ERR_STREAM);
         load(string);
         invoke(PRINT);
     }

     /**
      * Emit a System.err.println statement
      */
     void println(String string) {
         getField(ERR_STREAM);
         load(string);
         invoke(PRINTLN);
     }

     /**
      * Print a stacktrace to S
      */
     void stacktrace() {
         _new(Throwable.class);
         dup();
         invoke(constructorNoLookup(Throwable.class));
         invoke(PRINT_STACKTRACE);
     }

    private static int linePrefix = 0;

    /**
     * Debug function that outputs generated bytecode and stack contents
     */
    @SuppressWarnings("unused")
    private void debug(Object... args) {
        if (debug) {
            debug(30, args);
        }
    }

    private void debug(String arg) {
        if (debug) {
            debug(30, arg);
        }
    }

    private void debug(Object arg0, Object arg1) {
        if (debug) {
            debug(30, new Object[] { arg0, arg1 });
        }
    }

    private void debug(Object arg0, Object arg1, Object arg2) {
        if (debug) {
            debug(30, new Object[] { arg0, arg1, arg2 });
        }
    }

    private void debug(Object arg0, Object arg1, Object arg2, Object arg3) {
        if (debug) {
            debug(30, new Object[] { arg0, arg1, arg2, arg3 });
        }
    }

    private void debug(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4) {
        if (debug) {
            debug(30, new Object[] { arg0, arg1, arg2, arg3, arg4 });
        }
    }

    private void debug(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {
        if (debug) {
            debug(30, new Object[] { arg0, arg1, arg2, arg3, arg4, arg5 });
        }
    }

    private void debug(Object arg0, Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) {
        if (debug) {
            debug(30, new Object[] { arg0, arg1, arg2, arg3, arg4, arg5, arg6 });
        }
    }

    /**
     * Debug function that outputs generated bytecode and stack contents for a label - indentation is currently the only thing that differs
     */
    private void debug_label(Object... args) {
        if (debug) {
            debug(22, args);
        }
    }

    private void debug(int padConstant, Object... args) {
        if (debug) {
            var sb = new StringBuilder();
            int pad;

            sb.append('#');
            sb.append(++linePrefix);

            pad = 5 - sb.length();
            while (pad > 0) {
                sb.append(' ');
                pad--;
            }

            if (isReachable() && !stack.isEmpty()) {
                sb.append("{");
                sb.append(stack.size());
                sb.append(":");
                for (var pos = 0; pos < stack.size(); pos++) {
                    var t = stack.peek(pos);

                    if (t == Type.SCOPE) {
                        sb.append("scope");
                    } else if (t == Type.THIS) {
                        sb.append("this");
                    } else if (t.isObject()) {
                        var desc = t.getDescriptor();
                        int i;
                        for (i = 0; desc.charAt(i) == '[' && i < desc.length(); i++) {
                            sb.append('[');
                        }
                        desc = desc.substring(i);
                        var slash = desc.lastIndexOf('/');
                        if (slash != -1) {
                            desc = desc.substring(slash + 1, desc.length() - 1);
                        }
                        if ("Object".equals(desc)) {
                            sb.append('O');
                        } else {
                            sb.append(desc);
                        }
                    } else {
                        sb.append(t.getDescriptor());
                    }
                    var loadIndex = stack.localLoads[stack.sp - 1 - pos];
                    if (loadIndex != Label.Stack.NON_LOAD) {
                        sb.append('(').append(loadIndex).append(')');
                    }
                    if (pos + 1 < stack.size()) {
                        sb.append(' ');
                    }
                }
                sb.append('}');
                sb.append(' ');
            }

            pad = padConstant - sb.length();
            while (pad > 0) {
                sb.append(' ');
                pad--;
            }

            for (var arg : args) {
                sb.append(arg);
                sb.append(' ');
            }

            if (context.getEnv() != null) { //early bootstrap code doesn't have inited context yet
                log.info(sb);
                if (DEBUG_TRACE_LINE == linePrefix) {
                    new Throwable().printStackTrace(log.getOutputStream());
                }
            }
        }
    }

    /**
     * Set the current function node being emitted
     */
    void setFunctionNode(FunctionNode functionNode) {
        this.functionNode = functionNode;
    }

    /**
     * Invoke to enforce assertions preventing load from a local variable slot that's known to not have been written to.
     * Used by CodeGenerator, as it strictly enforces tracking of stores.
     * Simpler uses of MethodEmitter, e.g. those for creating initializers for structure  classes, array getters, etc. don't have strict tracking of stores, therefore they would fail if they had this assertion turned on.
     */
    void setPreventUndefinedLoad() {
        this.preventUndefinedLoad = true;
    }

    private static boolean isOptimistic(int flags) {
        return (flags & CALLSITE_OPTIMISTIC) != 0;
    }

}
