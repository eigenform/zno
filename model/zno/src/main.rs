
#![allow(unused_variables)]
#![allow(unused_parens)]
#![allow(unused_imports)]
#![allow(unused_mut)]
#![allow(dead_code)]
#![allow(unreachable_patterns)]


use sim::hle::mem::*;
use sim::hle::riscv::*;

use sim::lle::register::*;
use sim::lle::mem::*;
use sim::lle::*;

pub mod rename;
use rename::*;



#[derive(Clone, Copy, Debug)]
pub struct L1CacheLine(pub [u8; 64]);
impl Default for L1CacheLine {
    fn default() -> Self { Self([0; 64]) }
}

#[derive(Clone, Copy, Debug, Default)]
pub struct L1CacheTag {
    tag: usize,
}

pub struct L1ICache<const SETS: usize, const WAYS: usize> {
    rp_idx: Reg<usize>,
    rp_out_data: Reg<L1CacheLine>,
    rp_out_tags: Reg<L1CacheTag>,

    data: [ [ L1CacheLine; SETS ]; WAYS ],
    tags: [ [ L1CacheTag; SETS ]; WAYS ],
}
impl <const SETS: usize, const WAYS: usize> L1ICache<SETS, WAYS> {
    pub fn new() -> Self {
        Self {
            rp_idx: Reg::default(),
            rp_out_data: Reg::default(),
            rp_out_tags: Reg::default(),

            data: [ [ L1CacheLine::default(); SETS]; WAYS ],
            tags: [ [ L1CacheTag::default(); SETS]; WAYS ],
        }
    }
}

impl <const SETS: usize, const WAYS: usize> 
Clocked for L1ICache<SETS, WAYS> 
{
    fn update(&mut self) {
    }
}

#[derive(Clone, Copy, Debug)]
pub struct ProgramCounter(usize);
impl ProgramCounter {
    pub fn new(addr: usize) -> Self { 
        assert!(addr & 0x3 == 0);
        Self(addr) 
    }
    pub fn value(&self) -> usize { self.0 }
    pub fn fetch_addr(&self) -> usize { self.0 & !0x1f }
    pub fn fblk_start_idx(&self) -> usize { 
        (self.0 & 0x1f) >> 2
    }
    pub fn inc_next_fblk(&mut self) {
        self.0 += 0x20;
    }
}

#[derive(Clone, Copy, Debug)]
enum ControlFlowEvent {
    ResetVector(ProgramCounter),
    Sequential(ProgramCounter),
    Static(BranchKind, ProgramCounter),
}
impl ControlFlowEvent {
    fn get_pc(&self) -> ProgramCounter { 
        match self {
            Self::ResetVector(pc) => *pc,
            Self::Sequential(pc) => *pc,
            Self::Static(_, pc) => *pc,
        }
    }
}

#[derive(Clone, Copy)]
struct FetchBlock {
    pc: ProgramCounter,
    data: [u32; 8],
}
impl FetchBlock {
    pub fn new(pc: ProgramCounter, data: [u32; 8]) -> Self {
        Self { pc, data }
    }
    pub fn from_bytes(pc: ProgramCounter, data: [u8; 32]) -> Self { 
        let data: [u32; 8] = unsafe { std::mem::transmute(data) };
        Self { pc, data }
    }

    pub fn data(&self) -> [Option<u32>; 8] {
        let mut res = [ None; 8 ];
        for idx in self.pc.fblk_start_idx()..8 {
            res[idx] = Some(self.data[idx]);
        }
        res
    }

    pub fn hle_decode(&self) -> [Option<Instr>; 8] {
        let mut res = [ None ; 8];
        let data = self.data();
        for idx in 0..8 {
            if let Some(opcd) = data[idx] {
                let inst = Rv32::disas(opcd);
                res[idx] = Some(inst);
            }
        }
        res
    }

    pub fn imm_decode(&self) -> [Option<Rv32Imm>; 8] {
        let mut res = [ None; 8 ];
        let data = self.data();
        for idx in 0..8 {
            if let Some(opcd) = data[idx] {
                let imm = Rv32::decode_imm(opcd);
                res[idx] = Some(imm);
            }
        }
        res
    }
}

#[derive(Clone, Copy)]
struct PredecodeBlock {
    pub pc: ProgramCounter,
    pub imm: [Option<Rv32Imm>; 8],
    pub brn: [Option<BranchKind>; 8],
    pub data: [u32; 8],
    pub last_idx: usize,
}
impl PredecodeBlock {
    pub fn data(&self) -> [Option<u32>; 8] {
        let mut res = [ None; 8 ];
        for idx in self.pc.fblk_start_idx()..8 {
            res[idx] = Some(self.data[idx]);
        }
        res
    }

    pub fn valid_range(&self) -> std::ops::RangeInclusive<usize> {
        self.pc.fblk_start_idx()..=self.last_idx
    }

    pub fn hle_decode(&self) -> [Option<Instr>; 8] {
        let mut res = [ None ; 8];
        let data = self.data();

        for idx in self.valid_range() {
            if let Some(opcd) = data[idx] {
                let inst = Rv32::disas(opcd);
                res[idx] = Some(inst);
            }
        }
        res
    }

    pub fn imm_decode(&self) -> [Option<Rv32Imm>; 8] {
        let mut res = [ None; 8 ];
        let data = self.data();
        for idx in 0..8 {
            if let Some(opcd) = data[idx] {
                let imm = Rv32::decode_imm(opcd);
                res[idx] = Some(imm);
            }
        }
        res
    }

    pub fn from_fetch_block(fblk: &FetchBlock) -> Self {
        let mut imm = [None; 8];
        let mut brn = [None; 8];

        let start_idx = fblk.pc.fblk_start_idx();
        for idx in start_idx..8 {
            let i = Rv32::decode_imm(fblk.data[idx]);
            let b = Rv32::disas(fblk.data[idx]).branch_kind();
            imm[idx] = Some(i);
            brn[idx] = Some(b);
        }

        Self { 
            pc: fblk.pc, 
            imm, 
            brn, 
            data: fblk.data, 
            last_idx: 7
        }
    }

    pub fn first_branch_idx(&self) -> Option<usize> {
        self.brn.iter().enumerate()
            .find(|(idx,bk)| {
                if let Some(kind) = bk {
                    *kind != BranchKind::None
                } else {
                    false
                }
            })
            .map(|(idx, &bk)| idx)
    }

    pub fn first_branch(&self) -> Option<(usize, Rv32Imm, BranchKind)> {
        if let Some(idx) = self.first_branch_idx() {
            Some((idx, self.imm[idx].unwrap(), self.brn[idx].unwrap()))
        } else {
            None
        }
    }

}


#[derive(Clone, Copy)]
struct DecodeBlock {
    pc: ProgramCounter,
    data: [Option<Instr>; 8],
}
impl DecodeBlock {
    //pub fn from_fetch_block(fblk: &FetchBlock) -> Self {
    //    Self { pc: fblk.pc, data: fblk.hle_decode() }
    //}
    pub fn from_predecode_block(pdblk: &PredecodeBlock) -> Self {
        Self { pc: pdblk.pc, data: pdblk.hle_decode() }
    }

    pub fn print(&self) {
        for idx in 0..8 {
            let pc = self.pc.fetch_addr().wrapping_add(idx << 2);
            if let Some(inst) = self.data[idx] {
                println!("{:08x}: {}", pc, inst);
            } else { 
                println!("{:08x}: <none>", pc);
            }
        }
    }
}


#[derive(Clone, Copy, Debug, Default)]
pub struct PhysReg(pub usize);

#[derive(Clone, Copy, Debug)]
pub enum UopStorage {
    Imm(usize),
    Pc,
    PhysReg(usize),

}

#[derive(Clone, Copy, Debug)]
pub enum UopKind {
    AluReg(usize, usize, usize),
    AluImm(usize, usize, usize),
    Illegal, 
}


#[derive(Clone, Copy, Debug)]
pub struct Uop {
    instr: Instr,
    kind: UopKind,
}
impl Uop {
    pub fn nop() -> Self { 
        Self { 
            instr: Instr::Illegal(0xdeadc0de), 
            kind: UopKind::Illegal
        }
    }
}


const MAP_INIT: &[usize; 32] = &[
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
];
type RegisterMap = Mem<usize, 32>;

fn main() {
    const RAM_SIZE: usize = 0x0200_0000;
    let mut ram = Ram::new(RAM_SIZE);
    let entry   = read_prog(&mut ram, "programs/test.elf");


    // Control-flow event
    let mut r_cfe = Reg::<Option<ControlFlowEvent>>::new(
        Some(ControlFlowEvent::ResetVector(ProgramCounter::new(entry)))
    );

    // Fetch target address
    let mut r_fpc = Reg::<Option<ProgramCounter>>::new(None);
    // Fetch block
    let mut r_fblk = Reg::<Option<FetchBlock>>::new(None);
    // Predecode block
    let mut r_pdblk = Reg::<Option<PredecodeBlock>>::new(None);
    // Decode block
    let mut r_dblk = Reg::<Option<DecodeBlock>>::new(None);
    // Rename block
    let mut r_rblk = Reg::<Option<RenameBlock>>::new(None);
    // Register map
    let mut r_map = Mem::<usize, 32>::new_init_array(&MAP_INIT);
    // Freelist
    let mut r_frl = Reg::<Freelist<256>>::new(Freelist::default());


    for cyc in 0..24 {
        println!("================ cycle {} ==================", cyc);

        // If predecode generates a CFE this cycle, we need a wire to 
        // tell the decode stage to avoid 
        let mut redirect_from_predecode = false;


        // Control-flow control
        if let Some(cfe) = r_cfe.sample() {
            let pc = cfe.get_pc();
            println!("Control flow event @ {:08x}, {:08x?}", pc.value(), cfe);
            r_fpc.drive(Some(pc));

            // Generate the next event. 
            // NOTE: This is only relevant if later stages do not drive 
            // 'r_cfe' (in which case, those values will take precedence). 
            let mut npc = ProgramCounter::new(pc.fetch_addr() + 0x20);
            r_cfe.drive(Some(ControlFlowEvent::Sequential(npc)));
        } else {
            println!("No valid CFE for this cycle");
        }

        // Fetch Unit
        if let Some(fpc) = r_fpc.sample() {
            println!("Fetching block @ {:08x}", fpc.fetch_addr());
            let mut tmp = [0u8; 32];
            ram.read_bytes(fpc.fetch_addr(), &mut tmp);
            let mut fblk = FetchBlock::from_bytes(fpc, tmp);
            r_fblk.drive(Some(fblk));
        } else {
            println!("No valid fetch pc to fetch this cycle");
            r_fblk.drive(None);
        }

        // Predecode Unit
        if let Some(fblk) = r_fblk.sample() {
            println!("Predecoding block @ {:08x}", &fblk.pc.value());
            let mut pdblk = PredecodeBlock::from_fetch_block(&fblk);

            // The target of relative call/jmp can be computed here.
            // If we act on this control-flow event:
            //   - Any fetch block generated this cycle must be invalidated
            //   - Any fetch pc generated this cycle must be invalidated
            //   - The decode stage this cycle must ignore the fetch block
            //     generated on the previous cycle
            if let Some((idx, imm, brn)) = pdblk.first_branch() { 
                let pc = pdblk.pc.value() + (idx * 4);

                let static_tgt = match brn {
                    BranchKind::CallRelative => {
                        let imm32 = imm.expand().unwrap();
                        Some(pc as u32 + imm32)
                    },
                    BranchKind::JmpRelative => {
                        let imm32 = imm.expand().unwrap();
                        Some(pc as u32 + imm32)
                    },
                    _ => None,
                };
                if let Some(tgt) = static_tgt { 
                    println!("Discovered branch {:08x}: {:?}, idx={}, tgt={:08x?}", pc, brn, idx, tgt);
                    let npc = ProgramCounter::new(tgt as usize);
                    r_cfe.drive(Some(ControlFlowEvent::Static(brn, npc)));

                    // Flush incorrect spec. from the pipeline
                    redirect_from_predecode = true;
                    r_fblk.drive(None);
                    r_fpc.drive(None);
                }

                // Do not decode speculatively past the branch
                if brn != BranchKind::None {
                    pdblk.last_idx = idx;
                }

            }

            r_pdblk.drive(Some(pdblk));
        } else {
            println!("No valid fetch block to predecode this cycle");
            r_pdblk.drive(None);
        }

        // Decode Unit
        if let Some(pdblk) = r_pdblk.sample() {
            if redirect_from_predecode {
                println!("Decoder ignoring block @ {:08x}", &pdblk.pc.value());
            } 
            else 
            {
                println!("Decoding block @ {:08x}", &pdblk.pc.value());
                let mut dblk = DecodeBlock::from_predecode_block(&pdblk);
                dblk.print();

                r_dblk.drive(Some(dblk));
            }
        } else {
            println!("No valid predecode block to decode this cycle");
        }


        // Rename Unit
        if let Some(dblk) = r_dblk.sample() {
            println!("Renaming block @ {:08x}", &dblk.pc.value());

            let mut frl = r_frl.sample();
            let mut window = RenameWindowInfo::from_decode_block(&dblk);
            window.resolve_dependencies(&r_map);
            window.allocate(&mut frl, &mut r_map).unwrap();
            window.forward_allocs();


            window.print();

            r_frl.drive(frl);
            //r_rblk.drive(Some(rblk));
        } else {
            println!("No valid edecode block to rename this cycle");
        }


        r_fpc.update();
        r_cfe.update();
        r_fblk.update();
        r_pdblk.update();
        r_dblk.update();
        r_frl.update();
        r_map.update();
    }


}
