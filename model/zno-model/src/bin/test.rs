
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

    let mut ftq: Queue<usize>          = Queue::new();
    let mut fbq: Queue<FetchBlock>     = Queue::new();
    let mut dbq: Queue<DecodeBlock>    = Queue::new();
    let mut pdq: Queue<PredecodeBlock> = Queue::new();

    // Put reset vector on the FTQ
    ftq.enq(entry);
    ftq.update();

    for cyc in 0..8 {
        println!("============== cycle {} ================", cyc);

        // --------------------------------------------------------------------
        // 0: Fetch
        //
        // Take the pending fetch address and fetch the appropriate block.
        // Pop the fetch address and push a new fetch block.
        if let Some(fetch_addr) = ftq.front() {
            let mut fblk = FetchBlock { addr: *fetch_addr, data: [0; 0x20] };
            ram.read_bytes(*fetch_addr, &mut fblk.data);
            println!("[IFU] Fetched {:08x}", fblk.addr);
            fbq.enq(fblk);
            ftq.set_deq();
        }
        println!();

        // --------------------------------------------------------------------
        // 1: Predecode
        //
        // Take the pending fetch block and pre-decode it.
        // Pop the fetch block and push a new predecoded block.
        if let Some(fblk) = fbq.front() {

            let mut pd_info = [PredecodeInfo::default(); 8];
            let mut pdblk = PredecodeBlock {
                addr: fblk.addr,
                data: fblk.data,
                info: pd_info
            };
            let words = pdblk.as_words();
            for idx in 0..8 {
                let enc = words[idx];
                let (imm_fmt, imm_data) = Rv32::decode_imm(enc);
                // FIXME: Separate predecode logic from decode
                let pd = Rv32::decode(enc);
                let ill = matches!(pd, Instr::Illegal(_));
                let brinfo = pd.branch_info();
                pd_info[idx].illegal = ill;
                pd_info[idx].imm_data = imm_data;
                pd_info[idx].imm_ctl = ImmCtl {
                    storage: ImmStorage::from_imm19(imm_data.imm19),
                    fmt: imm_fmt,
                };
            }

            println!("[PDU] Predecoded {:08x}", pdblk.addr);
            pdq.enq(pdblk);
            fbq.set_deq();
        }
        println!();

        // --------------------------------------------------------------------
        // 2: Decode
        //
        // Take the pending fetch block and decode it. 
        // Pop the fetch block and push a new decode block.
        if let Some(pdblk) = pdq.front() {
            let mut dblk = DecodeBlock {
                addr: pdblk.addr,
                data: Rv32::decode_arr(pdblk.as_words()),
            };
            for inst in dblk.data {
                println!("{}", inst);
            }
            println!("[IDU] Decoded {:08x}", pdblk.addr);
            dbq.enq(dblk);
            pdq.set_deq();
        }
        println!();

        // --------------------------------------------------------------------
        // 3: Rename
        if let Some(dblk) = dbq.front() {

            println!("[RRN] Renamed {:08x}", dblk.addr);
            //dbq.set_deq();
        }


        // --------------------------------------------------------------------
        // Put the next-sequential fetch address on the FTQ
        // FIXME: 
        if let Some(fetch_addr) = ftq.front() {
            let next_fetch_addr = fetch_addr.wrapping_add(0x20);
            println!("Put {:08x} on FTQ", next_fetch_addr);
            ftq.enq(next_fetch_addr);
        }

        // --------------------------------------------------------------------
        // Update all of the stateful elements.
        //
        // Everything that occurs before this point in the loop is either
        // (a) some representation of combinational logic, or (b) staging
        // changes to stateful elements. 
        ftq.update();
        fbq.update();
        dbq.update();
        pdq.update();

    }
}


//    pub fn npc(&mut self) {
//        match self.npcstage.npc {
//            NPCValue::Reset => {
//                self.ftq.clear();
//                self.ftq.push_back(FtqEntry { 
//                    addr: self.reset_vector,
//                    spec: false,
//                });
//            },
//            _ => unimplemented!("{:?}", self.npcstage.npc),
//        }
//
//        // CFM access technically starts here ..
//    }
//
//    /// Instruction fetch
//    pub fn fetch(&mut self) {
//
//        if let Some(ftq_ent) = self.ftq.get(0) { 
//            println!("[IF] pc={:08x} spec={}", ftq_ent.addr, ftq_ent.spec);
//            let mut fblk = [0; 0x40];
//            self.ram.read_bytes(ftq_ent.addr, &mut fblk);
//            let fbq_entry = FbqEntry { 
//                addr: ftq_ent.addr,
//                spec: ftq_ent.spec,
//                data: fblk,
//            };
//            self.fbq.push_back(fbq_entry);
//            self.ftq.pop_front();
//        }
//
//        // Pipeline registers
//        let fstage = FetchStage { 
//            pc: fetch_pc, 
//            blk, 
//            cfm_ent, 
//            spec: self.npcstage.spec 
//        };
//        self.fstage = Some(fstage);
//
//        // FIXME
//        //self.npcstage.npc = NPCValue::NextSeq(fetch_pc);
//        self.npcstage.npc = NPCValue::None;
//    }
//
//    /// Instruction predecode
//    pub fn predecode(&mut self) {
//        if let Some(stage) = &self.fstage { 
//            println!("[PD] pc={:08x} spec={}", stage.pc, stage.spec);
//
//            match stage.cfm_ent {
//                None => {
//                    println!("[PD] CFM miss for {:08x}", stage.pc);
//                },
//                Some(cfm_ent) => {
//                    println!("[PD] CFM hit for {:08x}", stage.pc);
//                },
//
//            }
//
//
//            // Predecoders
//            let words: [u32; 8] = unsafe { std::mem::transmute(stage.blk) };
//            let mut inst = [Instr::Illegal(0xdeadc0de); 8];
//            let mut info = {
//                let mut res = [None; 8];
//                for i in 0..8 {
//                    let inst = Rv32::decode(words[i]);
//                    res[i] = inst.branch_info();
//                }
//                res
//            };
//
//            let mut terminal = CfmEntryKind::Fallthrough;
//            let mut end = None;
//            for i in 0..8 {
//                if let Some(brn_info) = info[i] {
//                    println!("[PD] \tidx {} {:?}", i, brn_info);
//                    match brn_info { 
//                        // Terminates a basic block
//                        BranchInfo::CallDirect { lr, simm } => {
//                            let base_pc = stage.pc.wrapping_add(i*4);
//                            let tgt = (base_pc as u32).wrapping_add(simm as u32) 
//                                as usize;
//                            println!("{:08x}", tgt);
//                            terminal = CfmEntryKind::ExitStatic {
//                                idx: i, tgt: tgt
//                            };
//                            end = Some(i);
//                            break;
//                        },
//                        _ => unimplemented!("{:?}", brn_info),
//                    }
//
//                }
//            }
//
//            let pdstage = PredecodeStage { 
//                pc: stage.pc,
//                spec: stage.spec,
//                blk: stage.blk,
//                end: end
//            };
//            self.pdstage = Some(pdstage);
//
//        }
//    }
//
//    /// Instruction decode
//    pub fn decode(&mut self) {
//        if let Some(stage) = &self.pdstage { 
//            println!("[DE] pc={:08x} spec={}", stage.pc, stage.spec);
//            let words: [u32; 8] = unsafe { std::mem::transmute(stage.blk) };
//            let mut window = [Instr::Illegal(0xdeadc0de); 8];
//            let end = stage.end.unwrap_or(8-1);
//            for i in 0..=end {
//                window[i] = Rv32::decode(words[i]);
//                println!("[DE] \t{}", window[i]);
//            }
//
//            let dstage = DecodeStage { 
//                pc: stage.pc, 
//                spec: stage.spec,
//                window: window,
//                end: stage.end,
//            };
//            self.dstage = Some(dstage);
//        }
//    }
//
//    pub fn rename(&mut self) { 
//        if let Some(stage) = &self.dstage { 
//        }
//    }


