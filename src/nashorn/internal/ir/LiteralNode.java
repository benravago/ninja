/*
 * Copyright (c) 2010, 2016, Oracle and/or its affiliates. All rights reserved.
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

package nashorn.internal.ir;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nashorn.internal.codegen.types.ArrayType;
import nashorn.internal.codegen.types.Type;
import nashorn.internal.ir.annotations.Ignore;
import nashorn.internal.ir.annotations.Immutable;
import nashorn.internal.ir.visitor.NodeVisitor;
import nashorn.internal.objects.NativeArray;
import nashorn.internal.parser.Lexer.LexerToken;
import nashorn.internal.parser.Token;
import nashorn.internal.parser.TokenType;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.Undefined;

/**
 * Literal nodes represent JavaScript values.
 *
 * @param <T> the literal type
 */
@Immutable
public abstract class LiteralNode<T> extends Expression implements PropertyKey {

    /** Literal value */
    protected final T value;

    /** Marker for values that must be computed at runtime */
    public static final Object POSTSET_MARKER = new Object();

    /**
     * Constructor
     */
    protected LiteralNode(long token, int finish, T value) {
        super(token, finish);
        this.value = value;
    }

    /**
     * Copy constructor
     */
    protected LiteralNode(LiteralNode<T> literalNode) {
        this(literalNode, literalNode.value);
    }

    /**
     * A copy constructor with value change.
     */
    protected LiteralNode(LiteralNode<T> literalNode, T newValue) {
        super(literalNode);
        this.value = newValue;
    }

    /**
     * Initialization setter, if required for immutable state.
     * This is used for things like ArrayLiteralNodes that need to carry state for the splitter.
     * Default implementation is just a nop.
     */
    public LiteralNode<?> initialize(LexicalContext lc) {
        return this;
    }

    /**
     * Check if the literal value is null
     */
    public boolean isNull() {
        return value == null;
    }

    @Override
    public Type getType() {
        return Type.typeFor(value.getClass());
    }

    @Override
    public String getPropertyName() {
        return JSType.toString(getObject());
    }

    /**
     * Fetch boolean value of node.
     */
    public boolean getBoolean() {
        return JSType.toBoolean(value);
    }

    /**
     * Fetch int32 value of node.
     */
    public int getInt32() {
        return JSType.toInt32(value);
    }

    /**
     * Fetch uint32 value of node.
     */
    public long getUint32() {
        return JSType.toUint32(value);
    }

    /**
     * Fetch long value of node
     */
    public long getLong() {
        return JSType.toLong(value);
    }

    /**
     * Fetch double value of node.
     */
    public double getNumber() {
        return JSType.toNumber(value);
    }

    /**
     * Fetch String value of node.
     */
    public String getString() {
        return JSType.toString(value);
    }

    /**
     * Fetch Object value of node.
     */
    public Object getObject() {
        return value;
    }

    /**
     * Test if the value is an array
     */
    public boolean isArray() {
        return false;
    }

    public List<Expression> getElementExpressions() {
        return null;
    }

    /**
     * Test if the value is a boolean.
     */
    public boolean isBoolean() {
        return value instanceof Boolean;
    }

    /**
     * Test if the value is a string.
     */
    public boolean isString() {
        return value instanceof String;
    }

    /**
     * Test if the value is a number
     */
    public boolean isNumeric() {
        return value instanceof Number;
    }

    /**
     * Assist in IR navigation.
     */
    @Override
    public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
        if (visitor.enterLiteralNode(this)) {
            return visitor.leaveLiteralNode(this);
        }
        return this;
    }

    @Override
    public void toString(StringBuilder sb, boolean printType) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value.toString());
        }
    }

    /**
     * Get the literal node value
     */
    public final T getValue() {
        return value;
    }

    private static Expression[] valueToArray(List<Expression> value) {
        return value.toArray(new Expression[0]);
    }

    /**
     * Create a new null literal
     */
    public static LiteralNode<Object> newInstance(long token, int finish) {
        return new NullLiteralNode(token, finish);
    }

    /**
     * Create a new null literal based on a parent node (source, token, finish)
     */
    public static LiteralNode<Object> newInstance(Node parent) {
        return new NullLiteralNode(parent.getToken(), parent.getFinish());
    }

    /**
     * Super class for primitive (side-effect free) literals.
     */
    public static class PrimitiveLiteralNode<T> extends LiteralNode<T> {

        private PrimitiveLiteralNode(long token, int finish, T value) {
            super(token, finish, value);
        }

        private PrimitiveLiteralNode(PrimitiveLiteralNode<T> literalNode) {
            super(literalNode);
        }

        /**
         * Check if the literal value is boolean true
         */
        public boolean isTrue() {
            return JSType.toBoolean(value);
        }

        @Override
        public boolean isLocal() {
            return true;
        }

        @Override
        public boolean isAlwaysFalse() {
            return !isTrue();
        }

        @Override
        public boolean isAlwaysTrue() {
            return isTrue();
        }
    }

    @Immutable
    private static final class BooleanLiteralNode extends PrimitiveLiteralNode<Boolean> {

        private BooleanLiteralNode(long token, int finish, boolean value) {
            super(Token.recast(token, value ? TokenType.TRUE : TokenType.FALSE), finish, value);
        }

        private BooleanLiteralNode(BooleanLiteralNode literalNode) {
            super(literalNode);
        }

        @Override
        public boolean isTrue() {
            return value;
        }

        @Override
        public Type getType() {
            return Type.BOOLEAN;
        }

        @Override
        public Type getWidestOperationType() {
            return Type.BOOLEAN;
        }
    }

    /**
     * Create a new boolean literal
     */
    public static LiteralNode<Boolean> newInstance(long token, int finish, boolean value) {
        return new BooleanLiteralNode(token, finish, value);
    }

    /**
     * Create a new boolean literal based on a parent node (source, token, finish)
     */
    public static LiteralNode<?> newInstance(Node parent, boolean value) {
        return new BooleanLiteralNode(parent.getToken(), parent.getFinish(), value);
    }

    @Immutable
    private static final class NumberLiteralNode extends PrimitiveLiteralNode<Number> {

        private final Type type = numberGetType(value);

        private NumberLiteralNode(long token, int finish, Number value) {
            super(Token.recast(token, TokenType.DECIMAL), finish, value);
        }

        private NumberLiteralNode(NumberLiteralNode literalNode) {
            super(literalNode);
        }

        private static Type numberGetType(Number number) {
            if (number instanceof Integer) {
                return Type.INT;
            } else if (number instanceof Double) {
                return Type.NUMBER;
            } else {
                assert false;
            }

            return null;
        }

        @Override
        public Type getType() {
            return type;
        }

        @Override
        public Type getWidestOperationType() {
            return getType();
        }

    }
    /**
     * Create a new number literal
     */
    public static LiteralNode<Number> newInstance(long token, int finish, Number value) {
        assert !(value instanceof Long);
        return new NumberLiteralNode(token, finish, value);
    }

    /**
     * Create a new number literal based on a parent node (source, token, finish)
     */
    public static LiteralNode<?> newInstance(Node parent, Number value) {
        return new NumberLiteralNode(parent.getToken(), parent.getFinish(), value);
    }

    private static class UndefinedLiteralNode extends PrimitiveLiteralNode<Undefined> {

        private UndefinedLiteralNode(long token, int finish) {
            super(Token.recast(token, TokenType.OBJECT), finish, ScriptRuntime.UNDEFINED);
        }

        private UndefinedLiteralNode(UndefinedLiteralNode literalNode) {
            super(literalNode);
        }
    }

    /**
     * Create a new undefined literal
     */
    public static LiteralNode<Undefined> newInstance(long token, int finish, Undefined value) {
        return new UndefinedLiteralNode(token, finish);
    }

    /**
     * Create a new null literal based on a parent node (source, token, finish)
     */
    public static LiteralNode<?> newInstance(Node parent, Undefined value) {
        return new UndefinedLiteralNode(parent.getToken(), parent.getFinish());
    }

    @Immutable
    private static class StringLiteralNode extends PrimitiveLiteralNode<String> {

        private StringLiteralNode(long token, int finish, String value) {
            super(Token.recast(token, TokenType.STRING), finish, value);
        }

        private StringLiteralNode(StringLiteralNode literalNode) {
            super(literalNode);
        }

        @Override
        public void toString(StringBuilder sb, boolean printType) {
            sb.append('\"');
            sb.append(value);
            sb.append('\"');
        }
    }

    /**
     * Create a new string literal
     */
    public static LiteralNode<String> newInstance(long token, int finish, String value) {
        return new StringLiteralNode(token, finish, value);
    }

    /**
     * Create a new String literal based on a parent node (source, token, finish)
     */
    public static LiteralNode<?> newInstance(Node parent, String value) {
        return new StringLiteralNode(parent.getToken(), parent.getFinish(), value);
    }

    @Immutable
    private static class LexerTokenLiteralNode extends LiteralNode<LexerToken> {

        private LexerTokenLiteralNode(long token, int finish, LexerToken value) {
            super(Token.recast(token, TokenType.STRING), finish, value); //TODO is string the correct token type here?
        }

        private LexerTokenLiteralNode(LexerTokenLiteralNode literalNode) {
            super(literalNode);
        }

        @Override
        public Type getType() {
            return Type.OBJECT;
        }

        @Override
        public void toString(StringBuilder sb, boolean printType) {
            sb.append(value.toString());
        }
    }

    /**
     * Create a new literal node for a lexer token
     */
    public static LiteralNode<LexerToken> newInstance(long token, int finish, LexerToken value) {
        return new LexerTokenLiteralNode(token, finish, value);
    }

    /**
     * Create a new lexer token literal based on a parent node (source, token, finish)
     */
    public static LiteralNode<?> newInstance(Node parent, LexerToken value) {
        return new LexerTokenLiteralNode(parent.getToken(), parent.getFinish(), value);
    }

    /**
     * Get the constant value for an object, or {@link #POSTSET_MARKER} if the value can't be statically computed.
     */
    public static Object objectAsConstant(Object object) {
        if (object == null) {
            return null;
        } else if (object instanceof Number || object instanceof String || object instanceof Boolean) {
            return object;
        } else if (object instanceof LiteralNode) {
            return objectAsConstant(((LiteralNode<?>)object).getValue());
        }

        return POSTSET_MARKER;
    }

    /**
     * Test whether {@code object} represents a constant value.
     */
    public static boolean isConstant(Object object) {
        return objectAsConstant(object) != POSTSET_MARKER;
    }

    private static final class NullLiteralNode extends PrimitiveLiteralNode<Object> {

        private NullLiteralNode(long token, int finish) {
            super(Token.recast(token, TokenType.OBJECT), finish, null);
        }

        @Override
        public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
            if (visitor.enterLiteralNode(this)) {
                return visitor.leaveLiteralNode(this);
            }
            return this;
        }

        @Override
        public Type getType() {
            return Type.OBJECT;
        }

        @Override
        public Type getWidestOperationType() {
            return Type.OBJECT;
        }
    }

    /**
     * Array literal node class.
     */
    @Immutable
    public static final class ArrayLiteralNode extends LiteralNode<Expression[]> implements LexicalContextNode, Splittable {

        /** Array element type. */
        private final Type elementType;

        /** Preset constant array. */
        private final Object presets;

        /** Indices of array elements requiring computed post sets. */
        private final int[] postsets;

        /** Ranges for splitting up large literals in code generation */
        @Ignore
        private final List<Splittable.SplitRange> splitRanges;

        /** Does this array literal have a trailing comma?*/
        private final boolean hasTrailingComma;

        @Override
        public boolean isArray() {
            return true;
        }

        private static final class ArrayLiteralInitializer {

            static ArrayLiteralNode initialize(ArrayLiteralNode node) {
                var elementType = computeElementType(node.value);
                var postsets = computePostsets(node.value);
                var presets = computePresets(node.value, elementType, postsets);
                return new ArrayLiteralNode(node, node.value, elementType, postsets, presets, node.splitRanges);
            }

            private static Type computeElementType(Expression[] value) {
                Type widestElementType = Type.INT;

                for (var elem : value) {
                    if (elem == null) {
                        // no way to represent undefined as number
                        widestElementType = widestElementType.widest(Type.OBJECT);
                        break;
                    }

                    var type = elem.getType().isUnknown() ? Type.OBJECT : elem.getType();
                    if (type.isBoolean()) {
                        // TODO fix this with explicit boolean types
                        widestElementType = widestElementType.widest(Type.OBJECT);
                        break;
                    }

                    widestElementType = widestElementType.widest(type);
                    if (widestElementType.isObject()) {
                        break;
                    }
                }
                return widestElementType;
            }

            private static int[] computePostsets(Expression[] value) {
                var computed = new int[value.length];
                var nComputed = 0;

                for (var i = 0; i < value.length; i++) {
                    var element = value[i];
                    if (element == null || !isConstant(element)) {
                        computed[nComputed++] = i;
                    }
                }
                return Arrays.copyOf(computed, nComputed);
            }

            private static boolean setArrayElement(int[] array, int i, Object n) {
                if (n instanceof Number) {
                    array[i] = ((Number)n).intValue();
                    return true;
                }
                return false;
            }

            private static boolean setArrayElement(long[] array, int i, Object n) {
                if (n instanceof Number) {
                    array[i] = ((Number)n).longValue();
                    return true;
                }
                return false;
            }

            private static boolean setArrayElement(double[] array, int i, Object n) {
                if (n instanceof Number) {
                    array[i] = ((Number)n).doubleValue();
                    return true;
                }
                return false;
            }

            private static int[] presetIntArray(Expression[] value, int[] postsets) {
                var array = new int[value.length];
                var nComputed = 0;
                for (var i = 0; i < value.length; i++) {
                    if (!setArrayElement(array, i, objectAsConstant(value[i]))) {
                        assert postsets[nComputed++] == i;
                    }
                }
                assert postsets.length == nComputed;
                return array;
            }

            private static long[] presetLongArray(Expression[] value, int[] postsets) {
                var array = new long[value.length];
                var nComputed = 0;
                for (var i = 0; i < value.length; i++) {
                    if (!setArrayElement(array, i, objectAsConstant(value[i]))) {
                        assert postsets[nComputed++] == i;
                    }
                }
                assert postsets.length == nComputed;
                return array;
            }

            private static double[] presetDoubleArray(Expression[] value, int[] postsets) {
                var array = new double[value.length];
                var nComputed = 0;
                for (var i = 0; i < value.length; i++) {
                    if (!setArrayElement(array, i, objectAsConstant(value[i]))) {
                        assert postsets[nComputed++] == i;
                    }
                }
                assert postsets.length == nComputed;
                return array;
            }

            private static Object[] presetObjectArray(Expression[] value, int[] postsets) {
                var array = new Object[value.length];
                var nComputed = 0;

                for (var i = 0; i < value.length; i++) {
                    var node = value[i];

                    if (node == null) {
                        assert postsets[nComputed++] == i;
                        continue;
                    }
                    var element = objectAsConstant(node);

                    if (element != POSTSET_MARKER) {
                        array[i] = element;
                    } else {
                        assert postsets[nComputed++] == i;
                    }
                }

                assert postsets.length == nComputed;
                return array;
            }

            static Object computePresets(Expression[] value, Type elementType, int[] postsets) {
                assert !elementType.isUnknown();
                if (elementType.isInteger()) {
                    return presetIntArray(value, postsets);
                } else if (elementType.isNumeric()) {
                    return presetDoubleArray(value, postsets);
                } else {
                    return presetObjectArray(value, postsets);
                }
            }
        }

        /**
         * Constructor
         */
        protected ArrayLiteralNode(long token, int finish, Expression[] value) {
            this(token, finish, value, false);
        }

        /**
         * Constructor
         */
        protected ArrayLiteralNode(long token, int finish, Expression[] value, boolean hasTrailingComma) {
            super(Token.recast(token, TokenType.ARRAY), finish, value);
            this.elementType = Type.UNKNOWN;
            this.presets = null;
            this.postsets = null;
            this.splitRanges = null;
            this.hasTrailingComma = hasTrailingComma;
        }

        /**
         * Copy constructor
         */
        private ArrayLiteralNode(ArrayLiteralNode node, Expression[] value, Type elementType, int[] postsets, Object presets, List<Splittable.SplitRange> splitRanges) {
            super(node, value);
            this.elementType = elementType;
            this.postsets = postsets;
            this.presets = presets;
            this.splitRanges = splitRanges;
            this.hasTrailingComma = node.hasTrailingComma;
        }

        /**
         * Returns {@code true} if this array literal has a trailing comma.
         */
        public boolean hasTrailingComma() {
             return hasTrailingComma;
        }

        /**
         * Returns a list of array element expressions.
         * Note that empty array elements manifest themselves as null.
         */
        @Override
        public List<Expression> getElementExpressions() {
            return Collections.unmodifiableList(Arrays.asList(value));
        }

        /**
         * Setter that initializes all code generation meta data for an ArrayLiteralNode.
         * This acts a setter, so the return value may return a new node and must be handled
         */
        @Override
        public ArrayLiteralNode initialize(LexicalContext lc) {
            return Node.replaceInLexicalContext(lc, this, ArrayLiteralInitializer.initialize(this));
        }

        /**
         * Get the array element type as Java format, e.g. [I
         */
        public ArrayType getArrayType() {
            return getArrayType(getElementType());
        }

        private static ArrayType getArrayType(Type elementType) {
            if (elementType.isInteger()) {
                return Type.INT_ARRAY;
            } else if (elementType.isNumeric()) {
                return Type.NUMBER_ARRAY;
            } else {
                return Type.OBJECT_ARRAY;
            }
        }

        @Override
        public Type getType() {
            return Type.typeFor(NativeArray.class);
        }

        /**
         * Get the element type of this array literal
         */
        public Type getElementType() {
            assert !elementType.isUnknown() : this + " has elementType=unknown";
            return elementType;
        }

        /**
         * Get indices of arrays containing computed post sets.
         * 'post sets' are things like non literals e.g. "x+y" instead of i or 17
         */
        public int[] getPostsets() {
            assert postsets != null : this + " elementType=" + elementType + " has no postsets";
            return postsets;
        }

        private boolean presetsMatchElementType() {
            if (elementType == Type.INT) {
                return presets instanceof int[];
            } else if (elementType == Type.NUMBER) {
                return presets instanceof double[];
            } else {
                return presets instanceof Object[];
            }
        }

        /**
         * Get presets constant array
         */
        public Object getPresets() {
            assert presets != null && presetsMatchElementType() : this + " doesn't have presets, or invalid preset type: " + presets;
            return presets;
        }

        /**
         * Get the split ranges for this ArrayLiteral, or null if this array does not have to be split.
         * @see Splittable.SplitRange
         */
        @Override
        public List<Splittable.SplitRange> getSplitRanges() {
            return splitRanges == null ? null : Collections.unmodifiableList(splitRanges);
        }

        /**
         * Set the SplitRanges that make up this ArrayLiteral
         * @see Splittable.SplitRange
         */
        public ArrayLiteralNode setSplitRanges(LexicalContext lc, List<Splittable.SplitRange> splitRanges) {
            if (this.splitRanges == splitRanges) {
                return this;
            }
            return Node.replaceInLexicalContext(lc, this, new ArrayLiteralNode(this, value, elementType, postsets, presets, splitRanges));
        }

        @Override
        public Node accept(NodeVisitor<? extends LexicalContext> visitor) {
            return Acceptor.accept(this, visitor);
        }

        @Override
        public Node accept(LexicalContext lc, NodeVisitor<? extends LexicalContext> visitor) {
            if (visitor.enterLiteralNode(this)) {
                final List<Expression> oldValue = Arrays.asList(value);
                final List<Expression> newValue = Node.accept(visitor, oldValue);
                return visitor.leaveLiteralNode(oldValue != newValue ? setValue(lc, newValue) : this);
            }
            return this;
        }

        private ArrayLiteralNode setValue(LexicalContext lc, Expression[] value) {
            if (this.value == value) {
                return this;
            }
            return Node.replaceInLexicalContext(lc, this, new ArrayLiteralNode(this, value, elementType, postsets, presets, splitRanges));
        }

        private ArrayLiteralNode setValue(LexicalContext lc, List<Expression> value) {
            return setValue(lc, value.toArray(new Expression[0]));
        }

        @Override
        public void toString(StringBuilder sb, boolean printType) {
            sb.append('[');
            var first = true;
            for (var node : value) {
                if (!first) {
                    sb.append(", ");
                }
                if (node == null) {
                    sb.append("undefined");
                } else {
                    node.toString(sb, printType);
                }
                first = false;
            }
            sb.append(']');
        }
    }

    /**
     * Create a new array literal of Nodes from a list of Node values
     */
    public static LiteralNode<Expression[]> newInstance(long token, int finish, List<Expression> value) {
        return new ArrayLiteralNode(token, finish, valueToArray(value));
    }

    /**
     * Create a new array literal based on a parent node (source, token, finish)
     */
    public static LiteralNode<?> newInstance(Node parent, List<Expression> value) {
        return new ArrayLiteralNode(parent.getToken(), parent.getFinish(), valueToArray(value));
    }

    /**
     * Create a new array literal of Nodes from a list of Node values
     */
    public static LiteralNode<Expression[]> newInstance(long token, int finish, List<Expression> value, boolean hasTrailingComma) {
        return new ArrayLiteralNode(token, finish, valueToArray(value), hasTrailingComma);
    }

    /**
     * Create a new array literal of Nodes
     */
    public static LiteralNode<Expression[]> newInstance(long token, int finish, Expression[] value) {
        return new ArrayLiteralNode(token, finish, value);
    }

}
