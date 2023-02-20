
package zno.core.front.fetch

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._
import zno.core.front._

// Instruction fetch unit. 
class FetchUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    // Connection to some instruction memory
    val ibus = new ZnoInstBusIO
    // Input fetch target
    val tgt = Flipped(Decoupled(new FetchTarget))
    // Output fetch block
    val fblk = Valid(new FetchBlock)
  })

  io.fblk.bits.drive_defaults()

  val pending = RegInit(false.B)

  val tmp = WireDefault(0.U.asTypeOf(new FetchTarget))
  tmp.addr := io.tgt.bits.addr
  val tgt = RegEnable(tmp, io.tgt.fire)

  // Cycle 0 
  // We're ready to accept a new fetch target when:
  //  - The instruction bus is guaranteed to be ready
  //  - We aren't already in the middle of a transaction
  io.tgt.ready := (!pending && io.ibus.req.ready)
  when (io.tgt.fire) {
    pending := true.B
    tgt     := io.tgt.bits
  }

  // Cycle 1 - N
  // Wait for the instruction bus to complete the transaction.
  io.ibus.req.valid := pending
  io.ibus.req.bits  := Mux(io.ibus.req.fire, tgt.addr, 0.U)

  val done = (pending && io.ibus.resp.valid)
  when (done) {
    pending := false.B
    io.fblk := io.ibus.resp
  }

}

