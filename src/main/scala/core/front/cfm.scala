package zno.core.front.cfm

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._

class CfmReadPort(implicit p: ZnoParam) extends Bundle {
  val en   = Input(Bool())
  val tag  = Input(p.FetchBlockAddress())
  val data = Output(Valid(new CfmEntry))
}

class CfmWritePort(implicit p: ZnoParam) extends Bundle {
  val en   = Input(Bool())
  val tag  = Input(p.FetchBlockAddress())
  val data = Input(new CfmEntry)
}

class CfmInstInfo(implicit p: ZnoParam) extends Bundle {
  val btype = BranchType()
}

// An entry in the control-flow map.
class CfmEntry(implicit p: ZnoParam) extends Bundle {
  // Index of the first instruction in this fetch block 
  val start_off = UInt(log2Ceil(p.fetch_words).W)
  // The fetch block address of the next basic block
  val next_fblk = p.FetchBlockAddress()

  // Bits describing each instruction in this block
  val inst = Vec(p.fetch_words, new CfmInstInfo)

}

class ControlFlowMap(
  num_rp: Int = 2, 
  num_wp: Int = 1
)(implicit p: ZnoParam) extends Module 
{
  val io = IO(new Bundle {
    val rp = Vec(num_rp, new CfmReadPort)
    val wp = Vec(num_wp, new CfmWritePort)
  })

  val map_v    = RegInit(Vec(p.cfm_sz, false.B))
  val map_data = Mem(p.cfm_sz, new CfmEntry)
  val map_tags = Mem(p.cfm_sz, p.FetchBlockAddress())

  //for (ridx <- 0 until num_rp) {
  //  val hit_arr  = (0 until p.cfm_sz).map(i => io.rp.tag === map_tags(i))
  //}
}


