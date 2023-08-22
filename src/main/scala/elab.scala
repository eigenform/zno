package zno.elab

import chisel3._
import circt.stage.{ChiselStage, FirtoolOption}

object Emitter {
  def apply(gen: => RawModule) = {
    //val firtoolOpts: Array[String] = Array(
    //  "--disable-all-randomization",
    //  "--preserve-values=named",
    //  "--strip-debug-info",
    //  "--strip-fir-debug-info",
    //)


    (new ChiselStage).execute(
      Array("--target-dir", "rtl"),
      Seq(
        chisel3.stage.ChiselGeneratorAnnotation(() => gen),
        circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.CHIRRTL),
      )
    )

    (new ChiselStage).execute(
      Array("--target-dir", "rtl"),
      Seq(
        chisel3.stage.ChiselGeneratorAnnotation(() => gen),
        circt.stage.CIRCTTargetAnnotation(circt.stage.CIRCTTarget.SystemVerilog),
        FirtoolOption("--disable-all-randomization"),
      )
    )


  }
}


object Elaborate extends App {
  implicit val p = zno.core.uarch.ZnoParam()

  def print_width_bundle[T <: Bundle](ty: T) = {
    val name = ty.className
    val width = ty.getWidth
    //val width_kb = width.toFloat / 1024.toFloat
    println(f"${name}%16s: ${width}%dbit")
  }

  def print_width_arr[T <: Data](name: String, capacity: Int, ty: T) = {
    val width = ty.getWidth
    val size_b = width * capacity
    val size_kb = size_b.toFloat / 1024.toFloat
    val size_kbyte = size_kb.toFloat / 8.toFloat 
    println(f"${name}%16s: ${size_kb}%.2fkbit (${size_kbyte}%.2fkB)")
  }

  println("============================================")
  println("ZnoParam bundle sizes: ")
  print_width_bundle(new zno.core.uarch.FetchBlock())
  print_width_bundle(new zno.core.uarch.PredecodeBlock())
  print_width_bundle(new zno.core.uarch.DecodeBlock())
  print_width_bundle(new zno.core.uarch.MacroOp())
  print_width_bundle(new zno.core.uarch.IntUop())
  print_width_bundle(new zno.core.uarch.BrnUop())
  print_width_bundle(new zno.core.uarch.JmpUop())
  print_width_bundle(new zno.core.uarch.LdUop())
  print_width_bundle(new zno.core.uarch.StUop())

  println("")
  println("Approximate structure sizes: ")
  print_width_arr("FBQ", p.fbq.size, new zno.core.uarch.FetchBlock())
  print_width_arr("DBQ", p.dbq.size, new zno.core.uarch.DecodeBlock())
  print_width_arr("CFM tags", p.cfm.size, p.FetchBlockAddr())
  print_width_arr("CFM pdblk", p.cfm.size, new zno.core.uarch.PredecodeBlock())
  println("============================================")


  Emitter(new zno.core.front.ZnoFrontcore)
}



