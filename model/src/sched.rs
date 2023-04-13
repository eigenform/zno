
use std::collections::*;
use crate::state::*;
use crate::packet::*;
use crate::uarch::*;
use crate::prim::*;

pub struct IntegerScheduler<const CAP: usize, const ISIZE: usize> {
    data: VecDeque<IntegerUop>,
    input: Option<Packet<IntegerUop, ISIZE>>,
}
impl <const CAP: usize, const ISIZE: usize> IntegerScheduler<CAP, ISIZE> {
    pub fn new() -> Self { 
        Self { 
            data: VecDeque::new(),
            input: None,
        }
    }

    pub fn push(&mut self, p: Packet<IntegerUop, ISIZE>) {
        self.input = Some(p);
    }

}

impl <const CAP: usize, const ISIZE: usize> Storage<CAP, ISIZE>
    for IntegerScheduler<CAP, ISIZE> 
{
    fn num_used(&self) -> usize {
        self.data.len()
    }
}


impl <const CAP: usize, const ISIZE: usize> Clocked 
    for IntegerScheduler<CAP, ISIZE> 
{
    fn name(&self) -> &str { "sch" }
    fn update(&mut self) {
        if let Some(input) = &self.input {  
            assert!(self.num_free() >= input.valid_len());
            for idx in 0..input.valid_len() { 
                self.data.push_back(input[idx]);
            }
            self.input = None;
        } else { 
            unreachable!("IntegerScheduler had no input driven this cycle?");
        }
        self.data.make_contiguous();

    }
}


