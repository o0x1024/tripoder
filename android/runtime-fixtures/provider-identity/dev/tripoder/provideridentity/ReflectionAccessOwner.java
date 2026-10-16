package dev.tripoder.provideridentity;

/** Public, independent fixture class so class visibility cannot mask field access behavior. */
public final class ReflectionAccessOwner {
    private Object secret = new Object();
    private static Object shared = new Object();
    public Object exposed = new Object();
    public int number = 7;
    public final Object fixed = new Object();
    Object readOwn() throws Exception { return getClass().getDeclaredField("secret").get(this); }
    void writeOwn(Object value) throws Exception { getClass().getDeclaredField("secret").set(this,value); }
    static Object readStatic() throws Exception { return ReflectionAccessOwner.class.getDeclaredField("shared").get(null); }
    static void writeStatic(Object value) throws Exception { ReflectionAccessOwner.class.getDeclaredField("shared").set(new Object(),value); }
}
