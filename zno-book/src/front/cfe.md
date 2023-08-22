# Control-flow

A **control-flow event** occurs when frontend must move to a new point in 
the instruction stream. A unique point in the instruction stream is always
represented by a **program counter** value. 

A control-flow event indicates the start of the instruction pipeline, and 
results in the instruction fetch unit bringing the the appropriate fetch block 
into the machine.

## Speculative Events

Ideally, control-flow events occur *every cycle* in order to keep the pipeline 
filled with instructions. However, in pipelined machines, there is an inherent 
latency associated with architectural changes in control-flow: instructions
take multiple cycles to move through the pipeline. 

In order to compensate for this, changes in control-flow may be *predicted* 
and may occur *speculatively*.

Control-flow events are called *speculative* when the next set of 
instructions are part of a *predicted* path through the instruction 
stream. 

Speculative events are generated from a **branch prediction pipeline**
which is partially decoupled from the instruction pipeline.

## Architectural Events

Control-flow events are called *architectural* when the next set 
of instructions are *guaranteed* to belong to the path through the instruction 
stream. For instance:

- When execution begins at the reset vector, the architectural path continues
  at the reset vector
- When a faulting instruction retires and causes a precise exception,
  the architectural path continues at the location of an exception handler
- When a branch or jump instruction retires, the architectural path continues
  at the appropriate branch target address
- When an entire block of instructions retires without causing any of the 
  events listed above, the architectural path *must* continue at the 
  next-sequential block of instructions

Architectural events are generated from the retire control unit. 

## Fetch Blocks

```admonish note

It's useful to think of the instruction stream as a **control-flow graph** 
where the edges are **control-flow operations** (jump and branch instructions),
and the nodes are **basic blocks** which: 

- Begin with an instruction which is the target of a control-flow operation
- End with a single control-flow operation (whose target is *necessarily*
  the start of a basic block)

Note that these are distinct from **fetch blocks**. A basic block might 
consist of multiple *sequential* fetch blocks. Or conversely, a single fetch 
block might have multiple basic blocks.

There's a sense in which we ideally want to maintain a cached version of 
something like a control-flow graph:

1. When entering a basic block, how many sequential fetch blocks until we 
   reach the end of the basic block? These can be immediately queued up for 
   fetch.
2. When we reach the last fetch block within a basic block, which instruction
   is terminal, and what is the address of the next basic block? 


```

The instruction stream is quantized in terms of **fetch blocks** which 
correspond to the smallest addressible elements in instruction memory 
(typically, a line in some first-level instruction cache).


