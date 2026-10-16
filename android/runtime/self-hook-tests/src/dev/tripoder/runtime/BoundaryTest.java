package dev.tripoder.runtime;

import java.util.ArrayList;
import java.util.List;

public final class BoundaryTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    @FunctionalInterface private interface Action{void run()throws Throwable;}
    private static void sameFailure(Action action,Throwable expected)throws Throwable {
        try{action.run();throw new AssertionError("failure lost");}catch(Throwable actual){check(actual==expected);}
    }
    private static final Object HOST_CONTEXT=new Object(),GUEST_CONTEXT=new Object(),HOST_APPLICATION=new Object(),GUEST_APPLICATION=new Object();
    private static final Object INSTANCE=new Object(),THREAD=new Object(),TOKEN=new Object(),MANAGER=new Object(),INFO=new Object(),OBSERVER=new Object(),URI=new Object();
    private static final class Services implements RuntimeSelfHookBoundaries.ServiceRoute {
        boolean owned=true,result;int calls,id;Throwable failure;final List<String> events=new ArrayList<>();
        public boolean owns(Object value){check(value==INSTANCE);events.add("owns");return owned;}
        public Object context(Object value){check(value==HOST_CONTEXT);events.add("context");return GUEST_CONTEXT;}
        public Object application(){events.add("application");return GUEST_APPLICATION;}
        public boolean stop(Object value,int id)throws Throwable{check(value==INSTANCE);events.add("stop");this.id=id;calls++;if(failure!=null)throw failure;return result;}
    }
    private static final class Providers implements RuntimeSelfHookBoundaries.ProviderRoute {
        boolean owned=true,scoped;RuntimeException contextFailure,restoreFailure;final List<String> events=new ArrayList<>();
        public boolean owns(Object value){check(value==INSTANCE);events.add("owns");return owned;}
        public boolean scoped(Object value){check(value==INSTANCE);events.add("scoped");return scoped;}
        public Object context(Object provider,Object supplied,Object declaration,boolean testing){
            check(provider==INSTANCE&&supplied==HOST_CONTEXT&&declaration==INFO&&!testing);events.add("context");
            if(contextFailure!=null)throw contextFailure;return GUEST_CONTEXT;
        }
        public Object callingPackage(Object value){check(value==INSTANCE);events.add("package");return "guest.package";}
        public Object attribution(Object value){check(value==INSTANCE);events.add("attribution");return INFO;}
        public void cleared(Object value,Object token){check(value==INSTANCE&&token==TOKEN);events.add("clear");}
        public void restoring(Object value,Object token){check(value==INSTANCE&&token==TOKEN);events.add("restore");if(restoreFailure!=null)throw restoreFailure;}
    }
    private static final class Observers implements RuntimeSelfHookBoundaries.ObserverRoute {
        boolean owned=true;int user;RuntimeException failure;final List<String> events=new ArrayList<>();
        public boolean owns(Object value){check(value==INSTANCE);events.add("owns");return owned;}
        public int currentUser(){events.add("user");return 17;}
        public void register(Object uri,boolean descendants,Object observer,int user){check(uri==URI&&descendants&&observer==OBSERVER);events.add("register");this.user=user;if(failure!=null)throw failure;}
        public void unregister(Object value){check(value==OBSERVER);events.add("unregister");if(failure!=null)throw failure;}
    }
    public static void main(String[] args)throws Throwable {
        Services service=new Services();Object[] attach={HOST_CONTEXT,THREAD,"guest.Service",TOKEN,HOST_APPLICATION,MANAGER};
        check(RuntimeSelfHookBoundaries.serviceAttach(service,INSTANCE,attach,a->{service.events.add("original");
            check(a.length==6&&a[0]==GUEST_CONTEXT&&a[4]==GUEST_APPLICATION);check(a[1]==THREAD&&a[2].equals("guest.Service")&&a[3]==TOKEN&&a[5]==MANAGER);return null;})==null);
        check(service.events.equals(List.of("owns","context","application","original")));
        check(attach[0]==HOST_CONTEXT&&attach[4]==HOST_APPLICATION);
        service.events.clear();service.owned=false;
        RuntimeSelfHookBoundaries.serviceAttach(service,INSTANCE,attach,a->{check(a==attach);service.events.add("original");return null;});
        check(service.events.equals(List.of("owns","original")));
        service.events.clear();Object[] stopArgs={Integer.MAX_VALUE};
        check(RuntimeSelfHookBoundaries.stopService(service,INSTANCE,Integer.MAX_VALUE,true,stopArgs,a->{check(a==stopArgs);return true;}).equals(true));check(service.calls==0);
        service.owned=true;service.events.clear();
        check(RuntimeSelfHookBoundaries.stopService(service,INSTANCE,Integer.MIN_VALUE,true,new Object[]{Integer.MIN_VALUE},a->{throw new AssertionError();}).equals(false));
        check(service.calls==1&&service.id==Integer.MIN_VALUE);check(service.events.equals(List.of("owns","stop")));
        service.result=true;check(RuntimeSelfHookBoundaries.stopService(service,INSTANCE,42,true,new Object[]{42},a->{throw new AssertionError();}).equals(true));
        check(service.calls==2&&service.id==42);
        check(RuntimeSelfHookBoundaries.stopService(service,INSTANCE,-1,false,new Object[0],a->{throw new AssertionError();})==null);check(service.calls==3&&service.id==-1);
        check(RuntimeSelfHookBoundaries.stopService(service,INSTANCE,43,false,new Object[]{43},a->{throw new AssertionError();})==null);check(service.calls==4&&service.id==43);
        Exception remote=new Exception("remote");service.failure=remote;
        sameFailure(()->RuntimeSelfHookBoundaries.stopService(service,INSTANCE,1,true,new Object[]{1},a->{throw new AssertionError();}),remote);check(service.calls==5);

        Providers provider=new Providers();Object[] providerArgs={HOST_CONTEXT,INFO,false};
        RuntimeSelfHookBoundaries.providerAttach(provider,INSTANCE,providerArgs,a->{provider.events.add("original");check(a[0]==GUEST_CONTEXT&&a[1]==INFO&&a[2].equals(false));return null;});
        check(provider.events.equals(List.of("owns","context","original")));check(providerArgs[0]==HOST_CONTEXT);
        provider.owned=false;provider.events.clear();
        RuntimeSelfHookBoundaries.providerAttach(provider,INSTANCE,providerArgs,a->{check(a==providerArgs);provider.events.add("original");return null;});
        check(provider.events.equals(List.of("owns","scoped","original")));
        provider.scoped=true;provider.events.clear();
        RuntimeSelfHookBoundaries.providerAttach(provider,INSTANCE,providerArgs,a->{check(a[0]==GUEST_CONTEXT);return null;});check(provider.events.equals(List.of("owns","scoped","context")));
        RuntimeException rejected=new SecurityException("scope");provider.contextFailure=rejected;
        sameFailure(()->RuntimeSelfHookBoundaries.providerAttach(provider,INSTANCE,providerArgs,a->{throw new AssertionError("scope escaped");}),rejected);
        provider.owned=true;provider.contextFailure=null;
        for(var kind:RuntimeSelfHookBoundaries.ProviderIdentity.values()){
            Object result=RuntimeSelfHookBoundaries.providerIdentity(provider,INSTANCE,kind,a->{throw new AssertionError();});
            if(kind==RuntimeSelfHookBoundaries.ProviderIdentity.ATTRIBUTION_TAG)check(result==null);
            else if(kind==RuntimeSelfHookBoundaries.ProviderIdentity.ATTRIBUTION_SOURCE)check(result==INFO);
            else check(result.equals("guest.package"));
        }
        provider.owned=false;check(RuntimeSelfHookBoundaries.providerIdentity(provider,INSTANCE,RuntimeSelfHookBoundaries.ProviderIdentity.PACKAGE,a->"host.package").equals("host.package"));
        provider.owned=true;provider.events.clear();
        check(RuntimeSelfHookBoundaries.clearProviderIdentity(provider,INSTANCE,a->{provider.events.add("original");return TOKEN;})==TOKEN);
        check(provider.events.equals(List.of("original","owns","clear")));
        provider.events.clear();sameFailure(()->RuntimeSelfHookBoundaries.clearProviderIdentity(provider,INSTANCE,a->{provider.events.add("original");throw remote;}),remote);
        check(provider.events.equals(List.of("original")));
        provider.events.clear();RuntimeSelfHookBoundaries.restoreProviderIdentity(provider,INSTANCE,TOKEN,a->{check(a[0]==TOKEN);provider.events.add("original");return null;});
        check(provider.events.equals(List.of("owns","restore","original")));
        provider.events.clear();provider.restoreFailure=rejected;
        sameFailure(()->RuntimeSelfHookBoundaries.restoreProviderIdentity(provider,INSTANCE,TOKEN,a->{throw new AssertionError("invalid token reached platform");}),rejected);
        check(provider.events.equals(List.of("owns","restore")));
        provider.owned=false;provider.events.clear();
        RuntimeSelfHookBoundaries.restoreProviderIdentity(provider,INSTANCE,TOKEN,a->{check(a[0]==TOKEN);provider.events.add("original");return null;});
        check(provider.events.equals(List.of("owns","original")));

        Observers observer=new Observers();Object[] register={URI,true,OBSERVER};
        check(RuntimeSelfHookBoundaries.registerObserver(observer,INSTANCE,register,a->{throw new AssertionError();})==null);
        check(observer.user==17&&observer.events.equals(List.of("owns","user","register")));
        observer.events.clear();RuntimeSelfHookBoundaries.registerObserver(observer,INSTANCE,new Object[]{URI,true,OBSERVER,Integer.MAX_VALUE},a->{throw new AssertionError();});
        check(observer.user==Integer.MAX_VALUE&&observer.events.equals(List.of("owns","register")));
        observer.events.clear();observer.owned=false;
        RuntimeSelfHookBoundaries.registerObserver(observer,INSTANCE,register,a->{check(a==register);observer.events.add("original");return null;});
        check(observer.events.equals(List.of("owns","original")));
        observer.owned=true;observer.events.clear();
        try{RuntimeSelfHookBoundaries.registerObserver(observer,INSTANCE,new Object[5],a->{throw new AssertionError();});throw new AssertionError();}
        catch(UnsupportedOperationException error){check(error.getMessage().equals("SETTINGS_OBSERVER_OVERLOAD_UNSUPPORTED"));}
        check(observer.events.equals(List.of("owns")));
        observer.events.clear();RuntimeSelfHookBoundaries.unregisterObserver(observer,INSTANCE,OBSERVER,a->{throw new AssertionError();});
        check(observer.events.equals(List.of("owns","unregister")));
        observer.events.clear();observer.failure=rejected;
        sameFailure(()->RuntimeSelfHookBoundaries.registerObserver(observer,INSTANCE,register,a->{throw new AssertionError();}),rejected);
        sameFailure(()->RuntimeSelfHookBoundaries.unregisterObserver(observer,INSTANCE,OBSERVER,a->{throw new AssertionError();}),rejected);
        for(String denial:List.of("FOREGROUND_SERVICE_POLICY_NOT_IMPLEMENTED","URI_PERSISTABLE_GRANT_NOT_IMPLEMENTED","PROVIDER_OBSERVER_NOT_IMPLEMENTED","RECEIVER_PEEK_SERVICE_NOT_IMPLEMENTED")){
            try{RuntimeSelfHookBoundaries.denyOwned(value->true,INSTANCE,denial,register,a->{throw new AssertionError();});throw new AssertionError();}
            catch(UnsupportedOperationException error){check(error.getMessage().equals(denial));}
            check(RuntimeSelfHookBoundaries.denyOwned(value->false,INSTANCE,denial,register,a->{check(a==register);return TOKEN;})==TOKEN);
        }
        System.out.println("boundary checks="+checks);
    }
}
