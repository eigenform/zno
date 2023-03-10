# Introduction

This project is a vehicle for exploring the following questions:

- How are modern [superscalar, out-of-order] microprocessors implemented?
- How do you create a behavioral description of these machines in RTL?
- What does it take to *physically* implement these machines? 

Currently, `ZNO` is a RISC-V machine designed around the RV32I base integer 
instruction set. This seemed like sufficient ground for exploring many
of the problems involved in the design of modern machines. 

## Rough Overview

The `ZNO` core is split into three pieces:

- The **frontcore** (for dealing with control-flow)
- The **midcore** (for managing state)
- The **backcore** (for dealing with data-flow)


