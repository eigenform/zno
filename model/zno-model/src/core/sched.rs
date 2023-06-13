

use crate::core::uarch::*;
use crate::common::*;
use std::collections::*;

pub struct SimpleReorderBuffer<const SIZE: usize> {
    alloc_pending: Option<DecodeBlock>,
    data: [Option<DecodeBlock>; SIZE],
    alloc_ptr: usize,
    commit_ptr: usize,
}
impl <const SIZE: usize> SimpleReorderBuffer<SIZE> {
    pub fn new() -> Self {
        Self {
            alloc_pending: None,
            data: [None; SIZE],
            alloc_ptr: 0,
            commit_ptr: 0,
        }
    }
    pub fn alloc_ptr(&self) -> usize { self.alloc_ptr }
    pub fn commit_ptr(&self) -> usize { self.commit_ptr }
    fn inc_alloc(&mut self) {
        if self.alloc_ptr == SIZE - 1 { 
            self.alloc_ptr = 0; 
        } else { 
            self.alloc_ptr += 1; 
        }
    }
    fn inc_commit(&mut self) {
        if self.commit_ptr == SIZE - 1 { 
            self.commit_ptr = 0; 
        } else { 
            self.commit_ptr += 1; 
        }
    }

    fn num_free(&self) -> usize {
        //    alc com
        //     |   |
        // X X F F X X X X
        // 0 1 2 3 4 5 6 7
        if self.commit_ptr > self.alloc_ptr {
            return self.commit_ptr - self.alloc_ptr;
        } 
        //    com alc
        //     |   |
        // F F X X F F F F
        // 0 1 2 3 4 5 6 7
        else if self.commit_ptr < self.alloc_ptr {
            return SIZE - (self.alloc_ptr - self.commit_ptr);
        } 
        else {
            // X X X X X X X X
            // 0 1 2 3 4 5 6 7
            if self.full() {
                return 0;
            } 
            // F F F F F F F F
            // 0 1 2 3 4 5 6 7
            else if self.empty() {
                return SIZE;
            } 
            else {
                unreachable!();
            }
        }
    }

    pub fn full(&self) -> bool {
        self.alloc_ptr == self.commit_ptr &&
        self.data[self.alloc_ptr].is_some() &&
        self.data[self.commit_ptr].is_some()
    }

    pub fn empty(&self) -> bool {
        self.alloc_ptr == self.commit_ptr &&
        self.data[self.alloc_ptr].is_none() &&
        self.data[self.commit_ptr].is_none()
    }


    pub fn drive_alloc(&mut self, dblk: &DecodeBlock) 
        -> Result<usize, &'static str> 
    {
        if self.full() {
            Err("ROB full")
        } else {
            self.alloc_pending = Some(*dblk);
            Ok(self.alloc_ptr)
        }
    }

}
impl <const SIZE: usize> Clocked for SimpleReorderBuffer<SIZE> {
    fn update(&mut self) {
        if let Some(dblk) = self.alloc_pending.take() {
            assert!(self.data[self.alloc_ptr].is_none());
            self.data[self.alloc_ptr] = Some(dblk);
            self.inc_alloc();
        }

    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum IntSchedulerStatus {
    None,
    Pending,
    Done,
}

#[derive(Clone, Copy)]
pub struct IntSchedulerEntry {
    /// Index of the parent decode block
    pub rob_idx: usize,
    /// Offset of this uop within the parent decode block
    pub blk_off: usize,
    /// Scheduled micro-op
    pub uop: MicroOp,
    /// Status for this entry
    pub sts: IntSchedulerStatus,
}

pub struct IntScheduler<const SIZE: usize> {
    data: [Option<IntSchedulerEntry>; SIZE],
}
impl <const SIZE: usize> IntScheduler<SIZE> {
    pub fn new() -> Self {
        Self {
            data: [None; SIZE],
        }
    }
    pub fn available_slots(&self) -> usize {
        self.data.iter().filter(|x| x.is_none()).count()
    }
}

impl <const SIZE: usize> Clocked for IntScheduler<SIZE> {
    fn update(&mut self) {
    }
}
