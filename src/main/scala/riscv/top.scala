
package zno.riscv.hart

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.riscv.isa._
import zno.riscv.uarch._
import zno.riscv.decode._
import zno.riscv.dbg._


class FetchPacket extends Bundle {
  val pc   = Output(UInt(32.W))
  val inst = Output(UInt(32.W))
}

class Hart extends Module {
  import ExecutionUnit._

  val dbg      = IO(new DebugOutput)
  val ibus     = IO(new DebugBus)
  val dbus     = IO(new DebugBus)

  val fetch    = Module(new FetchUnit)
  val decode   = Module(new DecodeUnit)
  val opsel    = Module(new OpSel)
  val rf       = Module(new RegisterFileFF)
  val alu      = Module(new ArithmeticLogicUnit)
  val bcu      = Module(new BranchCompareUnit)
  val lsu      = Module(new LoadStoreUnit)

  val uop      = decode.uop
  val stalled  = RegInit(true.B)
  val cyc      = RegInit(0x00000000.U(32.W))
  val pc       = RegInit(0x00000000.U(32.W))
  val op1_data = Wire(UInt(32.W))
  val op2_data = Wire(UInt(32.W))
  val rr_data  = Wire(UInt(32.W))

  dbg.pc      := pc
  dbg.cyc     := cyc

  // Connect the instruction/data bus to the appropriate functional units
  fetch.ibus     <> ibus
  lsu.dbus       <> dbus

  // Connect the register file read ports 
  opsel.rf_rp(0) <> rf.io.rp(0)
  opsel.rf_rp(1) <> rf.io.rp(1)

  // The program counter selects an instruction to fetch
  fetch.io.pc    := pc

  // The decode unit uses output data from the fetch unit
  decode.io.inst := fetch.io.inst
  decode.io.pc   := pc

  // Use the current instruction to generate the appropriate data signals
  opsel.uop      := uop
  op1_data       := opsel.op1_data
  op2_data       := opsel.op2_data

  // Connect control/data signals to the ALU
  alu.io.op   := uop.aluop
  alu.io.rs1  := op1_data
  alu.io.rs2  := op2_data

  // Connect control/data signals to the BCU
  bcu.io.op   := uop.bcuop
  bcu.io.rs1  := op1_data
  bcu.io.rs2  := op2_data
  bcu.io.imm  := uop.imm
  bcu.io.pc   := uop.pc

  // Connect control/data signals to the LSU
  lsu.io.op   := uop.lsuop
  lsu.io.rs1  := op1_data
  lsu.io.rs2  := op2_data
  lsu.io.imm  := uop.imm

  // If the current instruction has a register result, select the result data
  // from the appropriate execution unit and write to the register file
  rr_data          := MuxCase(0.U, Array(
    (uop.eu === EU_ALU) -> alu.io.res,
    (uop.eu === EU_LSU) -> lsu.io.data,
    (uop.eu === EU_BCU) -> (uop.pc + 4.U),
  ))
  rf.io.wp(0).en   := uop.rr && (uop.rd =/= 0.U)
  rf.io.wp(0).addr := uop.rd
  rf.io.wp(0).data := rr_data

  // If a branch is taken, write to the program counter.
  // Otherwise, move to the next-sequential instruction.
  pc  := Mux(bcu.io.ok, bcu.io.tgt, (pc + 4.U))
  cyc := cyc + 1.U
}

class OpSel extends Module {
  import Op1Sel._
  import Op2Sel._

  val rf_rp    = IO(Vec(2, Flipped(new RFReadPort)))
  val uop      = IO(Input(new Uop))
  val op1_data = IO(Output(UInt(32.W)))
  val op2_data = IO(Output(UInt(32.W)))

  rf_rp(0).addr := Mux((uop.op1 === OP1_RS1), uop.rs1, 0.U)
  rf_rp(1).addr := Mux((uop.op2 === OP2_RS2), uop.rs2, 0.U)

  op1_data := MuxCase(0.U, Array(
    (uop.op1 === OP1_RS1) -> rf_rp(0).data,
    (uop.op1 === OP1_PC)  -> uop.pc,
    (uop.op1 === OP1_NAN) -> 0.U,
  ))
  op2_data := MuxCase(0.U, Array(
    (uop.op2 === OP2_RS2) -> rf_rp(1).data,
    (uop.op2 === OP2_IMM) -> uop.imm,
    (uop.op2 === OP2_NAN) -> 0.U,
  ))
}

class FetchUnit extends Module {
  val ibus = IO(new DebugBus)
  val io = IO(new Bundle {
    val pc   = Input(UInt(32.W))
    val inst = Output(UInt(32.W))
  })
  ibus.wen   := false.B
  ibus.wdata := 0.U
  ibus.addr  := io.pc
  ibus.wid   := DebugWidth.WORD
  io.inst    := ibus.data
}

class ArithmeticLogicUnit extends Module {
  import ALUOp._

  val io = IO(new Bundle {
    val op  = Input(ALUOp())
    val rs1 = Input(UInt(32.W))
    val rs2 = Input(UInt(32.W))
    val res = Output(UInt(32.W))
    val err = Output(Bool())
  })
  val shamt = io.rs2(4, 0).asUInt

  io.res := 0.U
  io.err := false.B

  switch (io.op) {
    is (ALU_ILL)  { io.err := true.B }
    is (ALU_NOP)  { io.err := false.B }
    is (ALU_AND)  { io.res := io.rs1 & io.rs2 }
    is (ALU_OR)   { io.res := io.rs1 | io.rs2 }
    is (ALU_ADD)  { io.res := io.rs1 + io.rs2 }
    is (ALU_SUB)  { io.res := io.rs1 - io.rs2 }
    is (ALU_SRA)  { io.res := (io.rs1.asSInt >> shamt).asUInt }
    is (ALU_SLTU) { io.res := io.rs1 < io.rs2 }
    is (ALU_XOR)  { io.res := io.rs1 ^ io.rs2 }
    is (ALU_SRL)  { io.res := io.rs1 >> shamt }
    is (ALU_SLT)  { io.res := (io.rs1.asSInt < io.rs2.asSInt).asUInt }
    is (ALU_SLL)  { io.res := io.rs1 << shamt }
  }
  when (io.op =/= ALU_NOP) {
    printf("[ALU] rs1=%x rs2=%x res=%x\n", io.rs1, io.rs2, io.res)
  }
}

class BranchCompareUnit extends Module {
  import BCUOp._

  val io = IO(new Bundle {
    val pc  = Input(UInt(32.W))
    val op  = Input(BCUOp())
    val rs1 = Input(UInt(32.W))
    val rs2 = Input(UInt(32.W))
    val imm = Input(UInt(32.W))
    val tgt = Output(UInt(32.W))
    val ok  = Output(Bool())
    val err = Output(Bool())
  })

  io.ok  := false.B
  io.tgt := 0.U
  io.err := (io.op === BCU_ILL)

  // Compute the target address.
  // NOTE: JALR is the only exceptional case (the immediate is added to RS1).
  // NOTE: Just explicitly set to zero for BCU_NOP and BCU_ILL.
  val base = Mux(io.op === BCU_JALR, io.rs1, io.pc)
  io.tgt := MuxCase(
    ((base.asSInt + io.imm.asSInt).asUInt), 
    Array(
      (io.op === BCU_NOP) -> 0.U,
      (io.op === BCU_ILL) -> 0.U,
    )
  )

  // Evaluate the condition associated with this branch
  switch (io.op) {
    is (BCU_NOP)  { io.ok := false.B }
    is (BCU_ILL)  { io.ok := false.B }
    is (BCU_EQ)   { io.ok := (io.rs1 === io.rs2) }
    is (BCU_NEQ)  { io.ok := (io.rs1 =/= io.rs2) }
    is (BCU_LT)   { io.ok := (io.rs1.asSInt < io.rs2.asSInt) }
    is (BCU_LTU)  { io.ok := (io.rs1 < io.rs2) }
    is (BCU_GE)   { io.ok := (io.rs1.asSInt >= io.rs2.asSInt) }
    is (BCU_GEU)  { io.ok := (io.rs1 >= io.rs2) }
    is (BCU_JAL)  { io.ok := true.B }
    is (BCU_JALR) { io.ok := true.B }
  }
}

class LoadStoreUnit extends Module {
  import LSUOp._

  val dbus = IO(new DebugBus)
  val io = IO(new Bundle {
    val op   = Input(LSUOp())
    val rs1  = Input(UInt(32.W))
    val rs2  = Input(UInt(32.W))
    val imm  = Input(UInt(32.W))
    val data = Output(UInt(32.W))
  })

  val is_store = (io.op === LSU_SB || io.op === LSU_SH || io.op === LSU_SW);
  val is_nop   = (io.op === LSU_ILL || io.op === LSU_NOP)

  dbus.addr  := Mux(is_nop, 0.U, (io.rs1 + io.imm))
  dbus.wdata := Mux(is_store, io.rs2, 0.U)
  dbus.wen   := is_store
  dbus.wid   := MuxCase(DebugWidth.NONE, Array(
    (io.op === LSU_LB || io.op === LSU_LBU || io.op === LSU_SB) 
      -> DebugWidth.BYTE,
    (io.op === LSU_LH || io.op === LSU_LHU || io.op === LSU_SH) 
      -> DebugWidth.HALF,
    (io.op === LSU_LW || io.op === LSU_SW) 
      -> DebugWidth.WORD,
  ))
  io.data    := MuxCase(0.U, Array(
    (io.op === LSU_LB)  -> Cat(Fill(24, dbus.data(7)), dbus.data(7,0)),
    (io.op === LSU_LH)  -> Cat(Fill(16, dbus.data(15)), dbus.data(15,0)),
    (io.op === LSU_LW)  -> dbus.data,
    (io.op === LSU_LBU) -> Cat(Fill(24, 0.U), dbus.data(7,0)),
    (io.op === LSU_LHU) -> Cat(Fill(16, 0.U), dbus.data(15,0)),
  ))

  switch (io.op) {
    is (LSU_LB) { printf("ld byte addr=%x data=%x\n", dbus.addr, dbus.data) }
    is (LSU_LH) { printf("ld half addr=%x data=%x\n", dbus.addr, dbus.data) }
    is (LSU_LW) { printf("ld word addr=%x data=%x\n", dbus.addr, dbus.data) }
    is (LSU_SB) { printf("st byte addr=%x data=%x\n", dbus.addr, dbus.wdata) }
    is (LSU_SH) { printf("st half addr=%x data=%x\n", dbus.addr, dbus.wdata) }
    is (LSU_SW) { printf("st word addr=%x data=%x\n", dbus.addr, dbus.wdata) }
  }
}

class RFReadPort extends Bundle {
  val addr = Input(UInt(5.W))
  val data = Output(UInt(32.W))
}
class RFWritePort extends Bundle {
  val addr = Input(UInt(5.W))
  val data = Input(UInt(32.W))
  val en   = Input(Bool())
}


class RegisterFileBRAM extends Module {
  val io = IO(new Bundle {
    val rp = Vec(2, new RFReadPort)
    val wp = Vec(1, new RFWritePort)
  })
  val reg = Mem(32, UInt(32.W))
  for (wp <- io.wp) {
    when (wp.en && wp.addr =/= 0.U) { 
      reg.write(wp.addr, wp.data) 
    }
  }
  for (rp <- io.rp) {
    rp.data := Mux(rp.addr === 0.U, 0.U, reg.read(rp.addr))
  }
  printf("x1=%x x2=%x x3=%x x4=%x x5=%x x6=%x\n",
    reg.read(1.U), reg.read(2.U), reg.read(3.U), reg.read(4.U), reg.read(5.U), reg.read(6.U))
}


class RegisterFileFF extends Module {
  val io = IO(new Bundle {
    val rp = Vec(2, new RFReadPort)
    val wp = Vec(1, new RFWritePort)
  })
  val reg = Reg(Vec(32, UInt(32.W)))
  for (wp <- io.wp)
    when (wp.en && wp.addr =/= 0.U) { 
      reg(wp.addr) := wp.data 
    }
  for (rp <- io.rp)
    rp.data := Mux(rp.addr === 0.U, 0.U, reg(rp.addr))

  printf("x1=%x x2=%x x3=%x x4=%x x5=%x x6=%x\n",
    reg(1), reg(2), reg(3), reg(4), reg(5), reg(6))

}


