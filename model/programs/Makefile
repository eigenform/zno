
PREFIX 	:= riscv64-unknown-elf
CC   	:= $(PREFIX)-gcc

# This is just a copy of the newlib linkerscript, adjusted to actually place
# '_start' at 0x00010000. Kind of relying on the simulator to initialize 
# the stack pointer for us. Otherwise, you need to make your own crt
LSCRIPT := rv.ld

CFLAGS  := -march=rv32i -mabi=ilp32 -Wl,-T$(LSCRIPT)

AS   	:= $(PREFIX)-as
AFLAGS  := -march=rv32i

OBJDUMP := $(PREFIX)-objdump
OBJCOPY := $(PREFIX)-objcopy

all:
	$(CC) $(CFLAGS) -O0 test.c -o test.elf
	#$(OBJCOPY) -O binary -j .text test.elf test.bin

dis:
	$(OBJDUMP) -M numeric,no-aliases -d test.elf

clean:
	rm -fv *.{o,elf,bin}
