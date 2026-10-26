package dev.tripoder.runtime;

import org.json.JSONObject;

/** One protocol identity for trusted controller receipts and their recovery proofs. */
final class RuntimeControllerOperationIdentity {
    static final String CALLER="trusted-workbench";
    static String stopDigest(String space,String pkg,String generation)throws Exception{
        return ApkStore.textDigest(new JSONObject().put("kind","controller-stop-v1").put("space",space).put("package",pkg).put("generation",generation).toString());
    }
    private RuntimeControllerOperationIdentity(){}
}
