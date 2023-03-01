
package zno.core

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.core.front._
import zno.core.mid._
import zno.core.back._

import zno.core.uarch._
import zno.common._

class ZnoCore extends Module {
  implicit val p = ZnoParam()

  val io = IO(new Bundle {
    // Connection from the frontend to some external memory
    val ibus  = new ZnoInstBusIO
  })

  // This seems like a reasonable way of slicing things up. 
  val fc = Module(new ZnoFrontcore)
  val mc = Module(new ZnoMidcore)
  val bc = Module(new ZnoBackcore)


  val opq = Module(new Queue(new zno.core.front.decode.DecodeBlock, p.opq_sz))

  fc.io.ibus    <> io.ibus
  fc.io.ftq_in  <> DontCare

  fc.io.opq     <> opq.io.enq
  mc.io.opq     <> opq.io.deq

}


