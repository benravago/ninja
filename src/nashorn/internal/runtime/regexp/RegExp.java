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

package nashorn.internal.runtime.regexp;

import java.util.regex.MatchResult;

import nashorn.internal.runtime.BitVector;
import nashorn.internal.runtime.ECMAErrors;
import nashorn.internal.runtime.ParserException;

/**
 * This is the base class for representing a parsed regular expression.
 *
 * Instances of this class are created by a {@link RegExpFactory}.
 */
public abstract class RegExp {

    /** Pattern string. */
    private final String source;

    /** Global search flag for this regexp.*/
    private boolean global;

    /** Case insensitive flag for this regexp */
    private boolean ignoreCase;

    /** Multi-line flag for this regexp */
    private boolean multiline;

    /** BitVector that keeps track of groups in negative lookahead */
    protected BitVector groupsInNegativeLookahead;

    /**
     * Constructor.
     */
    protected RegExp(String source, String flags) {
        this.source = source.length() == 0 ? "(?:)" : source;
        for (var i = 0; i < flags.length(); i++) {
            var ch = flags.charAt(i);
            switch (ch) {
                case 'g' -> {
                    if (this.global) {
                        throwParserException("repeated.flag", "g");
                    }
                    this.global = true;
                }
                case 'i' -> {
                    if (this.ignoreCase) {
                        throwParserException("repeated.flag", "i");
                    }
                    this.ignoreCase = true;
                }
                case 'm' -> {
                    if (this.multiline) {
                        throwParserException("repeated.flag", "m");
                    }
                    this.multiline = true;
                }
                default -> throwParserException("unsupported.flag", Character.toString(ch));
            }
        }
    }

    /**
     * Get the source pattern of this regular expression.
     */
    public String getSource() {
        return source;
    }

    /**
     * Set the global flag of this regular expression to {@code global}.
     */
    public void setGlobal(boolean global) {
        this.global = global;
    }

    /**
     * Get the global flag of this regular expression.
     */
    public boolean isGlobal() {
        return global;
    }

    /**
     * Get the ignore-case flag of this regular expression.
     */
    public boolean isIgnoreCase() {
        return ignoreCase;
    }

    /**
     * Get the multiline flag of this regular expression.
     */
    public boolean isMultiline() {
        return multiline;
    }

    /**
     * Get a bitset indicating which of the groups in this regular expression are inside a negative lookahead.
     */
    public BitVector getGroupsInNegativeLookahead() {
        return groupsInNegativeLookahead;
    }

    /**
     * Match this regular expression against {@code str}, starting at index {@code start} and return a {@link MatchResult} with the result.
     */
    public abstract RegExpMatcher match(String str);

    /**
     * Throw a regexp parser exception.
     */
    protected static void throwParserException(String key, String str) throws ParserException {
        throw new ParserException(ECMAErrors.getMessage("parser.error.regex." + key, str));
    }

}
