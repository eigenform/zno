package zno.axi_periph.simple

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.amba.axi._
import zno.bus._

// This is the state machine associated with an [[AXISourceCtrl]] device.
object AXICtrlState extends ChiselEnum {
    val IDLE, READ_ADDR, READ_DATA, WRITE_ADDR, WRITE_DATA, WRITE_RESP = Value
}

// Device for translating transactions from a [[SimpleBusSource]] into
// transactions on an [[AXISourcePort]].
//
// NOTE: We're assuming for now that the underlying transactions will always 
// be a single transfer of 4 bytes. 
//
// TODO: You probably want some kind of counter to enforce some kind of hard
// limit on the number of cycles we can spend waiting for the AXI bus?
//
class AXISourceCtrl extends Module {
  val dbg_state = IO(Output(AXICtrlState()))

  // Outbound requests to an external AXI port
  val axi = IO(new AXISourcePort(32, 32))
  // Inbound requests from somewhere else in the design
  val bus = IO(new SimpleBusSink)

  // State machine registers
  val state       = RegInit(AXICtrlState.IDLE)
  dbg_state      := state
  val reg_req     = Reg(new SimpleBusReq)

  // AXI write address default assignments
  axi.waddr.valid      := false.B
  axi.waddr.bits.addr  := 0.U
  axi.waddr.bits.size  := 0.U
  axi.waddr.bits.len   := 0.U
  axi.waddr.bits.burst := AXIBurstType.FIXED
  axi.waddr.bits.id    := "b100001".U
  axi.waddr.bits.lock  := 0.U
  axi.waddr.bits.cache := 0.U
  axi.waddr.bits.prot  := 0.U
  axi.waddr.bits.qos   := 0.U

  // AXI write data default assignments
  axi.wdata.valid     := false.B
  axi.wdata.bits.last := true.B
  axi.wdata.bits.data := 0.U
  axi.wdata.bits.strb := 0.U

  // AXI read address default assignments
  axi.raddr.valid      := false.B
  axi.raddr.bits.addr  := 0.U
  axi.raddr.bits.size  := 0.U
  axi.raddr.bits.len   := 0.U
  axi.raddr.bits.burst := AXIBurstType.FIXED
  axi.raddr.bits.id    := "b100001".U
  axi.raddr.bits.lock  := 0.U
  axi.raddr.bits.cache := 0.U
  axi.raddr.bits.prot  := 0.U
  axi.raddr.bits.qos   := 0.U

  // AXI read data / AXI write response default assignments
  axi.rdata.ready    := false.B
  axi.wresp.ready    := false.B

  // SimpleBus default assignments
  bus.req.ready      := false.B
  bus.resp.valid     := false.B
  bus.resp.bits.data := 0.U
  bus.resp.bits.err  := SimpleBusErr.OKAY

  switch (state) {

    // Register a request from the bus and move to the next state.
    // The IDLE state *must* indicate that there are no pending transactions.
    is (AXICtrlState.IDLE) {
      bus.req.ready := true.B
      when (bus.req.fire) {
        reg_req := bus.req.bits
        state := Mux(bus.req.bits.wen, 
          AXICtrlState.WRITE_ADDR, 
          AXICtrlState.READ_ADDR
        )
      }
    }

    // Start an AXI read transaction.
    is (AXICtrlState.READ_ADDR) {
      axi.raddr.valid := true.B
      when (axi.raddr.fire) {
        axi.raddr.bits.addr := reg_req.addr
        axi.raddr.bits.size := 4.U
        axi.raddr.bits.len  := 1.U
        state := AXICtrlState.READ_DATA
      }
    }

    // Complete an AXI read transaction.
    //
    // NOTE: We're masking the contents of RDATA based on the requested width.
    // NOTE: We're only *assuming* RLAST is always asserted here. 
    //
    is (AXICtrlState.READ_DATA) {
      axi.rdata.ready := true.B
      when (axi.rdata.fire) {
        val bdata = bus.resp.bits.data
        val adata = axi.rdata.bits.data
        bus.resp.valid    := true.B
        bus.resp.bits.err := Mux( (axi.rdata.bits.resp =/= AXIRespType.OKAY), 
          SimpleBusErr.AXI,
          SimpleBusErr.OKAY
        )
        switch (reg_req.wid) {
          is (SimpleBusWidth.BYTE) { bdata := adata & 0xff.U }
          is (SimpleBusWidth.HALF) { bdata := adata & 0xffff.U }
          is (SimpleBusWidth.WORD) { bdata := adata }
        }
        reg_req := 0.U.asTypeOf(new SimpleBusReq)
        state   := AXICtrlState.IDLE
      }
    }

    // Start an AXI write transaction.
    //
    // NOTE: Ordering between write address and write data is not defined
    // in the AXI protocol. For now, let's specify that they should be
    // separated by a single cycle; maybe later we can think about presenting 
    // them in the same cycle?
    //
    is (AXICtrlState.WRITE_ADDR) {
      axi.waddr.valid := true.B
      when (axi.waddr.fire) {
        axi.waddr.bits.addr := reg_req.addr
        axi.waddr.bits.size := 4.U
        axi.waddr.bits.len  := 1.U
        state := AXICtrlState.WRITE_DATA
      }
    }

    // Drive the data associated with this write transaction.
    //
    // NOTE: We're masking the output on WDATA based on the requested width.
    // NOTE: WLAST should always be asserted.
    //
    is (AXICtrlState.WRITE_DATA) {
      axi.wdata.valid := true.B
      when (axi.wdata.fire) {
        axi.wdata.bits.last := true.B
        switch (reg_req.wid) {
          is (SimpleBusWidth.BYTE) { 
            axi.wdata.bits.data := reg_req.data & 0xff.U
            axi.wdata.bits.strb := "b0001".U 
          }
          is (SimpleBusWidth.HALF) { 
            axi.wdata.bits.data := reg_req.data & 0xffff.U
            axi.wdata.bits.strb := "b0011".U 
          }
          is (SimpleBusWidth.WORD) { 
            axi.wdata.bits.data := reg_req.data
            axi.wdata.bits.strb := "b1111".U
          }
        }
        state := AXICtrlState.WRITE_RESP
      }
    }

    // Complete an AXI write transaction.
    is (AXICtrlState.WRITE_RESP) {
      axi.wresp.ready := true.B
      when (axi.wresp.fire) {
        bus.resp.valid     := true.B
        bus.resp.bits.data := 0.U
        bus.resp.bits.err  := Mux( (axi.rdata.bits.resp =/= AXIRespType.OKAY), 
          SimpleBusErr.AXI,
          SimpleBusErr.OKAY
        )
        reg_req := 0.U.asTypeOf(new SimpleBusReq)
        state   := AXICtrlState.IDLE
      }
    }

  }
}



class SimpleSourceDeviceTop extends Module {
  val axi0_port_src = IO(new AXIExternalPort(32, 32))
    .suggestName("M_AXI0")
  val axi0_ctrl = Module(new AXISourceCtrl)
  axi0_port_src.connect_axi_source(axi0_ctrl.axi)
}


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
    val rdata   = Wire(new AXIReadDataChannel(32))
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




