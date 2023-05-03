package zno.core.mid

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.ChiselEnum
import chisel3.util.experimental.decode

import zno.common._
import zno.riscv.isa._
import zno.core.uarch._

class IntegerDispatchIO(implicit p: ZnoParam) extends Bundle {
    val uops     = Decoupled(Vec(p.int_dis_width, Valid(new IntUop)))
    val num_free = Input(UInt(log2Ceil(p.int_dis_width).W))
    def drive_defaults(): Unit = { 
      this.uops.valid := false.B
      for (idx <- 0 until p.int_dis_width) {
        this.uops.bits(idx).valid := false.B
        this.uops.bits(idx).bits  := 0.U.asTypeOf(new IntUop)
      }
    }
}

class IntegerDispatchQueue(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val from_dis = Flipped(new IntegerDispatchIO)
  })

  val q_valid = RegInit(Vec(8, false.B))
  val q_data  = RegInit(Vec(8, 0.U.asTypeOf(new IntUop)))
  val num_free = RegInit(0.U.asTypeOf(UInt(log2Ceil(p.int_dis_width).W)))

  num_free   := PopCount(q_valid)
  io.from_dis.num_free := num_free
}

// Bridge between the front-end and back-end of the machine. 
//
// There's a definite order to the things that need to happen during rename. 
// As far as I can tell, it should be something like this: 
//
//  1. Use the map to check all of the source registers for zero. 
//     (We use this to do better 'mov' detection)
//  2. Determine which ops will be squashed into 'mov' via renames. 
//  3. Determine which ops will need to allocate for a destination. 
//       - Squashed 'mov' operations do not allocate 
//  4. Determine the physical destination for all ops. 
//       - Squashed 'mov' operations do not have a physical destination
//  5. Determine the physical sources for all ops.
//       - If a provider exists, bypass from the physical destination
//       - Otherwise, resolve with the register map 
//
//  And then afterwards: 
//
//  1. Build renamed ops with physical operands.
//  2. Tell the freelist about allocations that we made.
//  3. Write back to the register map.
//       - For ops that allocate, bind 'rd' to the physical destination
//       - For 'mov' ops, bind 'rd' to the appropriate physical source
//     
class ZnoMidcore(implicit p: ZnoParam) extends Module {
  val io = IO(new Bundle {
    val dblk     = Flipped(Decoupled(new DecodeBlock))
    val int_disp = new IntegerDispatchIO
  })

  val frl = Module(new RegisterFreeList)
  val map = Module(new RegisterMap)

  io.int_disp.drive_defaults()

  // Queue up macro-ops from decode
  val dbq  = Module(new Queue(new DecodeBlock, p.dbq_sz))
  val dbq_mops = dbq.io.deq.bits.data
  dbq.io.enq <> io.dblk
  dbq.io.deq.ready := true.B // FIXME

  // Detect zero values
  val zprop = Module(new ZeroPropagationUnit)
  val zprop_mops = zprop.io.out_mops
  zprop.io.map_zero := map.zeroes
  zprop.io.in_mops  := dbq_mops

  // Detect non-scheduled instructions
  val nsop  = Module(new NonscheduledOpDetector)
  val nsop_mops = nsop.io.out_mops
  nsop.io.in_mops := zprop_mops

  // Determine which instructions will allocate. 
  // Check against the number of remaining free registers.
  //
  // FIXME: How do we want to deal with this? Renaming should not proceed
  // if we don't have enough resources to actually dispatch. 
  val pd_alc   = WireInit(VecInit(nsop_mops.map(x => x.is_allocation())))
  val num_alcs = PopCount(pd_alc)
  val can_alc  = (num_alcs <= frl.io.num_free)

  val rename = Module(new RegisterRenameUnit)
  val pd  = rename.io.pd
  val ps1 = rename.io.ps1
  val ps2 = rename.io.ps2
  rename.io.mops := nsop_mops
  rename.io.map_rp_rs1 <> map.rp_rs1
  rename.io.map_rp_rs2 <> map.rp_rs2
  rename.io.frl_alc    <> frl.io.alc_idx

  // Tell the freelist which registers we've allocated
  frl.io.alc_en := pd_alc

  // FIXME: Drive register map write ports
  for (idx <- 0 until p.dec_bw) { 
    map.wp(idx).en := false.B
    map.wp(idx).rd := 0.U
    map.wp(idx).pd := 0.U
  }


//  // Select integer ops to dispatch
//  val int_mop = WireInit(VecInit(nsop_mops.map(x => x.is_int()))).asUInt
//  val int_dis_ptr = ChainedPriorityEncoderOH(int_mop, p.int_dis_width)
//  io.int_disp.uops.valid := true.B // FIXME
//  for (tgt_idx <- 0 until p.int_dis_width) {
//    val mop_idx = OHToUInt(int_dis_ptr(tgt_idx))
//    val tgt_uop = io.int_disp.uops.bits(tgt_idx).bits
//    io.int_disp.uops.bits(tgt_idx).valid := true.B // FIXME
//    tgt_uop.ridx    := 0.U // FIXME
//    tgt_uop.alu_op  := nsop_mops(mop_idx).alu_op
//    tgt_uop.pd      := 0.U // FIXME
//    tgt_uop.ps1     := ps1_idx(mop_idx)
//    tgt_uop.ps2     := ps2_idx(mop_idx)
//    tgt_uop.src1    := nsop_mops(mop_idx).src1
//    tgt_uop.src2    := nsop_mops(mop_idx).src2
//    tgt_uop.imm_ctl := nsop_mops(mop_idx).imm_ctl
//  }

}






