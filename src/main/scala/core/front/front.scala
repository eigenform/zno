
package zno.core.front

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._

import zno.core.front.cfm._
import zno.core.front.fetch._
import zno.core.front.predecode._
import zno.core.front.decode._

// Connection between the frontcore and instruction memories
class ZnoInstBusIO(implicit p: ZnoParam) extends Bundle {
  val req  = Decoupled(p.FetchBlockAddress())
  val resp = Flipped(Decoupled(new FetchBlock))
}

// A block of fetched bytes. 
class FetchBlock(implicit p: ZnoParam) extends Bundle {
  // The address of this block
  val addr = p.FetchBlockAddress()
  // Fetched bytes in this block
  val data = p.FetchBlockData()
  // Default values
  def drive_defaults(): Unit = {
    this.addr := 0.U
    this.data := 0.U.asTypeOf(p.FetchBlockData())
  }
}


class PredictionChecker(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
  })
}

// The front-end of the machine.
class ZnoFrontcore(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    // Connection to instruction memory
    val ibus = new ZnoInstBusIO

    // FIXME: Input fetch target address feeding the FTQ
    val ftq_in  = Flipped(Decoupled(p.FetchBlockAddress()))

    val opq = Decoupled(new DecodeBlock)

    val dbg_pd_uop = Output(Vec(p.id_width, new PdUop))
  })

  // "Fetch target queue" 
  // Holds fetch block addresses until the fetch unit is ready.
  // FIXME: FTQ input is driven outside this module
  val ftq = Module(new Queue(p.FetchBlockAddress(), p.ftq_sz))
  ftq.io.enq <> io.ftq_in

  // "Fetch block queue"
  // Holds fetch blocks until the decode unit is ready
  val fbq = Module(new Queue(new FetchBlock, p.fbq_sz))

  // "Instruction fetch unit" 
  // Fetch targets flow into the IFU from the FTQ.
  val ifu = Module(new FetchUnit)
  ifu.io.tgt_in <> ftq.io.deq
  ifu.io.ibus   <> io.ibus

  // When an IFU response is available, the fetch block is (a) driven to the 
  // FBQ, and (b) captured by registers used for predecode. 

  val s1_vld = RegNext(ifu.io.blk_out.fire)
  val s1_reg = RegEnable(ifu.io.blk_out.bits, ifu.io.blk_out.fire)
  fbq.io.enq.valid     := ifu.io.blk_out.valid
  fbq.io.enq.bits      := ifu.io.blk_out.bits
  ifu.io.blk_out.ready := fbq.io.enq.ready && !s1_vld

  // "Predecode units"
  // Predecode each instruction word in a fetch block. 

  val pdu  = Seq.fill(p.id_width)(Module(new Predecoder))
  val s2_reg = RegInit(0.U.asTypeOf(Vec(p.id_width, new PdUop)))
  val s2_vld = RegNext(s1_vld)
  for (i <- 0 until p.id_width) {
    pdu(i).io.opcd := s1_reg.data(i)
    s2_reg(i)   := pdu(i).io.out
  }
  io.dbg_pd_uop := s2_reg

  // "Decode unit"
  // Decoded instructions flow into the OPQ (outside this module)
  val idu = Module(new DecodeUnit)
  idu.io.fbq <> fbq.io.deq
  idu.io.opq <> io.opq

}


