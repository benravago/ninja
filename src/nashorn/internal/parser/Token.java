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

import static nashorn.internal.parser.TokenKind.LITERAL;

import nashorn.internal.runtime.Source;

/**
 * A token is a 64 bit long value that represents a basic parse/lex unit.
 * This class provides static methods to manipulate lexer tokens.
 */
public class Token {
    private Token() {}

    /**
     * We use 28 bits for the position and 28 bits for the length of the token.
     * This limits the maximal length of code we can handle to 2 ^ 28 - 1 bytes.
     */
    public final static int LENGTH_MASK = 0xfffffff;

    // The first 8 bits are used for the token type, followed by length and position
    private final static int LENGTH_SHIFT = 8;
    private final static int POSITION_SHIFT  = 36;

    /**
     * Create a compact form of token information.
     * @param type     Type of token.
     * @param position Start position of the token in the source.
     * @param length   Length of the token.
     * @return Token descriptor.
     */
    public static long toDesc(TokenType type, int position, int length) {
        assert position <= LENGTH_MASK && length <= LENGTH_MASK;
        return (long)position << POSITION_SHIFT | (long)length << LENGTH_SHIFT | type.ordinal();
    }

    /**
     * Extract token position from a token descriptor.
     * @param token Token descriptor.
     * @return Start position of the token in the source.
     */
    public static int descPosition(long token) {
        return (int)(token >>> POSITION_SHIFT);
    }

    /**
     * Normally returns the token itself, except in case of string tokens which report their position past their opening delimiter and thus need to have position and length adjusted.
     */
    public static long withDelimiter(long token) {
        var tokenType = Token.descType(token);
        switch (tokenType) {
            case STRING, ESCSTRING, EXECSTRING, TEMPLATE, TEMPLATE_TAIL -> {
                var start = Token.descPosition(token) - 1;
                var len = Token.descLength(token) + 2;
                return toDesc(tokenType, start, len);
            }
            case TEMPLATE_HEAD, TEMPLATE_MIDDLE -> {
                var start = Token.descPosition(token) - 1;
                var len = Token.descLength(token) + 3;
                return toDesc(tokenType, start, len);
            }
            default ->  {
                return token;
            }
        }
    }

    /**
     * Extract token length from a token descriptor.
     */
    public static int descLength(long token) {
        return (int)((token >>> LENGTH_SHIFT) & LENGTH_MASK);
    }

    /**
     * Extract token type from a token descriptor.
     */
    public static TokenType descType(long token) {
        return TokenType.getValues()[(int)token & 0xff];
    }

    /**
     * Change the token to use a new type.
     */
    public static long recast(long token, TokenType newType) {
        return token & ~0xFFL | newType.ordinal();
    }

    /**
     * Return a string representation of a token.
     * @param source  Token source.
     * @param token   Token descriptor.
     * @param verbose True to include details.
     * @return String representation.
     */
    public static String toString(Source source, long token, boolean verbose) {
        var type = Token.descType(token);
        String result;

        if (source != null && type.getKind() == LITERAL) {
            result = source.getString(token);
        } else {
            result = type.getNameOrType();
        }

        if (verbose) {
            var position = Token.descPosition(token);
            var length = Token.descLength(token);
            result += " (" + position + ", " + length + ")";
        }

        return result;
    }

    /**
     * String conversion of token
     */
    public static String toString(Source source, long token) {
        return Token.toString(source, token, false);
    }

    /**
     * String conversion of token - version without source given
     */
    public static String toString(long token) {
        return Token.toString(null, token, false);
    }

    /**
     * Static hash code computation function token
     */
    public static int hashCode(long token) {
        return (int)(token ^ token >>> 32);
    }

}
