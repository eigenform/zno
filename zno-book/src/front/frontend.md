
# ZNO Frontend

The front-end of a modern microprocessor is mostly concerned with keeping
a constant stream of instructions moving into the machine. 

[Branch Prediction](./bpred.md) attempts to predict the direction/target of 
individual control-flow instructions by caching branch target addresses and
tracking the history of previous branch outcomes.

[Next-Fetch Prediction](./fpred.md) attempts to predictively guide instruction 
fetch by caching branch predictions and associating them with the names of 
previously-fetched blocks.

[Instruction Fetch](./fetch.md) retrieves instructions from memory. 

[Instruction Predecode](./predecode.md) extracts immediate data/control bits
from instructions, and attempts to discover control-flow instructions early
in the pipeline. 

[Instruction Decode](./decode.md) transforms instructions into macro-ops.


