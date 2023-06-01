# Instruction Fetch

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
The instruction stream is quantized in **fetch blocks**, which are the 
smallest addressable elements in an instruction memory. 

```admonish note
In modern machines, instruction fetch typically targets a first-level 
instruction cache, and fetch blocks correspond to first-level cache lines. 
Moreover, modern machines often include **prefetch** logic for 
opportunistically moving data upwards in the cache hierarchy. 
```

The size of a fetch block constrains the superscalar width of the machine. 
For now, we assume that a fetch block corresponds directly with the width
of the pipeline (ie. for an 8-wide RV32I machine, a 32-byte fetch block). 

```admonish note
Having the fetch block width coincide with the superscalar width is only 
reasonable if we can keep instruction fetch in a state where it is 
*continuously* streaming in blocks that lie along the correct 
architectural path?
```


