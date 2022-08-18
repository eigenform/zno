
import chisel3._
import chiseltest._
import chiseltest.experimental._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.experimental.BundleLiterals._

import zno.bus._
import zno.amba.axi._
import zno.axi_periph.simple._

class AXISourceSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AXISourceCtrl"

  it should "handle read transactions" in {
    test(new zno.axi_periph.simple.AXISourceCtrl) { dut => 
      val ex = List( 
        (SimpleBusWidth.WORD, 0xdeadbeefL.U),
        (SimpleBusWidth.HALF, 0x0000beefL.U),
        (SimpleBusWidth.BYTE, 0x000000efL.U),
      )

      for ((req_width, expected_value) <- ex) {
        // Assume the AXI read address channel is ready.
        dut.axi.raddr.ready.poke(true.B)

        // Send a request from the internal bus.
        dut.dbg_state.expect(AXICtrlState.IDLE)
        dut.bus.req.valid.poke(true.B)
        dut.bus.req.bits.addr.poke(0x00001000.U)
        dut.bus.req.bits.wid.poke(req_width)
        dut.bus.req.bits.wen.poke(false.B)

        // Check the AXI read address channel.
        // Assume the AXI bus presents a response in the same cycle.
        // Drive the AXI read data channel.
        dut.clock.step(1)
        dut.dbg_state.expect(AXICtrlState.READ_ADDR)
        dut.axi.raddr.bits.addr.expect(0x00001000.U)
        dut.axi.raddr.bits.size.expect(4.U)
        dut.axi.raddr.bits.len.expect(1.U)
        dut.axi.rdata.valid.poke(true.B)
        dut.axi.rdata.bits.data.poke(0xdeadbeefL.U)
        dut.axi.rdata.bits.id.poke("b111111".U)
        dut.axi.rdata.bits.last.poke(true.B)
        dut.axi.rdata.bits.resp.poke(AXIRespType.OKAY)

        // Check the response on the internal bus
        dut.clock.step(1)
        dut.dbg_state.expect(AXICtrlState.READ_DATA)
        dut.bus.resp.valid.expect(true.B)
        dut.bus.resp.bits.err.expect(SimpleBusErr.OKAY)
        dut.bus.resp.bits.data.expect(expected_value)

        // The response is invalidated on the next cycle when returning
        // to the IDLE state.
        dut.clock.step(1)
        dut.bus.resp.valid.expect(false.B)
      }
    }
  }

  it should "handle write transactions" in {
    test(new zno.axi_periph.simple.AXISourceCtrl) { dut => 
      val ex = List( 
        (SimpleBusWidth.WORD, "b1111".U, 0xdeadbeefL.U),
        (SimpleBusWidth.HALF, "b0011".U, 0x0000beefL.U),
        (SimpleBusWidth.BYTE, "b0001".U, 0x000000efL.U),
      )
      for ((req_width, expected_strb, expected_value) <- ex) {

        // Assume the AXI write address and write data channels are ready
        dut.axi.waddr.ready.poke(true.B)
        dut.axi.wdata.ready.poke(true.B)

        // Send a request from the internal bus.
        dut.dbg_state.expect(AXICtrlState.IDLE)
        dut.bus.req.valid.poke(true.B)
        dut.bus.req.bits.addr.poke(0x00001000.U)
        dut.bus.req.bits.wid.poke(req_width)
        dut.bus.req.bits.wen.poke(true.B)
        dut.bus.req.bits.data.poke(0xdeadbeefL.U)

        // Check the AXI write address channel.
        dut.clock.step(1)
        dut.dbg_state.expect(AXICtrlState.WRITE_ADDR)
        dut.axi.waddr.bits.addr.expect(0x00001000.U)
        dut.axi.waddr.bits.size.expect(4.U)
        dut.axi.waddr.bits.len.expect(1.U)
        dut.axi.wdata.valid.expect(false.B)

        val waddr = dut.axi.waddr.bits.addr.peek().litValue
        val wsize = dut.axi.waddr.bits.size.peek().litValue
        val wlen  = dut.axi.waddr.bits.len.peek().litValue

        // Check the AXI write data channel
        dut.clock.step(1)
        dut.dbg_state.expect(AXICtrlState.WRITE_DATA)
        dut.axi.wdata.valid.expect(true.B)
        dut.axi.wdata.bits.data.expect(expected_value)
        dut.axi.wdata.bits.strb.expect(expected_strb)
        dut.axi.wdata.bits.last.expect(true.B)

        val wdata = dut.axi.wdata.bits.data.peek().litValue
        val wstrb = dut.axi.wdata.bits.strb.peek().litValue
        val wlast = dut.axi.wdata.bits.last.peek().litValue


        // Drive the AXI write response channel.
        // Check the response on the internal bus.
        dut.clock.step(1)
        dut.dbg_state.expect(AXICtrlState.WRITE_RESP)
        dut.axi.wresp.valid.poke(true.B)
        dut.axi.wresp.bits.resp.poke(AXIRespType.OKAY)
        dut.axi.wresp.bits.id.poke("b111111".U)
        dut.bus.resp.valid.expect(true.B)
        dut.bus.resp.bits.err.expect(SimpleBusErr.OKAY)
        dut.bus.resp.bits.data.expect(0.U)

        // The response is invalidated on the next cycle when returning
        // to the IDLE state.
        dut.clock.step(1)
        dut.bus.resp.valid.expect(false.B)
      }
    }
  }


}

