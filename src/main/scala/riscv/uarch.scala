
package zno.riscv.uarch

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.riscv.isa._

object ExecutionUnit extends ChiselEnum {
  val EU_ILL = Value // No execution unit (illegal instruction)
  val EU_ALU = Value // ALU instruction
  val EU_LSU = Value // Load/store instruction
  val EU_BCU = Value // Branch/jump/compare instruction
}

object Op1Sel extends ChiselEnum {
  val OP1_RS1 = Value // Architectural source register #1
  val OP1_NAN = Value // Zero
  val OP1_PC  = Value // Program counter
}
object Op2Sel extends ChiselEnum {
  val OP2_RS2 = Value // Architectural source register #2
  val OP2_IMM = Value // Immediate data from instruction encoding
  val OP2_NAN = Value // Zero
}

class Uop extends Bundle {
  val pc    = UInt(32.W)      // Program counter associated with this micro-op
  val eu    = ExecutionUnit() // Execution unit

  val aluop = ALUOp()         // Arithmetic/logic unit operation
  val bcuop = BCUOp()         // Branch comparator unit operation
  val lsuop = LSUOp()         // Load/store unit operation
  val rr    = Bool()          // Produces a register result
  val op1   = Op1Sel()        // Operand #1 select 
  val op2   = Op2Sel()        // Operand #2 select

  val imm   = UInt(32.W)      // Immediate data
  val rd    = UInt(5.W)       // Architectural destination register
  val rs1   = UInt(5.W)       // Architectural source register #1
  val rs2   = UInt(5.W)       // Architectural source register #2
}


