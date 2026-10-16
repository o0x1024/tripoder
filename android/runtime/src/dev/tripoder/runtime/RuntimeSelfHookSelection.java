package dev.tripoder.runtime;

/** Immutable snapshot decoded by the fixed framework member adapter. */
final class RuntimeSelfHookSelection {
    static final class BackReference {
        final int fromIndex;final String fromKey;
        BackReference(int fromIndex,String fromKey){this.fromIndex=fromIndex;this.fromKey=fromKey;}
    }
    private final int[] positions;
    private final Object[] values;
    RuntimeSelfHookSelection(int[] positions,Object[] values) {
        if(positions==null||values==null||positions.length!=values.length||positions.length>128)
            throw new IllegalArgumentException("PROVIDER_BATCH_SELECTION_TOO_LARGE");
        this.positions=positions.clone();this.values=values.clone();
    }
    int[] keys(int operationIndex) {
        if(operationIndex<0)throw new IllegalArgumentException("PROVIDER_BATCH_OPERATION_INDEX_INVALID");
        int previous=-1;
        for(int i=0;i<positions.length;i++) {
            int key=positions[i];
            if(key<0||key>=128||key<=previous)throw new IllegalArgumentException("PROVIDER_BATCH_SELECTION_INDEX_INVALID");
            previous=key;Object value=values[i];
            if(value==null)continue;
            if(value instanceof String) {
                if(((String)value).length()>8192)throw new IllegalArgumentException("PROVIDER_BATCH_SELECTION_TOO_LARGE");
            } else if(value instanceof BackReference) {
                BackReference reference=(BackReference)value;
                if(reference.fromIndex<0||reference.fromIndex>=operationIndex||reference.fromKey!=null&&reference.fromKey.length()>512)
                    throw new IllegalArgumentException("PROVIDER_BATCH_BACK_REFERENCE_INVALID");
            } else throw new IllegalArgumentException("PROVIDER_BATCH_SELECTION_TYPE_INVALID");
        }
        return positions.clone();
    }
}
