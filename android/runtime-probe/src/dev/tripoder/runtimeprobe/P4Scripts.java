package dev.tripoder.runtimeprobe;

import org.json.*;

final class P4Scripts {
    static final String TYPE="dev.tripoder.p2.Hooks$Target";
    static JSONArray selectors()throws Exception{
        JSONArray result=new JSONArray();String[][] methods={{"sum","add","int","int"},{"text","add","java.lang.String"},{"exact","exact","long"},{"throwing","throwing"},{"before","beforeFailure","int"},{"after","afterFailure","int"},{"recursive","recursive","int"},{"thread","threaded","int"},{"blocking","blocking"}};
        for(String[] method:methods){JSONArray args=new JSONArray();for(int i=2;i<method.length;i++)args.put(method[i]);result.put(new JSONObject().put("id",method[0]).put("kind","java").put("className",TYPE).put("methodName",method[1]).put("argumentTypes",args));}
        return result;
    }
    static String source(){return "import { definePlan,param } from '@abox/hook'; export default definePlan({id:'owned-java',target,params:{increment:param.integer(100,{minimum:0,maximum:1000})},setup(ctx){"
        +method("add","['int','int']")+".before(({args})=>[args[0]+10,args[1]]).after(({result})=>result*2);"
        +method("add","['java.lang.String']")+".replace(({args})=>args[0]===null?null:'hook:'+args[0]);"
        +method("exact","['long']")+".replace(({args})=>args[0]+1n);"
        +method("throwing","[]")+".after(({result})=>result+1);"
        +method("beforeFailure","['int']")+".before(({args})=>[2147483648]);"
        +method("afterFailure","['int']")+".after(({result})=>'invalid-int');"
        +method("recursive","['int']")+".after(({result})=>result+1);"
        +method("threaded","['int']")+".replace(({args})=>args[0]+10);"
        +method("blocking","[]")+".after(({result,params})=>result+params.increment);"
        +"}});";}
    static String method(String name,String args){return "ctx.java.method('"+TYPE+"','"+name+"',"+args+")";}
    private P4Scripts(){}
}
