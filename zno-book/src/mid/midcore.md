# ZNO Midcore

[Register Renaming](./rename.md) rewrites macro-ops and their operands 
with the goal of revealing true data dependences between instructions. 

[Instruction Dispatch](./dispatch.md) is responsible for releasing macro-ops
to the out-of-order backend of the machine, and for managing the state
used to maintain/recover the original program order. 

