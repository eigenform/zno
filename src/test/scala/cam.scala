
import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._

import chiseltest._
import chiseltest.experimental._
import chiseltest.simulator.WriteVcdAnnotation

import org.scalatest.flatspec.AnyFlatSpec
import zno.common._



class SimpleCAMSpec extends AnyFlatSpec with ChiselScalatestTester {
  object TagBits  { def apply(): UInt = UInt(5.W)  }
  object DataBits { def apply(): UInt = UInt(32.W) }

  class CAM extends SimpleCAM(tgen=TagBits(), dgen=DataBits(),
    num_entries=32, num_rp=2, num_wp=1)

  (new chisel3.stage.ChiselStage)
    .emitVerilog(new SimpleCAM(tgen=TagBits(), dgen=DataBits(), 
      num_entries=32, num_rp=2,num_wp=1), Array("-td", "/tmp/"))

  def reset_read(dut: CAM): Unit = {
    dut.io.rp(0).en.poke(false.B)
    dut.io.rp(0).tag.poke(0.U)
  }
  def reset_write(dut: CAM): Unit = {
    dut.io.wp(0).en.poke(false.B)
    dut.io.wp(0).tag.poke(0.U)
    dut.io.wp(0).cmd.poke(CAMWriteCmd.UPDATE)
    dut.io.wp(0).data.poke(0.U)
  }
  def drive_read(dut: CAM, tag: UInt): Unit = {
    dut.io.rp(0).en.poke(true.B)
    dut.io.rp(0).tag.poke(tag)
  }
  def expect_read(dut: CAM, valid: Bool, bits: UInt): Unit = {
    dut.io.rp(0).data.valid.expect(valid)
    dut.io.rp(0).data.bits.expect(bits)
  }
  def drive_write(dut: CAM, tag: UInt, data: UInt): Unit = {
    dut.io.wp(0).en.poke(true.B)
    dut.io.wp(0).tag.poke(tag)
    dut.io.wp(0).data.poke(data)
    dut.io.wp(0).cmd.poke(CAMWriteCmd.UPDATE)
  }
  def drive_invalidate(dut: CAM, tag: UInt): Unit = {
    dut.io.wp(0).en.poke(true.B)
    dut.io.wp(0).tag.poke(tag)
    dut.io.wp(0).data.poke(0.U)
    dut.io.wp(0).cmd.poke(CAMWriteCmd.INVALIDATE)
  }


  behavior of "SimpleCAM"
  it should "read a hit" in {
    test(new CAM).withAnnotations(Seq(WriteVcdAnnotation)) { dut => 
      implicit val clk: Clock = dut.clock
      drive_write(dut, "b11111".U, 0xdeadbeefL.U)
      clk.step()

      reset_write(dut)
      drive_read(dut, "b11111".U)
      expect_read(dut, true.B, 0xdeadbeefL.U)
    }
  }

  it should "handle misses" in {
    test(new CAM).withAnnotations(Seq(WriteVcdAnnotation)) { dut => 
      implicit val clk: Clock = dut.clock
      drive_read(dut, "b11111".U)
      expect_read(dut, false.B, 0.U)
      clk.step()
    }
  }

  it should "invalidate an entry" in {
    test(new CAM).withAnnotations(Seq(WriteVcdAnnotation)) { dut => 
      implicit val clk: Clock = dut.clock
      drive_write(dut, "b11111".U, 0xdeadbeefL.U)
      clk.step()

      reset_write(dut)
      drive_read(dut, "b11111".U)
      expect_read(dut, true.B, 0xdeadbeefL.U)
      reset_read(dut)
      clk.step()

      drive_invalidate(dut, "b11111".U)
      clk.step()

      reset_write(dut)
      drive_read(dut, "b11111".U)
      expect_read(dut, false.B, 0.U)
      clk.step()

    }
  }
}

