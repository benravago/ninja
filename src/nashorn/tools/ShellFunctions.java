/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

package nashorn.tools;

import java.io.IOException;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import nashorn.internal.runtime.JSType;
import nashorn.internal.runtime.ScriptingFunctions;
import nashorn.internal.objects.Global;
import static nashorn.internal.lookup.Lookup.MH;
import static nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

/**
 * Global functions supported only in shell interactive mode.
 */
public final class ShellFunctions {
    private ShellFunctions() {}

    /** Handle to implementation of {@link ShellFunctions#input} - Nashorn extension */
    public static final MethodHandle INPUT = findOwnMH("input", Object.class, Object.class, Object.class, Object.class);

    /** Handle to implementation of {@link ShellFunctions#evalinput} - Nashorn extension */
    public static final MethodHandle EVALINPUT = findOwnMH("evalinput",     Object.class, Object.class, Object.class, Object.class);

    /**
     * Nashorn extension: Read one or more lines of input from the standard input till the given end marker is seen in standard input.
     * Note: global.input (shell-interactive-mode-only).
     * 'endMarker' is the String used as end marker for input.
     * 'prompt' is the String used as input prompt.
     * Returns the line that was read.
     */
    public static Object input(Object self, Object endMarker, Object prompt) throws IOException {
        var endMarkerStr = (endMarker != UNDEFINED)? JSType.toString(endMarker) : "";
        var promptStr = (prompt != UNDEFINED)? JSType.toString(prompt)  : ">> ";
        var buf = new StringBuilder();
        for (;;) {
            var line = ScriptingFunctions.readLine(promptStr);
            if (line == null || line.equals(endMarkerStr)) {
                break;
            }
            buf.append(line);
            buf.append('\n');
        }
        return buf.toString();
    }

    /**
     * Nashorn extension: Reads zero or more lines from standard input and evaluates the concatenated string as code
     */
    public static Object evalinput(Object self, Object endMarker, Object prompt) throws IOException {
        return Global.eval(self, input(self, endMarker, prompt));
    }

    private static MethodHandle findOwnMH(String name, Class<?> rtype, Class<?>... types) {
        return MH.findStatic(MethodHandles.lookup(), ShellFunctions.class, name, MH.type(rtype, types));
    }

}
