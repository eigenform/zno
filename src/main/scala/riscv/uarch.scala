
package zno.riscv.uarch

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.riscv.isa._

object ExecutionUnit extends ChiselEnum {
  val EU_ILL = Value
  val EU_ALU = Value
  val EU_LSU = Value
  val EU_BCU = Value
}

object Operand extends ChiselEnum {
  val NAN = Value
  val RS1 = Value
  val RS2 = Value
  val IMM = Value
  val PC  = Value
}

class Uop extends Bundle {
  val pc    = UInt(32.W)      // Program counter associated with this uop
  val enc   = InstEnc()       // Instruction encoding
  val eu    = ExecutionUnit() // Execution unit
  val aluop = ALUOp()         // Arithmetic/logic unit operation
  val bcuop = BCUOp()         // Branch comparator unit operation
  val lsuop = LSUOp()         // Load/store unit operation
  val imm   = UInt(32.W)      // Immediate data
  val rd    = UInt(5.W)       // Architectural destination register
  val rs1   = UInt(5.W)       // Architectural source register #1
  val rs2   = UInt(5.W)       // Architectural source register #2
}


