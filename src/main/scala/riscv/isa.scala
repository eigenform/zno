package zno.riscv.isa

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

// RISC-V instruction encoding types.
object InstEnc extends ChiselEnum {
  val ENC_ILL = Value
  val ENC_R   = Value // rd, rs1, rs2
  val ENC_I   = Value // rd, rs1,   _, imm
  val ENC_S   = Value //  _, rs1, rs2, imm
  val ENC_B   = Value //  _, rs1, rs2, imm
  val ENC_U   = Value // rd,   _,   _, imm
  val ENC_J   = Value // rd,   _,   _, imm
}

// Distinct types of ALU operations.
object ALUOp extends ChiselEnum {
  val ALU_ADD   = Value
  val ALU_SUB   = Value
  val ALU_SLL   = Value
  val ALU_SLT   = Value
  val ALU_SLTU  = Value
  val ALU_XOR   = Value
  val ALU_SRL   = Value
  val ALU_SRA   = Value
  val ALU_OR    = Value
  val ALU_AND   = Value

  val ALU_ADDI  = Value
  val ALU_SLTI  = Value
  val ALU_SLTIU = Value
  val ALU_XORI  = Value
  val ALU_ORI   = Value
  val ALU_ANDI  = Value

  val ALU_ILL  = Value
  val ALU_NOP  = Value
}

// Distinct types of branch/compare/jump operations.
object BCUOp extends ChiselEnum {
  val BCU_EQ   = Value
  val BCU_NEQ  = Value
  val BCU_LT   = Value
  val BCU_LTU  = Value
  val BCU_GE   = Value
  val BCU_GEU  = Value

  val BCU_JAL  = Value
  val BCU_JALR = Value

  val BCU_ILL  = Value
  val BCU_NOP  = Value
}

// Distinct types of branch/compare operations.
object LSUOp extends ChiselEnum {
  val LSU_LB   = Value
  val LSU_LH   = Value
  val LSU_LW   = Value
  val LSU_LBU  = Value
  val LSU_LHU  = Value
  val LSU_SB   = Value
  val LSU_SH   = Value
  val LSU_SW   = Value

  val LSU_NOP  = Value
  val LSU_ILL  = Value
}


