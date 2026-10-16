"""Closed ELF audit for the fixed leaf wrappers' BTI-preserving absolute windows."""
import struct


def audit(image, sections, entries):
    windows=[(entry['rva']+4,entry['rva']+20) for entry in entries]
    def inside(address):
        return any(low<=address<high for low,high in windows)
    symbols={}
    symbol_count=0
    for index,section in enumerate(sections):
        if section[1] not in (2,11):continue
        if section[9]!=24:raise RuntimeError('LIBC_PATCH_SYMBOL_FORMAT')
        table=[]
        for offset in range(section[4],section[4]+section[5],24):
            _,_,_,owner,value,size=struct.unpack_from('<IBBHQQ',image,offset)
            table.append((owner,value,size));symbol_count+=1
            if owner and inside(value):raise RuntimeError('LIBC_PATCH_INTERIOR_SYMBOL:'+hex(value))
        symbols[index]=table
    relocation_count=0
    for section in sections:
        if section[1] in (9,19,0x60000001,0x60000002,0x6fffff00):
            raise RuntimeError('LIBC_PATCH_UNAUDITED_RELOCATION_FORMAT')
        if section[1]!=4:continue
        if section[9]!=24 or section[6] not in symbols:raise RuntimeError('LIBC_PATCH_RELOCATION_FORMAT')
        table=symbols[section[6]]
        for offset in range(section[4],section[4]+section[5],24):
            where,info,addend=struct.unpack_from('<QQq',image,offset);relocation_count+=1
            if inside(where):raise RuntimeError('LIBC_PATCH_TEXT_RELOCATION')
            kind,symbol=info&0xffffffff,info>>32
            if symbol>=len(table):raise RuntimeError('LIBC_PATCH_RELOCATION_SYMBOL')
            owner,value,_=table[symbol]
            # AArch64 RELATIVE (including IRELATIVE resolver) is load-base + A.
            # A defined symbol's address relocation is load-base + S + A.
            if kind in (1027,1032):target=addend
            elif owner:target=value+addend
            else:continue
            if inside(target):raise RuntimeError('LIBC_PATCH_INTERIOR_RELOCATION')
    instructions=0
    def signed(value,bits):return value-(1<<bits) if value&(1<<(bits-1)) else value
    for section in sections:
        if section[1]!=1 or not section[2]&4:continue
        if section[3]&3 or section[5]&3:raise RuntimeError('LIBC_PATCH_CODE_ALIGNMENT')
        for offset in range(0,section[5],4):
            word=struct.unpack_from('<I',image,section[4]+offset)[0];pc=section[3]+offset;target=None;instructions+=1
            if word&0x7c000000==0x14000000:target=pc+signed(word&0x3ffffff,26)*4
            elif word&0xff000010==0x54000000 or word&0x7e000000==0x34000000:
                target=pc+signed((word>>5)&0x7ffff,19)*4
            elif word&0x7e000000==0x36000000:target=pc+signed((word>>5)&0x3fff,14)*4
            elif word&0x9f000000==0x10000000:
                target=pc+signed(((word>>5)&0x7ffff)<<2|(word>>29)&3,21)
            elif word&0x3b000000==0x18000000:target=pc+signed((word>>5)&0x7ffff,19)*4
            if target is not None and inside(target):raise RuntimeError('LIBC_PATCH_INTERIOR_PC_REFERENCE:'+hex(pc))
    for entry in entries:
        words=entry['words']
        # BTI c; MOV x8,#syscall; SVC; CMN x0,#4095; B.HS errno; RET.
        # There is no SP/LR mutation and no PAC prologue in these complete leaves.
        if words[0]!=0xd503245f or words[1]&0xffe0001f!=0xd2800008 or words[2:4]!=[0xd4000001,0xb13ffc1f] or words[4]&0xff00001f!=0x54000002 or words[5]!=0xd65f03c0:
            raise RuntimeError('LIBC_PATCH_LEAF_ABI')
    return {'patchBytesPerEntry':16,'preservedBtiBytes':4,'auditedInstructions':instructions,
            'auditedSymbols':symbol_count,'auditedRelocations':relocation_count,
            'interiorReferences':0,'leafStackBytes':0,'originalWrapperCalled':False}
