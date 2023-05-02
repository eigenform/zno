
# ZNO Frontend

The front-end of a modern microprocessor is mostly concerned with keeping
a constant stream of instructions moving into the machine. 

## Instruction Fetch

```admonish note
For now, we're assuming that all addressing is *physical* - totally ignoring
the existence of a **memory-management unit** (MMU) that handles virtual
addressing.

For machines with an virtual addressing, instruction fetch would also need
to resolve a virtual address by interacting with an MMU, or by interacting 
with a **translation lookaside buffer** (TLB) that caches previous
virtual-to-physical translations. 

```

A **fetch block address** is sent to the instruction fetch unit, which
performs a transaction with some instruction memory device.

Ordinarily, instruction fetch targets some hierarchy of caches for the 
content of instruction memory. We're assuming that in the best case, the 
first-level cache is able to return a response to the instruction fetch unit 
in a single cycle. 

For now, we assume that all fetch transactions occur in 64-byte 
**fetch blocks** which correspond to lines in the first-level cache. 
Each fetch block consists of eight 32-bit RV32I instructions. 

## Next-Fetch Prediction

At any point, instruction fetch behavior is either *speculative* (resulting
from a predicted control-flow operation) or *architectural* (resulting 
from an architecturally-resolved control-flow operation). This distinction 
must be maintained by tracking the state of all **in-flight** fetch blocks.

### Speculative Fetch

Speculative fetch blocks always correspond to a generating prediction, 
and remain in-flight until they are either promoted or discarded. 

Speculative fetch blocks are promoted [to architectural] only after their 
prediction has been determined to be correct. Otherwise, when the prediction 
is determined to be incorrect, the fetch block [and all downstream state] must 
be flushed from the pipeline. 

Each cycle, a candidate address for instruction fetch is computed using the
state of the branch prediction pipeline. When a branch prediction exists:

1. The target address of a 'taken' branch prediction is used to form the 
   address of a speculative fetch block 
2. A 'not-taken' branch prediction results in a speculative next-sequential
   fetch block (following the youngest in-flight fetch block)

In the *abscence* of any output from the branch prediction pipeline, a 
speculative next-sequential fetch block always follows the youngest in-flight 
fetch block. 

### Architectural Fetch
```admonish note
TODO: There's actually two cases where we determine that a prediction is 
incorrect:

1. A branch instruction completes and is compared against the prediction
2. A prediction is invalidated when the result of instruction predecode 
   does not match the generating CFM entry for the prediction
```

Architectural fetch blocks remain in-flight until all associated instructions 
have retired and committed to the architectural state. 

Apart from the case where a speculative fetch block is promoted, a 
**control-flow redirect** also results in an architectural fetch block. 
Redirects occur under the following conditions:

- A speculative fetch block is discarded after a prediction has been 
  determined to be incorrect. Control-flow *must* move to the correct 
  architecturally-resolved target address. 

- A faulting instruction retires and causes an exception.
  Control-flow *must* move into an exception handler.


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
and "decode handling register operands." 

However, this amounts to *a lot* of wires (a full 32-bit immediate for each 
instruction in a fetch block), even before we completely decode the instruction. 
On top of that, these need to be stored somewhere. 
```

## Instruction Decode

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


### About RISC-V Instructions

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

### About Macro-ops

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



