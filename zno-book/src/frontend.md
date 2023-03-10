

# ZNO Frontend

## Instruction Fetch

A **fetch block address** is sent to the instruction fetch unit, which
performs a transaction with some instruction memory device.
For now, we assume that all transactions occur in 32-byte **fetch blocks**. 
Each fetch block consists of eight 32-bit RV32I instructions. 

## Instruction Predecode

After completing a transaction, the fetch unit passes a fetch block to the
predecoders. There is one predecode unit for each instruction in the block.
Each predecode unit determines the following:

- The immediate encoding format
- The fully-expanded 32-bit immediate offset used to calculate a branch target
- Whether or not an instruction is a control-flow operation, ie.
    - An unconditional jump (`JAL`)
    - An unconditional direct jump (`JALR`)
    - A conditional branch (`B{EQ,GE,GEU,LT,LTU,NE}`)
    - A call (`JAL` where `rd == x1`)
    - A return (`JALR` where `rs1 == x1`)

This allows us to discover control-flow operations shortly after fetch.
Predecode output flows into the branch prediction pipeline. 

```admonish note title="About Immediates"
It'd be nice to extract *all* immediates up-front here. 
This makes a nice separation between "predecode handling immediate operands"
and "decode handling register operands." The cost of doing this is not 
[immediately] clear to me.
```

## Instruction Decode

In this context, all RISC-V instructions have a 32-bit encoding. 
Each decoder converts an instruction into a set of signals called a 
**macro-op** (or "mop"). 

Each macro-op may describe the following:

- The type of underlying operation (integer, control-flow, load/store)
- A particular integer operation (if one exists)
- A particular control-flow operation (if one exists)
- A particular load/store operation (if one exists)
- Whether or not the operation produces a register result
- The types of operands (register, immediate, or zero)
- The architectural destination and source registers
- Compressed immediate data and format (if any exists)
- Control bits used to determine how an immediate is stored

For now, we assume that there is one decoder for each instruction in a fetch 
block. The resulting block of macro-ops is called a **decode window**
(or just "a decoded block").



