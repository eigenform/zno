# Next-Fetch Prediction

Ideally, instruction fetch is constantly producing an uninterrupted stream of 
fetch blocks which contain the correct, architecturally-relevant path. 
In order to approach this goal, each address sent to instruction fetch must 
also be subject to a **next-fetch prediction**. 

Ideally, this occurs *immediately* when the machine obtains the next 
program counter value - otherwise, pipeline bubbles will occur as the machine
becomes starved for instructions.

```admonish note
Presumably, next-fetch prediction relies on a hierarchy of branch prediction
strategies, ie.

1. Have a small fully-associative cache of predictions indexed by the 
   fetch block address (I think sometimes you find this is called an "L0 BTB," 
   a "micro-BTB," etc - this is your "control-flow map"), where 
   target/direction predictions can be obtained within a single cycle. 
2. Otherwise, a CFM miss means that we must access larger [slower] predictors,
   and the next-fetch address may not be predicted for several cycles. 
3. Since the CFM is fully-associative, we need some kind of replacement 
   policy (presumably based on the temperature of an entry?)

...
```
