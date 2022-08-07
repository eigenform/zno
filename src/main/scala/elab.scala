package zno.elab

import chisel3._

object VerilogEmitter extends App {
  val emitter_args = Array("-td", "rtl")
  (new chisel3.stage.ChiselStage)
    .emitVerilog(new zno.axi_periph.simple.SimpleDeviceTop(), emitter_args)
}
