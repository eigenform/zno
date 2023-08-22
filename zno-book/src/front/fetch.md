# Instruction Fetch

```admonish note
For now, we're assuming that all addressing is *physical* - totally ignoring
the existence of a **memory-management unit** (MMU) that handles virtual
addressing.

In modern machines, instruction fetch typically targets a first-level 
instruction cache, and fetch blocks correspond to first-level cache lines
(or half cache lines). Moreover, modern machines often include **prefetch** 
logic for opportunistically moving data upwards in the cache hierarchy. 

For machines with an virtual addressing, instruction fetch would also need
to resolve a virtual address by interacting with an MMU. Virtual memory is
typically mapped with multiple levels of page-tables (and varying page
sizes). An MMU typically includes **translation lookaside buffers** (TLBs) 
that cache previous virtual-to-physical translations. On top of that, 
TLB maintainence is typically supported by dedicated hardware (ie. a 
**hardware page-table walker** and dedicated caches for paths through the 
page-tables). 

First-level caches are typically **virtually-indexed and physically-tagged**
in order to allow address translation and cache indexing to occur in parallel.
```

A **fetch block address** is sent to the instruction fetch unit, which
performs a transaction with some instruction memory device. 
The instruction stream is quantized in **fetch blocks**, which are the 
smallest addressable elements in an instruction memory. 

The size of a fetch block constrains the superscalar width of the machine. 
For now, we assume that a fetch block corresponds directly with the width
of the pipeline (ie. for an 8-wide RV32I machine, a 32-byte fetch block). 

```admonish note
Having the fetch block width coincide with the superscalar width is only 
reasonable if we can keep instruction fetch in a state where it is 
*continuously* streaming in blocks that lie along the correct 
architectural path?
```


