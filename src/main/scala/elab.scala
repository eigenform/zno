package zno.elab

import chisel3._

object VerilogEmitter extends App {
  val emitter_args = Array("-td", "rtl")
  (new chisel3.stage.ChiselStage)
    .emitVerilog(new zno.amba.axi.TestAXISinkTop(32, 32), emitter_args)
}
