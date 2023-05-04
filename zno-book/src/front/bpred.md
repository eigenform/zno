# Branch Prediction


## Control-flow Events

```admonish note
Ideally, control-flow events occur *every cycle* in order to keep the pipeline 
filled with instructions. However, in pipelined machines, there is an inherent 
latency associated with *architectural* changes in control-flow, and they may 
not occur every cycle. In order to compensate for this, changes in 
control-flow are *predicted* and may occur *speculatively*. 

Furthermore, in superscalar machines, the instruction stream is quantized into 
blocks of multiple instructions. A control-flow event must always result
in the instruction fetch unit bringing an entire block of instructions into 
the machine. Despite this, there are many cases where some instructions in
a block are not relevant to the expected *architectural* path through the
underlying program. 

For instance, not all branch targets may correspond to the start of a block. 
In these cases, the instructions preceeding the branch target within the block
do not belong to the architectural path. For this reason, control-flow events 
always carry the next expected value of the program counter.
```

A **control-flow event** occurs when the machine must move to a point in the 
instruction stream. A unique point in the instruction stream is represented by 
a **program counter** value. Control-flow events indicate the start of the 
instruction pipeline, and result in the instruction fetch unit bringing the
the appropriate fetch block into the machine.

Control-flow events may either be *architectural*, *remedial*, or 
*speculative*.

### Architectural Events

Control-flow events are called *architectural* when the resulting fetch block
is guaranteed to continue the correct architectural path through the 
instruction stream:

- When execution begins at the reset vector, the architectural path continues
  at the reset vector
- When a faulting instruction retires and causes a precise exception,
  the architectural path continues at the location of an exception handler
- When a branch or jump instruction retires, the architectural path continues
  at the resolved branch target

```admonish note
When all instructions within a fetch block retire and the fetch block does not 
contain any branch or jump instructions, the architectural path continues at 
the next-sequential fetch block. Do we intend for this to also be called a
"control-flow event?" 
```

### Remedial Events

Control-flow events are called *remedial* when the resulting fetch block
is used to recover the correct architectural path after an incorrect 
speculative event:

- When a predecoded fetch block invalidates a prediction
- When the target of a branch or jump instruction is resolved in the backend
  and does not match the associated prediction

### Speculative Events

Control-flow events are called *speculative* when the resulting fetch block is 
part of a *predicted* architectural path (a speculative path) through the 
instruction stream.

A **branch prediction** is a speculative control-flow event. 

## Control-flow Map (CFM)
```admonish note
1. At first glance, the accuracy of this strategy comes from being able to 
   uniquely identify different fetch blocks. Aliasing between the "names" of
   fetch blocks would cause predictions for branches that do not exist. 
   What is the correct way of indexing into a structure like this? 

2. We're currently say that *no prediction* occurs on a CFM miss, and that we 
   must wait for fetch and predecode in order to make an informed decision. 
   Currently, we're assuming that *all* fetch blocks are given a CFM entry
   regardless of whether or not they contain control-flow instructions. 

3. What should happen for blocks that contain no control-flow instructions?
   After predecode, we *know* (barring the possibility of an exception) that
   a fetch block should fall through to the next-sequential block.

4. What should happen for blocks that only contain branches/jumps whose target
   is *internal* (and not a different block)?

```


The **control-flow map** (CFM) is indexed by fetch block address and stores
previously-discovered control-flow information about the contents of a fetch 
block. CFM entries are filled by the output of instruction predecode. 

The CFM is used to predict or infer *the existence of an instruction* within a 
fetch block that will result in a control-flow event. In this way, a CFM entry
can be used to obtain the address of the next-expected fetch block. 




This constitutes the start of the branch prediction pipeline. 
All control-flow events use their associated program counter value to obtain a 
fetch block address. The fetch block address is used to access the CFM while
simultaneously being sent to the instruction fetch unit.

When a CFM miss occurs, an informed prediction cannot be made in-parallel 
with instruction fetch. After instruction fetch and predecode, a new CFM entry
is created, and a new prediction can be made. 

When a CFM hit occurs, the contents of the entry are used to make a new 
prediction in-parallel with instruction fetch. This may involve accessing 
storage for other branch prediction structures. 

After instruction fetch and predecode have completed, the contents of a
CFM hit are compared against the newly-predecoded fetch block. 
If predecode output does not match the contents of the CFM hit, the entry
must be updated to reflect the fact that the contents of the fetch block
have apparently changed. This must also invalidate the prediction that 
previously occured.


## Branch Target Prediction

A **branch target buffer** (BTB) is keyed by the program counter value,
and caches the previously-resolved target/s of a branch. The BTB is used
to predict *the target of a particular branch instruction*. 

## Branch Direction Prediction

A **branch history buffer** (BHB) is keyed by the program counter value,
and records a history of previously-observed branch directions. The BHB
is used to predict *the direction of a branch instruction*. 


