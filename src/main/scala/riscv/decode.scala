
package zno.riscv.decode

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.riscv.isa._
import zno.riscv.uarch._


object DecoderTable {
  import Instructions._

  import ALUOp._
  import BCUOp._
  import LSUOp._
  import InstEnc._
  import ExecutionUnit._

  // This is a set of bitpatterns matching the instructions we support.
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

  // These are just helper functions to make these declarations easier.
  def lit[T <: Data](n: T): UInt = { n.litValue.U((n.getWidth).W) }
  def e[T <: Data](n: T): BitPat = { BitPat(lit(n)) }
  val N = BitPat.N()
  val Y = BitPat.Y()

  // This is an arbitrary set of signals that we want to associate to the
  // bitpattern for each instruction. 
  class ControlSignals extends Bundle {
    // The RISC-V instruction encoding type.
    // This indicates which set of operands are used.
    val enc   = InstEnc()

    // The execution unit associated with this instruction.
    // This indicates which opcode is used.
    val eu    = ExecutionUnit()

    var aluop = ALUOp() // An ALU opcode
    var bcuop = BCUOp() // A BCU opcode
    var lsuop = LSUOp() // An LSU opcode
  }

  // A map from bitpatterns to an associated set of control signals.
  val matches = Array(

    // Conditional branches compare RS1 and RS2, and compute a branch target
    // by adding the immediate to the PC
    BEQ     -> e(EU_BCU) ## e(ENC_B)   ## e(ALU_NOP)   ## e(BCU_EQ)   ## e(LSU_NOP),   
    BNE     -> e(EU_BCU) ## e(ENC_B)   ## e(ALU_NOP)   ## e(BCU_NEQ)  ## e(LSU_NOP),
    BLT     -> e(EU_BCU) ## e(ENC_B)   ## e(ALU_NOP)   ## e(BCU_LT)   ## e(LSU_NOP),
    BGE     -> e(EU_BCU) ## e(ENC_B)   ## e(ALU_NOP)   ## e(BCU_GE)   ## e(LSU_NOP),
    BLTU    -> e(EU_BCU) ## e(ENC_B)   ## e(ALU_NOP)   ## e(BCU_LTU)  ## e(LSU_NOP),
    BGEU    -> e(EU_BCU) ## e(ENC_B)   ## e(ALU_NOP)   ## e(BCU_GEU)  ## e(LSU_NOP),

    // Unconditionally add the immediate to the PC
    JAL     -> e(EU_BCU) ## e(ENC_J)   ## e(ALU_NOP)   ## e(BCU_JAL)  ## e(LSU_NOP),
    // Unconditionally add RS1 to the immediate (then to the PC)
    JALR    -> e(EU_BCU) ## e(ENC_I)   ## e(ALU_NOP)   ## e(BCU_JALR) ## e(LSU_NOP),

    // ALU operation (immediate + 0)
    LUI     -> e(EU_ALU) ## e(ENC_U)   ## e(ALU_LUI)   ## e(BCU_NOP)  ## e(LSU_NOP),
    // ALU operation (immediate + PC)
    AUIPC   -> e(EU_ALU) ## e(ENC_U)   ## e(ALU_AUIPC) ## e(BCU_NOP)  ## e(LSU_NOP),

    // ALU operations with RS1 and immediate
    ADDI    -> e(EU_ALU) ## e(ENC_I)   ## e(ALU_ADD)   ## e(BCU_NOP)  ## e(LSU_NOP),
    SLTI    -> e(EU_ALU) ## e(ENC_I)   ## e(ALU_SLT)   ## e(BCU_NOP)  ## e(LSU_NOP),
    SLTIU   -> e(EU_ALU) ## e(ENC_I)   ## e(ALU_SLTU)  ## e(BCU_NOP)  ## e(LSU_NOP),
    XORI    -> e(EU_ALU) ## e(ENC_I)   ## e(ALU_XOR)   ## e(BCU_NOP)  ## e(LSU_NOP),
    ORI     -> e(EU_ALU) ## e(ENC_I)   ## e(ALU_OR)    ## e(BCU_NOP)  ## e(LSU_NOP),
    ANDI    -> e(EU_ALU) ## e(ENC_I)   ## e(ALU_AND)   ## e(BCU_NOP)  ## e(LSU_NOP),

    // ALU operations with RS1 and RS2
    ADD     -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_ADD)   ## e(BCU_NOP)  ## e(LSU_NOP),
    SUB     -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_SUB)   ## e(BCU_NOP)  ## e(LSU_NOP),
    SLL     -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_SLL)   ## e(BCU_NOP)  ## e(LSU_NOP),
    SLT     -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_SLT)   ## e(BCU_NOP)  ## e(LSU_NOP),
    SLTU    -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_SLTU)  ## e(BCU_NOP)  ## e(LSU_NOP),
    XOR     -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_XOR)   ## e(BCU_NOP)  ## e(LSU_NOP),
    SRL     -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_SRL)   ## e(BCU_NOP)  ## e(LSU_NOP),
    SRA     -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_SRA)   ## e(BCU_NOP)  ## e(LSU_NOP),
    OR      -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_OR)    ## e(BCU_NOP)  ## e(LSU_NOP),
    AND     -> e(EU_ALU) ## e(ENC_R)   ## e(ALU_AND)   ## e(BCU_NOP)  ## e(LSU_NOP),

    LB      -> e(EU_LSU) ## e(ENC_I)   ## e(ALU_NOP)   ## e(BCU_NOP)  ## e(LSU_LB),
    LH      -> e(EU_LSU) ## e(ENC_I)   ## e(ALU_NOP)   ## e(BCU_NOP)  ## e(LSU_LH),
    LW      -> e(EU_LSU) ## e(ENC_I)   ## e(ALU_NOP)   ## e(BCU_NOP)  ## e(LSU_LW),

    // TODO
    //LBU     -> e(EU_LSU) ## e(ENC_I)   ## e(ALU_NOP)   ## e(BCU_NOP)  ## e(LSU_ILL),
    //LHU     -> e(EU_LSU) ## e(ENC_I)   ## e(ALU_NOP)   ## e(BCU_NOP)  ## e(LSU_ILL),

    SB      -> e(EU_LSU) ## e(ENC_S)   ## e(ALU_NOP)   ## e(BCU_NOP)  ## e(LSU_SB),
    SH      -> e(EU_LSU) ## e(ENC_S)   ## e(ALU_NOP)   ## e(BCU_NOP)  ## e(LSU_SH),
    SW      -> e(EU_LSU) ## e(ENC_S)   ## e(ALU_NOP)   ## e(BCU_NOP)  ## e(LSU_SW),
    //FENCE   -> List(),
    //FENCE_I -> List(),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = {
               e(EU_ILL) ## e(ENC_ILL) ## e(ALU_ILL)   ## e(BCU_ILL)  ## e(LSU_ILL) 
  }

  // Use some logic minimization (with ESPRESSO) to map an instruction to its 
  // associated set of control signals. You probably need 'espresso' in your 
  // $PATH (see https://github.com/chipsalliance/espresso), otherwise we fall 
  // back on using Quine-McCluskey (although maybe it doesn't matter too much 
  // for what we're interested doing here)?
  //
  // Chisel will complain about casting non-literal UInts to the various
  // ChiselEnum objects here, but I haven't thought hard enough about whether
  // or not we should actually care about this. 
  //
  def map_ctrl_signals(inst: UInt): ControlSignals = {
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

  val io = IO(new Bundle {
    val pc    = Input(UInt(32.W))
    val inst  = Input(UInt(32.W))
    val uop   = Output(new Uop)
  })

  val inst = io.inst
  val imm  = WireDefault(0.U(32.W))
  val ctrl = DecoderTable.map_ctrl_signals(inst)

  // Generate the immediate data for this micro-op (if any)
  switch (ctrl.enc) {
    is (ENC_I) { imm := Cat(Fill(20, inst(31)), inst(31, 20)) }
    is (ENC_S) { imm := Cat(Fill(20, inst(31)), inst(31, 25), inst(11, 7)) }
    is (ENC_B) { imm := Cat(Fill(19, inst(31)), inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)) }
    is (ENC_U) { imm := Cat(inst(31, 12), Fill(12, 0.U)) }
    is (ENC_J) { imm := Cat(Fill(11, inst(31)), inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)) }
  }

  io.uop.eu    := ctrl.eu
  io.uop.enc   := ctrl.enc
  io.uop.aluop := ctrl.aluop
  io.uop.bcuop := ctrl.bcuop
  io.uop.lsuop := ctrl.lsuop
  io.uop.rd    := inst(11, 7)
  io.uop.rs1   := inst(19, 15)
  io.uop.rs2   := inst(24, 20)
  io.uop.imm   := imm
  io.uop.pc    := io.pc

}


