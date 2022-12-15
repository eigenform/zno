

#[derive(Clone, Copy, Debug)]
pub enum IntegerUopKind {
    Nop,
    Add, Sub, And, Or, Xor, Sll, Srl, Sra,
    Lt, Ge, Ltu, Geu, Eq, Neq
}
impl Default for IntegerUopKind {
    fn default() -> Self { Self::Nop }
}

#[derive(Clone, Copy, Debug)]
pub enum Operand {
    Imm(u32),
    PhysReg(usize),
}
impl Default for Operand { 
    fn default() -> Self { Self::PhysReg(0) }
}

#[derive(Clone, Copy, Debug, Default)]
pub struct IntegerUop {
    kind: IntegerUopKind,
    x: Operand,
    y: Operand,
}


