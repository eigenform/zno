package zno.core.mid

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._


// Determines if an integer operation can be squashed into a nonscheduled 
// "move" operation. 
class IntegerMovDetector(implicit p: ZnoParam) extends Module {
  import AluOp._
  import SrcType._
  val io = IO(new Bundle {
    val in_mop  = Input(new MacroOp)
    val out_mop = Output(new MacroOp)
  })
  val mop = io.in_mop

  val add = (mop.alu_op === A_ADD)
  val or  = (mop.alu_op === A_OR)
  val sub = (mop.alu_op === A_SUB)
  val xor = (mop.alu_op === A_XOR)
  val and = (mop.alu_op === A_AND)

  val rs1_rs2_eq = (mop.rs1 === mop.rs2)
  val reg_op = ((mop.src1 === SrcType.S_REG) && (mop.src2 === SrcType.S_REG))

  val src1_zero = (mop.src1 === SrcType.S_ZERO) 
  val src2_zero = (mop.src2 === SrcType.S_ZERO) 

  // FIXME: There are probably more cases you're forgetting ...
  val mov_src1 = ((add || or || sub) && (src2_zero))
  val mov_src2 = ((add || or)        && (src1_zero))
  val zero_and = (and && (src1_zero || src2_zero))
  val zero_xs  = ((xor || sub) && reg_op && rs1_rs2_eq)
  val zero_s1  = (mov_src1 && src1_zero)
  val zero_s2  = (mov_src2 && src2_zero)
  val mov_zero = (zero_and || zero_xs || zero_s1 || zero_s2)

  io.out_mop         := mop
  io.out_mop.mov_ctl := MuxCase(MovCtl.NONE, Seq(
    (mov_zero) -> MovCtl.ZERO,
    (mov_src1) -> MovCtl.SRC1,
    (mov_src2) -> MovCtl.SRC2,
  ))
}

// Unit containing all logic for detecting non-scheduled instructions. 
class NonscheduledOpDetector(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle { 
    val in_mops  = Input(Vec(p.dec_bw, new MacroOp))
    val out_mops = Output(Vec(p.dec_bw, new MacroOp))
  })

  val movdet = Seq.fill(p.dec_bw)(Module(new IntegerMovDetector))
  for (i <- 0 until p.dec_bw) {
    movdet(i).io.in_mop := io.in_mops(i)
    io.out_mops(i)      := movdet(i).io.out_mop
  }
}


