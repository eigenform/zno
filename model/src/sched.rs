
use crate::state::*;
use crate::packet::*;
use crate::uarch::*;
use crate::prim::*;

pub struct IntegerScheduler<const CAP: usize, const ISIZE: usize> {
    data: [Option<IntegerUop>; CAP],
    input: Option<Packet<IntegerUop, ISIZE>>,
}
impl <const CAP: usize, const ISIZE: usize> IntegerScheduler<CAP, ISIZE> {
    pub fn new() -> Self { 
        Self { 
            data: [None; CAP],
            input: None,
        }
    }
}

impl <const CAP: usize, const ISIZE: usize> Storage<CAP, ISIZE>
    for IntegerScheduler<CAP, ISIZE> 
{
    fn num_used(&self) -> usize {
        self.data.iter().filter(|x| x.is_some()).count()
    }
}


impl <const CAP: usize, const ISIZE: usize> Clocked 
    for IntegerScheduler<CAP, ISIZE> 
{
    fn name(&self) -> &str { "sch" }
    fn update(&mut self) {
    }
}


