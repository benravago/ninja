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

package nashorn.api.scripting;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;

import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.Version;

/**
 * JSR-223 compliant script engine factory for Nashorn.
 *
 * The engine answers for:
 * <ul>
 * <li>names {@code "nashorn"}, {@code "Nashorn"}, {@code "js"}, {@code "JS"}, {@code "JavaScript"}, {@code "javascript"}, {@code "ECMAScript"}, and {@code "ecmascript"};</li>
 * <li>MIME types {@code "application/javascript"}, {@code "application/ecmascript"}, {@code "text/javascript"}, and {@code "text/ecmascript"};</li>
 * <li>as well as for the extension {@code "js"}.</li>
 * </ul>
 * Programs executing in engines created using {@link #getScriptEngine(String[])} will have the passed arguments accessible as a global variable named {@code "arguments"}.
 *
 * @since 1.8u40
 */
public final class NashornScriptEngineFactory implements ScriptEngineFactory {

    @Override
    public String getEngineName() {
        return (String) getParameter(ScriptEngine.ENGINE);
    }

    @Override
    public String getEngineVersion() {
        return (String) getParameter(ScriptEngine.ENGINE_VERSION);
    }

    @Override
    public List<String> getExtensions() {
        return Collections.unmodifiableList(extensions);
    }

    @Override
    public String getLanguageName() {
        return (String) getParameter(ScriptEngine.LANGUAGE);
    }

    @Override
    public String getLanguageVersion() {
        return (String) getParameter(ScriptEngine.LANGUAGE_VERSION);
    }

    @Override
    public String getMethodCallSyntax(String obj, String method, String... args) {
        var sb = new StringBuilder()
            .append(Objects.requireNonNull(obj)).append('.')
            .append(Objects.requireNonNull(method)).append('(');
        var len = args.length;

        if (len > 0) {
            sb.append(Objects.requireNonNull(args[0]));
        }
        for (var i = 1; i < len; i++) {
            sb.append(',').append(Objects.requireNonNull(args[i]));
        }
        sb.append(')');

        return sb.toString();
    }

    @Override
    public List<String> getMimeTypes() {
        return Collections.unmodifiableList(mimeTypes);
    }

    @Override
    public List<String> getNames() {
        return Collections.unmodifiableList(names);
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return "print(" + toDisplay + ")";
    }

    @Override
    public Object getParameter(String key) {
        return switch (key) {
            case ScriptEngine.NAME -> "javascript";
            case ScriptEngine.ENGINE -> "Oracle Nashorn";
            case ScriptEngine.ENGINE_VERSION -> Version.version();
            case ScriptEngine.LANGUAGE -> "ECMAScript";
            case ScriptEngine.LANGUAGE_VERSION -> "ECMA - 262 Edition 5.1";
            case "THREADING" -> null;
            default -> null;
        };
        // The engine implementation is not thread-safe.
        // Can't be used to execute scripts concurrently on multiple threads.
    }

    @Override
    public String getProgram(String... statements) {
        Objects.requireNonNull(statements);
        var sb = new StringBuilder();

        for (var statement : statements) {
            sb.append(Objects.requireNonNull(statement)).append(';');
        }

        return sb.toString();
    }

    // default options passed to Nashorn script engine
    private static final String[] DEFAULT_OPTIONS = new String[] { "-doe" };

    @Override
    public ScriptEngine getScriptEngine() {
        return new NashornScriptEngine(this, DEFAULT_OPTIONS, getAppClassLoader(), null);
    }

    /**
     * Create a new Script engine initialized with the given class loader.
     * 'appLoader' is the class loader to be used as script "app" class loader.
     */
    public ScriptEngine getScriptEngine(ClassLoader appLoader) {
        return newEngine(DEFAULT_OPTIONS, appLoader, null);
    }

    /**
     * Create a new Script engine initialized with the given class filter.
     */
    public ScriptEngine getScriptEngine(ClassFilter classFilter) {
        return newEngine(DEFAULT_OPTIONS, getAppClassLoader(), Objects.requireNonNull(classFilter));
    }

    /**
     * Create a new Script engine initialized with the given arguments.
     */
    public ScriptEngine getScriptEngine(String... args) {
        return newEngine(Objects.requireNonNull(args), getAppClassLoader(), null);
    }

    /**
     * Create a new Script engine initialized with the given arguments and the given class loader.
     */
    public ScriptEngine getScriptEngine(String[] args, ClassLoader appLoader) {
        return newEngine(Objects.requireNonNull(args), appLoader, null);
    }

    /**
     * Create a new Script engine initialized with the given arguments, class loader and class filter.
     * @throws SecurityException if the security manager's {@code checkPermission} denies {@code RuntimePermission("nashorn.setConfig")}
     */
    public ScriptEngine getScriptEngine(String[] args, ClassLoader appLoader, ClassFilter classFilter) {
        return newEngine(Objects.requireNonNull(args), appLoader, Objects.requireNonNull(classFilter));
    }

    private ScriptEngine newEngine(String[] args, ClassLoader appLoader, ClassFilter classFilter) {
        checkConfigPermission();
        try {
            return new NashornScriptEngine(this, args, appLoader, classFilter);
        } catch (RuntimeException e) {
            throw e;
        }
    }

    // -- Internals only below this point

    private static void checkConfigPermission() {
        var sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new RuntimePermission(Context.NASHORN_SET_CONFIG));
        }
    }

    private static final List<String> names = List.of(
        "nashorn", "Nashorn",
        "js", "JS",
        "JavaScript", "javascript",
        "ECMAScript", "ecmascript"
    );

    private static final List<String> mimeTypes = List.of(
        "application/javascript",
        "application/ecmascript",
        "text/javascript",
        "text/ecmascript"
    );

    private static final List<String> extensions = List.of(
        "js"
    );

    private static ClassLoader getAppClassLoader() {
        // Revisit: script engine implementation needs the capability to
        // find the class loader of the context in which the script engine
        // is running so that classes will be found and loaded properly
        var ccl = Thread.currentThread().getContextClassLoader();
        return (ccl == null) ? NashornScriptEngineFactory.class.getClassLoader() : ccl;
    }

}
