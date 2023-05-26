//! RISC-V ABI

#[derive(Debug)]
pub enum RvPkSyscall {
    Exit,
}
impl RvPkSyscall {
    pub fn from_u32(x: u32) -> Self { 
        match x { 
            93 => Self::Exit,
            _ => unimplemented!("syscall {}", x),
        }
    }
}


