import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum
import chisel3.experimental.BundleLiterals._

import chiseltest._
import chiseltest.experimental._
import chiseltest.simulator.WriteVcdAnnotation

import org.scalatest.flatspec.AnyFlatSpec

import zno.sim._
import zno.common._
import zno.core.front._
import zno.core.uarch._

class ZnoFrontcoreSpec extends AnyFlatSpec with ChiselScalatestTester {
  implicit val p = ZnoParam()

  behavior of "ZnoFrontcore"
  it should "work" in {
    test(new ZnoFrontcore).withAnnotations(Seq(WriteVcdAnnotation)) { 
      dut => 
      implicit val clk: Clock = dut.clock
      var h = new SimHarness
      var rom = new SimROM("tb/zno_front.text.bin")

      // We're always ready to take a [CfmBlock]
      def sim_midcore(): Unit = {
        dut.io.cfmblk.ready.poke(true.B)
      }

      h.init_reg("go", false)
      h.init_reg("addr", 0)
      h.init_reg("cyc", 0)
      def sim_ibus(): Unit = {
        // defaults
        dut.io.ibus.resp.valid.poke(false.B)
        dut.io.ibus.resp.bits.addr.poke(0)
        dut.io.ibus.resp.bits.data(0).poke(0)
        dut.io.ibus.resp.bits.data(1).poke(0)
        dut.io.ibus.resp.bits.data(2).poke(0)
        dut.io.ibus.resp.bits.data(3).poke(0)
        dut.io.ibus.resp.bits.data(4).poke(0)
        dut.io.ibus.resp.bits.data(5).poke(0)
        dut.io.ibus.resp.bits.data(6).poke(0)
        dut.io.ibus.resp.bits.data(7).poke(0)

        val req_vld    = dut.io.ibus.req.valid.peekBoolean()
        val req_addr   = dut.io.ibus.req.bits.peekInt()
        val resp_ready = dut.io.ibus.resp.ready.peekBoolean()
        val req_rdy = h.read_reg_bool("go")
        dut.io.ibus.req.ready.poke(!req_rdy)
        if (req_vld && !req_rdy) {
          h.write_reg("go", true)
          h.write_reg("addr", req_addr)
          h.write_reg("cyc", h.cycle)
        }
        if (h.read_reg_bool("go")) {
          if (h.cycle >= h.read_reg_int("cyc") + 0) {
            println("bus transaction driving results ...")
            dut.io.ibus.resp.valid.poke(true.B)
            val addr = h.read_reg_int("addr")
            val rom_addr = addr << p.fblk.byteIdxWidth

            dut.io.ibus.resp.bits.addr.poke(addr)
            dut.io.ibus.resp.bits.data(0).poke(rom.read32(rom_addr | 0x00))
            dut.io.ibus.resp.bits.data(1).poke(rom.read32(rom_addr | 0x04))
            dut.io.ibus.resp.bits.data(2).poke(rom.read32(rom_addr | 0x08))
            dut.io.ibus.resp.bits.data(3).poke(rom.read32(rom_addr | 0x0c))
            dut.io.ibus.resp.bits.data(4).poke(rom.read32(rom_addr | 0x10))
            dut.io.ibus.resp.bits.data(5).poke(rom.read32(rom_addr | 0x14))
            dut.io.ibus.resp.bits.data(6).poke(rom.read32(rom_addr | 0x18))
            dut.io.ibus.resp.bits.data(7).poke(rom.read32(rom_addr | 0x1c))

            // When DUT drives ready we assume the results are captured
            if (resp_ready) {
              println("bus transaction done")
              h.write_reg("go", false)
              h.write_reg("addr", 0)
              h.write_reg("cyc", 0)
            }
          } else {
            println("bus transaction in progress..")
          }
        }
      }


      for (_ <- 1 to 32) {
        h.print_state()
        sim_midcore()
        sim_ibus()
        h.step()
      }


      //var cyc = 0
      //while (cyc < 16) {
      //  clk.step()
      //  cyc += 1
      //}
    }
  }

}


