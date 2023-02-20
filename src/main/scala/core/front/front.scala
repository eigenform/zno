
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
  val req  = Decoupled(UInt(p.xlen.W))
  val resp = Flipped(Decoupled(new FetchBlock))
}

// A block of fetched bytes. 
class FetchBlock(implicit p: ZnoParam) extends Bundle {
  // The address of this block
  val addr = UInt(p.xlen.W)
  // Fetched bytes in this block
  val data = Vec(p.fetch_words, UInt(p.xlen.W))

  def drive_defaults(): Unit = {
    this.addr := 0.U
    this.data := 0.U.asTypeOf(Vec(p.fetch_words, UInt(p.xlen.W)))
  }
}

// A fetch target address. 
//
// FIXME: You should probably decide whether this is an *aligned* address,
// or just some value of the program counter 
class FetchTarget(implicit p: ZnoParam) extends Bundle {
  val addr = UInt(p.xlen.W)
}

// The front-end of the machine.
class ZnoFrontcore(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    // Connection from the fetch unit to some external memory
    val ibus = new ZnoInstBusIO
    // Output fetch block to the FBQ
    val fbq_out = Decoupled(new FetchBlock)
    val ftq_in  = Flipped(Decoupled(new FetchTarget))

    val dbg_info = Output(Vec(p.id_width, new PredecodeInfo))
    val dbg_immd = Output(Vec(p.id_width, new RvImmData))
  })

  // Predecode units
  val predec = Seq.fill(p.id_width)(Module(new Predecoder))

  //val cfm = Module(new ControlFlowMap)

  // The "fetch target queue"
  val ftq = Module(new Queue(new FetchTarget, p.ftq_sz))


  // FIXME: FTQ input is provided from ports to this module, for now
  ftq.io.enq <> io.ftq_in
  //ftq.io.enq.valid := false.B
  //ftq.io.enq.bits  := 0.U.asTypeOf(new FetchTarget)

  // FIXME: FBQ output
  io.fbq_out.valid := false.B
  io.fbq_out.bits  := DontCare

  // --------------------------------------
  // Instruction bus access and FTQ dequeue

  val ibus_result_valid  = RegInit(false.B)

  ftq.io.deq.ready   := (io.ibus.req.ready && !ibus_result_valid) // FIXME?
  io.ibus.req.valid  := ftq.io.deq.valid
  io.ibus.req.bits   := ftq.io.deq.bits.addr //FIXME: assuming alignment?
  io.ibus.resp.ready := !ibus_result_valid

  val ftgt = RegEnable(ftq.io.deq.bits.addr, ftq.io.deq.fire)
  val fblk = RegEnable(io.ibus.resp.bits, io.ibus.resp.fire)
  // The next stage is valid when the response occurs
  when (!ibus_result_valid && io.ibus.resp.fire) {
    ibus_result_valid := true.B
  }

  val info_out = Wire(Vec(p.id_width, new PredecodeInfo))
  val immd_out = Wire(Vec(p.id_width, new RvImmData))
  for (i <- 0 to p.id_width-1) {
    predec(i).io.opcd := fblk.data(i)
    info_out(i) := predec(i).io.info
    immd_out(i) := predec(i).io.immd
    io.dbg_info(i) := info_out(i)
    io.dbg_immd(i) := immd_out(i)
  }

  printf("[ZnoFrontCore] ftgt = %x\n", ftgt)
  printf("[ZnoFrontCore] fblk.addr = %x\n", fblk.addr)
  printf("[ZnoFrontCore] ibus_result_valid  = %b\n", ibus_result_valid)



}


