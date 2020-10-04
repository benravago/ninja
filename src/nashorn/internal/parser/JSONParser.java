/*
 * Copyright (c) 2010, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;
import java.util.List;
import nashorn.internal.codegen.ObjectClassGenerator;
import nashorn.internal.objects.Global;
import nashorn.internal.runtime.ECMAErrors;
import nashorn.internal.runtime.ErrorManager;
import nashorn.internal.runtime.JSErrorType;
import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.ParserException;
import nashorn.internal.runtime.Property;
import nashorn.internal.runtime.PropertyMap;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.Source;
import nashorn.internal.runtime.SpillProperty;
import nashorn.internal.runtime.arrays.ArrayData;
import nashorn.internal.runtime.arrays.ArrayIndex;
import nashorn.internal.scripts.JD;
import nashorn.internal.scripts.JO;

import static nashorn.internal.parser.TokenType.STRING;

/**
 * Parses JSON text and returns the corresponding IR node.
 * This is derived from the objectLiteral production of the main parser.
 * See: 15.12.1.2 The JSON Syntactic Grammar
 */
public class JSONParser {

    final private String source;
    final private Global global;
    final private boolean dualFields;
    final int length;
    int pos = 0;

    private static final int EOF = -1;

    private static final String TRUE  = "true";
    private static final String FALSE = "false";
    private static final String NULL  = "null";

    private static final int STATE_EMPTY          = 0;
    private static final int STATE_ELEMENT_PARSED = 1;
    private static final int STATE_COMMA_PARSED   = 2;

    /**
     * Constructor.
     * @param source     the source
     * @param global     the global object
     * @param dualFields whether the parser should regard dual field representation
     */
    public JSONParser(String source, Global global, boolean dualFields) {
        this.source = source;
        this.global = global;
        this.length = source.length();
        this.dualFields = dualFields;
    }

    /**
     * Implementation of the Quote(value) operation as defined in the ECMAscript spec.
     * It wraps a String value in double quotes and escapes characters within.
     */
    public static String quote(String value) {

        var product = new StringBuilder();

        product.append("\"");

        for (var ch : value.toCharArray()) {
            product.append( ch < ' '
                ? Lexer.unicodeEscape(ch)
                : switch(ch) {
                    case '\\' -> "\\\\";
                    case '"'  -> "\\\"";
                    case '\b' -> "\\b";
                    case '\f' -> "\\f";
                    case '\n' -> "\\n";
                    case '\r' -> "\\r";
                    case '\t' -> "\\t";
                    default   -> ch;
                }
            );
        }

        product.append("\"");

        return product.toString();
    }

    /**
     * Public parse method. Parse a string into a JSON object.
     *
     * @return the parsed JSON Object
     */
    public Object parse() {
        final Object value = parseLiteral();
        skipWhiteSpace();
        if (pos < length) {
            throw expectedError(pos, "eof", toString(peek()));
        }
        return value;
    }

    private Object parseLiteral() {
        skipWhiteSpace();

        var c = peek();
        if (c == EOF) {
            throw expectedError(pos, "json literal", "eof");
        }
        return switch(c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 'f' -> parseKeyword(FALSE, Boolean.FALSE);
            case 't' -> parseKeyword(TRUE, Boolean.TRUE);
            case 'n' -> parseKeyword(NULL, null);
            default  -> isDigit(c) || c == '-' ? parseNumber() : badLiteral(c);
        };
    }

    Object badLiteral(int c) {
        if (c == '.') {
            throw numberError(pos);
        } else {
            throw expectedError(pos, "json literal", toString(c));
        }
    }

    private Object parseObject() {
        var propertyMap = dualFields ? JD.getInitialMap() : JO.getInitialMap();
        var arrayData = ArrayData.EMPTY_ARRAY;
        var values = new ArrayList<Object>();
        int state = STATE_EMPTY;

        assert peek() == '{';
        pos++;

        while (pos < length) {
            skipWhiteSpace();
            var c = peek();

            switch (c) {
                case '"' -> {
                    if (state == STATE_ELEMENT_PARSED) {
                        throw expectedError(pos - 1, ", or }", toString(c));
                    }
                    var id = parseString();
                    expectColon();
                    var value = parseLiteral();
                    var index = ArrayIndex.getArrayIndex(id);
                    if (ArrayIndex.isValidArrayIndex(index)) {
                        arrayData = addArrayElement(arrayData, index, value);
                    } else {
                        propertyMap = addObjectProperty(propertyMap, values, id, value);
                    }
                    state = STATE_ELEMENT_PARSED;
                }
                case ',' -> {
                    if (state != STATE_ELEMENT_PARSED) {
                        throw error(AbstractParser.message("trailing.comma.in.json"), pos);
                    }
                    state = STATE_COMMA_PARSED;
                    pos++;
                }
                case '}' -> {
                    if (state == STATE_COMMA_PARSED) {
                        throw error(AbstractParser.message("trailing.comma.in.json"), pos);
                    }
                    pos++;
                    return createObject(propertyMap, values, arrayData);
                }
                default -> {
                    throw expectedError(pos, ", or }", toString(c));
                }
            }
        }
        throw expectedError(pos, ", or }", "eof");
    }

    private static ArrayData addArrayElement(ArrayData arrayData, int index, Object value) {
        var oldLength = arrayData.length();
        var longIndex = ArrayIndex.toLongIndex(index);
        var newArrayData = arrayData;
        if (longIndex >= oldLength) {
            newArrayData = newArrayData.ensure(longIndex);
            if (longIndex > oldLength) {
                newArrayData = newArrayData.delete(oldLength, longIndex - 1);
            }
        }
        return newArrayData.set(index, value); // false
    }

    private PropertyMap addObjectProperty(PropertyMap propertyMap, List<Object> values, String id, Object value) {
        var oldProperty = propertyMap.findProperty(id);
        PropertyMap newMap;
        Class<?> type;
        int flags;
        if (dualFields) {
            type = getType(value);
            flags = Property.DUAL_FIELDS;
        } else {
            type = Object.class;
            flags = 0;
        }

        if (oldProperty != null) {
            values.set(oldProperty.getSlot(), value);
            newMap = propertyMap.replaceProperty(oldProperty, new SpillProperty(id, flags, oldProperty.getSlot(), type));
        } else {
            values.add(value);
            newMap = propertyMap.addProperty(new SpillProperty(id, flags, propertyMap.size(), type));
        }

        return newMap;
    }

    private Object createObject(PropertyMap propertyMap, List<Object> values, ArrayData arrayData) {
        var primitiveSpill = dualFields ? new long[values.size()] : null;
        var objectSpill = new Object[values.size()];

        for (var property : propertyMap.getProperties()) {
            if (!dualFields || property.getType() == Object.class) {
                objectSpill[property.getSlot()] = values.get(property.getSlot());
            } else {
                primitiveSpill[property.getSlot()] = ObjectClassGenerator.pack((Number) values.get(property.getSlot()));
            }
        }

        var object = dualFields ? new JD(propertyMap, primitiveSpill, objectSpill) : new JO(propertyMap, null, objectSpill);
        object.setInitialProto(global.getObjectPrototype());
        object.setArray(arrayData);
        return object;
    }

    private static Class<?> getType(Object value) {
        if (value instanceof Integer) {
            return int.class;
        } else if (value instanceof Double) {
            return double.class;
        } else {
            return Object.class;
        }
    }

    private void expectColon() {
        skipWhiteSpace();
        var n = next();
        if (n != ':') {
            throw expectedError(pos - 1, ":", toString(n));
        }
    }

    private Object parseArray() {
        var arrayData = ArrayData.EMPTY_ARRAY;
        var state = STATE_EMPTY;

        assert peek() == '[';
        pos++;

        while (pos < length) {
            skipWhiteSpace();
            var c = peek();

            switch (c) {
                case ',' -> {
                    if (state != STATE_ELEMENT_PARSED) {
                        throw error(AbstractParser.message("trailing.comma.in.json"), pos);
                    }
                    state = STATE_COMMA_PARSED;
                    pos++;
                }
                case ']' -> {
                    if (state == STATE_COMMA_PARSED) {
                        throw error(AbstractParser.message("trailing.comma.in.json"), pos);
                    }
                    pos++;
                    return global.wrapAsObject(arrayData);
                }
                default -> {
                    if (state == STATE_ELEMENT_PARSED) {
                        throw expectedError(pos, ", or ]", toString(c));
                    }
                    var index = arrayData.length();
                    arrayData = arrayData.ensure(index).set((int) index, parseLiteral()); // true
                    state = STATE_ELEMENT_PARSED;
                }
            }
        }

        throw expectedError(pos, ", or ]", "eof");
    }

    private String parseString() {
        // String buffer is only instantiated if string contains escape sequences.
        var start = ++pos;
        StringBuilder sb = null;

        while (pos < length) {
            var c = next();
            if (c <= 0x1f) {
                // Characters < 0x1f are not allowed in JSON strings.
                throw syntaxError(pos, "String contains control character");

            } else if (c == '\\') {
                if (sb == null) {
                    sb = new StringBuilder(pos - start + 16);
                }
                sb.append(source, start, pos - 1);
                sb.append(parseEscapeSequence());
                start = pos;

            } else if (c == '"') {
                if (sb != null) {
                    sb.append(source, start, pos - 1);
                    return sb.toString();
                }
                return source.substring(start, pos - 1);
            }
        }

        throw error(Lexer.message("missing.close.quote"), pos, length);
    }

    private char parseEscapeSequence() {
        var c = next();
        return switch (c) {
            case '"'  -> '"';
            case '\\' -> '\\';
            case '/'  -> '/';
            case 'b'  -> '\b';
            case 'f'  -> '\f';
            case 'n'  -> '\n';
            case 'r'  -> '\r';
            case 't'  -> '\t';
            case 'u'  -> parseUnicodeEscape();
            default   -> throw error(Lexer.message("invalid.escape.char"), pos - 1, length);
        };
    }

    private char parseUnicodeEscape() {
        return (char) (parseHexDigit() << 12 | parseHexDigit() << 8 | parseHexDigit() << 4 | parseHexDigit());
    }

    private int parseHexDigit() {
        var c = next();
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else if (c >= 'A' && c <= 'F') {
            return c + 10 - 'A';
        } else if (c >= 'a' && c <= 'f') {
            return c + 10 - 'a';
        }
        throw error(Lexer.message("invalid.hex"), pos - 1, length);
    }

    private boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    private void skipDigits() {
        while (pos < length) {
            var c = peek();
            if (!isDigit(c)) {
                break;
            }
            pos++;
        }
    }

    private Number parseNumber() {
        var start = pos;
        var c = next();

        if (c == '-') {
            c = next();
        }
        if (!isDigit(c)) {
            throw numberError(start);
        }
        // no more digits allowed after 0
        if (c != '0') {
            skipDigits();
        }

        // fraction
        if (peek() == '.') {
            pos++;
            if (!isDigit(next())) {
                throw numberError(pos - 1);
            }
            skipDigits();
        }

        // exponent
        c = peek();
        if (c == 'e' || c == 'E') {
            pos++;
            c = next();
            if (c == '-' || c == '+') {
                c = next();
            }
            if (!isDigit(c)) {
                throw numberError(pos - 1);
            }
            skipDigits();
        }

        var d = Double.parseDouble(source.substring(start, pos));
        if (JSType.isRepresentableAsInt(d)) {
            return (int) d;
        }
        return d;
    }

    private Object parseKeyword(String keyword, Object value) {
        if (!source.regionMatches(pos, keyword, 0, keyword.length())) {
            throw expectedError(pos, "json literal", "ident");
        }
        pos += keyword.length();
        return value;
    }

    private int peek() {
        if (pos >= length) {
            return -1;
        }
        return source.charAt(pos);
    }

    private int next() {
        var next = peek();
        pos++;
        return next;
    }

    private void skipWhiteSpace() {
        while (pos < length) {
            switch (peek()) {
                case '\t', '\r', '\n', ' ' -> pos++;
                default -> { return; }
            }
        }
    }

    private static String toString(int c) {
        return c == EOF ? "eof" : String.valueOf((char) c);
    }

    ParserException error(String message, int start, int length) throws ParserException {
        var token = Token.toDesc(STRING, start, length);
        var pos = Token.descPosition(token);
        var src = Source.sourceFor("<json>", source);
        var lineNum = src.getLine(pos);
        var columnNum = src.getColumn(pos);
        var formatted = ErrorManager.format(message, src, lineNum, columnNum, token);
        return new ParserException(JSErrorType.SYNTAX_ERROR, formatted, src, lineNum, columnNum, token);
    }

    private ParserException error(String message, int start) {
        return error(message, start, length);
    }

    private ParserException numberError(int start) {
        return error(Lexer.message("json.invalid.number"), start);
    }

    private ParserException expectedError(int start, String expected, String found) {
        return error(AbstractParser.message("expected", expected, found), start);
    }

    private ParserException syntaxError(int start, String reason) {
        var message = ECMAErrors.getMessage("syntax.error.invalid.json", reason);
        return error(message, start);
    }

}
