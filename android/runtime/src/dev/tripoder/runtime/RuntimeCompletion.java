package dev.tripoder.runtime;

import android.os.Bundle;

/** Only already-admitted effects may report completion after the admission fence closes. */
final class RuntimeCompletion {
    private RuntimeCompletion(){}
    static boolean isReceipt(int code,Bundle input){
        return switch(code){
            case RuntimeWire.JOB_EVENT,RuntimeWire.SERVICE_ACK,RuntimeWire.SERVICE_EVENT,
                RuntimeWire.ACTIVITY_ATTACHED,RuntimeWire.ACTIVITY_CREATED,RuntimeWire.ACTIVITY_RESUMED,
                RuntimeWire.ACTIVITY_DESTROYED,RuntimeWire.ACTIVITY_NEW_INTENT_ACK,RuntimeWire.ACTIVITY_START_RESULT,
                RuntimeWire.RESULT,RuntimeWire.ACTIVITY_RESULT_ENTER,RuntimeWire.RECEIVED,
                RuntimeWire.PROVIDER_RELEASE,RuntimeWire.PROVIDER_FINISH,RuntimeWire.PROVIDER_RECEIVED,
                RuntimeWire.BROADCAST_EVENT -> true;
            case RuntimeWire.PROVIDER_WAIT -> input.containsKey("begin")&&!input.getBoolean("begin");
            case RuntimeWire.BROADCAST_REGISTRATION -> "CLOSED".equals(input.getString("state"));
            case RuntimeWire.APPLICATION_START_EVENT -> "FAILED".equals(input.getString("stage"));
            default -> false;
        };
    }
}
