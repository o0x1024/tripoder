package dev.tripoder.selfhookprobe;

class OwnedConstructorParent {
    static int parentCalls;
    final long parentValue;
    OwnedConstructorParent(long value){parentCalls++;parentValue=value;}
}
/** Natural constructor bytecode: super, final fields, delegation and a real throw. */
public final class OwnedConstructorTarget extends OwnedConstructorParent {
    static {System.setProperty("tripoder.owned.constructor.initialized","true");}
    static final IllegalArgumentException failure=new IllegalArgumentException("OWNED_CONSTRUCTOR_FAILURE");
    static int bodies;
    final int number;final String text;final Object reference;final double fraction;
    public OwnedConstructorTarget(int number,String text,long exact,Object reference,double fraction){
        super(exact);bodies++;if(number<0)throw failure;
        this.number=number;this.text=text;this.reference=reference;this.fraction=fraction;
    }
    public OwnedConstructorTarget(int number){this(number,"delegated",9007199254740993L,null,1.5);}
    public String echo(String suffix,int value){return text+":"+suffix+":"+value;}
}
