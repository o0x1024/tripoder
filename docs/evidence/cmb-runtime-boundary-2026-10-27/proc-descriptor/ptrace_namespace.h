#ifndef TRIPODER_PTRACE_NAMESPACE_H
#define TRIPODER_PTRACE_NAMESPACE_H
long tp_namespace_ptrace(long request,long target,long address,long data);
long tp_namespace_wait4(long target,void *status,long options,void *usage);
long tp_namespace_waitid(long kind,long target,void *information,long options,void *usage);
#endif
