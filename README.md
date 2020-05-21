This is a fork of the OpenJDK-14 Nashorn code from the jdk.scripting.nashorn module source.
This module is being removed in JDK15 and it seems like a waste to just abandon the code.
However, I don't plan to continue this as a true javascript implementation as [graaljs](https://github.com/graalvm/graaljs) is probably the better path for this going forward. Instead, I'm hoping to be use the Nashorn code as a base for a smaller javascript-like scripting language. 

At this time, the changes are:

1. change package `jdk.nashorn.*` to `nashorn.*`
2. change package `jdk.dynalink.*` to `dynalink.*`
3. change package `jdk.internal.org.objectweb.*` to `org.objectweb.*`
4. remove @[D|d]eprecation tags
5. remove @Deprecated methods and deprecation warnings
6. remove javafx and java.sql builtin support
7. remove joni regular expression support
8. replace Unsafe.defineAnonymousClass() with Lookup.defineHiddenClass()
9. remove debugging code in nashorn/internal/ir/debug, DumpBytecode.java, etc.

The main objective of these changes is to reduce and simplify the code base.  Eventually, I hope to work out some kind of plugin system to support extensions (like javafx).  I would also like to look into the possibility of using [lsp4j](https://github.com/eclipse/lsp4j) for the debugging. 
