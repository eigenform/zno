
package zno.core.front.predecode

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.common.bitpat._
import zno.riscv.isa._
import zno.core.uarch._
import zno.core.front.decode.BrnOp
import zno.core.front.decode.JmpOp

object BranchOp extends ChiselEnum {
  val PB_NONE = Value
  val PB_JAL  = Value
  val PB_JALR = Value
  val PB_B    = Value
}


// [PredecodeTable] output
class PredecodeInfo extends Bundle with AsBitPat {
  val brn_op  = BranchOp()
  val ifmt    = ImmFmt()
}

object PredecodeInfo {
  def apply(
    brn_op: BranchOp.Type, ifmt: ImmFmt.Type, 
  ): BitPat = {
    (new PredecodeInfo).Lit(
      _.brn_op   -> brn_op,   _.ifmt    -> ifmt,
    ).to_bitpat()
  }
}

object PredecodeTable {
  import Rv32iPattern._
  import ImmFmt._
  import BranchOp._

  val N = false.B
  val Y = true.B
  val X = DontCare

  // A map from an instruction bitpattern to a set of control signals.
  val matches = Array(
    JAL    -> PredecodeInfo(PB_JAL,  F_J),
    JALR   -> PredecodeInfo(PB_JALR, F_I),
    BEQ    -> PredecodeInfo(PB_B,    F_B),
    BGE    -> PredecodeInfo(PB_B,    F_B),
    BGEU   -> PredecodeInfo(PB_B,    F_B),
    BLT    -> PredecodeInfo(PB_B,    F_B),
    BLTU   -> PredecodeInfo(PB_B,    F_B),
    BNE    -> PredecodeInfo(PB_B,    F_B),
    ADDI   -> PredecodeInfo(PB_NONE, F_I),
    ANDI   -> PredecodeInfo(PB_NONE, F_I),
    ORI    -> PredecodeInfo(PB_NONE, F_I),
    SLLI   -> PredecodeInfo(PB_NONE, F_I),
    SLTI   -> PredecodeInfo(PB_NONE, F_I),
    SLTIU  -> PredecodeInfo(PB_NONE, F_I),
    SRAI   -> PredecodeInfo(PB_NONE, F_I),
    SRLI   -> PredecodeInfo(PB_NONE, F_I),
    XORI   -> PredecodeInfo(PB_NONE, F_I),
    LB     -> PredecodeInfo(PB_NONE, F_I),
    LH     -> PredecodeInfo(PB_NONE, F_I),
    LW     -> PredecodeInfo(PB_NONE, F_I),
    LBU    -> PredecodeInfo(PB_NONE, F_I),
    LHU    -> PredecodeInfo(PB_NONE, F_I),
    SB     -> PredecodeInfo(PB_NONE, F_S),
    SH     -> PredecodeInfo(PB_NONE, F_S),
    SW     -> PredecodeInfo(PB_NONE, F_S),
    AUIPC  -> PredecodeInfo(PB_NONE, F_U),
    LUI    -> PredecodeInfo(PB_NONE, F_U),
  )

  // The default set of control signals for instructions that do not match
  // any of the related bitpatterns.
  val default = {
              PredecodeInfo(PB_NONE, F_NA)
  }

  // NOTE: Chisel has some default logic minimzation, but I think this uses
  // chipsalliance/espresso if you have it in your $PATH. 
  def generate_decoder(inst: UInt): PredecodeInfo = {
    assert(inst.getWidth == 32)
    chisel3.util.experimental.decode.decoder(inst,
      chisel3.util.experimental.decode.TruthTable(
        this.matches, this.default
      )
    ).asTypeOf(new PredecodeInfo)
  }

}


// Instruction pre-decode logic. 
//
// This occurs in-between instruction fetch and instruction decode.
// This serves a few purposes: 
//
//  (a) By trying to discover branches as early as possible in the pipeline, 
//      this presumably makes it easier to validate a prediction before the
//      block reaches the back-end of the machine
//
//  (b) This seems like a clean way to distinguish "immediate decoding" from 
//      micro-op decoding?
//
//  (c) We can build a mask of valid instructions for each fetch block. 
//      
//
class Predecoder(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val opcd = Input(UInt(32.W))
    val info = Output(new PredecodeInfo)
    val immd = Output(Valid(new RvImmData))
  })

  val info   = PredecodeTable.generate_decoder(io.opcd)
  val immdec = Module(new ImmediateDecoder)
  immdec.io.ifmt := info.ifmt
  immdec.io.inst := io.opcd

  io.info := info
  io.immd := immdec.io.out

}

// Extracts an immediate value from a single 32-bit RISC-V instruction.
class ImmediateDecoder(implicit p: ZnoParam) extends Module {
  import ImmFmt._
  val io   = IO(new Bundle {
    val ifmt = Input(ImmFmt())
    val inst = Input(UInt(p.xlen.W))
    val out  = Output(Valid(new RvImmData))
  })
  val inst = io.inst

  val imm_i = Cat(Fill(9, 0.U), inst(30, 20))
  val imm_s = Cat(Fill(9, 0.U), inst(30, 25), inst(11, 7))
  val imm_b = Cat(Fill(9, 0.U), inst(7), inst(30, 25), inst(11, 8))
  val imm_u = inst(30, 12)
  val imm_j = Cat(inst(19, 12), inst(20), inst(30, 21))
  val imm   = MuxCase(0.U, Seq(
    (io.ifmt === F_I) -> imm_i,
    (io.ifmt === F_S) -> imm_s,
    (io.ifmt === F_B) -> imm_b,
    (io.ifmt === F_U) -> imm_u,
    (io.ifmt === F_J) -> imm_j,
  ))

  val is_zero = (imm === 0.U)
  val valid   = (io.ifmt =/= F_NA)

  val len     = Mux(is_zero, 0.U, PriorityEncoderHi(imm))
  val can_inl = (len <= p.pwidth.U)

  io.out.valid    := valid
  io.out.bits.ctl := MuxCase(UopImmCtl.NONE, Seq(
    (valid && is_zero)              -> UopImmCtl.ZERO,
    (valid && !is_zero && can_inl)  -> UopImmCtl.INL,
    (valid && !is_zero && !can_inl) -> UopImmCtl.ALC,
  ))
  io.out.bits.sign := inst(31)
  io.out.bits.imm  := imm
}


