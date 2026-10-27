import {definePlan} from '@abox/hook';export default definePlan({id:'owned-mtls-observation',target,params:{},setup(ctx){
    ctx.java.method('dev.tripoder.mtlsfixture.OwnedHttp','exchange',['java.lang.String'])
      .before(({args})=>{ctx.debug.emitEvent('mtls.attempt',args[0]);return args;})
      .after(({args,result})=>{ctx.debug.emitEvent('mtls.exchange',{request:args[0],response:result});ctx.debug.captureEvidence('mtls.exchange',{request:args[0],response:result});return result;});
  }});