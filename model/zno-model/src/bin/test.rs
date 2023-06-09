
#![allow(unused_variables)]
#![allow(unused_parens)]
#![allow(unused_imports)]
#![allow(unused_mut)]
#![allow(dead_code)]
#![allow(unreachable_patterns)]

extern crate goblin;
use goblin::*;
use std::collections::*;
use std::rc::Rc;
use std::cell::*;
use std::any::*;

use zno_model::sim::*;
use zno_model::common::*;
use zno_model::soc::mem::*;
use zno_model::riscv::rv32i::*;
use zno_model::core::uarch::*;

fn read_prog(ram: &mut Ram, filename: &'static str) -> usize { 
    let buffer = std::fs::read(filename).unwrap();
    let elf = elf::Elf::parse(&buffer).unwrap();
    let entry = elf.entry as usize;
    for hdr in elf.program_headers {
        if hdr.p_type == elf64::program_header::PT_LOAD {
            let off = hdr.p_offset as usize;
            let dst = hdr.p_paddr as usize;
            let sz  = hdr.p_filesz as usize;
            ram.write_bytes(dst, &buffer[off..off+sz]);
        }
    }
    entry
}

#[derive(Clone, Copy)]
pub enum ExitKind {
    None,
    Static(usize),
}

#[derive(Clone, Copy)]
pub struct Block {
    cfe: ControlFlowEvent,
    exit: ExitKind,
}

fn main() {
    const RAM_SIZE: usize = 0x0200_0000;
    let mut ram = Ram::new(RAM_SIZE);
    let entry = read_prog(&mut ram, "programs/test.elf");

    let mut cfe_s0 = Reg::<Option<ControlFlowEvent>>::new(Some(
        ControlFlowEvent { spec: false, redirect: true, npc: entry }
    ));

    // Fetch target queue
    let mut ftq: Queue<usize>          = Queue::new();
    // Fetch block queue
    let mut fbq: Queue<FetchBlock>     = Queue::new();
    // Predecode block queue
    let mut pdq: Queue<PredecodeBlock> = Queue::new();
    // Decode block queue
    let mut dbq: Queue<DecodeBlock>    = Queue::new();

    /// Renamed block queue
    let mut rbq: Queue<DecodeBlock>    = Queue::new();

    let mut frl: Freelist<256> = Freelist::new();
    let mut map = RegisterMap::new();

    // Control-flow map
    let mut cfm: AsyncReadCam<usize, CfmEntry> = AsyncReadCam::new();

    // Control-flow map stage registers
    let mut cfm_pdblk_s1 = Reg::<Option<PredecodeBlock>>::new(None);
    let mut cfm_rp0_s1   = Reg::<Option<(usize, Option<CfmEntry>)>>::new(None);


    let mut cfeq = CircularQueue::<Block, 8>::new();

    for cyc in 0..8 {
        println!("============== cycle {} ================", cyc);

        // ====================================================================
        // Control-flow events

        cfe_s0.drive(None);

        // Handle a control-flow event.
        if let Some(cfe) = cfe_s0.sample() {

            // Queue up this address for fetch
            println!("[CFE] Sending pc={:08x} to FTQ", cfe.npc);
            ftq.enq(cfe.npc);


            if let Some(cfm_entry) = cfm.sample_rp(cfe.npc) {
                println!("[CFM] CFM hit unimplemented");
            } 
            else {
                println!("[CFM] CFM miss unimplemented");
            }

        } 


        // ====================================================================

        // Take the pending fetch address and fetch the appropriate block.
        // Pop the fetch address and push a new fetch block.
        // FIXME: Fetch is instantaneous, there are no caches.
        if let Some(npc) = ftq.front() {
            let fetch_addr = npc & !(0x1f);
            let idx = (npc & 0x1f) >> 2;
            let mut fblk = FetchBlock { 
                start: idx,
                addr: fetch_addr, 
                data: [0; 0x20]
            };
            ram.read_bytes(fetch_addr, &mut fblk.data);
            println!("[IFU] Fetched {:08x}", fblk.addr);
            fbq.enq(fblk);
            ftq.set_deq();
        } else {
            println!("[IFU] FTQ is empty");
        }


        // ====================================================================
        // Stage 1

        // Take the pending fetch block and pre-decode it.
        // Pop the fetch block and push a new predecoded block.
        if let Some(fblk) = fbq.front() {
            let words = fblk.as_words();
            let mut pdblk = fblk.predecode();
            println!("[PDU] Predecoded {:08x}", pdblk.addr);
            pdq.enq(pdblk);
            fbq.set_deq();
        } 
        else {
            println!("[PDU] FBQ is empty");
        }


        // ====================================================================
        // Stage 2

        // Take the pending pre-decoded block and decode it. 
        // Pop the pre-decoded block and push a new decode block.
        if let Some(pdblk) = pdq.front() {

            let enc_arr = pdblk.as_words();
            let info_arr = pdblk.get_imm_info();

            println!("[IDU] Decoding {:08x}", pdblk.addr);
            let mut dblk = DecodeBlock {
                start: pdblk.start,
                addr: pdblk.addr,
                exit: pdblk.get_exit(),
                data: MacroOp::decode_arr(&enc_arr, &info_arr),
            };

            dblk.print();
            //for inst in dblk.data {
            //    println!("{}", inst);
            //}
            dbq.enq(dblk);
            pdq.set_deq();
        }

        // ====================================================================
        // Stage 3
        //
        // 1. Register Rename

        map.print();

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

        if let Some(rblk) = rbq.front() {
            println!("[DIS] Dispatching {:08x}", rblk.addr);
        } else {
            println!("[DIS] RBQ is empty");
        }



        // ====================================================================
        // Update simulation state.
        //
        // Everything that occurs before this point in the loop is either
        // (a) some representation of combinational logic, or (b) staging
        // changes to stateful elements. 
        //
        // FIXME: You have to remember to update whatever state we declare.
        // This is not ideal - how can we make this easier to write? 

        ftq.update();
        fbq.update();
        dbq.update();
        rbq.update();
        pdq.update();
        cfm.update();
        cfm_rp0_s1.update();
        cfm_pdblk_s1.update();
        cfe_s0.update();
        cfeq.update();

        frl.update();
        map.update();

    }
}


