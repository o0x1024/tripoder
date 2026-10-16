package dev.tripoder.runtime;

import android.content.SharedPreferences;
import android.os.*;
import android.system.*;
import java.io.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/** One ordered disk writer and one memory view per preference file in a guest process. */
final class RuntimePreferences implements SharedPreferences {
    private static final Object REMOVE=new Object();
    private final RuntimeStorage storage;
    private final File file;
    private final PreferencesDisk disk;
    private final ExecutorService writer;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<OnSharedPreferenceChangeListener,Boolean> listeners=new WeakHashMap<>();
    private Map<String,Object> values;
    private volatile Throwable diskFailure;
    RuntimePreferences(RuntimeStorage storage,File file,ExecutorService writer){
        this.storage=storage;this.file=file;this.writer=writer;disk=new PreferencesDisk(storage,file);
        try{values=disk.access(()->PreferencesXml.read(file));}
        catch(Exception error){throw new IllegalStateException("PREFERENCES_LOAD_FAILED:"+error.getMessage(),error);}
    }
    private void healthy(){if(diskFailure!=null)throw new IllegalStateException("PREFERENCES_WRITE_FAILED",diskFailure);}
    private static Object copy(Object value){return value instanceof Set?new LinkedHashSet<>((Set<?>)value):value;}
    private static Map<String,Object> copy(Map<String,Object> source){Map<String,Object> out=new LinkedHashMap<>();source.forEach((k,v)->out.put(k,copy(v)));return out;}
    @Override public synchronized Map<String,?> getAll(){healthy();return copy(values);}
    private synchronized Object value(String key,Object defaultValue){healthy();return values.containsKey(key)?copy(values.get(key)):copy(defaultValue);}
    @Override public String getString(String key,String value){return (String)value(key,value);}
    @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String key,Set<String> value){return (Set<String>)value(key,value);}
    @Override public int getInt(String key,int value){return (Integer)value(key,value);}
    @Override public long getLong(String key,long value){return (Long)value(key,value);}
    @Override public float getFloat(String key,float value){return (Float)value(key,value);}
    @Override public boolean getBoolean(String key,boolean value){return (Boolean)value(key,value);}
    @Override public synchronized boolean contains(String key){healthy();return values.containsKey(key);}
    @Override public Editor edit(){healthy();return new Edit();}
    @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){listeners.put(Objects.requireNonNull(listener),true);}
    @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){listeners.remove(listener);}
    private synchronized Publication submit(Map<String,Object> modified,boolean clear){
        healthy();List<String> changed=new ArrayList<>();if(clear){values.clear();changed.add(null);}
        modified.forEach((key,value)->{if(value==REMOVE){if(values.containsKey(key)){values.remove(key);changed.add(key);}}else if(!Objects.equals(values.get(key),value)||!values.containsKey(key)){values.put(key,copy(value));changed.add(key);}});
        List<OnSharedPreferenceChangeListener> observers=new ArrayList<>(listeners.keySet());
        Future<Boolean> written=writer.submit(()->{try{healthy();return disk.access(()->{
            Map<String,Object> latest=PreferencesXml.read(file);if(clear)latest.clear();
            modified.forEach((key,value)->{if(value==REMOVE)latest.remove(key);else latest.put(key,copy(value));});
            PreferencesXml.write(storage,file,latest);return true;
        });}catch(Exception error){diskFailure=error;android.util.Log.e("TripoderRuntime","Guest preferences commit failed",error);return false;}});
        return new Publication(written,changed,observers);
    }
    private void notify(Publication publication){Runnable callback=()->{for(String key:publication.changed)for(var listener:publication.listeners)listener.onSharedPreferenceChanged(this,key);};if(Looper.myLooper()==Looper.getMainLooper())callback.run();else main.post(callback);}
    private static <T>T await(Future<T> future){try{return future.get();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IllegalStateException("PREFERENCES_WAIT_INTERRUPTED",error);}catch(ExecutionException error){throw new IllegalStateException("PREFERENCES_WRITE_FAILED",error.getCause());}}
    void flush(){Future<?> barrier;synchronized(this){barrier=writer.submit(()->{});}await(barrier);healthy();}
    synchronized void reload(){
        // Writers never take this monitor: a concurrent apply cannot be lost during the reload.
        flush();try{values=disk.access(()->PreferencesXml.read(file));}
        catch(Exception error){diskFailure=error;throw new IllegalStateException("PREFERENCES_RELOAD_FAILED",error);}
    }
    synchronized boolean delete(){
        healthy();values.clear();Future<Boolean> deletion=writer.submit(()->{try{healthy();return disk.access(()->{File stage=new File(file+".new");storage.check(stage);if(file.exists())java.nio.file.Files.delete(file.toPath());if(stage.exists())java.nio.file.Files.delete(stage.toPath());RuntimeFiles.sync(file.getParentFile());return true;});}catch(Exception error){diskFailure=error;throw new IllegalStateException("PREFERENCES_DELETE_FAILED",error);}});
        return await(deletion);
    }
    private record Publication(Future<Boolean> written,List<String> changed,List<OnSharedPreferenceChangeListener> listeners){}
    private final class Edit implements Editor {
        private final Map<String,Object> modified=new LinkedHashMap<>();private boolean clear;
        private synchronized Editor put(String key,Object value){modified.put(Objects.requireNonNull(key),value==null?REMOVE:copy(value));return this;}
        @Override public Editor putString(String key,String value){return put(key,value);}
        @Override public Editor putStringSet(String key,Set<String> value){if(value!=null)for(String item:value)Objects.requireNonNull(item);return put(key,value);}
        @Override public Editor putInt(String key,int value){return put(key,value);}
        @Override public Editor putLong(String key,long value){return put(key,value);}
        @Override public Editor putFloat(String key,float value){return put(key,value);}
        @Override public Editor putBoolean(String key,boolean value){return put(key,value);}
        @Override public Editor remove(String key){return put(key,null);}
        @Override public synchronized Editor clear(){clear=true;return this;}
        private synchronized Publication publish(){Publication p=submit(new LinkedHashMap<>(modified),clear);modified.clear();clear=false;return p;}
        @Override public boolean commit(){Publication p=publish();boolean result=await(p.written);RuntimePreferences.this.notify(p);return result;}
        @Override public void apply(){Publication p=publish();RuntimePreferences.this.notify(p);}
    }
}
