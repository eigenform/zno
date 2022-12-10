#include <stdint.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>

typedef uint8_t  u8;
typedef uint16_t u16;
typedef uint32_t u32;

extern void *__stack_top;

//extern void main(void);
//
//void _start(void) {
//	main();
//}

//u32 loops(void) {
//	u32 res = 0;
//	u32 data[256] = { 0 };
//	for (int i = 0; i < 256; i++) {
//		if ((i % 4) == 0) {
//			res += 1;
//			data[i] = res;
//		}
//	}
//	return res;
//}

void main(int argc, char *argv) {
	//loops();
}


