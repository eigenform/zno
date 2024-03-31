
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
}

#[derive(Clone, Copy, Debug)]
enum ControlFlowEvent {
    ResetVector(ProgramCounter),
}
impl ControlFlowEvent {
    fn get_pc(&self) -> ProgramCounter { 
        match self {
            Self::ResetVector(pc) => *pc,
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

    pub fn hle_decode(&self) -> [Instr; 8] {
        let mut res = [Instr::Illegal(0); 8];
        for idx in 0..8 {
            let inst = Rv32::disas(self.data[idx]);
            res[idx] = inst;
        }
        res
    }
    pub fn imm_decode(&self) -> [Rv32Imm; 8] {
        let mut res = [ Rv32Imm::default(); 8 ];
        for idx in 0..8 {
            let imm = Rv32::decode_imm(self.data[idx]);
            res[idx] = imm;
        }
        res
    }
}

#[derive(Clone, Copy)]
struct PredecodeBlock {
    pc: ProgramCounter,
    imm: [Rv32Imm; 8],
    brn: [BranchKind; 8],
}
impl PredecodeBlock {
    pub fn from_fetch_block(fblk: &FetchBlock) -> Self {
        let mut imm = [ Rv32Imm::default(); 8 ];
        let mut brn = [ BranchKind::None; 8 ];
        for idx in 0..8 {
            let i = Rv32::decode_imm(fblk.data[idx]);
            let b = Rv32::disas(fblk.data[idx]).branch_kind();
            imm[idx] = i;
            brn[idx] = b;
        }

        Self { pc: fblk.pc, imm, brn }
    }
    pub fn first_branch_idx(&self) -> Option<usize> {
        self.brn.iter().enumerate()
            .find(|(idx,&bk)| bk != BranchKind::None)
            .map(|(idx, &bk)| idx)
    }

}


#[derive(Clone, Copy)]
struct DecodeBlock {
    pc: ProgramCounter,
    data: [Instr; 8],
}
impl DecodeBlock {
    pub fn from_fetch_block(fblk: &FetchBlock) -> Self {
        Self { pc: fblk.pc, data: fblk.hle_decode() }
    }



    pub fn print(&self) {
        for idx in 0..8 {
            let pc = self.pc.fetch_addr().wrapping_add(idx << 2);
            if idx < self.pc.fblk_start_idx() {
                println!("{:08x}: X {}", pc, self.data[idx]);
            } else {
                println!("{:08x}:   {}", pc, self.data[idx]);
            }
        }
    }
}

#[derive(Clone, Copy, Debug)]
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

struct RenameWindowInfo {
    rd_arr: [Option<ArchReg>; 8],
    rs1_arr: [Option<ArchReg>; 8],
    rs2_arr: [Option<ArchReg>; 8],

    rs1_dep: [Option<usize>; 8],
    rs2_dep: [Option<usize>; 8],

    pd_arr: [Option<PhysReg>; 8],
    ps1_arr: [Option<PhysReg>; 8],
    ps2_arr: [Option<PhysReg>; 8],
}
impl RenameWindowInfo {
    pub fn new() -> Self { 
        Self { 
            rd_arr: [ None; 8 ],
            rs1_arr: [ None; 8 ],
            rs2_arr: [ None; 8 ],
            rs1_dep: [ None; 8 ],
            rs2_dep: [ None; 8 ],

            pd_arr: [None; 8],
            ps1_arr: [None; 8],
            ps2_arr: [None; 8],
        }
    }
}

struct RegisterRename; 
impl RegisterRename {
    pub fn get_window(dblk: &DecodeBlock) -> RenameWindowInfo {
        let mut window = RenameWindowInfo::new();
        for (idx, instr) in dblk.data.iter().enumerate() {
            window.rd_arr[idx] = instr.rd();
            window.rs1_arr[idx] = instr.rs1();
            window.rs2_arr[idx] = instr.rs2();

            if let Some(rs1) = instr.rs1() {
                let dep_window = &window.rd_arr[0..idx];
                for j in (0..idx).rev() {
                    if let Some(rd) = dep_window[j] {
                        if rd == rs1 {
                            window.rs1_dep[idx] = Some(j);
                            break;
                        }
                    }
                }
            }
            if let Some(rs2) = instr.rs1() {
                let dep_window = &window.rd_arr[0..idx];
                for j in (0..idx).rev() {
                    if let Some(rd) = dep_window[j] {
                        if rd == rs2 {
                            window.rs2_dep[idx] = Some(j);
                            break;
                        }
                    }
                }
            }

        }
        window
    }
}

#[derive(Clone, Copy)]
struct RenameBlock {
    pc: ProgramCounter,
    data: [Uop; 8],
}
impl RenameBlock {
    pub fn from_decode_block(dblk: &DecodeBlock) -> Self {
        let mut data = [ Uop::nop(); 8];

        let mut rd_arr  = [ None; 8 ];
        let mut rs1_arr = [ None; 8 ];
        let mut rs1_dep = [ None; 8 ];
        let mut rs2_arr = [ None; 8 ];
        let mut rs2_dep = [ None; 8 ];

        for (idx, instr) in dblk.data.iter().enumerate() {
            rd_arr[idx] = instr.rd();
            rs1_arr[idx] = instr.rs1();
            rs2_arr[idx] = instr.rs2();

            if let Some(rs1) = instr.rs1() {
                let dep_window = &rd_arr[0..idx];
                for j in (0..idx).rev() {
                    if let Some(rd) = dep_window[j] {
                        if rd == rs1 {
                            rs1_dep[idx] = Some(j);
                            break;
                        }
                    }
                }
            }
            if let Some(rs2) = instr.rs1() {
                let dep_window = &rd_arr[0..idx];
                for j in (0..idx).rev() {
                    if let Some(rd) = dep_window[j] {
                        if rd == rs2 {
                            rs2_dep[idx] = Some(j);
                            break;
                        }
                    }
                }
            }



            data[idx].instr = *instr;
        }

        println!("{:?}", rd_arr.iter().flatten());
        println!("{:?}", rs1_arr);
        println!("{:?}", rs2_arr);
        println!("{:?}", rs1_dep);
        println!("{:?}", rs2_dep);




        Self { pc: dblk.pc, data }
    }



    pub fn print(&self) {
        for idx in 0..8 {
            let pc = self.pc.fetch_addr().wrapping_add(idx << 2);
            if idx < self.pc.fblk_start_idx() {
                println!("{:08x}: X {:?}", pc, self.data[idx]);
            } else {
                println!("{:08x}:   {:?}", pc, self.data[idx]);
            }
        }
    }
}





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
    // Decode block
    let mut r_dblk = Reg::<Option<DecodeBlock>>::new(None);
    let mut r_rblk = Reg::<Option<RenameBlock>>::new(None);

    let mut r_rmap = RegisterFile::<usize, 32>::new_init(
        [ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
          16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31 ]
    );


    for cyc in 0..16 {
        println!("================ cycle {} ==================", cyc);

        // Control-flow control
        if let Some(cfe) = r_cfe.sample() {
            let pc = cfe.get_pc();
            println!("Control flow event @ {:08x}", pc.value());
            r_fpc.drive(Some(pc));
            r_cfe.drive(None);
        }

        // Fetch Unit
        if let Some(fpc) = r_fpc.sample() {
            println!("Fetching block @ {:08x}", fpc.fetch_addr());
            let mut tmp = [0u8; 32];
            ram.read_bytes(fpc.fetch_addr(), &mut tmp);
            let mut fblk = FetchBlock::from_bytes(fpc, tmp);
            r_fpc.drive(None);
            r_fblk.drive(Some(fblk));
        }

        // Decode Unit
        if let Some(fblk) = r_fblk.sample() {
            println!("Decoding block @ {:08x}", &fblk.pc.value());
            let mut pdblk = PredecodeBlock::from_fetch_block(&fblk);
            let mut dblk = DecodeBlock::from_fetch_block(&fblk);
            dblk.print();

            r_fblk.drive(None);
            r_dblk.drive(Some(dblk));
        }

        // Rename Unit
        if let Some(dblk) = r_dblk.sample() {
            println!("Renaming block @ {:08x}", &dblk.pc.value());

            let mut info = RegisterRename::get_window(&dblk);

            //let mut rblk = RenameBlock::from_decode_block(&dblk);
            //rblk.print();


            r_dblk.drive(None);
            //r_rblk.drive(Some(rblk));
        }


        r_fpc.update();
        r_cfe.update();
        r_fblk.update();
        r_dblk.update();
    }


}
