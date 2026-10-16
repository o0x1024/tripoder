#ifndef TRIPODER_HOOK_LIMITS_H
#define TRIPODER_HOOK_LIMITS_H
/* Managed process lifetime budget, shared by C and the assembler.
 * The measured Runtime needs over 2100 fixed entries before guest hooks.
 * Java verifies this value against the loaded native library before admission. */
#define TP_MANAGED_ENTRY_LIMIT 4096
/* Separate immutable Native ABI entries. Disable never reuses a slot because
 * an already admitted call can still be executing its callback. */
#define TP_NATIVE_ENTRY_LIMIT 1024
#endif
