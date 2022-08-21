
.section .text
_start:
	la    x1, my_data
	li    x4, 0x00000003
	sw    x4, 0x0(x1)

loop_head:
	lw    x6, 0x0(x1)
	addi  x6, x6, -1
	sw    x6, 0x0(x1)
	bne   x6, x0, loop_head
done:
	li    x4, 0xdeadbeef
	nop
	

.section .data
my_data:
	.long 0x00000000
	.long 0x00000000
	.long 0x00000000
	.long 0x00000000
