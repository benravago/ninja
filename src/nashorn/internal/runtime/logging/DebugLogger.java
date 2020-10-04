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

package nashorn.internal.runtime.logging;

import java.io.PrintWriter;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.LoggingPermission;

import nashorn.internal.runtime.Context;
import nashorn.internal.runtime.ScriptFunction;
import nashorn.internal.runtime.ScriptObject;
import nashorn.internal.runtime.ScriptRuntime;
import nashorn.internal.runtime.events.RuntimeEvent;

/**
 * Wrapper class for Logging system.
 *
 * This is how you are supposed to register a logger and use it
 */
public final class DebugLogger {

    /** Disabled logger used for all loggers that need an instance, but shouldn't output anything */
    public static final DebugLogger DISABLED_LOGGER = new DebugLogger("disabled", Level.OFF, false);

    private final Logger  logger;
    private final boolean isEnabled;

    private int indent;

    private static final int INDENT_SPACE = 4;

    /** A quiet logger only logs {@link RuntimeEvent}s and does't output any text, regardless of level */
    private final boolean isQuiet;

    /**
     * Constructor.
     * A logger can be paired with a property, e.g. {@code --log:codegen:info} is equivalent to {@code -Dnashorn.codegen.log}
     * 'loggerName' is the name of logger - this is the unique key with which it can be identified.
     * 'loggerLevel' is the initial level of the logger.
     * 'isQuiet' - is this a quiet logger, i.e. enabled for things like e.g. RuntimeEvent:s, but quiet otherwise.
     */
    public DebugLogger(String loggerName, Level loggerLevel, boolean isQuiet) {
        this.logger  = instantiateLogger(loggerName, loggerLevel);
        this.isQuiet = isQuiet;
        assert logger != null;
        this.isEnabled = getLevel() != Level.OFF;
    }

    private static Logger instantiateLogger(String name, Level level) {
        var logger = java.util.logging.Logger.getLogger(name);
        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            @Override
            public Void run() {
                for (var h : logger.getHandlers()) {
                    logger.removeHandler(h);
                }

                logger.setLevel(level);
                logger.setUseParentHandlers(false);
                var c = new ConsoleHandler();

                c.setFormatter(new Formatter() {
                    @Override
                    public String format(LogRecord record) {
                        var sb = new StringBuilder();

                        sb.append('[')
                          .append(record.getLoggerName())
                          .append("] ")
                          .append(record.getMessage())
                          .append('\n');

                        return sb.toString();
                    }
                });
                logger.addHandler(c);
                c.setLevel(level);
                return null;
            }
        }, createLoggerControlAccCtxt());

        return logger;
    }

    /**
     * Do not currently support chaining this with parent logger.
     * Logger level null means disabled
     */
    public Level getLevel() {
        return logger.getLevel() == null ? Level.OFF : logger.getLevel();
    }

    /**
     * Get the output writer for the logger.
     * Loggers always default to stderr for output as they are used mainly to output debug info
     * Can be inherited so this should not be static.
     */
    @SuppressWarnings("static-method")
    public PrintWriter getOutputStream() {
        return Context.getCurrentErr();
    }

    /**
     * Add quotes around a string.
     */
    public static String quote(String str) {
        if (str.isEmpty()) {
            return "''";
        }

        var startQuote = '\0';
        var endQuote   = '\0';
        var quote      = '\0';

        if (str.startsWith("\\") || str.startsWith("\"")) {
            startQuote = str.charAt(0);
        }
        if (str.endsWith("\\") || str.endsWith("\"")) {
            endQuote = str.charAt(str.length() - 1);
        }

        if (startQuote == '\0' || endQuote == '\0') {
            quote = startQuote == '\0' ? endQuote : startQuote;
        }
        if (quote == '\0') {
            quote = '\'';
        }

        return (startQuote == '\0' ? quote : startQuote) + str + (endQuote == '\0' ? quote : endQuote);
    }

    /**
     * Check if the logger is enabled.
     */
    public boolean isEnabled() {
        return isEnabled;
    }

    /**
     * Check if the logger is enabled.
     * Returns true if enabled; null logger will return false
     */
    public static boolean isEnabled(DebugLogger logger) {
        return logger != null && logger.isEnabled();
    }

    /**
     * If you want to change the indent level of your logger, call indent with a new position.
     * Positions start at 0 and are increased by one for a new "tab"
     */
    public void indent(int pos) {
        if (isEnabled) {
            indent += pos * INDENT_SPACE;
        }
    }

    /**
     * Add an indent position.
     */
    public void indent() {
        indent += INDENT_SPACE;
    }

    /**
     * Unindent a position.
     */
    public void unindent() {
        indent -= INDENT_SPACE;
        if (indent < 0) {
            indent = 0;
        }
    }

    private static void logEvent(RuntimeEvent<?> event) {
        if (event != null) {
            var global = Context.getGlobal();
            if (global.has("Debug")) {
                var debug = (ScriptObject)global.get("Debug");
                var addRuntimeEvent = (ScriptFunction)debug.get("addRuntimeEvent");
                ScriptRuntime.apply(addRuntimeEvent, debug, event);
            }
        }
    }

    /**
     * @see java.util.logging.Level
     */
    public boolean isLoggable(Level level) {
        return logger.isLoggable(level);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINEST} on this logger
     */
    public void finest(String str) {
        log(Level.FINEST, str);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINEST} on this logger
     */
    public void finest(RuntimeEvent<?> event, String str) {
        finest(str);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINEST} on this logger
     */
    public void finest(Object... objs) {
        log(Level.FINEST, objs);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINEST} on this logger.
     * 'event' is an optional runtime event to log.
     * 'objs' is an object array to log - use this to perform lazy concatenation to avoid unconditional toString overhead
     */
    public void finest(RuntimeEvent<?> event, Object... objs) {
        finest(objs);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINER} on this logger
     */
    public void finer(String str) {
        log(Level.FINER, str);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINER} on this logger
     */
    public void finer(RuntimeEvent<?> event, String str) {
        finer(str);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINER} on this logger
     */
    public void finer(Object... objs) {
        log(Level.FINER, objs);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINER} on this logger
     */
    public void finer(RuntimeEvent<?> event, Object... objs) {
        finer(objs);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINE} on this logger
     */
    public void fine(String str) {
        log(Level.FINE, str);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINE} on this logger
     */
    public void fine(RuntimeEvent<?> event, String str) {
        fine(str);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINE} on this logger
     */
    public void fine(Object... objs) {
        log(Level.FINE, objs);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINE} on this logger
     */
    public void fine(RuntimeEvent<?> event, Object... objs) {
        fine(objs);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#CONFIG} on this logger
     */
    public void config(String str) {
        log(Level.CONFIG, str);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#CONFIG} on this logger
     */
    public void config(RuntimeEvent<?> event, String str) {
        config(str);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#CONFIG} on this logger
     */
    public void config(Object... objs) {
        log(Level.CONFIG, objs);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#CONFIG} on this logger
     */
    public void config(RuntimeEvent<?> event, Object... objs) {
        config(objs);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#INFO} on this logger
     */
    public void info(String str) {
        log(Level.INFO, str);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#INFO} on this logger
     */
    public void info(RuntimeEvent<?> event, String str) {
        info(str);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINE} on this logger
     */
    public void info(Object... objs) {
        log(Level.INFO, objs);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINE} on this logger
     */
    public void info(RuntimeEvent<?> event, Object... objs) {
        info(objs);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#WARNING} on this logger
     */
    public void warning(String str) {
        log(Level.WARNING, str);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#WARNING} on this logger
     */
    public void warning(RuntimeEvent<?> event, String str) {
        warning(str);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINE} on this logger
     */
    public void warning(Object... objs) {
        log(Level.WARNING, objs);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINE} on this logger
     */
    public void warning(RuntimeEvent<?> event, Object... objs) {
        warning(objs);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#SEVERE} on this logger
     */
    public void severe(String str) {
        log(Level.SEVERE, str);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#SEVERE} on this logger
     */
    public void severe(RuntimeEvent<?> event, String str) {
        severe(str);
        logEvent(event);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#SEVERE} on this logger
     */
    public void severe(Object... objs) {
        log(Level.SEVERE, objs);
    }

    /**
     * Shorthand for outputting a log string as log level {@link java.util.logging.Level#FINE} on this logger
     */
    public void severe(RuntimeEvent<?> event, Object... objs) {
        severe(objs);
        logEvent(event);
    }

    /**
     * Output log line on this logger at a given level of verbosity
     * @see java.util.logging.Level
     */
    public void log(Level level, String str) {
        if (isEnabled && !isQuiet && logger.isLoggable(level)) {
            var sb = new StringBuilder();
            for (var i = 0 ; i < indent ; i++) {
                sb.append(' ');
            }
            sb.append(str);
            logger.log(level, sb.toString());
        }
    }

    /**
     * Output log line on this logger at a given level of verbosity
     * @see java.util.logging.Level
     */
    public void log(Level level, Object... objs) {
        if (isEnabled && !isQuiet && logger.isLoggable(level)) {
            var sb = new StringBuilder();
            for (var obj : objs) {
                sb.append(obj);
            }
            log(level, sb.toString());
        }
    }

    /**
     * Access control context for logger level and instantiation permissions
     */
    private static AccessControlContext createLoggerControlAccCtxt() {
        var perms = new Permissions();
        perms.add(new LoggingPermission("control", null));
        return new AccessControlContext(new ProtectionDomain[] { new ProtectionDomain(null, perms) });
    }

}
