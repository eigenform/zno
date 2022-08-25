
.section .text
_start:
	li    x1, 0x11111111
	li    x2, 0x22222222
	li    x3, 0x33333333
	li    x4, 0x44444444
	li    x5, 0x55555555
	li    x6, 0x66666666
	li    x7, 0x77777777
	li    x8, 0x88888888

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
