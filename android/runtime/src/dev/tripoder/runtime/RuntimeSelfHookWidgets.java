package dev.tripoder.runtime;

import android.content.ComponentName;
import java.lang.reflect.Method;

/**
 * Widget-provider surface of the virtual device.
 *
 * <p>The platform answers widget queries by resolving a {@link ComponentName} against its own
 * install table and enforcing that the provider belongs to the calling UID. A guest component is
 * not installed under the host UID, so forwarding the query can only produce a security denial or a
 * crash in guest code that assumes an answer. The virtual device has no widget host, therefore every
 * widget id it could own is absent and every provider-side write is a no-op.
 *
 * <p>Component-scoped calls that do not name the guest are forwarded to the platform: they describe
 * the real device, not the guest.
 */
final class RuntimeSelfHookWidgets {
    private RuntimeSelfHookWidgets(){}

    static Object dispatch(Method method,Object[] arguments,RuntimeSelfHookLifecycle.Original original)throws Throwable {
        RuntimeEngine engine=RuntimeEngine.current;
        if(engine==null)return original.call(arguments);
        switch(method.getName()) {
            case "getAppWidgetIds": {
                ComponentName provider=(ComponentName)arguments[0];
                if(!owns(engine,provider))return original.call(arguments);
                return new int[0];
            }
            case "getAppWidgetInfo":return null;
            case "getAppWidgetOptions":return new android.os.Bundle();
            case "getInstalledProviders":return java.util.List.of();
            case "getInstalledProvidersForPackage": {
                String packageName=(String)arguments[0];
                if(packageName==null||!packageName.equals(engine.packageName))return original.call(arguments);
                return java.util.List.of();
            }
            case "getInstalledProvidersForProfile":return java.util.List.of();
            case "bindAppWidgetIdIfAllowed":return false;
            case "requestPinAppWidget":return false;
            case "isRequestPinAppWidgetSupported":return false;
            case "setWidgetPreview":return false;
            case "getWidgetPreview":return null;
            case "queryAppWidgetEvents":return java.util.List.of();
            case "updateAppWidget":case "partiallyUpdateAppWidget":case "notifyAppWidgetViewDataChanged":
            case "updateAppWidgetOptions":case "updateAppWidgetProviderInfo":case "removeWidgetPreview":
                return null;
            default:throw new IllegalStateException("WIDGET_BINDING_UNHANDLED:"+method);
        }
    }

    private static boolean owns(RuntimeEngine engine,ComponentName provider){
        return provider!=null&&engine.packageName.equals(provider.getPackageName());
    }
}
