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
package nashorn.internal.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

import nashorn.internal.Util;
import nashorn.internal.codegen.CompileUnit;
import nashorn.internal.runtime.logging.DebugLogger;
import nashorn.internal.runtime.logging.Loggable;
import nashorn.internal.runtime.logging.Logger;

/**
 * Simple wallclock timing framework
 */
@Logger(name="time")
public final class Timing implements Loggable {

    private DebugLogger log;
    private TimeSupplier timeSupplier;
    private final boolean isEnabled;
    private final long startTime;

    private static final String LOGGER_NAME = Timing.class.getAnnotation(Logger.class).name();

    /**
     * Instantiate singleton timer for ScriptEnvironment
     * @param isEnabled true if enabled, otherwise we keep the instance around for code brevity and "isEnabled" checks, but never instantiate anything inside it
     */
    public Timing(boolean isEnabled) {
        this.isEnabled = isEnabled;
        this.startTime = System.nanoTime();
    }

    /**
     * Get the log info accumulated by this Timing instance
     */
    public String getLogInfo() {
        assert isEnabled();
        return timeSupplier.get();
    }

    /**
     * Get the log info accumulated by this Timing instance
     * @return log info as and array of strings, one per line
     */
    public String[] getLogInfoLines() {
        assert isEnabled();
        return timeSupplier.getStrings();
    }

    /**
     * Check if timing is enabled
     */
    boolean isEnabled() {
        return isEnabled;
    }

    /**
     * When timing, this can be called to register a new module for timing or add to its accumulated time
     * @param module   module name
     * @param durationNano duration to add to accumulated time for module, in nanoseconds.
     */
    public void accumulateTime(String module, long durationNano) {
        if (isEnabled()) {
            ensureInitialized(Context.getContextTrusted());
            timeSupplier.accumulateTime(module, durationNano);
        }
    }

    private DebugLogger ensureInitialized(Context context) {
        // lazy init, as there is not necessarily a context available when a ScriptEnvironment gets initialize
        if (isEnabled() && log == null) {
            log = initLogger(context);
            if (log.isEnabled()) {
                this.timeSupplier = new TimeSupplier();
                Runtime.getRuntime()
                    .addShutdownHook(new Thread() {
                        @Override
                        public void run() {
                            //System.err.println because the context and the output streams may be gone
                            //when the shutdown hook executes
                            var sb = new StringBuilder();
                            for (var str : timeSupplier.getStrings()) {
                                sb.append('[')
                                  .append(Timing.getLoggerName())
                                  .append("] ")
                                  .append(str)
                                  .append('\n');
                            }
                            System.err.print(sb);
                        }
                    });
            }
        }
        return log;
    }

    static String getLoggerName() {
        return LOGGER_NAME;
    }

    @Override
    public DebugLogger initLogger(Context context) {
        return context.getLogger(this.getClass());
    }

    @Override
    public DebugLogger getLogger() {
        return log;
    }

    /**
     * Takes a duration in nanoseconds, and returns a string representation of it rounded to milliseconds.
     * @param durationNano duration in nanoseconds
     * @return the string representing the duration in milliseconds.
     */
    public static String toMillisPrint(long durationNano) {
        return Long.toString(TimeUnit.NANOSECONDS.toMillis(durationNano));
    }

    final class TimeSupplier implements Supplier<String> {

        private final Map<String, LongAdder> timings = new ConcurrentHashMap<>();
        private final LinkedBlockingQueue<String> orderedTimingNames = new LinkedBlockingQueue<>();

        private final Function<String, LongAdder> newTimingCreator = s -> {
            orderedTimingNames.add(s);
            return new LongAdder();
        };

        String[] getStrings() {
            var strs = new ArrayList<String>();
            var br = new BufferedReader(new StringReader(get()));
            String line;
            try {
                while ((line = br.readLine()) != null) {
                    strs.add(line);
                }
            } catch (IOException e) {
                Util.uncheck(e);
            }
            return strs.toArray(new String[0]);
        }

        @Override
        public String get() {
            var t = System.nanoTime();

            var knownTime = 0L;
            var maxKeyLength = 0;
            var maxValueLength = 0;

            for (var entry : timings.entrySet()) {
                maxKeyLength = Math.max(maxKeyLength, entry.getKey().length());
                maxValueLength = Math.max(maxValueLength, toMillisPrint(entry.getValue().longValue()).length());
            }
            maxKeyLength++;

            var sb = new StringBuilder();
            sb.append("Accumulated compilation phase timings:\n\n");
            for (var timingName: orderedTimingNames) {
                int len;

                len = sb.length();
                sb.append(timingName);
                len = sb.length() - len;

                while (len++ < maxKeyLength) {
                    sb.append(' ');
                }

                var duration = timings.get(timingName).longValue();
                var strDuration = toMillisPrint(duration);
                len = strDuration.length();
                for (var i = 0; i < maxValueLength - len; i++) {
                    sb.append(' ');
                }

                sb.append(strDuration)
                  .append(" ms\n");

                knownTime += duration;
            }

            var total = t - startTime;
            return sb
                .append("\nTotal runtime: ")
                .append(toMillisPrint(total))
                .append(" ms (Non-runtime: ")
                .append(toMillisPrint(knownTime))
                .append(" ms [")
                .append((int)(knownTime * 100.0 / total))
                .append("%])")
                .append("\n\nEmitted compile units: ")
                .append(CompileUnit.getEmittedUnitCount())
                .append("\nCompile units installed as named classes: ")
                .append(Context.getNamedInstalledScriptCount())
                .append("\nCompile units installed as anonymous classes: ")
                .append(Context.getAnonymousInstalledScriptCount())
                .toString();
        }

        private void accumulateTime(String module, long duration) {
            timings.computeIfAbsent(module, newTimingCreator).add(duration);
        }
    }

}
