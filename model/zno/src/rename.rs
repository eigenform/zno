
use sim::hle::riscv::*;

use crate::*;

#[derive(Copy, Clone)]
pub struct Freelist<const SZ: usize> { 
    data: [bool; SZ],
}
impl <const SZ: usize> Default for Freelist<SZ> {
    fn default() -> Self { 
        let mut res = Self { data: [true; SZ] };
        res.data[0] = false;
        res
    }
}
impl <const SZ: usize> Freelist<SZ> {
    pub fn num_free(&self) -> usize {
        self.data.iter().filter(|s| **s == true).count()
    }
    pub fn peek_next_alc(&self) -> Option<usize> { 
        self.data.iter().enumerate()
            .find(|(idx, val)| **val)
            .map(|(idx, val)| idx)
    }
    pub fn take_next_alc(&mut self) -> Option<usize> { 
        if let Some(idx) = self.peek_next_alc() {
            assert!(idx != 0);
            self.data[idx] = false;
            return Some(idx);
        }
        None
    }

}


#[derive(Copy, Clone)]
pub struct DepInfo {
    rd: Option<ArchReg>,
    rs1: Option<ArchReg>,
    rs2: Option<ArchReg>,

    rs1_dep: Option<usize>,
    rs2_dep: Option<usize>,

    pd: Option<usize>,
    ps1: Option<usize>,
    ps2: Option<usize>,
}

pub struct RenameWindowInfo {
    valid: [bool; 8],

    rd_arr: [Option<ArchReg>; 8],
    rs1_arr: [Option<ArchReg>; 8],
    rs2_arr: [Option<ArchReg>; 8],

    rs1_dep: [Option<usize>; 8],
    rs2_dep: [Option<usize>; 8],

    pd_arr: [Option<usize>; 8],
    ps1_arr: [Option<usize>; 8],
    ps2_arr: [Option<usize>; 8],

}
impl RenameWindowInfo {
    pub fn new() -> Self { 
        Self { 
            valid: [false; 8],
            rd_arr:  [None; 8],
            rs1_arr: [None; 8],
            rs2_arr: [None; 8],
            rs1_dep: [None; 8],
            rs2_dep: [None; 8],
            pd_arr:  [None; 8],
            ps1_arr: [None; 8],
            ps2_arr: [None; 8],
        }
    }

    pub fn from_decode_block(dblk: &DecodeBlock) -> Self { 
        let mut res = Self::new();

        for (idx, entry) in dblk.data.iter().enumerate() {
            if let Some(instr) = entry { 
                res.valid[idx]   = true;
                res.rd_arr[idx]  = instr.rd();
                res.rs1_arr[idx] = instr.rs1();
                res.rs2_arr[idx] = instr.rs2();
            }
        }
        res
    }

    pub fn print(&self) {
        let idx = [0,1,2,3,4,5,6,7].map(|v| format!("{:>3}", v)).join("|");
        let valid = self.valid.map(|v| {
            let x = match v { true => "t", false => "f" };
            format!("{:>3}", x)
        }).join("|");

        let rd = self.rd_arr.map(|v| 
            if let Some(rd) = v { format!("{:>3}", rd.as_usize()) } else { "XXX".to_string() }
        ).join("|");
        let rs1 = self.rs1_arr.map(|v| 
            if let Some(rs1) = v { format!("{:>3}", rs1.as_usize()) } else { "XXX".to_string() }
        ).join("|");
        let rs2 = self.rs2_arr.map(|v| 
            if let Some(rs2) = v { format!("{:>3}", rs2.as_usize()) } else { "XXX".to_string() }
        ).join("|");

        let rs1_dep = self.rs1_dep.map(|v| 
            if let Some(idx) = v { format!("{:>3}", idx) } else { "XXX".to_string() }
        ).join("|");
        let rs2_dep = self.rs2_dep.map(|v| 
            if let Some(idx) = v { format!("{:>3}", idx) } else { "XXX".to_string() }
        ).join("|");


        let pd = self.pd_arr.map(|v| 
            if let Some(pd) = v { format!("{:>3}", pd) } else { "XXX".to_string() }
        ).join("|");
        let ps1 = self.ps1_arr.map(|v| 
            if let Some(ps1) = v { format!("{:>3}", ps1) } else { "XXX".to_string() }
        ).join("|");
        let ps2 = self.ps2_arr.map(|v| 
            if let Some(ps2) = v { format!("{:>3}", ps2) } else { "XXX".to_string() }
        ).join("|");


        println!("idx=     {}", idx);
        println!("valid=   {}", valid);
        println!("         ---+---+---+---+---+---+---+---");
        println!(" rd=     {}", rd);
        println!("rs1=     {}", rs1);
        println!("rs2=     {}", rs2);
        println!("rs1_dep= {}", rs1_dep);
        println!("rs2_dep= {}", rs2_dep);

        println!("pd=      {}", pd);
        println!("ps1=     {}", ps1);
        println!("ps2=     {}", ps2);
    }

    pub fn resolve_dependencies(&mut self, map: &RegisterMap) {

        // For each source register operand in each instruction (with the 
        // exception of the youngest instruction in the window), find the 
        // index of the most-recent previous instruction which writes to 
        // the source register. 
        //
        // NOTE: The youngest instruction has no dependencies in this window. 
        // We always need to resolve them with the map (if they exist).

        for consumer_idx in 1..8 {
            let mut rs1_provider_idx = None;
            if let Some(rs1) = self.rs1_arr[consumer_idx] {
                'scan_rs1: for provider_idx in (0..consumer_idx).rev() {
                    if let Some(rd) = self.rd_arr[provider_idx] {
                        if rs1 == rd { 
                            rs1_provider_idx = Some(provider_idx);
                            break 'scan_rs1;
                        }
                    }
                }
            }

            let mut rs2_provider_idx = None;
            if let Some(rs2) = self.rs2_arr[consumer_idx] {
                'scan_rs2: for provider_idx in (0..consumer_idx).rev() {
                    if let Some(rd) = self.rd_arr[provider_idx] {
                        if rs2 == rd { 
                            rs2_provider_idx = Some(provider_idx);
                            break 'scan_rs2;
                        }
                    }
                }
            }
            self.rs1_dep[consumer_idx] = rs1_provider_idx;
            self.rs2_dep[consumer_idx] = rs2_provider_idx;
        }

        // For each source register operand in each instruction which 
        // *does not* have a local dependency, use the register map to 
        // resolve the physical register.

        for idx in 0..8 {
            if let Some(rs1) = self.rs1_arr[idx] {
                if self.rs1_dep[idx].is_none() {
                    self.ps1_arr[idx] = Some(map.sample(rs1.as_usize()));
                }
            }

            if let Some(rs2) = self.rs2_arr[idx] {
                if self.rs2_dep[idx].is_none() {
                    self.ps2_arr[idx] = Some(map.sample(rs2.as_usize()));
                }
            }
        }
    }

    pub fn allocate<const SZ: usize>(&mut self, 
        frl: &mut Freelist<SZ>, map: &mut RegisterMap,
    ) -> Result<(), ()>
    {
        let req_alcs = self.rd_arr.iter().filter(|x| x.is_some()).count();
        let num_free = frl.num_free();
        if req_alcs > num_free {
            return Err(());
        }

        for idx in 0..8 { 
            if let Some(rd) = self.rd_arr[idx] {
                let pd = frl.take_next_alc().unwrap();
                self.pd_arr[idx] = Some(pd);

                map.drive(rd.as_usize(), pd);
            }
        }

        Ok(())
    }

    pub fn forward_allocs(&mut self) {
        for consumer_idx in 1..8 { 
            if let Some(provider_idx) = self.rs1_dep[consumer_idx] {
                let ps1 = self.pd_arr[provider_idx].unwrap();
                self.ps1_arr[consumer_idx] = Some(ps1);
            }
            if let Some(provider_idx) = self.rs2_dep[consumer_idx] {
                let ps2 = self.pd_arr[provider_idx].unwrap();
                self.ps2_arr[consumer_idx] = Some(ps2);
            }

        }


    }

}

#[derive(Clone, Copy)]
pub struct RenameBlock {
    pub pc: ProgramCounter,
    pub data: [Uop; 8],
}
impl RenameBlock {
}


