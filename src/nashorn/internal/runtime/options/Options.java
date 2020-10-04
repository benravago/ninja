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

package nashorn.internal.runtime.options;

import java.io.PrintWriter;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Permissions;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;

import java.text.MessageFormat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.PropertyPermission;
import java.util.ResourceBundle;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

import nashorn.internal.runtime.QuotedStringTokenizer;

/**
 * Manages global runtime options.
 */
public final class Options {

    // permission to just read nashorn.* System properties
    private static AccessControlContext createPropertyReadAccCtxt() {
        var perms = new Permissions();
        perms.add(new PropertyPermission("nashorn.*", "read"));
        return new AccessControlContext(new ProtectionDomain[] { new ProtectionDomain(null, perms) });
    }

    private static final AccessControlContext READ_PROPERTY_ACC_CTXT = createPropertyReadAccCtxt();

    /** Resource tag. */
    private final String resource;

    /** Error writer. */
    private final PrintWriter err;

    /** File list. */
    private final List<String> files;

    /** Arguments list */
    private final List<String> arguments;

    /** The options map of enabled options */
    private final TreeMap<String, Option<?>> options;

    /** System property that can be used to prepend options to the explicitly specified command line. */
    private static final String NASHORN_ARGS_PREPEND_PROPERTY = "nashorn.args.prepend";

    /** System property that can be used to append options to the explicitly specified command line. */
    private static final String NASHORN_ARGS_PROPERTY = "nashorn.args";

    /**
     * Constructor.
     * Options will use System.err as the output stream for any errors
     */
    public Options(String resource) {
        this(resource, new PrintWriter(System.err, true));
    }

    /**
     * Constructor.
     * 'resource' is the resource prefix for options e.g. "nashorn".
     * 'err' is the print writer for reporting parse errors.
     */
    public Options(String resource, PrintWriter err) {
        this.resource  = resource;
        this.err       = err;
        this.files     = new ArrayList<>();
        this.arguments = new ArrayList<>();
        this.options   = new TreeMap<>();

        // set all default values
        for (var t : Options.validOptions) {
            if (t.getDefaultValue() != null) {
                // populate from system properties
                var v = getStringProperty(t.getKey(), null);
                if (v != null) {
                    set(t.getKey(), createOption(t, v));
                } else if (t.getDefaultValue() != null) {
                    set(t.getKey(), createOption(t, t.getDefaultValue()));
                 }
            }
        }
    }

    /**
     * Get the resource for this Options set, e.g. "nashorn"
     */
    public String getResource() {
        return resource;
    }

    @Override
    public String toString() {
        return options.toString();
    }

    private static void checkPropertyName(String name) {
        if (! Objects.requireNonNull(name).startsWith("nashorn.")) {
            throw new IllegalArgumentException(name);
        }
    }

    /**
     * Convenience function for getting system properties in a safe way.
     * 'name' is the of the property.
     * 'defValue' is the default value of property.
     * Returns true if set to true, default value if unset or set to false.
     */
    public static boolean getBooleanProperty(String name, Boolean defValue) {
        checkPropertyName(name);
        return AccessController.doPrivileged(
            new PrivilegedAction<Boolean>() {
                @Override
                public Boolean run() {
                    try {
                        var property = System.getProperty(name);
                        if (property == null && defValue != null) {
                            return defValue;
                        }
                        return property != null && !"false".equalsIgnoreCase(property);
                    } catch (SecurityException e) {
                        // if no permission to read, assume false
                        return false;
                    }
                }
            }, READ_PROPERTY_ACC_CTXT
        );
    }

    /**
     * Convenience function for getting system properties in a safe way
     */
    public static boolean getBooleanProperty(String name) {
        return getBooleanProperty(name, null);
    }

    /**
     * Convenience function for getting system properties in a safe way
     */
    public static String getStringProperty(String name, String defValue) {
        checkPropertyName(name);
        return AccessController.doPrivileged(
            new PrivilegedAction<String>() {
                @Override
                public String run() {
                    try {
                        return System.getProperty(name, defValue);
                    } catch (SecurityException e) {
                        // if no permission to read, assume the default value
                        return defValue;
                    }
                }
            }, READ_PROPERTY_ACC_CTXT
        );
    }

    /**
     * Convenience function for getting system properties in a safe way
     */
    public static int getIntProperty(String name, int defValue) {
        checkPropertyName(name);
        return AccessController.doPrivileged(
            new PrivilegedAction<Integer>() {
                @Override
                public Integer run() {
                    try {
                        return Integer.getInteger(name, defValue);
                    } catch (SecurityException e) {
                        // if no permission to read, assume the default value
                        return defValue;
                    }
                }
            }, READ_PROPERTY_ACC_CTXT
        );
    }

    /**
     * Return an option given its resource key.
     * If the key doesn't begin with {@literal <resource>}.option it will be completed using the resource from this instance.
     */
    public Option<?> get(String key) {
        return options.get(key(key));
    }

    /**
     * Return an option as a boolean
     */
    public boolean getBoolean(String key) {
        var option = get(key);
        return option != null ? (Boolean)option.getValue() : false;
    }

    /**
     * Return an option as a integer
     */
    public int getInteger(String key) {
        var option = get(key);
        return option != null ? (Integer)option.getValue() : 0;
    }

    /**
     * Return an option as a String
     */
    public String getString(String key) {
        var option = get(key);
        if (option != null) {
            var value = (String)option.getValue();
            if (value != null) {
                return value.intern();
            }
        }
        return null;
    }

    /**
     * Set an option, overwriting an existing state if one exists
     */
    public void set(String key, Option<?> option) {
        options.put(key(key), option);
    }

    /**
     * Set an option as a boolean value, overwriting an existing state if one exists
     */
    public void set(String key, boolean option) {
        set(key, new Option<>(option));
    }

    /**
     * Set an option as a String value, overwriting an existing state if one exists
     */
    public void set(String key, String option) {
        set(key, new Option<>(option));
    }

    /**
     * Return the user arguments to the program, i.e. those trailing "--" after the filename
     */
    public List<String> getArguments() {
        return Collections.unmodifiableList(this.arguments);
    }

    /**
     * Return the JavaScript files passed to the program
     */
    public List<String> getFiles() {
        return Collections.unmodifiableList(files);
    }

    /**
     * Return the option templates for all the valid option supported.
     */
    public static Collection<OptionTemplate> getValidOptions() {
        return Collections.unmodifiableCollection(validOptions);
    }

    /**
     * Make sure a key is fully qualified for table lookups
     */
    private String key(String shortKey) {
        var key = shortKey;
        while (key.startsWith("-")) {
            key = key.substring(1, key.length());
        }
        key = key.replace("-", ".");
        var keyPrefix = this.resource + ".option.";
        if (key.startsWith(keyPrefix)) {
            return key;
        }
        return keyPrefix + key;
    }

    static String getMsg(String msgId, String... args) {
        try {
            var msg = Options.bundle.getString(msgId);
            if (args.length == 0) {
                return msg;
            }
            return new MessageFormat(msg).format(args);
        } catch (MissingResourceException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Display context sensitive help
     */
    public void displayHelp(IllegalArgumentException e) {
        if (e instanceof IllegalOptionException) {
            var template = ((IllegalOptionException)e).getTemplate();
            if (template.isXHelp()) {
                // display extended help information
                displayHelp(true);
            } else {
                err.println(((IllegalOptionException)e).getTemplate());
            }
            return;
        }

        if (e != null && e.getMessage() != null) {
            err.println(getMsg("option.error.invalid.option", e.getMessage(), helpOptionTemplate.getShortName(), helpOptionTemplate.getName()));
            err.println();
            return;
        }

        displayHelp(false);
    }

    /**
     * Display full help.
     * If 'extended', show the extended help for all options, including undocumented ones
     */
    public void displayHelp(boolean extended) {
        for (var t : Options.validOptions) {
            if ((extended || !t.isUndocumented()) && t.getResource().equals(resource)) {
                err.println(t);
                err.println();
            }
        }
    }

    /**
     * Processes the arguments and stores their information.
     * Throws IllegalArgumentException on error.
     * The message can be analyzed by the displayHelp function to become more context sensitive
     */
    public void process(String[] args) {
        var argList = new LinkedList<String>();
        addSystemProperties(NASHORN_ARGS_PREPEND_PROPERTY, argList);
        processArgList(argList);
        assert argList.isEmpty();
        Collections.addAll(argList, args);
        processArgList(argList);
        assert argList.isEmpty();
        addSystemProperties(NASHORN_ARGS_PROPERTY, argList);
        processArgList(argList);
        assert argList.isEmpty();
    }

    private void processArgList(LinkedList<String> argList) {
        while (!argList.isEmpty()) {
            var arg = argList.remove(0);
            Objects.requireNonNull(arg);

            // skip empty args
            if (arg.isEmpty()) {
                continue;
            }

            // user arguments to the script
            if ("--".equals(arg)) {
                arguments.addAll(argList);
                argList.clear();
                continue;
            }

            // If it doesn't start with -, it's a file. But, if it is just "-", then it is a file representing standard input.
            if (!arg.startsWith("-") || arg.length() == 1) {
                files.add(arg);
                continue;
            }

            if (arg.startsWith(definePropPrefix)) {
                var value = arg.substring(definePropPrefix.length());
                var eq = value.indexOf('=');
                if (eq != -1) {
                    // -Dfoo=bar Set System property "foo" with value "bar"
                    System.setProperty(value.substring(0, eq), value.substring(eq + 1));
                } else {
                    // -Dfoo is fine. Set System property "foo" with "" as it's value
                    if (!value.isEmpty()) {
                        System.setProperty(value, "");
                    } else {
                        // do not allow empty property name
                        throw new IllegalOptionException(definePropTemplate);
                    }
                }
                continue;
            }

            // it is an argument,  it and assign key, value and template
            var parg = new ParsedArg(arg);

            // check if the value of this option is passed as next argument
            if (parg.template.isValueNextArg()) {
                if (argList.isEmpty()) {
                    throw new IllegalOptionException(parg.template);
                }
                parg.value = argList.remove(0);
            }

            // -h [args...]
            if (parg.template.isHelp()) {
                // check if someone wants help on an explicit arg
                if (!argList.isEmpty()) {
                    try {
                        var t = new ParsedArg(argList.get(0)).template;
                        throw new IllegalOptionException(t);
                    } catch (IllegalArgumentException e) {
                        throw e;
                    }
                }
                throw new IllegalArgumentException(); // show help for
                // everything
            }

            if (parg.template.isXHelp()) {
                throw new IllegalOptionException(parg.template);
            }

            if (parg.template.isRepeated()) {
                assert parg.template.getType().equals("string");

                var key = key(parg.template.getKey());
                var value = options.containsKey(key) ? (options.get(key).getValue() + "," + parg.value) : Objects.toString(parg.value);
                options.put(key, new Option<>(value));
            } else {
                set(parg.template.getKey(), createOption(parg.template, parg.value));
            }

            // Arg may have a dependency to set other args, e.g. scripting->anon.functions
            if (parg.template.getDependency() != null) {
                argList.addFirst(parg.template.getDependency());
            }
        }
    }

    private static void addSystemProperties(String sysPropName, List<String> argList) {
        var sysArgs = getStringProperty(sysPropName, null);
        if (sysArgs != null) {
            var st = new StringTokenizer(sysArgs);
            while (st.hasMoreTokens()) {
                argList.add(st.nextToken());
            }
        }
    }

    /**
     * Retrieves an option template identified by key.
     * 'shortKey' is the key without the e.g. "nashorn.option." part.
     */
    public OptionTemplate getOptionTemplateByKey(String shortKey) {
        var fullKey = key(shortKey);
        for (var t: validOptions) {
            if (t.getKey().equals(fullKey)) {
                return t;
            }
        }
        throw new IllegalArgumentException(shortKey);
    }

    private static OptionTemplate getOptionTemplateByName(String name) {
        for (OptionTemplate t : Options.validOptions) {
            if (t.nameMatches(name)) {
                return t;
            }
        }
        return null;
    }

    private static Option<?> createOption(OptionTemplate t, String value) {
        switch (t.getType()) {
            // default -> {}

            case "string" -> {
                // default value null
                return new Option<>(value);
            }
            case "timezone" -> {
                // default value "TimeZone.getDefault()"
                return new Option<>(TimeZone.getTimeZone(value));
            }
            case "locale" -> {
                return new Option<>(Locale.forLanguageTag(value));
            }
            case "keyvalues" -> {
                return new KeyValueOption(value);
            }
            case "log" -> {
                return new LoggingOption(value);
            }
            case "boolean" -> {
                return new Option<>(value != null && Boolean.parseBoolean(value));
            }
            case "integer" -> {
                try {
                    return new Option<>(value == null ? 0 : Integer.parseInt(value));
                } catch (NumberFormatException nfe) {
                    throw new IllegalOptionException(t);
                }
            }
            case "properties" -> {
                //swallow the properties and set them
                initProps(new KeyValueOption(value));
                return null;
            }
        }
        throw new IllegalArgumentException(value);
    }

    private static void initProps(KeyValueOption kv) {
        for (var entry : kv.getValues().entrySet()) {
            System.setProperty(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Resource name for properties file
     */
    private static final String MESSAGES_RESOURCE = "nashorn.internal.runtime.resources.Options";

    /**
     * Resource bundle for properties file
     */
    private static ResourceBundle bundle;

    /**
     * Usages per resource from properties file
     */
    private static HashMap<Object, Object> usage;

    /**
     * Valid options from templates in properties files
     */
    private static Collection<OptionTemplate> validOptions;

    /**
     * Help option
     */
    private static OptionTemplate helpOptionTemplate;

    /**
     * Define property option template.
     */
    private static OptionTemplate definePropTemplate;

    /**
     * Prefix of "define property" option.
     */
    private static String definePropPrefix;

    static {
        Options.bundle = ResourceBundle.getBundle(Options.MESSAGES_RESOURCE, Locale.getDefault());
        Options.validOptions = new TreeSet<>();
        Options.usage = new HashMap<>();

        for (var keys = Options.bundle.getKeys(); keys.hasMoreElements(); ) {
            var key = keys.nextElement();
            var st = new StringTokenizer(key, ".");
            String resource = null;
            String type = null;

            if (st.countTokens() > 0) {
                resource = st.nextToken(); // e.g. "nashorn"
            }

            if (st.countTokens() > 0) {
                type = st.nextToken(); // e.g. "option"
            }

            if ("option".equals(type)) {
                String helpKey = null;
                String xhelpKey = null;
                String definePropKey = null;
                try {
                    helpKey = Options.bundle.getString(resource + ".options.help.key");
                    xhelpKey = Options.bundle.getString(resource + ".options.xhelp.key");
                    definePropKey = Options.bundle.getString(resource + ".options.D.key");
                } catch (MissingResourceException e) {
                    // ignored: no help
                }
                var isHelp = key.equals(helpKey);
                var isXHelp = key.equals(xhelpKey);
                var t = new OptionTemplate(resource, key, Options.bundle.getString(key), isHelp, isXHelp);

                Options.validOptions.add(t);
                if (isHelp) {
                    helpOptionTemplate = t;
                }

                if (key.equals(definePropKey)) {
                    definePropPrefix = t.getName();
                    definePropTemplate = t;
                }
            } else if (resource != null && "options".equals(type)) {
                Options.usage.put(resource, Options.bundle.getObject(key));
            }
        }
    }

    private static class IllegalOptionException extends IllegalArgumentException {
        private final OptionTemplate template;

        IllegalOptionException(OptionTemplate t) {
            super();
            this.template = t;
        }

        OptionTemplate getTemplate() {
            return this.template;
        }
    }

    /**
     * This is a resolved argument of the form key=value
     */
    private static class ParsedArg {

        /** The resolved option template this argument corresponds to */
        OptionTemplate template;

        /** The value of the argument */
        String value;

        ParsedArg(String argument) {
            var st = new QuotedStringTokenizer(argument, "=");
            if (!st.hasMoreTokens()) {
                throw new IllegalArgumentException();
            }

            var token = st.nextToken();
            this.template = getOptionTemplateByName(token);
            if (this.template == null) {
                throw new IllegalArgumentException(argument);
            }

            value = "";
            if (st.hasMoreTokens()) {
                while (st.hasMoreTokens()) {
                    value += st.nextToken();
                    if (st.hasMoreTokens()) {
                        value += ':';
                    }
                }
            } else if ("boolean".equals(this.template.getType())) {
                value = "true";
            }
        }
    }

}
