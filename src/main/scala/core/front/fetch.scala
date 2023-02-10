
package zno.core.front.fetch

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._


class FetchUnit(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    // Connection to some instruction memory
    val ibus = new ZnoFetchIO
    // Branch target for redirecting instruction fetch
    val brn_tgt = Flipped(Valid(UInt(p.xlen.W)))
    // Output fetch block
    val fbq_enq = Decoupled(new FbqEntry)
  })

  // The current fetch PC 
  val pc = RegInit(0x00000000.U(p.xlen.W))

  // Transactions with the bus only begin when we determine that the FBQ 
  // is able to accept a new entry 
  val fbq_ready  = RegNext(io.fbq_enq.ready)
  val ibus_ready = RegNext(io.ibus.req.ready)

  io.ibus.req.valid := (fbq_ready && ibus_ready)
  io.ibus.req.bits := 0.U
  when (io.ibus.req.fire) {
    io.ibus.req.bits := pc
  }

  // Transactions are complete when the bus signals 'valid'.
  // When a transaction is completed, send the result to the FBQ
  // and capture the value of the next fetch PC.
  io.fbq_enq.valid := io.ibus.resp.valid
  io.fbq_enq.bits.drive_defaults()
  when (io.ibus.resp.valid) {
    io.fbq_enq.bits  := io.ibus.resp.bits
    pc := Mux(io.brn_tgt.valid, 
      io.brn_tgt.bits, 
      (pc + p.fetch_bytes.U)
    )
  }

}

