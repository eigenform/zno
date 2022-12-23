//package zno.riscv.hart
//
//import chisel3._
//import chisel3.util._
//import chisel3.experimental.ChiselEnum
//
//import zno.riscv.isa._
//import zno.riscv.uarch._
//import zno.riscv.decode._
//import zno.riscv.dbg._
//import zno.riscv.rf._
//import zno.riscv.eu._
//
//// The pipeline is organized like this:
////
////  | 1. Next program counter
////  | 2. Fetch unit
////  | 3. Decode unit
////  | 4. Schedule/RF-read
////  | 5. Execution (ALU/BCU/AGU)
////  | 6. Memory (LSU)
////  v 7. Retire/commit
////
//
//class Hart extends Module {
//  import ExecutionUnit._
//
//  val dbg      = IO(new DebugOutput)
//  val ibus     = IO(new DebugBus)
//  val dbus     = IO(new DebugBus)
//
//  // Functional units
//  val fetch    = Module(new FetchUnit)
//  val decode   = Module(new DecodeUnit)
//  val rf       = Module(new RegisterFileFF)
//  val sched    = Module(new SchedulerUnit)
//  val alu      = Module(new ArithmeticLogicUnit)
//  val agu      = Module(new AddressGenerationUnit)
//  val bcu      = Module(new BranchCompareUnit)
//  val lsu      = Module(new LoadStoreUnit)
//  val rcu      = Module(new RetireControlUnit)
//
//  // Pipeline registers
//  val stage_pc     = Reg(Valid(UInt(32.W)))
//  val stage_if     = Reg(Valid(new FetchPacket))
//  val stage_id     = Reg(Valid(new Uop))
//  val stage_sc_alu = Reg(Valid(new ALUPacket))
//  val stage_sc_agu = Reg(Valid(new AGUPacket))
//  val stage_sc_bcu = Reg(Valid(new BCUPacket))
//  val stage_ex_alu = Reg(Valid(new RRPacket))
//  val stage_ex_bcu = Reg(Valid(new BRPacket))
//  val stage_ex_agu = Reg(Valid(new LSUPacket))
//  val stage_ex_lsu = Reg(Valid(new RRPacket))
//
//  val cyc          = RegInit(0x00000000.U(32.W))
//  val started      = RegInit(false.B)
//
//  dbg.pc  := stage_pc.bits
//  dbg.cyc := cyc
//
//  // - Connect the instruction/data bus to the appropriate functional units.
//  // - Connect register file ports to appropriate functional units:
//  //   - The scheduler reads register operands before dispatch
//  //   - The RCU commits register results to the register file
//
//  fetch.ibus    <> ibus
//  lsu.dbus      <> dbus
//  sched.rfrp(0) <> rf.io.rp(0)
//  sched.rfrp(1) <> rf.io.rp(1)
//  rcu.rfwp(0)   <> rf.io.wp(0)
//  rcu.rfwp(1)   <> rf.io.wp(1)
//  rcu.rfwp(2)   <> rf.io.wp(2)
//
//  // Feed functional units by sampling the pipeline registers.
//
//  fetch.req     := stage_pc
//  decode.req    := stage_if
//  sched.req     := stage_id
//  alu.req       := stage_sc_alu
//  agu.req       := stage_sc_agu
//  bcu.req       := stage_sc_bcu
//  lsu.req       := stage_ex_agu
//  rcu.alu       := stage_ex_alu
//  rcu.lsu       := stage_ex_lsu
//  rcu.bcu       := stage_ex_bcu
//
//  // Save the outputs of functional units in the pipeline registers.
//
//  stage_if      := fetch.res
//  stage_id      := decode.res
//  stage_sc_alu  := sched.alu
//  stage_sc_agu  := sched.agu
//  stage_sc_bcu  := sched.bcu
//  stage_ex_alu  := alu.res
//  stage_ex_agu  := agu.res
//  stage_ex_bcu  := bcu.res
//  stage_ex_lsu  := lsu.res
//
//
//  // NOTE: This should probably be hooked up to reset
//  when (cyc === 0.U && !started) {
//    started        := true.B
//    stage_pc.valid := true.B
//    stage_pc.bits  := 0.U
//  } .otherwise {
//    stage_pc.valid := true.B
//    stage_pc.bits  := Mux(
//      (!rcu.stall && rcu.brn.valid),
//      rcu.brn.bits, stage_pc.bits + 4.U
//    )
//  }
//
//  printf("cycle %d\n", cyc)
//
//  cyc := cyc + 1.U
//}
//
//class FetchUnit extends Module {
//  val ibus = IO(new DebugBus)
//  val req  = IO(Flipped(Valid(UInt(32.W))))
//  val res  = IO(Valid(new FetchPacket))
//
//  res.valid := false.B
//  res.bits.drive_defaults()
//  ibus.drive_defaults()
//
//  when (req.valid) {
//    ibus.ren      := req.valid
//    ibus.wen      := false.B
//    ibus.wdata    := 0.U
//    ibus.addr     := req.bits
//    ibus.wid      := DebugWidth.WORD
//    res.valid     := req.valid
//    res.bits.inst := ibus.data
//    res.bits.pc   := req.bits
//  }
//
//  printf("[IF] valid=%d pc=%x\n", req.valid, req.bits)
//
//}
//
//// FIXME: Instructions are using stale data from RF-read because we aren't
//// keeping track of dependences at all yet. 
////
//// More generally, we have to make some kind of decision about how to design
//// a pipeline where *stalls* might occur: this means we need more control 
//// signalling (in addition to the 'valid' signals) between pipeline stages.
////
//class SchedulerUnit extends Module {
//  val rfrp = IO(Vec(2, Flipped(new RFReadPort)))
//
//  val req  = IO(Flipped(Valid(new Uop)))
//  val alu  = IO(Valid(new ALUPacket))
//  val agu  = IO(Valid(new AGUPacket))
//  val bcu  = IO(Valid(new BCUPacket))
//  val rs1  = WireDefault(0.U(32.W))
//  val rs2  = WireDefault(0.U(32.W))
//
//  // NOTE: You have a bunch of redundant bits for describing illegal ops? Why?
//  //val nop = uop.bits.eu === ExecutionUnit.EU_NOP
//  val ill = req.bits.eu === ExecutionUnit.EU_ILL
//  val ok  = (req.valid && !ill)
//
//  val disp_alu = (ok && req.bits.eu === ExecutionUnit.EU_ALU)
//  val disp_agu = (ok && req.bits.eu === ExecutionUnit.EU_LSU)
//  val disp_bcu = (ok && req.bits.eu === ExecutionUnit.EU_BCU)
//
//  alu.valid := false.B
//  agu.valid := false.B
//  bcu.valid := false.B
//  alu.bits.drive_defaults()
//  agu.bits.drive_defaults()
//  bcu.bits.drive_defaults()
//  rfrp(0).addr := 0.U
//  rfrp(1).addr := 0.U
//
//  printf("[SC] valid=%d pc=%x\n", req.valid, req.bits.pc)
//
//  when (ok) {
//    val rp1_en  = (req.bits.op1 === Op1Sel.OP1_RS1)
//    val rp2_en  = (req.bits.op2 === Op2Sel.OP2_RS2)
//    rfrp(0).addr := Mux(rp1_en, req.bits.rs1, 0.U)
//    rfrp(1).addr := Mux(rp2_en, req.bits.rs2, 0.U)
//  }
//
//  when (disp_alu) {
//    alu.valid    := true.B
//    alu.bits.op  := req.bits.aluop
//    alu.bits.pc  := req.bits.pc
//    alu.bits.rd  := req.bits.rd
//    alu.bits.x   := MuxCase(0.U, Array(
//      (req.bits.op1 === Op1Sel.OP1_RS1) -> rfrp(0).data,
//      (req.bits.op1 === Op1Sel.OP1_PC ) -> req.bits.pc,
//      (req.bits.op1 === Op1Sel.OP1_NAN) -> 0.U,
//    ))
//    alu.bits.y   := MuxCase(0.U, Array(
//      (req.bits.op2 === Op2Sel.OP2_RS2) -> rfrp(1).data,
//      (req.bits.op2 === Op2Sel.OP2_IMM) -> req.bits.imm,
//      (req.bits.op2 === Op2Sel.OP2_NAN) -> 0.U
//    ))
//  }
//
//  when (disp_agu) {
//    agu.valid    := true.B
//    agu.bits.op  := req.bits.lsuop
//    agu.bits.pc  := req.bits.pc
//    agu.bits.rd  := req.bits.rd
//    agu.bits.rs1 := rfrp(0).data
//    agu.bits.rs2 := rfrp(1).data
//    agu.bits.imm := req.bits.imm
//  }
//
//  when (disp_bcu) {
//    bcu.valid    := true.B
//    bcu.bits.op  := req.bits.bcuop
//    bcu.bits.pc  := req.bits.pc
//    bcu.bits.rd  := req.bits.rd
//    bcu.bits.rs1 := rfrp(0).data
//    bcu.bits.rs2 := rfrp(1).data
//    bcu.bits.imm := req.bits.imm
//  }
//}
//
//
//class RetireControlUnit extends Module {
//  val rfwp  = IO(Vec(3, Flipped(new RFWritePort)))
//  val alu   = IO(Flipped(Valid(new RRPacket)))
//  val lsu   = IO(Flipped(Valid(new RRPacket)))
//  val bcu   = IO(Flipped(Valid(new BRPacket)))
//
//  val brn   = IO(Valid(UInt(32.W)))
//  val stall = IO(Output(Bool()))
//
//  stall        := (!alu.valid && !lsu.valid && !bcu.valid)
//  brn.valid    := false.B
//  brn.bits     := 0.U
//
//  rfwp(0).drive_defaults()
//  rfwp(1).drive_defaults()
//  rfwp(2).drive_defaults()
//
//  printf("[RCU] alu_valid=%d bcu_valid=%d lsu_valid=%d\n", 
//    alu.valid, bcu.valid, lsu.valid
//  )
//
//  val alu_rr = (!stall && alu.valid)
//  val lsu_rr = (!stall && lsu.valid && lsu.bits.rd =/= 0.U)
//  val bcu_rr = (!stall && bcu.valid && bcu.bits.ok && bcu.bits.link)
//
//  rfwp(0).addr := Mux(alu_rr, alu.bits.rd, 0.U)
//  rfwp(0).data := Mux(alu_rr, alu.bits.data, 0.U)
//  rfwp(0).en   := alu_rr
//
//  rfwp(1).addr := Mux(lsu_rr, lsu.bits.rd, 0.U)
//  rfwp(1).data := Mux(lsu_rr, lsu.bits.data, 0.U)
//  rfwp(1).en   := lsu_rr
//
//  rfwp(2).addr := Mux(bcu_rr, bcu.bits.rd, 0.U)
//  rfwp(2).data := Mux(bcu_rr, bcu.bits.pc + 4.U, 0.U)
//  rfwp(2).en   := bcu_rr
//
//  when (!stall && bcu.valid && bcu.bits.ok) {
//    brn.valid := true.B
//    brn.bits  := bcu.bits.tgt
//  }
//}


