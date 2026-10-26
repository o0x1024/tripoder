// Hook signatures name actual JVM types. External invocation values are narrower.
export const invocationJavaTypes=['boolean','byte','short','int','long','char','float','double','java.lang.String','[B','byte[]'];
const name='[^<>.;/\\[\\]\\x00-\\x20\\x7f]+';
const binaryName=`${name}(?:\\.${name})*`;
const reserved='void|boolean|byte|short|int|long|char|float|double|int32|uint32|int64|uint64';
export const hookJavaType={type:'string',minLength:1,maxLength:512,pattern:`^(?:boolean|byte|short|int|long|char|float|double|byte\\[\\]|(?!(?:${reserved})$)${binaryName}|\\[+(?:[ZBSCIJFD]|L${binaryName};))$(?![\\s\\S])`,description:'Exact reflected Java parameter type: primitive, binary class name (including default-package a), or Class.getName array form ([I, [La;, [Ljava.lang.Object;). void and reserved ABI keywords cannot name object parameters. Object values are invocation-scoped references, not JSON objects.'};
