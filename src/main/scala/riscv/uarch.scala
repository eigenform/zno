
package zno.riscv.uarch

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.riscv.isa._

object ExecutionUnit extends ChiselEnum {
  val EU_ILL = Value // No execution unit (illegal instruction)
  val EU_NOP = Value // Explicit no-op 
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

  def drive_defaults(): Unit = {
      this.eu    := ExecutionUnit.EU_NOP
      this.rr    := false.B
      this.op1   := Op1Sel.OP1_NAN
      this.op2   := Op2Sel.OP2_NAN
      this.aluop := ALUOp.ALU_NOP
      this.bcuop := BCUOp.BCU_NOP
      this.lsuop := LSUOp.LSU_NOP
      this.rd    := 0.U
      this.rs1   := 0.U
      this.rs2   := 0.U
      this.imm   := 0.U
      this.pc    := 0.U
  }
}

// Fetch unit output.
class FetchPacket extends Bundle {
  val pc   = UInt(32.W)
  val inst = UInt(32.W)
}

// A request to an AGU.
class AGUPacket extends Bundle {
  val op  = LSUOp()
  val pc  = UInt(32.W)
  val rd  = UInt(5.W)
  val rs1 = UInt(32.W)
  val rs2 = UInt(32.W)
  val imm = UInt(32.W)

  def drive_defaults(): Unit = {
    this.op  := LSUOp.LSU_NOP
    this.pc  := 0.U
    this.rd  := 0.U
    this.rs1 := 0.U
    this.rs2 := 0.U
    this.imm := 0.U
  }
}

// A request to an ALU.
class ALUPacket extends Bundle {
  val op  = ALUOp()
  val pc  = UInt(32.W)
  val rd  = UInt(5.W)
  val x   = UInt(32.W)
  val y   = UInt(32.W)

  def drive_defaults(): Unit = {
    this.op := ALUOp.ALU_NOP
    this.pc := 0.U
    this.rd := 0.U
    this.x  := 0.U
    this.y  := 0.U
  }
}

// A request to a BCU.
class BCUPacket extends Bundle {
  val op  = BCUOp()
  val pc  = UInt(32.W)
  val rd  = UInt(5.W)
  val rs1 = UInt(32.W)
  val rs2 = UInt(32.W)
  val imm = UInt(32.W)

  def drive_defaults(): Unit = {
    this.op  := BCUOp.BCU_NOP
    this.pc  := 0.U
    this.rd  := 0.U
    this.rs1 := 0.U
    this.rs2 := 0.U
    this.imm := 0.U
  }
}

// A request to an LSU.
class LSUPacket extends Bundle {
  val op   = LSUOp()
  val pc   = UInt(32.W)
  val rd   = UInt(5.W)
  val addr = UInt(32.W)
  val src  = UInt(32.W)

  def drive_defaults(): Unit = {
    this.op   := LSUOp.LSU_NOP
    this.pc   := 0.U
    this.rd   := 0.U
    this.addr := 0.U
    this.src  := 0.U
  }
}

// Register result data from an execution unit.
class RRPacket extends Bundle {
  val rd   = UInt(5.W)
  val pc   = UInt(32.W)
  val data = UInt(32.W)

  def drive_defaults(): Unit = {
    this.rd   := 0.U
    this.pc   := 0.U
    this.data := 0.U
  }
}

// Control flow/register result data from an execution unit.
class BRPacket extends Bundle {
  val rd   = UInt(5.W)
  val pc   = UInt(32.W)
  val tgt  = UInt(32.W)
  val ok   = Bool()
  val link = Bool()

  def drive_defaults(): Unit = {
    this.rd   := 0.U
    this.pc   := 0.U
    this.tgt  := 0.U
    this.ok   := false.B
    this.link := false.B
  }
}

