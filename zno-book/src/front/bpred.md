# Branch Prediction



## Branch Target Prediction

A **branch target buffer** (BTB) is keyed by the program counter value,
and caches the previously-resolved target/s of a branch. The BTB is used
to predict *the target of a particular branch instruction*. 

## Branch Direction Prediction

A **branch history buffer** (BHB) is keyed by the program counter value,
and records a history of previously-observed branch directions. The BHB
is used to predict *the direction of a branch instruction*. 


