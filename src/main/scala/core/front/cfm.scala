package zno.core.front

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._

//class CfmReadPort(implicit p: ZnoParam) extends Bundle {
//  val en   = Input(Bool())
//  val tag  = Input(p.FetchBlockAddress())
//  val data = Output(Valid(new CfmEntry))
//}
//
//class CfmWritePort(implicit p: ZnoParam) extends Bundle {
//  val en   = Input(Bool())
//  val tag  = Input(p.FetchBlockAddress())
//  val data = Input(new CfmEntry)
//}
//
//class CfmInstInfo(implicit p: ZnoParam) extends Bundle {
//  val btype = BranchType()
//}
//
//// An entry in the control-flow map.
//class CfmEntry(implicit p: ZnoParam) extends Bundle {
//  // Index of the first instruction in this fetch block 
//  val start_off = p.FetchBlockIndex()
//  // The fetch block address of the next basic block
//  val next_fblk = p.FetchBlockAddress()
//
//  // Bits describing each instruction in this block
//  val inst = Vec(p.fblk_sz_w, new CfmInstInfo)
//
//}


class ControlFlowMap(implicit p: ZnoParam) extends Module 
{
  val io = IO(new Bundle {
    // Impinging architectural control-flow event
    val arch_cfe = Flipped(Decoupled(new ArchitecturalCfEvent))
    // Impinging speculative control-flow event
    val spec_cfe = Flipped(Decoupled(new SpeculativeCfEvent))

    // A newly-predecoded block
    val pdblk   = Flipped(Decoupled(new PredecodeBlock))

    // Connection to a queue of fetch targets
    val ftgt = Decoupled(new FetchBlockAddr)
  })

  val tag_array = SyncReadMem(p.cfm.size, new FetchBlockAddr)

  // FIXME
  io.arch_cfe.ready := true.B
  io.spec_cfe.ready := true.B
  io.ftgt.valid := false.B
  io.ftgt.bits  := DontCare
  io.pdblk.ready := true.B


}






