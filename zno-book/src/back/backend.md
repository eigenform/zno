# ZNO Backend

[Instruction Scheduling](./sched.md) holds micro-ops until their data 
dependences are satisfied, and then releases them to the appropriate 
execution units. 

The [Integer Pipeline](./integer.md) is responsible for executing integer
operations and branch operations. 

The [Load/Store Pipeline](./integer.md) is responsible for address generation,
tracking the state of pending load/store operations, and interacting with 
data memory.

