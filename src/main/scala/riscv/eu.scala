package zno.riscv.eu

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import zno.riscv.isa._
import zno.riscv.uarch._
import zno.riscv.dbg._

class AddressGenerationUnit extends Module {
  import LSUOp._
  val req = IO(Flipped(Valid(new AGUPacket)))
  val res = IO(Valid(new LSUPacket))

  res.valid     := (req.valid && req.bits.op =/= LSU_ILL)
  res.bits.op   := LSU_NOP
  res.bits.pc   := 0.U
  res.bits.rd   := 0.U
  res.bits.addr := 0.U
  res.bits.src  := 0.U

  printf("[AGU] valid=%d pc=%x\n", req.valid, req.bits.pc)

  when (res.valid) {
    res.bits.op   := req.bits.op
    res.bits.pc   := req.bits.pc
    res.bits.rd   := req.bits.rd
    res.bits.addr := (req.bits.rs1 + req.bits.imm)
    res.bits.src  := req.bits.rs2
  }
}

// NOTE: Operand select occurs when building an ALUPacket 
class ArithmeticLogicUnit extends Module {
  import ALUOp._
  val req = IO(Flipped(Valid(new ALUPacket)))
  val res = IO(Valid(new RRPacket))
  val tmp = WireDefault(0.U(32.W))

  val ok = (req.valid && req.bits.op =/= ALU_ILL)

  res.valid     := ok
  res.bits.drive_defaults()

  val op    = req.bits.op
  val x     = req.bits.x
  val y     = req.bits.y
  val shamt = y(4, 0).asUInt

  printf("[ALU] valid=%d pc=%x\n", req.valid, req.bits.pc)

  when (ok) {
    switch (op) {
      is (ALU_AND)  { tmp := x & y }
      is (ALU_OR)   { tmp := x | y }
      is (ALU_ADD)  { tmp := x + y }
      is (ALU_SUB)  { tmp := x - y }
      is (ALU_SRA)  { tmp := (x.asSInt >> shamt).asUInt }
      is (ALU_SLTU) { tmp := x < y }
      is (ALU_XOR)  { tmp := x ^ y }
      is (ALU_SRL)  { tmp := x >> shamt }
      is (ALU_SLT)  { tmp := (x.asSInt < y.asSInt).asUInt }
      is (ALU_SLL)  { tmp := x << shamt }
    }
    res.bits.rd   := req.bits.rd
    res.bits.pc   := req.bits.pc
    res.bits.data := tmp
    when (op =/= ALU_NOP) {
      printf("[ALU] x=%x y=%x res=%x\n", x, y, tmp)
    }
  }
}

class BranchCompareUnit extends Module {
  import BCUOp._
  val req = IO(Flipped(Valid(new BCUPacket)))
  val res = IO(Valid(new BRPacket))

  val op   = req.bits.op
  val rd   = req.bits.rd
  val rs1  = req.bits.rs1
  val rs2  = req.bits.rs2
  val imm  = req.bits.imm
  val pc   = req.bits.pc

  val base = Mux((op === BCU_JALR), rs1, pc)
  val ok   = (req.valid && op =/= BCU_ILL)

  res.valid      := ok
  res.bits.drive_defaults()

  printf("[BCU] valid=%d pc=%x\n", req.valid, req.bits.pc)

  when (ok) {
    res.bits.pc   := pc
    res.bits.rd   := rd
    res.bits.link := (op === BCU_JAL || op === BCU_JALR)
    res.bits.tgt  := (base.asSInt + imm.asSInt).asUInt
    res.bits.ok   := MuxCase(false.B, Array(
      (op === BCU_JAL)  -> true.B,
      (op === BCU_JALR) -> true.B,
      (op === BCU_EQ)   -> (rs1 === rs2),
      (op === BCU_NEQ)  -> (rs1 =/= rs2),
      (op === BCU_LT)   -> (rs1.asSInt < rs2.asSInt),
      (op === BCU_LTU)  -> (rs1 < rs2),
      (op === BCU_GE)   -> (rs1.asSInt >= rs2.asSInt),
      (op === BCU_GEU)  -> (rs1 >= rs2),
    ))
  }
}

class LoadStoreUnit extends Module {
  import LSUOp._
  val dbus = IO(new DebugBus)
  val req  = IO(Flipped(Valid(new LSUPacket)))
  val res  = IO(Valid(new RRPacket))

  val op   = req.bits.op
  val addr = req.bits.addr
  val src  = req.bits.src
  val rd   = req.bits.rd
  val pc   = req.bits.pc

  val ok       = (req.valid && req.bits.op =/= LSU_ILL)
  val is_store = (op === LSU_SB  || op === LSU_SH || op === LSU_SW);
  val is_nop   = (op === LSU_ILL || op === LSU_NOP)

  res.valid := ok
  res.bits.drive_defaults()
  dbus.drive_defaults()

  printf("[LSU] valid=%d pc=%x\n", req.valid, req.bits.pc)
  
  when (ok && !is_nop) {
    dbus.ren   := true.B
    dbus.addr  := addr
    dbus.wdata := Mux(is_store, src, 0.U)
    dbus.wen   := is_store
    dbus.wid   := MuxCase(DebugWidth.NONE, Array(
      (op === LSU_LB || op === LSU_LBU || op === LSU_SB) -> DebugWidth.BYTE,
      (op === LSU_LH || op === LSU_LHU || op === LSU_SH) -> DebugWidth.HALF,
      (op === LSU_LW || op === LSU_SW) -> DebugWidth.WORD,
    ))

    res.bits.rd   := Mux(!is_store, rd, 0.U)
    res.bits.pc   := pc
    res.bits.data := MuxCase(0.U, Array(
      (op === LSU_LB)  -> Cat(Fill(24, dbus.data(7)), dbus.data(7,0)),
      (op === LSU_LH)  -> Cat(Fill(16, dbus.data(15)), dbus.data(15,0)),
      (op === LSU_LW)  -> dbus.data,
      (op === LSU_LBU) -> Cat(Fill(24, 0.U), dbus.data(7,0)),
      (op === LSU_LHU) -> Cat(Fill(16, 0.U), dbus.data(15,0)),
    ))
    switch (op) {
      is (LSU_LB) { printf("ld byte addr=%x data=%x\n", dbus.addr, dbus.data) }
      is (LSU_LH) { printf("ld half addr=%x data=%x\n", dbus.addr, dbus.data) }
      is (LSU_LW) { printf("ld word addr=%x data=%x\n", dbus.addr, dbus.data) }
      is (LSU_SB) { printf("st byte addr=%x data=%x\n", dbus.addr, dbus.wdata) }
      is (LSU_SH) { printf("st half addr=%x data=%x\n", dbus.addr, dbus.wdata) }
      is (LSU_SW) { printf("st word addr=%x data=%x\n", dbus.addr, dbus.wdata) }
    }
  }
}

