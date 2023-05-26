
use std::collections::*;
use std::fmt::Debug;
use crate::sim::*;

///// A FIFO queue with emulated sequential semantics.
//pub struct Queue<T, const CAP: usize> 
//    where T: Copy + Default + Debug
//{
//    name: String,
//    data: VecDeque<T>,
//    input: Option<T>,
//    take: Option<()>,
//}
//
//impl <T, const CAP: usize> Queue<T, CAP> 
//    where T: Copy + Default + Debug
//{
//    pub fn new(name: impl ToString) -> Self {
//        Self { 
//            name: name.to_string(),
//            data: VecDeque::with_capacity(CAP),
//            input: None,
//            take: None,
//        }
//    }
//
//    pub fn dbg_push(&mut self, e: T) {
//        self.data.push_back(e);
//        self.data.make_contiguous();
//    }
//
//    /// Return the oldest element in the queue.
//    pub fn output(&self) -> Option<&T> {
//        self.data.front()
//    }
//
//    /// Send a value to-be-enqueued on the next clock edge.
//    pub fn push(&mut self, input: T) {
//        self.input = Some(input);
//    }
//
//    /// Indicate that the current output has been consumed this cycle,
//    /// dequeuing the oldest element on the next clock edge.
//    pub fn pop(&mut self) {
//        assert!(self.data.len() != 0);
//        self.take = Some(());
//    }
//}
//
//impl <T, const CAP: usize> Clocked for Queue<T, CAP> 
//    where T: Copy + Default + Debug
//{
//    fn name(&self) -> &str { &self.name }
//    fn update(&mut self) { 
//        // Take an entry off the queue
//        if self.take.is_some() {
//            self.data.pop_front().unwrap();
//            self.take = None;
//        }
//        // Put a new entry onto the queue 
//        if let Some(input) = &self.input {  
//            assert!(self.data.len() + 1 < CAP);
//            self.data.push_back(*input);
//            self.input = None;
//        }
//        self.data.make_contiguous();
//    }
//}

