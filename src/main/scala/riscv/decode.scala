
package zno.riscv.decode

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.riscv.isa._
import zno.riscv.uarch._

// Trait for automatically converting a [[Bundle]] literal into a [[BitPat]].
//
// NOTE: '.to_bitpat()' is only supposed to be used on Bundle literals!
//
trait AsBitPat {
  this: Bundle =>

  // Get the UInt associated with some [[Data]].
  def lit[T <: Data](n: T): UInt   = { n.litValue.U((n.getWidth).W) }

  // Convert some [[Data]] into a [[BitPat]].
  def pat[T <: Data](n: T): BitPat = { BitPat(lit(n)) }

  def to_bitpat(): BitPat = {
    val list: Seq[Data] = this.getElements.reverse
    if (list.length == 1) {
      pat(list(0))
    }
    else if (list.length >= 2) {
      var res = pat(list(0)) ## pat(list(1))
      for (field <- list.drop(2)) { 
        res = res ## pat(field) 
      }
      res
    } else {
      throw new IllegalArgumentException("No elements in this bundle?")
    }
  }
}


object DecoderTable {
  import Instructions._

  import ALUOp._
  import BCUOp._
  import LSUOp._
  import Op1Sel._
  import Op2Sel._
  import InstEnc._
  import ExecutionUnit._

  // This is a set of [[BitPat]] matching the instructions we support.
  // See https://github.com/riscv/riscv-opcodes for more information.
  // You can generate these with './parse_opcodes -chisel opcodes-rv32i'.
  object Instructions {
    def BEQ                = BitPat("b?????????????????000?????1100011")
    def BNE                = BitPat("b?????????????????001?????1100011")
    def BLT                = BitPat("b?????????????????100?????1100011")
    def BGE                = BitPat("b?????????????????101?????1100011")
    def BLTU               = BitPat("b?????????????????110?????1100011")
    def BGEU               = BitPat("b?????????????????111?????1100011")
    def JALR               = BitPat("b?????????????????000?????1100111")
    def JAL                = BitPat("b?????????????????????????1101111")
    def LUI                = BitPat("b?????????????????????????0110111")
    def AUIPC              = BitPat("b?????????????????????????0010111")
    def ADDI               = BitPat("b?????????????????000?????0010011")
    def SLTI               = BitPat("b?????????????????010?????0010011")
    def SLTIU              = BitPat("b?????????????????011?????0010011")
    def XORI               = BitPat("b?????????????????100?????0010011")
    def ORI                = BitPat("b?????????????????110?????0010011")
    def ANDI               = BitPat("b?????????????????111?????0010011")
    def ADD                = BitPat("b0000000??????????000?????0110011")
    def SUB                = BitPat("b0100000??????????000?????0110011")
    def SLL                = BitPat("b0000000??????????001?????0110011")
    def SLT                = BitPat("b0000000??????????010?????0110011")
    def SLTU               = BitPat("b0000000??????????011?????0110011")
    def XOR                = BitPat("b0000000??????????100?????0110011")
    def SRL                = BitPat("b0000000??????????101?????0110011")
    def SRA                = BitPat("b0100000??????????101?????0110011")
    def OR                 = BitPat("b0000000??????????110?????0110011")
    def AND                = BitPat("b0000000??????????111?????0110011")
    def LB                 = BitPat("b?????????????????000?????0000011")
    def LH                 = BitPat("b?????????????????001?????0000011")
    def LW                 = BitPat("b?????????????????010?????0000011")
    def LBU                = BitPat("b?????????????????100?????0000011")
    def LHU                = BitPat("b?????????????????101?????0000011")
    def SB                 = BitPat("b?????????????????000?????0100011")
    def SH                 = BitPat("b?????????????????001?????0100011")
    def SW                 = BitPat("b?????????????????010?????0100011")
    def FENCE              = BitPat("b?????????????????000?????0001111")
    def FENCE_I            = BitPat("b?????????????????001?????0001111")
  }


  // The set of signals that we want to associate to the [[BitPat]] matching 
  // each instruction.
  class ControlSignals extends Bundle with AsBitPat {
    val eu    = ExecutionUnit()
    val enc   = InstEnc()
    val rr    = Bool()
    val op1   = Op1Sel()
    val op2   = Op2Sel()
    var aluop = ALUOp()
    var bcuop = BCUOp()
    var lsuop = LSUOp()
  }

  // NOTE: This is just a nicer way to define a [[ControlSignals]] literal
  // in the table below (instead of using the ugly BundleLiterals syntax).
  object CtrlDef {
    def apply(
      eu: ExecutionUnit.Type, 
      enc: InstEnc.Type, 
      rr: Data,
      op1: Op1Sel.Type,
      op2: Op2Sel.Type,
      aluop: ALUOp.Type, 
      bcuop: BCUOp.Type,
      lsuop: LSUOp.Type
    ): BitPat = {
      (new ControlSignals).Lit(_.eu -> eu, _.enc -> enc,
        _.rr -> rr, _.op1 -> op1, _.op2 -> op2, 
        _.aluop -> aluop, _.bcuop -> bcuop, _.lsuop -> lsuop,
      ).to_bitpat()
    }

  }

  //val N = BitPat.N()
  //val Y = BitPat.Y()
  val N = false.B
  val Y = true.B


  // A map from an instruction bitpattern to a set of control signals.
  val matches = Array(
    BEQ     -> CtrlDef(EU_BCU, ENC_B, N, OP1_RS1, OP2_RS2, ALU_NOP, BCU_EQ,  LSU_NOP),   
    BNE     -> CtrlDef(EU_BCU, ENC_B, N, OP1_RS1, OP2_RS2, ALU_NOP, BCU_NEQ, LSU_NOP),
    BLT     -> CtrlDef(EU_BCU, ENC_B, N, OP1_RS1, OP2_RS2, ALU_NOP, BCU_LT,  LSU_NOP),
    BGE     -> CtrlDef(EU_BCU, ENC_B, N, OP1_RS1, OP2_RS2, ALU_NOP, BCU_GE,  LSU_NOP),
    BLTU    -> CtrlDef(EU_BCU, ENC_B, N, OP1_RS1, OP2_RS2, ALU_NOP, BCU_LTU, LSU_NOP),
    BGEU    -> CtrlDef(EU_BCU, ENC_B, N, OP1_RS1, OP2_RS2, ALU_NOP, BCU_GEU, LSU_NOP),

    JAL     -> CtrlDef(EU_BCU, ENC_J, Y, OP1_NAN, OP2_NAN, ALU_NOP, BCU_JAL,  LSU_NOP),
    JALR    -> CtrlDef(EU_BCU, ENC_I, Y, OP1_RS1, OP2_NAN, ALU_NOP, BCU_JALR, LSU_NOP),

    LUI     -> CtrlDef(EU_ALU, ENC_U, Y, OP1_NAN, OP2_IMM, ALU_ADD, BCU_NOP, LSU_NOP),
    AUIPC   -> CtrlDef(EU_ALU, ENC_U, Y, OP1_PC,  OP2_IMM, ALU_ADD, BCU_NOP, LSU_NOP),

    ADDI    -> CtrlDef(EU_ALU, ENC_I, Y, OP1_RS1, OP2_IMM, ALU_ADD,  BCU_NOP, LSU_NOP),
    SLTI    -> CtrlDef(EU_ALU, ENC_I, Y, OP1_RS1, OP2_IMM, ALU_SLT,  BCU_NOP, LSU_NOP),
    SLTIU   -> CtrlDef(EU_ALU, ENC_I, Y, OP1_RS1, OP2_IMM, ALU_SLTU, BCU_NOP, LSU_NOP),
    XORI    -> CtrlDef(EU_ALU, ENC_I, Y, OP1_RS1, OP2_IMM, ALU_XOR,  BCU_NOP, LSU_NOP),
    ORI     -> CtrlDef(EU_ALU, ENC_I, Y, OP1_RS1, OP2_IMM, ALU_OR,   BCU_NOP, LSU_NOP),
    ANDI    -> CtrlDef(EU_ALU, ENC_I, Y, OP1_RS1, OP2_IMM, ALU_AND,  BCU_NOP, LSU_NOP),

    ADD     -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_ADD,  BCU_NOP, LSU_NOP),
    SUB     -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_SUB,  BCU_NOP, LSU_NOP),
    SLL     -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_SLL,  BCU_NOP, LSU_NOP),
    SLT     -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_SLT,  BCU_NOP, LSU_NOP),
    SLTU    -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_SLTU, BCU_NOP, LSU_NOP),
    XOR     -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_XOR,  BCU_NOP, LSU_NOP),
    SRL     -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_SRL,  BCU_NOP, LSU_NOP),
    SRA     -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_SRA,  BCU_NOP, LSU_NOP),
    OR      -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_OR,   BCU_NOP, LSU_NOP),
    AND     -> CtrlDef(EU_ALU, ENC_R, Y, OP1_RS1, OP2_RS2, ALU_AND,  BCU_NOP, LSU_NOP),

    LB      -> CtrlDef(EU_LSU, ENC_I, Y, OP1_RS1, OP2_RS2, ALU_NOP,  BCU_NOP, LSU_LB),
    LH      -> CtrlDef(EU_LSU, ENC_I, Y, OP1_RS1, OP2_RS2, ALU_NOP,  BCU_NOP, LSU_LH),
    LW      -> CtrlDef(EU_LSU, ENC_I, Y, OP1_RS1, OP2_RS2, ALU_NOP,  BCU_NOP, LSU_LW),
    LBU     -> CtrlDef(EU_LSU, ENC_I, Y, OP1_RS1, OP2_RS2, ALU_NOP,  BCU_NOP, LSU_ILL),
    LHU     -> CtrlDef(EU_LSU, ENC_I, Y, OP1_RS1, OP2_RS2, ALU_NOP,  BCU_NOP, LSU_ILL),
    SB      -> CtrlDef(EU_LSU, ENC_S, N, OP1_RS1, OP2_RS2, ALU_NOP,  BCU_NOP, LSU_SB),
    SH      -> CtrlDef(EU_LSU, ENC_S, N, OP1_RS1, OP2_RS2, ALU_NOP,  BCU_NOP, LSU_SH),
    SW      -> CtrlDef(EU_LSU, ENC_S, N, OP1_RS1, OP2_RS2, ALU_NOP,  BCU_NOP, LSU_SW),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = {
        CtrlDef(EU_ILL, ENC_ILL, N, OP1_NAN, OP2_NAN, ALU_ILL, BCU_ILL, LSU_ILL)
  }

  // Generate a decoder that maps an instruction to some [[ControlSignals]].
  // This uses logic minimization with ESPRESSO (which you'll probably need to
  // install and put in your $PATH, see chipsalliance/espresso; otherwise
  // I think Chisel will fall back to a different algorithm).
  //
  // If [[ControlSignals]] contains ChiselEnum objects, it will probably 
  // complain about casting non-literal UInts; I haven't thought hard enough 
  // about whether or not we should actually care about this. 
  def generate_decoder(inst: UInt): ControlSignals = {
    assert(inst.getWidth == 32)
    chisel3.util.experimental.decode.decoder(inst,
      chisel3.util.experimental.decode.TruthTable(
        this.matches, this.default
      )
    ).asTypeOf(new ControlSignals)
  }
}


class DecodeUnit extends Module {
  import InstEnc._
  val uop = IO(Output(new Uop))

  val io = IO(new Bundle {
    val pc    = Input(UInt(32.W))
    val inst  = Input(UInt(32.W))
  })

  val inst   = io.inst
  val imm    = WireDefault(0.U(32.W))
  val ctrl   = DecoderTable.generate_decoder(inst)
  val imm_en = WireDefault(false.B)
  val rd_en  = WireDefault(false.B)
  val rs1_en = WireDefault(false.B)
  val rs2_en = WireDefault(false.B)

  // Generate information about operands for this micro-op
  switch (ctrl.enc) {
    is (ENC_R) { imm := 0.U }
    is (ENC_I) { imm := Cat(Fill(20, inst(31)), inst(31, 20)) }
    is (ENC_S) { imm := Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7)) }
    is (ENC_B) { imm := Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)) }
    is (ENC_U) { imm := Cat(inst(31, 12), Fill(12, 0.U)) }
    is (ENC_J) { imm := Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)) }
  }

  uop.eu        := ctrl.eu
  uop.rr        := ctrl.rr
  uop.op1       := ctrl.op1
  uop.op2       := ctrl.op2
  uop.aluop     := ctrl.aluop
  uop.bcuop     := ctrl.bcuop
  uop.lsuop     := ctrl.lsuop
  uop.rd        := inst(11, 7)
  uop.rs1       := inst(19, 15)
  uop.rs2       := inst(24, 20)
  uop.imm       := imm
  uop.pc        := io.pc

}


