#!/usr/bin/env python3
"""Sanitized range/allocator checks and independent FP/SIMD assembler words."""
import argparse,ctypes,hashlib,json,struct,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[4]
def run(*args):return subprocess.check_output(list(map(str,args)),stderr=subprocess.STDOUT,text=True)
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.unlink(missing_ok=True);native=ROOT/'android/self-hook/native';tests=Path(__file__).parent
    sources=[native/'a64_relocate.c',native/'a64_entry.c',native/'abi/native_prefix.c',native/'abi/native_address.c']
    inputs=[*sources,native/'near_address.c',native/'near_address.h',native/'abi/native_prefix.h',native/'a64_internal.h',ROOT/'android/self-hook/include/tripoder_a64.h',tests/'native_address_test.c',tests/'near_address_test.c',Path(__file__).resolve()]
    before={str(f.relative_to(ROOT)):sha(f) for f in inputs};flags=['-std=c11','-O2','-Wall','-Wextra','-Werror','-I'+str(ROOT/'android/self-hook/include')]
    address=a.output.parent/'native-address-host';allocator=a.output.parent/'native-near-host'
    run('clang',*flags,'-fsanitize=address,undefined',*sources,tests/'native_address_test.c','-o',address)
    address_result=run(address);assert 'NATIVE_ADDRESS_PASS' in address_result and 'checks=62' in address_result
    run('clang',*flags,'-fsanitize=address,undefined',tests/'near_address_test.c','-o',allocator)
    allocator_result=run(allocator);assert allocator_result.strip()=='NEAR_ADDRESS_PASS checks=6'
    tools=a.ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin';asm=a.output.parent/'native-safe-data-oracle.S';obj=asm.with_suffix('.o');binary=asm.with_suffix('.bin')
    instructions=['fadd s0,s1,s2','fsub d2,d3,d4','fmul s7,s8,s9','fdiv d10,d11,d12','fmov d0,#0.5','fmov s1,#1.0',
        'fmov x0,d0','fmov d0,xzr','fmov w0,s0','fmov s0,w0','movi v0.16b,#1','movi d0,#0','eor v1.16b,v2.16b,v3.16b',
        'ldr x0,[x1,#16]','str q0,[x1,#16]','ldrsw x0,[x1,#4]','mov w0,#42','movk x0,#0x1234,lsl #16']
    asm.write_text('.text\n'+''.join('    '+i+'\n' for i in instructions));run(tools/'aarch64-linux-android36-clang','-c',asm,'-o',obj)
    run(tools/'llvm-objcopy','-O','binary','--only-section=.text',obj,binary)
    words=struct.unpack('<'+str(len(instructions))+'I',binary.read_bytes());library=a.output.parent/'native-range-oracle.dylib'
    run('clang',*flags,'-dynamiclib',*sources,'-o',library);planner=ctypes.CDLL(str(library));word=ctypes.c_uint32
    planner.tp_a64_relocate.argtypes=[ctypes.POINTER(word),ctypes.c_size_t,ctypes.c_uint64,ctypes.c_uint64,ctypes.POINTER(word)]
    planner.tp_a64_relocate.restype=ctypes.c_int
    for name,encoded in zip(instructions,words):
        value=word(encoded);output=word();status=planner.tp_a64_relocate(ctypes.byref(value),1,0x100000,0x110000,ctypes.byref(output))
        if status!=0 or output.value!=encoded:raise RuntimeError('ORACLE_REJECTED:'+name+':'+str(status))
    assert before=={str(f.relative_to(ROOT)):sha(f) for f in inputs}
    report={'passed':True,'addressChecks':62,'allocatorChecks':6,'assemblerCases':len(instructions),'sanitizers':['address','undefined'],
        'sourceHashes':before,'oracleSha256':sha(obj),'deviceExecuted':False}
    a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='sourceHashes'}))
if __name__=='__main__':main()
