package fixture;

public final class OwnedDelegatingLoader extends ClassLoader {
    private final ClassLoader delegate;
    public OwnedDelegatingLoader(ClassLoader delegate){super(delegate.getParent());this.delegate=delegate;}
    @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{return delegate.loadClass(name);}
}
