
MEMORY
{
	IROM (rx)  : ORIGIN = 0x00000000, LENGTH = 0x00000400
	RAM  (rwx) : ORIGIN = 0x80000000, LENGTH = 0x00000400
}

SECTIONS
{
	.text : 
	{
		*(.text)
		*(.rodata*)
	} > IROM
	.data :
	{
		*(.data)
		*(.bss)
	} > RAM
}
