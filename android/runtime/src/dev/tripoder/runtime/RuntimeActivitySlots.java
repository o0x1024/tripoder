package dev.tripoder.runtime;

import java.util.regex.*;

/** Names encode immutable manifest profiles. Never mutate a leased physical component. */
final class RuntimeActivitySlots {
    static final int AFFINITIES=8,NO_AFFINITY=8,ALIASES=2,ALIAS_COMPONENTS=2;
    private static final String PREFIX="dev.tripoder.runtime.generated."+RuntimeComponentBuild.ACTIVITY_POOL_PREFIX;
    private static final String SUFFIX="([0-7])\\$F([0-8])M([0-3])C([0-9]+)(R1)?(?:E([0-1]))?";
    private static final Pattern NAME=Pattern.compile(Pattern.quote(PREFIX)+SUFFIX);
    static int capacity(int mode){if(mode<0||mode>3)throw new IllegalArgumentException("ACTIVITY_LAUNCH_MODE_UNSUPPORTED");return mode<2?16:8;}
    static String name(int process,int component,int mode,int affinity,boolean reparent){
        if(process<0||process>=RuntimeSlot.CAPACITY||component<0||component>=capacity(mode)||affinity<0||affinity>NO_AFFINITY||(reparent&&mode>=2))throw new IllegalArgumentException("ACTIVITY_STUB_INVALID");
        return PREFIX+process+"$F"+affinity+"M"+mode+"C"+component+(reparent?"R1":"");
    }
    private static Matcher parsed(String name){Matcher m=NAME.matcher(name);if(!m.matches()||Integer.parseInt(m.group(4))>=capacity(Integer.parseInt(m.group(3)))||(m.group(6)!=null&&Integer.parseInt(m.group(4))>=ALIAS_COMPONENTS)||(m.group(5)!=null&&Integer.parseInt(m.group(3))>=2))throw new IllegalArgumentException("ACTIVITY_STUB_INVALID");return m;}
    static String alias(String canonical,int index){if(index<0||index>=ALIASES||Integer.parseInt(parsed(canonical).group(4))>=ALIAS_COMPONENTS||!target(canonical).equals(canonical))throw new IllegalArgumentException("ACTIVITY_ALIAS_INVALID");return canonical+"E"+index;}
    static String target(String stub){Matcher m=parsed(stub);return name(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(4)),Integer.parseInt(m.group(3)),Integer.parseInt(m.group(2)),m.group(5)!=null);}
    static int process(String name){return Integer.parseInt(parsed(name).group(1));}
    static boolean owns(String name){try{parsed(name);return true;}catch(IllegalArgumentException error){return false;}}
    static boolean retired(String name){return name.matches("dev\\.tripoder\\.runtime\\.generated\\.ActivityP[0-7]\\$[ST](?:[0-9]|1[0-5])")||(!RuntimeComponentBuild.ACTIVITY_POOL_PREFIX.equals("TaskP")&&name.matches("dev\\.tripoder\\.runtime\\.generated\\.TaskP"+SUFFIX));}
    private RuntimeActivitySlots(){}
}
