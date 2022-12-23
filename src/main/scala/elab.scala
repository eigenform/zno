package zno.elab

import chisel3._

object VerilogEmitter extends App {
  implicit val p = zno.core.uarch.ZnoParam()
  val emitter_args = Array("-td", "rtl")

  //(new chisel3.stage.ChiselStage)
  //  .emitVerilog(new zno.axi_periph.simple.SimpleDeviceTop(), emitter_args)
  //(new chisel3.stage.ChiselStage)
  //  .emitVerilog(new zno.riscv.decode.DecodeUnit(), emitter_args)
  //(new chisel3.stage.ChiselStage)
  //  .emitVerilog(new zno.riscv.hart.Hart(), emitter_args)
  //(new chisel3.stage.ChiselStage)
  //  .emitVerilog(new zno.riscv.top.Top(), emitter_args)
  //(new chisel3.stage.ChiselStage)
  //  .emitVerilog(new zno.pipeline_tests.PipelineModelO3(), emitter_args)

  //(new chisel3.stage.ChiselStage)
  //  .emitVerilog(new zno.common.DecouplingFIFO(UInt(8.W), width=4, entries=32),
  //    emitter_args)
 
  //(new chisel3.stage.ChiselStage)
  //  .emitVerilog(new zno.core.dispatch.DispatchStage(), emitter_args)

  (new chisel3.stage.ChiselStage)
    .emitVerilog(new zno.core.front.decode.DecodeStage(), emitter_args)



}
