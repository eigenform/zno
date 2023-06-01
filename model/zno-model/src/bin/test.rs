
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

pub enum ExitKind {
    None,
    Static(usize),

}

pub struct Block {
    start: usize,
    exit: Option<usize>,
    exitkind: ExitKind,
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

    // Control-flow map
    let mut cfm: SyncReadCam<usize, CfmEntry, 1, 1> = SyncReadCam::new();

    // Control-flow map stage registers
    let mut cfm_pdblk_s1 = Reg::<Option<PredecodeBlock>>::new(None);
    let mut cfm_rp0_s1   = Reg::<Option<(usize, Option<CfmEntry>)>>::new(None);

    let mut blk = Block {
        start: entry,
        exit: None,
        exitkind: ExitKind::None,
    };

    for cyc in 0..8 {
        println!("============== cycle {} ================", cyc);

        cfe_s0.drive(None);

        // Send the next program counter to the FTQ.
        if let Some(cfe) = cfe_s0.sample() {
            println!("[CFE] Sending pc={:08x} to FTQ", cfe.npc);
            ftq.enq(cfe.npc);
        } 

        if let Some(cfe) = cfe_s0.sample() {
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
            let mut pd_info = [PredecodeInfo::default(); 8];

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
        //
        // 1. Instruction decode
        // 2. CFM validate


        // Take the pending fetch block and decode it. 
        // Pop the fetch block and push a new decode block.
        if let Some(pdblk) = pdq.front() {
            println!("[IDU] Decoding {:08x}", pdblk.addr);
            let mut dblk = DecodeBlock {
                start: pdblk.start,
                addr: pdblk.addr,
                data: Rv32::decode_arr(pdblk.as_words()),
            };
            for inst in dblk.data {
                println!("{}", inst);
            }
            dbq.enq(dblk);
            pdq.set_deq();
        }

        if let Some(pdblk) = pdq.front() {
            // If no branch exists, this must result in a next-sequential
            // control-flow event. 
            if pdblk.is_sequential() {
                println!("[PDU] Block {:08x} is sequential", pdblk.addr);
                cfe_s0.drive(Some(ControlFlowEvent {
                    spec: true,
                    npc: pdblk.addr.wrapping_add(0x20),
                    redirect: false,
                }));
            } 
            else { 
                // Easy case where a single unconditional relative jump 
                // exists within this block. 
                if let Some((idx, npc)) = pdblk.get_single_static_exit() {
                    println!("[CFM] Discovered always-taken jmp at {:08x}",
                             pdblk.addr.wrapping_add(idx << 2));
                    cfe_s0.drive(Some(ControlFlowEvent {
                        spec: true,
                        npc: npc,
                        redirect: false,
                    }));
                } 
                else {
                    let branches = pdblk.get_branches();
                    let next_brn = branches.first().unwrap();
                    let brn_pc = pdblk.addr.wrapping_add(next_brn.0 << 2);
                    let current_pc = pdblk.addr.wrapping_add(pdblk.start << 2);
                    println!("pc={:08x} brn_addr={:08x}", current_pc, brn_pc);
                    println!("{:?}", next_brn);
                }

            }
        }


        //// Compare the CFM access with the corresponding predecode output
        //{
        //    let new_pdblk = cfm_pdblk_s1.sample();
        //    let rp0_res   = cfm_rp0_s1.sample();
        //    if new_pdblk.is_some() && rp0_res.is_some() {
        //        let pdblk = new_pdblk.unwrap();
        //        let (rp0_addr, rp0_entry) = rp0_res.unwrap();
        //        assert!(pdblk.addr == rp0_addr);
        //        // This was a CFM hit
        //        if let Some(entry) = rp0_entry {
        //            println!("[CFM] Verifying CFM hit {:08x}", pdblk.addr);
        //        } 
        //        // If this was a CFM miss, we need to determine *why* and take
        //        // some kind of corrective action
        //        else {

        //            // For blocks with no control-flow instructions, all we 
        //            // can do is continue fetching sequentially. 
        //            if pdblk.is_sequential() {
        //                println!("[CFM] Sequential block miss {:08x}", 
        //                         pdblk.addr);
        //                cfe_s0.drive_valid(
        //                    ControlFlowEvent {
        //                        spec: true,
        //                        npc: pdblk.addr.wrapping_add(0x20),
        //                    }
        //                );
        //            }
        //            // Otherwise, we need to build a new CFM entry 
        //            else {
        //                println!("[CFM] New CFM entry for miss {:08x}", 
        //                         pdblk.addr);
        //                let new_entry = pdblk.to_cfm_entry();
        //            }

        //        }
        //    } 
        //    else {
        //    }
        //}

        // ====================================================================
        // Stage 3
        //
        // 1. Register Rename

        // Take the pending decode block and rename it. 
        if let Some(dblk) = dbq.front() {
            println!("[RRN] Renamed {:08x}", dblk.addr);
            dbq.set_deq();
        } else {
            println!("[RRN] DBQ is empty");

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
        pdq.update();
        cfm.update();
        cfm_rp0_s1.update();
        cfm_pdblk_s1.update();
        cfe_s0.update();

    }
}


