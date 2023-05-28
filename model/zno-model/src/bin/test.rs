
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
    println!("entry {:08x}", entry);
    for hdr in elf.program_headers {
        if hdr.p_type == elf64::program_header::PT_LOAD {
            let off = hdr.p_offset as usize;
            let dst = hdr.p_paddr as usize;
            let sz  = hdr.p_filesz as usize;
            ram.write_bytes(dst, &buffer[off..off+sz]);
            println!("{:?}", hdr);
        }
    }
    entry
}

fn main() {
    const RAM_SIZE: usize = 0x0200_0000;
    let mut ram = Ram::new(RAM_SIZE);
    let entry = read_prog(&mut ram, "programs/test.elf");

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

    // Put reset vector on the FTQ
    ftq.enq(entry);
    ftq.update();

    for cyc in 0..8 {
        println!("============== cycle {} ================", cyc);

        // ====================================================================
        // Stage 0 
        //
        // 1. Instruction fetch
        // 2. CFM read 

        // Take the pending fetch address and fetch the appropriate block.
        // Pop the fetch address and push a new fetch block.
        if let Some(fetch_addr) = ftq.front() {
            let mut fblk = FetchBlock { addr: *fetch_addr, data: [0; 0x20] };
            ram.read_bytes(*fetch_addr, &mut fblk.data);
            println!("[IFU] Fetched {:08x}", fblk.addr);
            fbq.enq(fblk);
            ftq.set_deq();
        } else {
            println!("[IFU] FTQ is empty");
        }

        // Send the fetch address to CFM on rp0
        if let Some(fetch_addr) = ftq.front() {
            cfm.drive_rp(0, *fetch_addr);
        }

        // ====================================================================
        // Stage 1
        //
        // 1. Predecode
        // 2. CFM result

        // Take the pending fetch block and pre-decode it.
        // Pop the fetch block and push a new predecoded block.
        if let Some(fblk) = fbq.front() {
            let words = fblk.as_words();
            let mut pd_info = [PredecodeInfo::default(); 8];

            // FIXME: Separate this completely from [Rv32::decode] impl
            for idx in 0..8 {
                let enc = words[idx];
                let pd = Rv32::decode(enc);
                let (imm_fmt, imm_data) = Rv32::decode_imm(enc);
                let ill = pd.is_illegal();
                pd_info[idx].illegal = pd.is_illegal();
                pd_info[idx].imm_data = imm_data;
                pd_info[idx].imm_ctl = ImmCtl {
                    storage: ImmStorage::from_imm19(imm_data.imm19),
                    fmt: imm_fmt,
                };
                pd_info[idx].brn_kind = pd.branch_kind();
            }

            let mut pdblk = PredecodeBlock {
                addr: fblk.addr,
                data: fblk.data,
                info: pd_info
            };

            // The CFM uses this on the next cycle to create/validate an entry
            cfm_pdblk_s1.drive(Some(pdblk));

            println!("[PDU] Predecoded {:08x}", pdblk.addr);
            pdq.enq(pdblk);
            fbq.set_deq();
        } 
        else {
            println!("[PDU] FBQ is empty");
        }

        // Control-flow map read-port result
        if let Some(SyncReadCamOutput { index, data }) = cfm.sample_rp(0) {
            cfm_rp0_s1.drive(Some((index, data)));
            let addr = index;

            // On CFM hit, we proceed with some kind of prediction
            if let Some(entry) = data {
                println!("[CFM] rp0 access hit for {:08x}", addr);
                println!("[CFM] prediction unimplemented for {:08x}", addr);
            } 
            // On CFM miss, we have no choice but to speculate into the 
            // next-sequential fetch block?
            else {
                let next_fetch_addr = addr.wrapping_add(0x20);
                ftq.enq(next_fetch_addr);
                println!("[CFM] rp0 access miss for {:08x}", addr);
                println!("[CFM] Fallback prediction {:08x} for {:08x}", 
                         next_fetch_addr, addr);
                println!("[CFM] Put {:08x} on FTQ", next_fetch_addr);
            }
        } 
        // The read-port wasn't driven
        else {
            println!("[CFM] No rp0 output");
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
                addr: pdblk.addr,
                data: Rv32::decode_arr(pdblk.as_words()),
            };
            for inst in dblk.data {
                println!("{}", inst);
            }
            dbq.enq(dblk);
            pdq.set_deq();
        }

        // Compare the CFM access with the corresponding predecode output
        {
            let new_pdblk = cfm_pdblk_s1.sample();
            let rp0_res   = cfm_rp0_s1.sample();
            if new_pdblk.is_some() && rp0_res.is_some() {
                let pdblk = new_pdblk.unwrap();
                let (rp0_addr, rp0_entry) = rp0_res.unwrap();
                assert!(pdblk.addr == rp0_addr);
                // This was a CFM hit
                if let Some(entry) = rp0_entry {
                    println!("[CFM] Verifying CFM hit {:08x}", pdblk.addr);
                } 
                // If this was a CFM miss, we need to use the predecode info
                // to create a new CFM entry 
                else {
                    println!("[CFM] New CFM entry for miss {:08x}", pdblk.addr);
                    let new_entry = pdblk.to_cfm_entry();
                }

            }
        }

        // ====================================================================
        // Stage 3
        //
        // 1. Register Rename

        // Take the pending decode block and rename it. 
        if let Some(dblk) = dbq.front() {
            println!("[RRN] Renamed {:08x}", dblk.addr);
            //dbq.set_deq();
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

    }
}


