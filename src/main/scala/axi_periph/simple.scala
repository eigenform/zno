package zno.axi_periph.simple

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.amba.axi._

class SimpleDeviceTop extends Module {

  // Port for accepting transactions on the Zynq PS7 AXI bus
  val axi0_port_sink = IO(Flipped(new AXIExternalPort(4, 32)))
    .suggestName("S_AXI0")

  // Connect our logic to the AXI port
  val my_dev = Module(new AXIRegisterDevice)
  axi0_port_sink.connect_axi_sink(my_dev.io.axi);

}


// This is just a simple 16-word read-only memory addressible on the AXI bus. 
class AXIRegisterDevice extends Module {
  val io = IO(new Bundle {
    val axi = new AXISinkPort(4, 32)
  })

  object TransferState extends ChiselEnum {
    val IDLE, RESP = Value
  }

  val mem = VecInit(Seq(
    "h_00000000".U(32.W), "h_10001000".U(32.W), 
    "h_20002000".U(32.W), "h_30003000".U(32.W),
    "h_40004000".U(32.W), "h_50005000".U(32.W),
    "h_60006000".U(32.W), "h_70007000".U(32.W),
    "h_80008000".U(32.W), "h_90009000".U(32.W), 
    "h_a000a000".U(32.W), "h_b000b000".U(32.W),
    "h_c000c000".U(32.W), "h_d000d000".U(32.W), 
    "h_e000e000".U(32.W), "h_f000f000".U(32.W),
  ))

  // Register for the state machine
  val state     = RegInit(TransferState.IDLE)
  // Register for the response data
  val data_reg  = RegInit(0.U(32.W))

  // We're always ready to handle a read address in the IDLE state
  // We're always trasmitting valid read response data in the RESP state
  io.axi.raddr.ready := (state === TransferState.IDLE)
  io.axi.rdata.valid := (state === TransferState.RESP)

  // Always [initially] drive these values on the read data channel.
  // These will be changed when we're actually responding with some data.
  io.axi.rdata.bits.data    := 0.U
  io.axi.rdata.bits.id      := 0.U
  io.axi.rdata.bits.resp    := AXIRespType.OKAY;
  io.axi.rdata.bits.last    := false.B

  // Leave the write channels unconnected for now (i.e. do nothing)
  io.axi.waddr              := DontCare
  io.axi.wdata              := DontCare
  io.axi.wresp              := DontCare

  switch (state) {
    // Use the address to latch some data into the response register.
    is (TransferState.IDLE) {
      when (io.axi.raddr.fire) {
        state     := TransferState.RESP
        data_reg  := mem(io.axi.raddr.bits.addr)
      }
    }
    // Read the response register and reply with the data.
    is (TransferState.RESP) {
      when (io.axi.rdata.fire) {
        state := TransferState.IDLE
        io.axi.rdata.bits.data := data_reg
        io.axi.rdata.bits.resp := AXIRespType.OKAY
        io.axi.rdata.bits.id   := io.axi.raddr.bits.id
        io.axi.rdata.bits.last := true.B
      }
    }
  }
}


