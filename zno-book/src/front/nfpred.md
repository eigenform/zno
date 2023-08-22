# Next-Fetch Prediction

Ideally, instruction fetch is constantly producing an uninterrupted stream of 
fetch blocks which contain the correct, architecturally-relevant path. 
In order to approach this goal, each address sent to instruction fetch must 
also be subject to a **next-fetch prediction**. 

Ideally, this occurs *immediately* when the machine obtains the next 
program counter value - otherwise, pipeline bubbles will occur as the machine
becomes starved for instructions.

## "Control-flow Map" (CFM)

```admonish note

- This is a somewhat aggressive solution?? There's probably a more relaxed 
  version of this where we only handle certain "easy" cases. 
- This can probably be used to store certain predictions alongside branch
  information
- To what degree do we want *speculative* control-flow events to be subject to 
  next-fetch prediction?
- You can probably use this to create some kind of basic-block map by
  counting fetch blocks which contain no branches?

```

A **control-flow map** (CFM) is indexed by fetch block address and stores 
previously-discovered control-flow information about the contents of a fetch 
block. When a fetch block contains branches, a corresponding CFM entry may 
be filled by the output of instruction predecode. 

The CFM is used to predict *the existence of an instruction* within a fetch 
block that will result in a control-flow event **and** predict the associated
target address. In this way, a CFM entry can be used to obtain the address 
of the next-expected fetch block while the previous is being fetched. 

1. All control-flow events have an associated target (program counter value).
The corresponding fetch block address is used to access the CFM while
simultaneously being sent to the instruction fetch unit.

2. When a CFM miss occurs, an informed prediction cannot be made in-parallel 
with instruction fetch. 

3. When a CFM hit occurs, the contents of the entry are used to make a new 
prediction in parallel with instruction fetch. This may involve accessing 
storage for other branch prediction structures. 

4. After instruction fetch and predecode have completed, the contents of a
CFM hit are validated against the newly-predecoded fetch block. 
If predecode output does not match the branches cached by the CFM entry, the 
entry must be updated to reflect the fact that the contents of the fetch block
have apparently changed. This must also invalidate any prediction made with
the now-invalid cached branch information.


