package nashorn.internal;

public interface Util {

    /**
     * Return the system identity hashcode for an object as a human readable string
     *
     * @param x object
     * @return system identity hashcode as string
     */
    static String id(final Object x) {
        return String.format("0x%08x", System.identityHashCode(x));
    }

    /**
     * Make an Exception look like a RuntimeException for the compiler
     */
    @SuppressWarnings("unchecked")
	static <T extends Throwable,V> V uncheck(Throwable e) throws T { throw (T)e; }
    
}
