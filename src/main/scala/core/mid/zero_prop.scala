package zno.core.mid

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._


class ZeroPropagationUnit(implicit p: ZnoParam) extends Module { 
  val io = IO(new Bundle {
    // List of known-zero architectural registers
    val map_zero = Input(Vec(p.arf.size, Bool()))

    val in_mops  = Input(Vec(p.dec_win.size, new MacroOp))
    val out_mops = Output(Vec(p.dec_win.size, new MacroOp))
  })

  io.out_mops := io.in_mops
  for (idx <- 0 until p.dec_win.size) {
    val rs1  = io.in_mops(idx).rs1
    val rs2  = io.in_mops(idx).rs2
    val src1 = io.in_mops(idx).src1
    val src2 = io.in_mops(idx).src2
    val imm  = io.in_mops(idx).imm_ctl

    // FIXME: This assumes S_IMM is always decoded into the src2 field
    val rs1_zero = (rs1 === 0.U || io.map_zero(rs1))
    val rs2_zero = (rs2 === 0.U || io.map_zero(rs2))
    val imm_zero = (imm.storage === ImmStorageKind.ZERO)
    val have_rs1 = (src1 === SrcType.S_REG)
    val have_rs2 = (src2 === SrcType.S_REG)
    val have_imm = (src2 === SrcType.S_IMM)

    val src1_zero = (have_rs1 && rs1_zero)
    val src2_zero = (have_rs2 && rs2_zero) || (have_imm && imm_zero)
    val out_src1  = Mux(src1_zero, SrcType.S_ZERO, src1)
    val out_src2  = Mux(src2_zero, SrcType.S_ZERO, src2)
    io.out_mops(idx).src1 := out_src1
    io.out_mops(idx).src2 := out_src2
  }
}


