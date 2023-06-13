
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
use zno_model::core::sched::*;
use zno_model::core::rename::*;

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
    // Renamed block queue
    let mut rbq: Queue<DecodeBlock>    = Queue::new();

    let mut frl: Freelist<256> = Freelist::new();
    let mut prf: PhysicalRegisterFile<256> = PhysicalRegisterFile::new();
    let mut map = RegisterMap::new();

    let mut srob: SimpleReorderBuffer<64> = SimpleReorderBuffer::new();
    let mut sch: IntScheduler<24> = IntScheduler::new();

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
            dbq.enq(dblk);
            pdq.set_deq();
        }

        // ====================================================================
        // Stage 3
        //
        // 1. Register Rename

        map.print();
        rename_stage(&mut dbq, &mut map, &mut frl, &mut rbq);

        // ====================================================================
        // Stage 4
        //
        // 1. Dispatch

        if let Some(rblk) = rbq.front() {
            println!("[DIS] Dispatching {:08x}", rblk.addr);

            let rob_idx = srob.drive_alloc(&rblk).unwrap();
            println!("[DIS] Allocated ROB index {}", rob_idx);
            for (idx, mop) in rblk.iter_seq() {

                // Non-schedulable macro-ops
                if mop.is_nonsched() {
                    continue;
                }


                println!("[DIS] {}: {:?} {}", idx, mop.kind, mop);
            }

            rbq.set_deq();
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
        sch.update();
        srob.update();

        frl.update();
        map.update();

    }
}


