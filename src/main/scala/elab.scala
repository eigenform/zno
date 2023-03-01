package zno.elab

import chisel3._

object VerilogEmitter extends App {
  implicit val p = zno.core.uarch.ZnoParam()
  val emitter_args = Array("-td", "rtl")

  println("ZNO Core parameters")
  println("===========================")
  println("num_areg:    " + p.num_areg)
  println("num_preg:    " + p.num_preg)
  println("rob_sz:      " + p.rob_sz)
  println("id_width:    " + p.id_width)
  println("fetch_bytes: " + p.fetch_bytes)

  println("Uop          bits: " + new zno.core.uarch.Uop().getWidth)
  println("IntUop       bits: " + new zno.core.uarch.IntUop().getWidth)
  println("StUop        bits: " + new zno.core.uarch.StUop().getWidth)
  println("LdUop        bits: " + new zno.core.uarch.LdUop().getWidth)
  println("BrnUop       bits: " + new zno.core.uarch.BrnUop().getWidth)
  println("JmpUop       bits: " + new zno.core.uarch.JmpUop().getWidth)
  println("UopCtl       bits: " + new zno.core.front.decode.UopCtl().getWidth)
  println("PdUop        bits: " + new zno.core.front.predecode.PdUop().getWidth)

  //println("ZnoUop bits: " + zno.core.uarch.ZnoUop.width)
  println("===========================")

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
 
  //(new chisel3.stage.ChiselStage)
  //  .emitVerilog(new zno.core.front.decode.UopDecoder(), emitter_args)

  //(new chisel3.stage.ChiselStage)
  //  .emitVerilog(new zno.core.front.decode.DecodeStage(), emitter_args)

  (new chisel3.stage.ChiselStage)
    .emitVerilog(new zno.core.front.ZnoFrontcore(), emitter_args)

  (new chisel3.stage.ChiselStage)
    .emitVerilog(new zno.core.ZnoCore(), emitter_args)




}
