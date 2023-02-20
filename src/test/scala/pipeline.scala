
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._
import org.scalatest.flatspec.AnyFlatSpec

import chiseltest.simulator.WriteVcdAnnotation

class PipelineTest1 extends Module {
}

class PipelineSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "PipelineTest1"
  it should "work" in {
    test(new PipelineTest1).withAnnotations(Seq(WriteVcdAnnotation)) { dut => 
      implicit val clk: Clock = dut.clock
    }
  }
}


