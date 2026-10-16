import Java from "frida-java-bridge";

/** Populate only facts in the logical startup ledger. Never clone a host startup Intent. */
export function applicationHistory(Bridge: any) {
  const Info = (Java.classFactory as any).use("android.app.ApplicationStartInfo", { members: "methods" });
  const construct = Info.$new.overload("long");
  const launchKey = Info.class.getField("START_TIMESTAMP_LAUNCH").getInt(null);
  const createKey = Info.class.getField("START_TIMESTAMP_APPLICATION_ONCREATE").getInt(null);
  const setters = ["setStartupState", "setPid", "setRealUid", "setPackageUid", "setDefiningUid", "setReason", "setStartType", "setStartComponent", "setLaunchMode"];
  for (const setter of setters) Info[setter].overload("int");
  Info.setPackageName.overload("java.lang.String"); Info.setProcessName.overload("java.lang.String");
  Info.setForceStopped.overload("boolean"); Info.addStartupTimestamp.overload("int", "long");
  return (max: number) => {
    const rows = Bridge.historicalStarts(max), result = Java.use("java.util.ArrayList").$new();
    for (let index = 0; index < rows.size(); index++) {
      const row = Java.cast(rows.get(index), Java.use("android.os.Bundle"));
      const info = construct.call(Info, row.getLong("createdMs"));
      info.setPid(row.getInt("pid")); info.setRealUid(row.getInt("uid"));
      info.setPackageUid(row.getInt("uid")); info.setDefiningUid(row.getInt("uid"));
      info.setPackageName(row.getString("package")); info.setProcessName(row.getString("processName"));
      info.setStartupState(row.getInt("startupState")); info.setStartType(row.getInt("startType"));
      info.setReason(row.getInt("reason")); info.setStartComponent(row.getInt("component"));
      info.setLaunchMode(row.getInt("launchMode")); info.setForceStopped(false);
      info.addStartupTimestamp(launchKey, row.getLong("startedNs"));
      if (row.containsKey("onCreateNs")) info.addStartupTimestamp(createKey, row.getLong("onCreateNs"));
      result.add(info);
    }
    return result;
  };
}
