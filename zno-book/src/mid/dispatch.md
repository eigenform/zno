# Instruction Dispatch

```admonish note
With the exception of loads and stores, all other RV32I instructions map onto 
a single scheduler entry. The phrase "micro-op" and "scheduler entry" are 
effectively synonymous in this context. 
```

**Instruction dispatch** is the process of submitting a block of macro-ops for 
execution in the backend of the machine. 

Dispatch is roughly broken into the following pieces:

1. Allocate/write a retire queue entry for the dispatched block
2. Ensure that non-scheduled macro-ops are marked as completed
3. Decompose each macro-op into one or more micro-ops 
4. Allocate/write a scheduler entry for each micro-op

Dispatch must stall for the availability of a retire queue entry, and for the
availability of scheduler storage. 



