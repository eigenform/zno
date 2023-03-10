# ZNO Midcore

```admonish note
Currently, this page describes renaming for integer operations only. 
```

## Freelist

A **freelist** stores the set of physical registers that are available for
allocation. 
There is one read port for each entry in the decode window.
The index of a free physical register is always being driven over the read
port (if a register is available), along with a `valid` bit.
When the `alloc` bit on a read port is held high, the corresponding physical
register is marked as in-use starting on the next clock cycle. 


## Register Map

A **register map** associates a physical register to each architectural 
register (excluding the zero register `x0`). There are two read ports and 
one write port for each entry in the decode window. 

The register map also tracks which architectural registers are bound to the 
physical register 0. Each write port has a comparator used to determine 
how the zero bit should be updated along with the binding. 
These status bits are always driven as output.

## Register Rename

```admonish info title="Abstract Nonsense"
Without aliasing between architectural storage locations, a set of 
instructions can be easily rearranged into a **dataflow graph**: think of 
instructions as nodes in the graph, and their **read-after-write** (RAW) 
dependences as edges. This makes it obvious which instructions can be 
completed in parallel with one another, and which instructions must wait
for others before being executed. Out-of-order instruction issue depends on
this property. 

If you're familiar with the way that modern compilers are implemented, you 
might know that programs are often represented in **single-static assignment** 
(SSA) form. The rationale behind register renaming is very similar, although
we don't exactly have an *infinite* amount of registers ...
```

The register map and freelist are both used to rename architectural operands 
into physical operands. Renaming is mainly used to prepare instructions for 
out-of-order scheduling and execution in the backend. 

However, some macro-ops can be completed early during this stage by simply
writing to the register map. These need to be detected before determining 
what should happen with the rest of the instructions in the window. 


## Detecting Non-Scheduled Instructions

Integer operations in the decode window may be able to complete early if 
their operands satisfy some conditions. For now, all of these cases rely on 
our ability to recognize whether or not an operand will resolve to zero, ie.

- During decode, comparators determine whether or not an immediate is zero
- By checking the register map zero bits, we can determine if an architectural 
  register is currently mapped to zero

The following integer operations can be squashed into a move operation:

- For integer operations where zero is the identity, and when either operand 
  is zero, move the non-zero operand
- For subtraction, when the second operand is zero, move the first operand
- For subtraction and logical XOR, when both operands are equal, move zero 
- For logical AND, when either operand is zero, move zero

After scanning for non-scheduled instructions, we can determine whether or
not each instruction in the decode window should allocate a physical register. 
Non-scheduled instructions do not allocate, and the remaining instructions
that 


all entries in the
decode window have an appropriate destination. 

In general, there are two possible interactions with the write ports: 

1. For scheduled instructions that must allocate, we bind `rd` to the 
   newly-allocated physical destination register
2. For non-scheduled instructions, we bind `rd` to the appropriate 
   physical source register 


## Local Dependences

For those instructions whose source registers are written by some previous
instruction in the window, the register map cannot be used to resolve the
physical register. Instead, the most-recent newly-allocated physical 
destination must be forwarded. 



