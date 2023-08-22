# Instruction Predecode

```admonish note
Some other questions that we probably want to answer with information gathered
during predecode:

- If there are no branches, this fetch block is expected to always fall 
  through to the next-sequential fetch block (ignoring the possibility of 
  an exception)
- Does a branch target lie inside/outside this fetch block? 
- Despite the existence of branch instructions, is it still possible for this
  fetch block to fall through to the next-sequential fetch block?
```

After completing a transaction, the fetch unit passes a fetch block to the
predecoders. There is one predecode unit for each instruction in the block.
Each predecode unit determines the following:

- The immediate encoding format
- The fully-expanded 32-bit immediate offset used to calculate a branch target
- Whether or not an instruction is a control-flow operation, ie.
    - An unconditional jump (`JAL`)
    - An unconditional direct jump (`JALR`)
    - A conditional branch (`B{EQ,GE,GEU,LT,LTU,NE}`)
    - A call (`JAL` where `rd == x1`)
    - A return (`JALR` where `rs1 == x1`)

This allows for control-flow instructions to be discovered shortly after fetch.
Predecode output flows into the branch prediction pipeline, where it is used
to [in]validate a previous prediction made for the corresponding fetch block.

```admonish note title="About Immediates"
It'd be nice to extract *all* immediates up-front here. 
This makes a nice separation between "predecode handling immediate operands"
and "decode handling register operands." 
However, this amounts to *a lot* of wires (a full 32-bit immediate for each 
instruction in a fetch block), even before we completely decode the instruction. 
On top of that, these need to be stored somewhere. 
```


