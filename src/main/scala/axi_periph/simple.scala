package zno.axi_periph.simple

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.amba.axi._

class SimpleDeviceTop extends Module {

  // Port for accepting transactions on the Zynq PS7 AXI bus
  val axi0_port_sink = IO(Flipped(new AXIExternalPort(32, 32)))
    .suggestName("S_AXI0")

  // Connect our logic to the AXI port
  val my_dev = Module(new AXIRegisterDevice)
  axi0_port_sink.connect_axi_sink(my_dev.io.axi);

}


// This is just a simple read-only memory addressible on the AXI bus. 
class AXIRegisterDevice extends Module {
  val io = IO(new Bundle {
    val axi = new AXISinkPort(32, 32)
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

  // Register for the read data response channel data. 
  val resp_reg  = RegInit({
    val rdata   = Wire(new AXIReadDataChannel(4, 32))
    rdata.data := 0.U
    rdata.resp := AXIRespType.OKAY
    rdata.id   := 0.U
    rdata.last := false.B
    rdata
  })

  // Indicates when a transaction is valid
  val valid_req = Wire(Bool())

  // Carries an index into the memory
  val offset    = Wire(UInt(4.W))

  // We're always ready to handle a read address in the IDLE state
  // We're always trasmitting valid read response data in the RESP state
  io.axi.raddr.ready := (state === TransferState.IDLE)
  io.axi.rdata.valid := (state === TransferState.RESP)

  // This memory is only addressible in 32-bit words.
  // For 16 elements, we only need to look at addr[5:2]. 
  offset := io.axi.raddr.bits.addr(5, 2)

  // Always [initially] drive these values on the read data channel.
  // These will be changed when we're actually responding with some data.
  io.axi.rdata.bits.data    := 0.U
  io.axi.rdata.bits.id      := 0.U
  io.axi.rdata.bits.resp    := AXIRespType.OKAY;
  io.axi.rdata.bits.last    := false.B

  // We only handle 4-byte single-transfer transactions.
  // The PROT/LOCK/CACHE/QOS signals are ignored.
  valid_req := (io.axi.raddr.bits.size === 4.U) && 
    (io.axi.raddr.bits.len === 1.U) && 
    (io.axi.raddr.bits.burst === AXIBurstType.FIXED)

  switch (state) {

    // Latch the appropriate response for this transaction.
    // The response is driven on the next cycle. 
    is (TransferState.IDLE) {
      when (io.axi.raddr.fire) {
        state     := TransferState.RESP
        when (valid_req) {
          resp_reg.data := mem(offset)
          resp_reg.resp := AXIRespType.OKAY
          resp_reg.id   := io.axi.raddr.bits.id
          resp_reg.last := true.B
        }.otherwise {
          resp_reg.data := 0.U
          resp_reg.resp := AXIRespType.SLVERR
          resp_reg.id   := io.axi.raddr.bits.id
          resp_reg.last := true.B
        }
      }
    }

    // Reply with the registered response.
    is (TransferState.RESP) {
      when (io.axi.rdata.fire) {
        state := TransferState.IDLE
        io.axi.rdata.bits := resp_reg
      }
    }
  }

  // Leave the write channels unconnected for now (i.e. do nothing)
  io.axi.waddr              := DontCare
  io.axi.wdata              := DontCare
  io.axi.wresp              := DontCare

}


