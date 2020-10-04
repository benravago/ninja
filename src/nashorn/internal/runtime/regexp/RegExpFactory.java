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

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import nashorn.internal.runtime.ParserException;

/**
 * Factory class for regular expressions.
 *
 * This class creates instances of {@link JdkRegExp}.
 * An alternative factory can be installed using the {@code nashorn.regexp.impl} system property.
 */
public class RegExpFactory {

    private final static RegExpFactory instance = new RegExpFactory();

    /**
     * Weak cache of already validated regexps - when reparsing, we don't, for example need to recompile (reverify) all regexps that have previously been parsed by this RegExpFactory in a previous compilation.
     * This saves significant time in e.g. avatar startup
     */
    private static final Map<String, RegExp> REGEXP_CACHE = Collections.synchronizedMap(new WeakHashMap<String, RegExp>());

    /**
     * Creates a Regular expression from the given {@code pattern} and {@code flags} strings.
     */
    public RegExp compile(String pattern, String flags) throws ParserException {
        return new JdkRegExp(pattern, flags);
    }

    /**
     * Compile a regexp with the given {@code source} and {@code flags}.
     */
    public static RegExp create(String pattern, String flags) {
        var key = pattern + "/" + flags;
        var regexp = REGEXP_CACHE.get(key);
        if (regexp == null) {
            regexp = instance.compile(pattern,  flags);
            REGEXP_CACHE.put(key, regexp);
        }
        return regexp;
    }

    /**
     * Validate a regexp with the given {@code source} and {@code flags}.
     */
    public static void validate(String pattern, String flags) throws ParserException {
        create(pattern, flags);
    }

}
