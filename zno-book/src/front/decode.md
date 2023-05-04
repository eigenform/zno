# Instruction Decode

```admonish note
Since all encodings in RV32I are 32-bit, the process of decoding instructions 
is dead-simple. However, compared to other kinds of machines, the code density 
is not ideal: programs occupy more space as a result of this.

In RISC-V, the extension for 16-bit compressed instructions is really the only 
way to mitigate this. This also ideally means implementing some kind of 
**macro-op fusion**, where we combine some instructions into a single 
operation. 
```

In this context, all RISC-V instructions (for RV32I) have a 32-bit encoding. 
Each decoder expands an instruction into a set of signals called a 
**macro-op** (or "mop"). 

For now, we assume that there is one decoder for each instruction in a fetch 
block. The resulting block of macro-ops is called a **decode window**
(or just "a decoded block").


## About RISC-V Instructions

For RV32I, there are very few fundamental kinds of instructions: 

- There are simple integer arithmetic/logical operations 
- There are conditional branches
- There are direct and indirect unconditional jumps
- There are memory operations (loads and stores)

Notice that *all* of these ultimately involve an integer operation:

- Load/store addresses must be calculated with addition
- Jump/branch target addresses must be calculated with addition
- Conditional branches must be evaluated by comparing two values

With that in mind, the only real difference between these instructions 
is *how the result of the integer operation is used.* For instance:

- For simple integer operations, the result is written to the register file
- For conditional branches, there are two integer operations: 
    - One result (comparing `rs1` and `rs2`) determines the branch outcome
    - The other result (`pc + imm`) determines the target address
- For jumps, the result (`pc + imm`) determines the target address
- For memory operations, the result (`rs1 + imm`) determines the target address

This means that RV32I instructions have a natural decomposition in terms of:

- An integer operation
- The set of operands
- Some kind of side-effect involving the integer result

## About Macro-ops

A macro-op corresponds to a single instruction which has been decompressed 
into a set of control signals. In general, a macro-op should capture the 
following pieces of information:

- The type of underlying operation (integer, control-flow, load/store)
- A particular integer operation (if one exists)
- A particular control-flow operation (if one exists)
- A particular load/store operation (if one exists)
- The types of operands (register, immediate, zero, program counter)
- The architectural destination and source registers
- Whether or not the instruction produces a register result
- Immediate data and control bits (if any exists)


