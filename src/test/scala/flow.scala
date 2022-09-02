
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.experimental._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._
import org.scalatest.flatspec.AnyFlatSpec

class ValidFlowSpec extends AnyFlatSpec with ChiselScalatestTester {
  import zno.pipeline_tests.Instr
  import zno.pipeline_tests.InstType._

  behavior of "PipelineModelValid"
  it should "work" in {
    test(new zno.pipeline_tests.PipelineModelValid) { dut => 
      implicit val clk: Clock = dut.clock
      val prog = Seq(
        Instr(5, LIT, 1, 0, 0, 0x1),
        Instr(5, LIT, 2, 0, 0, 0x2),
        Instr(5, ADD, 3, 1, 2, 0x0),
        Instr(5),
        Instr(5),
        Instr(5),
        Instr(5),
      )
      for (i <- prog) {
        println("----------------------------------------")
        dut.req_in.poke(i)
        clk.step()
      }
    }
  }
}


class O3FlowSpec extends AnyFlatSpec with ChiselScalatestTester {
  import zno.pipeline_tests.Instr
  import zno.pipeline_tests.InstType._
  behavior of "PipelineModelO3"
  it should "work" in {
    test(new zno.pipeline_tests.PipelineModelO3) { 
      dut => 
      implicit val clk: Clock = dut.clock
      val prog = Seq(
        Instr(5, LIT, 1, 0, 0, 0x1),
        Instr(5, LIT, 2, 0, 0, 0x2),
        Instr(5, ADD, 3, 1, 2, 0x0),
        Instr(5, ADD, 4, 1, 3, 0x0),
        Instr(5, ADD, 1, 2, 4, 0x0),
        Instr(5, ADD, 1, 0, 1, 0x0),
        Instr(5),
        Instr(5),
        Instr(5),
        Instr(5),
        Instr(5),
        Instr(5),
        Instr(5),
        Instr(5),
        Instr(5),
        Instr(5),
        Instr(5),
      )
      var pc  = 0
      var cyc = 0

      while (cyc < 16) {
        println("----------------------------------------")
        val stalled = dut.stall.peek().litToBoolean
        if (stalled) {
          dut.req_in.poke(Instr(5))
        } else {
          dut.req_in.poke(prog(pc))
          pc += 1
        }

        clk.step()
        cyc += 1
      }


      //for (i <- prog) {
      //  println("----------------------------------------")
      //  dut.req_in.poke(i)
      //  clk.step()
      //}
    }
  }
}


