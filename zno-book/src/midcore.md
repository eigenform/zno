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

In general, renaming involves the following steps:

1. Determine which operands resolve to known values (ie. zero)
2. Determine which instructions are non-scheduled
3. Determine which instructions allocate a new physical register
4. Resolve "local" physical dependences (solely between instructions within 
   the window) by forwarding newly-allocated registers
5. Resolve "global" physical dependences (between instructions in the window
   and older instructions in the pipeline) by reading from the register map
6. Write back to the freelist and the register map

Some macro-ops can be completed early during this stage by simply writing to 
the register map. These **non-scheduled instructions** need to be detected 
before we can determine what should happen with the rest of the instructions in 
the window. 

## Detecting Non-Scheduled Instructions

Integer operations in the decode window may complete during rename if their 
operands satisfy some conditions. For now, these cases are:

- Register-to-register moves
- Immediate-to-register moves
- Zero-to-register moves
- No-ops 

Most of these occur for integer operations where zero is the identity, and
our ability to recognize these depends on recognizing which operands can be
resolved as zero. There are two places where this occurs:

- During decode, some operands are explicitly set to zero (like for `LUI`)
- During decode, comparators determine whether or not an immediate is zero
- By checking the register map zero bits during rename, we can determine if an 
  architectural register is currently mapped to zero

```admonish info title="Abstract Nonsense"
This is kind of like a very-limited form of **constant propagation**, but 
only for values that are known to be zero. As far as I can tell, the cost of 
this is "putting comparators on the register map write ports." 
```

Given this information, the following integer operations can be squashed into 
a move operation:

- For integer operations where zero is the identity, and when either operand 
  is zero, move the non-zero operand
- For subtraction, when the second operand is zero, move the first operand
- For subtraction and logical XOR, when both operands are the same, move zero 
- For logical AND, when either operand is zero, move zero

On top of this, integer operations where *both* operands are zero must be 
equivalent to a no-op. 

## Register Allocation

After scanning for non-scheduled instructions, we can determine whether or
not each instruction in the decode window should allocate a physical register. 
Non-scheduled instructions do not allocate.

## Resolving Dependences

In general, the register map read ports are used to convert a source register
into the appropriate physical register. These are cases for "global" 
dependences: registers that have been bound to a physical register on
a previous cycle. 

However, for those instructions whose source registers are the destination 
for some previous instruction in the window, the register map cannot be used 
to resolve a physical register, since the bindings have not occured yet. 

Instead, the appropriate register name must be forwarded from the most-recent 
producer in the decode window:

- When the producer is a scheduled instruction (with allocates), the 
  newly-allocated physical destination is used to resolve the name
- **TODO**

```admonish note
Since non-scheduled instructions do not have a physical destination, what
are we supposed to do when some source relies on a previous non-scheduled
instruction? 

- We could add read ports for each architectural destination?
- We could add ports that copy from one binding to another?
```

## Preparing State

This whole process results in writes to the register map and freelist. 
There are two possible interactions with the register map write ports: 

1. For scheduled instructions that must allocate, we bind `rd` to the 
   newly-allocated physical destination register
2. For non-scheduled instructions, we bind `rd` to the appropriate 
   physical source register 



