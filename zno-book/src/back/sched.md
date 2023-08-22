# Instruction Scheduling

After register renaming, the storage locations of each source/destination 
operand belonging to a macro-op have been completely disambiguated.
Instead of the architectural registers, each operand has a single unambigous 
storage location:

- The program counter (identified by/computed with the parent fetch block)
- An immediate value (identified by each individual macro-op)
- A physical register (identified by a physical register number)

Instead of the original program order, this is a **data-flow graph** 
representation of the program, where:

- Nodes in the graph are *operations*
- Edges in the graph are *names of values* subject to operations
- Edges flowing into a node are the *dependent values* required to perform 
  the operation
- Edges flowing out of a node are the *resulting values* produced by an 
  operation

By tracking the *readiness* of values, a **scheduler** evaluates and 
maintains the state of this graph and ultimately determines when operations 
can be released for execution. This process is called **instruction issue**. 

## Value Readiness

Some kinds of dependences are *always* ready-for-use because the underlying 
value is already available, and can always be computed independently of 
other operations:

- Operations do not need to wait for the value of the program counter because
  (a) the base address of the parent decode block is stored in the scheduler,
  and (b) the actual program counter can be trivially computed by adding this 
  to the offset of the parent macro-op

- Operations do not need to wait for immediate values because (a) the 
  immediate data/control bits are stored alongside each micro-op waiting in 
  the scheduler and (b) the full immediate value can be trivially computed
  from this
  
Both of these computations can [concievably?] occur (a) immediately when an 
operation is ready for issue, or (b) in-parallel with physical register file 
accesses. 

Otherwise, dependences on physical registers always indicate that an operation
depends on the result value of *some other* operation which may or may not 
be ready.


## "Dynamic" Scheduling

Many instructions will have dependences that must be resolved before they
can be issued. A **wake-up queue** is populated with instructions whose
dependences are not satisfied upon entering the scheduler during dispatch.

1. The name (destination physical register) of each result value produced in
   the backend is broadcast to all entries waiting in the queue.
2. Each entry compares its source register names to all wake-ups being broadcast
3. A match indicates that the data dependences for an entry have been resolved

## "Static" Scheduling

A **ready queue** populated with instructions whose dependences are already 
known to be satisfied when entering the scheduler during dispatch. 
In these cases, instructions to not need to be subject to more-complicated 
dynamic scheduling, and can instead be released from a simple FIFO when the 
appropriate execution resources are available. 


