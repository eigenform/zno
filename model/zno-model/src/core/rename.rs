
use crate::common::*;
use crate::core::uarch::*;

pub fn rename_stage(
    dbq: &mut Queue<DecodeBlock>,
    map: &mut RegisterMap,
    frl: &mut Freelist<256>,
    rbq: &mut Queue<DecodeBlock>,
) 
{
    // Take the pending decode block and rename it. 
    if let Some(dblk) = dbq.front() {
        println!("[RRN] Renamed {:08x}", dblk.addr);

        let mut blk = dblk.clone();
        blk.rewrite_static_zero_operands();

        // Resolve dynamic zeroes and mov operations, propagating the 
        // results until the output has settled. 
        //
        // NOTE: It's not actually clear whether or not this is something 
        // you can do in hardware? This is like waiting for a comb loop
        // to become stable? Otherwise, we need a hard limit on the 
        // number of times this logic is allowed to repeat. 
        //
        // NOTE: Another question is whether or not you *want* to do 
        // this: do zeroes occur often enough that it's worth the cost?
        //
        let mut rewrite_done = false;
        let mut rewrite_pass = 0;
        while !rewrite_done {
            assert!(rewrite_pass < 16);
            let num_zeroes = blk.rewrite_dyn_zero_operands(map.sample_zeroes());
            let num_movs = blk.rewrite_mov_ops();
            rewrite_pass += 1;
            rewrite_done = (num_zeroes == 0 && num_movs == 0);
            if !rewrite_done {
                println!("[RRN] Pass {}: rewrote {} dynamic zeroes", 
                         rewrite_pass, num_zeroes);
                println!("[RRN] Pass {}: rewrote {} mov ops", 
                         rewrite_pass, num_movs);
            }
        }

        // Rewrite physical destinations with allocations.
        // Bind destination register to allocation by driving map write ports.
        let num_alcs = blk.num_preg_allocs();
        println!("[RRN] FRL has {} free entries, need {}", 
                 frl.num_free(), num_alcs);
        let mut alcs = frl.sample_alcs(num_alcs).unwrap();
        alcs.reverse();
        for (idx, mut mop) in blk.iter_seq_mut() {
            if mop.has_rr_alc() {
                let prn = alcs.pop().unwrap();
                mop.pd = PhysRegDst::Allocated(prn);
                map.drive_wp(mop.rd, prn);
            }
            //println!("  {} {}", idx, mop);
        }
        assert!(alcs.is_empty());

        // Rename with local dependences
        let ldeps = blk.calc_local_deps();
        for (sidx, rs1_pidx, rs2_pidx) in ldeps {
            if let Some(pidx) = rs1_pidx {
                let byp_pd = blk.data[pidx].get_pd().unwrap();
                blk.data[sidx].ps1 = PhysRegSrc::Local(byp_pd);
            }
            if let Some(pidx) = rs2_pidx {
                let byp_pd = blk.data[pidx].get_pd().unwrap();
                blk.data[sidx].ps2 = PhysRegSrc::Local(byp_pd);
            }
        }

        // Rename with global dependences
        for (idx, mut mop) in blk.iter_seq_mut() {
            if mop.op1 == Operand::Reg && mop.ps1 == PhysRegSrc::None {
                let (prn, zero) = map.sample_rp(mop.rs1);
                assert!(!zero);
                mop.ps1 = PhysRegSrc::Global(prn);
            }
            if mop.op2 == Operand::Reg && mop.ps2 == PhysRegSrc::None {
                let (prn, zero) = map.sample_rp(mop.rs2);
                assert!(!zero);
                mop.ps2 = PhysRegSrc::Global(prn);
            }
        }

        // Complete move operations by driving map write ports
        for (idx, mop) in blk.iter_seq() {
            match mop.mov {
                MovCtl::None => {},
                MovCtl::Zero => {
                    map.drive_wp(mop.rd, 0);
                },
                MovCtl::Op1 => {
                    match mop.op1 {
                        Operand::None => unreachable!(),
                        Operand::Zero => unreachable!(),
                        Operand::Pc => unimplemented!(),
                        Operand::Imm => unimplemented!(),
                        Operand::Reg => {
                            map.drive_wp(mop.rd, mop.get_ps1().unwrap());
                        },
                    }
                },
                MovCtl::Op2 => {
                    match mop.op2 {
                        Operand::None => unreachable!(),
                        Operand::Zero => unreachable!(),
                        Operand::Pc => unimplemented!(),
                        Operand::Imm => unimplemented!(),
                        Operand::Reg => {
                            map.drive_wp(mop.rd, mop.get_ps2().unwrap());
                        },
                    }
                },
            }

            println!("  {} {}", idx, mop);
        }

        rbq.enq(blk);
        dbq.set_deq();
    } else {
        println!("[RRN] DBQ is empty");

    }


}
