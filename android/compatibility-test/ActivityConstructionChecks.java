package dev.tripoder.runtime;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BiConsumer;

/** Real SDK values and SQLite; synthetic PIDs, no physical component dispatch. */
final class ActivityConstructionChecks {
    private final BiConsumer<String,Boolean> check;
    ActivityConstructionChecks(BiConsumer<String,Boolean> check){this.check=check;}
    private interface Call {void run()throws Exception;}
    private void denied(String name,String reason,Call call)throws Exception{
        boolean rejected=false;try{call.run();}catch(Exception error){rejected=error instanceof ExecutionContract.ContractException?reason.equals(((ExecutionContract.ContractException)error).code):reason.equals(error.getMessage());}
        check.accept("activity-construction-model-"+name,rejected);
    }
    private static long changes(RuntimeBroker broker){try(var c=broker.store.getReadableDatabase().rawQuery("SELECT total_changes()",null)){c.moveToFirst();return c.getLong(0);}}
    private static Bundle request(String generation,String ticket,String component,String delivery){Bundle value=new Bundle();value.putString("generation",generation);value.putString("ticket",ticket);value.putString("component",component);value.putString("delivery",delivery);return value;}
    private static final class Fixture implements AutoCloseable {
        final RuntimeBroker broker;final ApkStore.BundleInstall install;final int uid=android.os.Process.myUid();
        final String stub=RuntimeSlot.activity(7).getName();RuntimeBroker.Slot slot;int pid=290001;
        Fixture(Context context,Context assets)throws Exception{
            broker=new RuntimeBroker(context);File stage=new File(context.getFilesDir(),"apk");if(!stage.mkdir())throw new IllegalStateException("OWNED_CONSTRUCTION_STAGE");
            try(var input=assets.getAssets().open("aliases.apk")){Files.copy(input,new File(stage,"base.apk").toPath());}
            ArtifactSet artifacts=ArtifactSet.materialize(stage);PackageInfo info=context.getPackageManager().getPackageArchiveInfo(artifacts.base.toString(),PackageManager.GET_ACTIVITIES|PackageManager.MATCH_DISABLED_COMPONENTS);
            install=new ApkStore.BundleInstall("owned-construction",UUID.randomUUID().toString(),artifacts.base,artifacts.parts.get(0).sha256,info,new File(stage,"data"),artifacts);
            var db=broker.store.getWritableDatabase();db.execSQL("INSERT INTO spaces VALUES(?,1)",new Object[]{install.space});
            db.execSQL("INSERT INTO installs VALUES(?,?,?,?,?,?,'COMMITTED','',1,'owned',?,'owned-import')",new Object[]{install.space,info.packageName,install.generation,artifacts.base.toString(),artifacts.parts.get(0).sha256,artifacts.descriptorSha,install.data.getName()});
            allocate();
        }
        @SuppressWarnings("unchecked") void allocate()throws Exception{
            var allocation=broker.contract.allocate(install.space,install.info.packageName,install.generation,install.info.packageName,uid);
            broker.store.reserve(allocation.session,stub);broker.store.session(broker.contract.attach(allocation.bootstrapToken,pid,uid));
            slot=new RuntimeBroker.Slot(install,allocation,stub,null);var field=RuntimeBroker.class.getDeclaredField("slots");field.setAccessible(true);((Map<String,RuntimeBroker.Slot>)field.get(broker)).put(generation(),slot);
        }
        String generation(){return slot.allocation.session.processGeneration;}
        String prepare(String component)throws Exception{String operation=UUID.randomUUID().toString();broker.contract.accept(operation,"owned-construction-caller",ApkStore.textDigest(operation));broker.contract.start(operation);return broker.activities.prepareContext(slot,component,operation,install.info.packageName,"owned-payload",Intent.FLAG_ACTIVITY_NEW_TASK);}
        Bundle query(String ticket){return request(generation(),ticket,broker.activities.get(ticket).component,broker.activities.launches.forTicket(ticket));}
        Bundle record(Bundle input)throws Exception{return RuntimeActivityConstruction.record(broker,input,pid,uid);}
        public void close(){broker.store.close();}
    }
    void run(Context owned,Context assets)throws Exception{
        for(String sample:new String[]{"runtime"}){
            File directory=new File(owned.getFilesDir(),sample);if(!directory.mkdir())throw new IllegalStateException("OWNED_CONSTRUCTION_MODE");
            try(Fixture f=new Fixture(CompatibilityIntegration.scoped(owned,directory),assets)){
                String main=f.install.info.packageName+".Main",alias=f.install.info.packageName+".Alias0";
                String plain=f.prepare(main),aliased=f.prepare(alias);Bundle input=f.query(plain),aliasInput=f.query(aliased);long before=changes(f.broker);
                Bundle plainView=f.record(input),aliasView=f.record(aliasInput);
                check.accept("activity-construction-model-"+sample+"-declared-construction-target",main.equals(plainView.getString("constructionTarget"))&&main.equals(plainView.getString("component")));
                check.accept("activity-construction-model-"+sample+"-alias-keeps-separate-component",main.equals(aliasView.getString("constructionTarget"))&&alias.equals(aliasView.getString("component")));
                check.accept("activity-construction-model-"+sample+"-metadata-read-does-not-write",changes(f.broker)==before);
                Bundle wrong=new Bundle(input);wrong.putString("component",alias);denied(sample+"-wrong-component","ACTIVITY_CONSTRUCTION_COMPONENT_MISMATCH",()->f.record(wrong));
                Bundle otherDelivery=new Bundle(input);otherDelivery.putString("delivery",f.broker.activities.launches.forTicket(aliased));denied(sample+"-wrong-delivery","ACTIVITY_DELIVERY_TICKET_MISMATCH",()->f.record(otherDelivery));
                Bundle extra=new Bundle(input);extra.putString("unexpected","owned");denied(sample+"-unexpected-field","ACTIVITY_CONSTRUCTION_REQUEST_FIELDS",()->f.record(extra));
                denied(sample+"-wrong-pid","CALLER_MISMATCH",()->RuntimeActivityConstruction.record(f.broker,input,f.pid+1,f.uid));
                denied(sample+"-wrong-uid","CALLER_UID_MISMATCH",()->RuntimeActivityConstruction.record(f.broker,input,f.pid,f.uid+1));
                check.accept("activity-construction-model-"+sample+"-denied-queries-do-not-write",changes(f.broker)==before);
                    String delivery=input.getString("delivery");
                    for(String state:new String[]{"UNKNOWN","ENTERED"}){
                        f.broker.store.getWritableDatabase().execSQL("UPDATE activity_launches SET state=? WHERE id=?",new Object[]{state,delivery});
                        denied("thin-"+state+"-does-not-authorize-metadata","ACTIVITY_CONSTRUCTION_THIN_RECEIPT_MISMATCH",()->f.record(input));
                    }
                    f.broker.store.getWritableDatabase().execSQL("UPDATE activity_launches SET state='RECEIVED',receiver='owned-wrong-receiver' WHERE id=?",new Object[]{delivery});
                    denied("thin-wrong-receiver","ACTIVITY_CONSTRUCTION_THIN_RECEIPT_MISMATCH",()->f.record(input));
                    f.broker.store.getWritableDatabase().execSQL("UPDATE activity_launches SET receiver=? WHERE id=?",new Object[]{plain,delivery});
                f.broker.store.getWritableDatabase().execSQL("UPDATE installs SET generation='owned-stale' WHERE generation=?",new Object[]{f.install.generation});
                Bundle latest=f.query(plain);denied(sample+"-stale-install","ACTIVITY_CONSTRUCTION_INSTALL_STALE",()->f.record(latest));
                f.broker.store.getWritableDatabase().execSQL("UPDATE installs SET generation=? WHERE generation='owned-stale'",new Object[]{f.install.generation});
                f.broker.store.getWritableDatabase().execSQL("UPDATE activity_tickets SET state='REVOKED' WHERE ticket=?",new Object[]{plain});long closedReads=changes(f.broker);Bundle closed=f.record(latest);
                check.accept("activity-construction-model-"+sample+"-closed-entry-has-no-guest-target",closed.getBoolean("closed")&&!closed.containsKey("constructionTarget")&&!closed.containsKey("component")&&changes(f.broker)==closedReads);
                f.broker.store.session(f.broker.contract.markDead(f.generation()));denied(sample+"-dead-source","SESSION_INACTIVE",()->f.record(latest));
                projection(owned,aliasView.getString("stub"),main);
            }
        }
        opaqueEnvelope(owned);
    }
    private void projection(Context owned,String stub,String target)throws Exception{
        ActivityInfo source=new ActivityInfo();source.name=stub;source.packageName=owned.getPackageName();source.applicationInfo=new ApplicationInfo(owned.getApplicationInfo());source.targetActivity=RuntimeActivitySlots.target(stub);
        source.permission="owned.permission";source.flags=ActivityInfo.FLAG_NO_HISTORY;source.launchMode=ActivityInfo.LAUNCH_SINGLE_TOP;source.exported=false;
        ActivityInfo projected=RuntimeActivityConstructionInfo.project(source,owned.getPackageName(),android.os.Process.myUid(),stub,target);
        check.accept("activity-construction-model-info-copy-keeps-physical-owner-profile",projected!=source&&projected.applicationInfo==source.applicationInfo&&projected.name.equals(source.name)&&projected.packageName.equals(source.packageName)&&projected.flags==source.flags&&projected.launchMode==source.launchMode&&!projected.exported&&projected.permission.equals(source.permission));
        check.accept("activity-construction-model-info-projects-only-construction-target",target.equals(projected.targetActivity)&&RuntimeActivitySlots.target(stub).equals(source.targetActivity));
        ActivityInfo wrong=new ActivityInfo(source);wrong.targetActivity="owned.foreign.Activity";denied("unproven-input-target","ACTIVITY_CONSTRUCTION_PHYSICAL_INFO_MISMATCH",()->RuntimeActivityConstructionInfo.project(wrong,owned.getPackageName(),android.os.Process.myUid(),stub,target));
        ActivityInfo foreign=new ActivityInfo(source);foreign.applicationInfo=new ApplicationInfo(source.applicationInfo);foreign.applicationInfo.uid++;
        denied("foreign-physical-uid","ACTIVITY_CONSTRUCTION_PHYSICAL_INFO_MISMATCH",()->RuntimeActivityConstructionInfo.project(foreign,owned.getPackageName(),android.os.Process.myUid(),stub,target));
        denied("engine-class-is-not-guest-target","ACTIVITY_CONSTRUCTION_GUEST_TARGET_REQUIRED",()->RuntimeActivityConstructionInfo.project(source,owned.getPackageName(),android.os.Process.myUid(),stub,RuntimeActivitySlots.target(stub)));
    }
    public static final class OwnedEarlyPayload implements Parcelable {
        static int creates;public int describeContents(){return 0;}public void writeToParcel(Parcel p,int flags){p.writeInt(7);}
        public static final Creator<OwnedEarlyPayload> CREATOR=new Creator<>(){public OwnedEarlyPayload createFromParcel(Parcel p){p.readInt();creates++;return new OwnedEarlyPayload();}public OwnedEarlyPayload[] newArray(int n){return new OwnedEarlyPayload[n];}};
    }
    private void opaqueEnvelope(Context owned)throws Exception{
        Intent source=new Intent().setComponent(new ComponentName(owned.getPackageName(),RuntimeActivitySlots.name(7,0,0,0,false))).putExtra("tripoder.ticket","owned-ticket").putExtra("tripoder.component","owned.Main").putExtra("tripoder.delivery","owned-delivery").putExtra("owned-payload",new OwnedEarlyPayload());
        Parcel parcel=Parcel.obtain();Intent physical;
        try{source.writeToParcel(parcel,0);parcel.setDataPosition(0);physical=Intent.CREATOR.createFromParcel(parcel);}finally{parcel.recycle();}
        OwnedEarlyPayload.creates=0;Bundle envelope=RuntimeActivityConstruction.request("owned-generation",physical,getClass().getClassLoader());
        check.accept("activity-construction-model-envelope-does-not-run-guest-parcelable-creator",OwnedEarlyPayload.creates==0&&envelope.keySet().equals(Set.of("generation","ticket","component","delivery"))&&"owned-ticket".equals(envelope.getString("ticket")));
        check.accept("activity-construction-model-original-intent-component-kept",source.getComponent().equals(physical.getComponent()));
    }
}
