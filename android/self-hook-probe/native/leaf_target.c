#include <stdint.h>
/* Ordinary optimized C leaf functions. No padding, hooks, constructors or
 * helper calls are introduced to make their real first instructions easier. */
int32_t tp_leaf_constant(void){return 42;}
int32_t tp_leaf_add(int32_t a,int32_t b){return a+b;}
uint64_t tp_leaf_identity(uint64_t value){return value;}
float tp_leaf_float(float value){return value+0.5f;}
double tp_leaf_zero(void){return 0.0;}
void tp_leaf_void(void){}
