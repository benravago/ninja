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

package nashorn.internal.runtime;

import java.util.LinkedList;
import java.util.ArrayDeque;
import java.util.StringTokenizer;

/**
 * A string tokenizer that supports entries with quotes and nested quotes.
 * If the separators are quoted either by ' and ", or whatever quotes the user supplies they will be ignored and considered part of another token
 */
public final class QuotedStringTokenizer {

    private final LinkedList<String> tokens;

    private final char quotes[];

    /**
     * Constructor
     * @param str string to tokenize
     */
    public QuotedStringTokenizer(String str) {
        this(str, " ");
    }

    /**
     * Create a quoted string tokenizer
     * @param str a string to tokenize
     * @param delim delimiters between tokens
     */
    public QuotedStringTokenizer(String str, String delim) {
        this(str, delim, new char[] { '"', '\'' });
    }

    /**
     * Create a quoted string tokenizer
     * @param str string to tokenize
     * @param delim delimiters between tokens
     * @param quotes all the characters that should be accepted as quotes, default is ' or "
     */
    private QuotedStringTokenizer(String str, String delim, char[] quotes) {
        this.quotes = quotes;

        var delimIsWhitespace = true;
        for (var i = 0; i < delim.length(); i++) {
            if (!Character.isWhitespace(delim.charAt(i))) {
                delimIsWhitespace = false;
                break;
            }
        }

        var st = new StringTokenizer(str, delim);
        tokens = new LinkedList<>();
        while (st.hasMoreTokens()) {
            var token = st.nextToken();

            while (unmatchedQuotesIn(token)) {
                if (!st.hasMoreTokens()) {
                    throw new IndexOutOfBoundsException(token);
                }
                token += (delimIsWhitespace ? " " : delim) + st.nextToken();
            }
            tokens.add(stripQuotes(token));
        }
    }

    /**
     * @return the number of tokens in the tokenizer
     */
    public int countTokens() {
        return tokens.size();
    }

    /**
     * @return true if there are tokens left
     */
    public boolean hasMoreTokens() {
        return countTokens() > 0;
    }

    /**
     * @return the next token in the tokenizer
     */
    public String nextToken() {
        return tokens.removeFirst();
    }

    private String stripQuotes(String value0) {
        var value = value0.trim();
        for (var q : quotes) {
            if (value.length() >= 2 && value.startsWith("" + q) && value.endsWith("" + q)) {
                // also go over the value and remove \q sequences. they are just plain q now
                value = value.substring(1, value.length() - 1);
                value = value.replace("\\" + q, "" + q);
            }
        }
        return value;
    }

    private boolean unmatchedQuotesIn(String str) {
        var quoteStack = new ArrayDeque<Character>();
        for (var i = 0; i < str.length(); i++) {
            var c = str.charAt(i);
            for (var q : this.quotes) {
                if (c == q) {
                    if (quoteStack.isEmpty()) {
                        quoteStack.push(c);
                    } else {
                        var top = quoteStack.pop();
                        if (top != c) {
                            quoteStack.push(top);
                            quoteStack.push(c);
                        }
                    }
                }
            }
        }

        return !quoteStack.isEmpty();
    }

}
