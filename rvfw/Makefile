
PREFIX 	:= riscv32-unknown-elf
CC 		:= $(PREFIX)-gcc
AS 		:= $(PREFIX)-as
OBJDUMP := $(PREFIX)-objdump
OBJCOPY := $(PREFIX)-objcopy

CFLAGS  := -nostdlib -march=rv32i -mabi=ilp32
LDFLAGS := -T,rvre.ld,-O,binary

all:
	$(CC) $(CFLAGS) -Wl,$(LDFLAGS) test.s -o test.elf
	$(OBJCOPY) -O binary -j .text test.elf test.text.bin
	$(OBJCOPY) -O binary -j .data test.elf test.data.bin
	hexdump -ve '1/4 "%08x\n"' test.text.bin > test.text.mem
	hexdump -ve '1/4 "%08x\n"' test.data.bin > test.data.mem

	#$(AS) -march=rv32i -Lscript=test.s -o test.o
	#$(OBJCOPY) -O binary -j .text test.o test.bin
	#$(OBJCOPY) -O ihex -j .text test.o test.hex
	#hexdump -ve '1/4 "%08x\n"' test.bin > test.mem
dis:
	#$(OBJDUMP) -Mnumeric -d test.o
	$(OBJDUMP) -Mnumeric -d test.elf

clean:
	rm -rvf *.bin *.elf *.mem
