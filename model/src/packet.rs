
use std::collections::*;
use std::fmt::Debug;
use std::ops::{ Index, IndexMut };

use crate::state::*;
use crate::prim::*;



#[derive(Debug)]
pub struct Packet<T, const SIZE: usize> 
    where T: Copy + Default + Debug
{
    data: [Option<T>; SIZE]
}
impl <T, const SIZE: usize> Packet<T, SIZE> 
    where T: Copy + Default + Debug
{
    /// Create a new empty [Packet].
    pub fn new() -> Self { 
        Self { data: [None; SIZE] }
    }

    /// Return the maximum width (number of entries) for this packet.
    pub fn capacity(&self) -> usize { 
        SIZE
    }

    /// Returns true if this packet has no valid entries.
    pub fn is_empty(&self) -> bool { 
        self.data.iter().all(|x| x.is_none())
    }

    /// Returns true if this packet is filled with valid entries.
    pub fn is_full(&self) -> bool { 
        self.data.iter().all(|x| x.is_some())
    }

    /// Return the number of *valid* entries in this packet.
    pub fn len(&self) -> usize { 
        self.data.iter().filter(|x| x.is_some()).count()
    }

    /// Return an iterator over all *valid* entries in this packet.
    pub fn iter(&self) -> impl Iterator<Item=&T> + '_ {
        self.data.iter().flat_map(|x| x)
    }

    pub fn dump(&self, s: &'static str) {
        println!("| {}", s);
        for idx in 0..SIZE {
            println!("| [{:02}] {:x?}", idx, self.data[idx]);
        }
    }
}

impl <T, const SIZE: usize> Index<usize> for Packet<T, SIZE> 
    where T: Copy + Default + Debug
{
    type Output = T;
    fn index(&self, idx: usize) -> &Self::Output {
        assert!(idx < SIZE);
        self.data[idx].as_ref().unwrap()
    }
}

impl <T, const SIZE: usize> IndexMut<usize> for Packet<T, SIZE> 
    where T: Copy + Default + Debug
{
    fn index_mut(&mut self, idx: usize) -> &mut Self::Output {
        if self.data[idx].is_none() {
            self.data[idx].insert(T::default())
        } else { 
            self.data[idx].as_mut().unwrap()
        }
    }
}

impl <T, const SIZE: usize> Default for Packet<T, SIZE> 
    where T: Copy + Default + Debug
{
    fn default() -> Self { 
        Self { data: [None; SIZE] }
    }
}


pub struct PacketQueue<T, const CAP: usize, const PSIZE: usize>
    where T: Copy + Default + Debug
{
    name: String,
    data: VecDeque<T>,
    input: Option<Packet<T, PSIZE>>,
    take: Option<usize>,
}

impl <T, const CAP: usize, const PSIZE: usize> Storage<CAP, PSIZE> 
    for PacketQueue<T, CAP, PSIZE> 
    where T: Copy + Default + Debug
{
    fn num_used(&self) -> usize { self.data.len() }
}

impl <T, const CAP: usize, const PSIZE: usize> PacketQueue<T, CAP, PSIZE> 
    where T: Copy + Default + Debug
{
    pub fn new(name: impl ToString) -> Self {
        Self { 
            name: name.to_string(),
            data: VecDeque::with_capacity(CAP),
            input: None,
            take: None,
        }
    }

    /// Return a [Packet] with the oldest entries in the queue.
    ///
    /// The maximum number of entries in the output is given by `PSIZE`.
    pub fn output(&self) -> Packet<T, PSIZE> {
        let mut res = Packet::new();
        if self.data.len() >= PSIZE { 
            for idx in 0..PSIZE {
                res[idx] = *self.data.get(idx).unwrap();
            }
        } else {
            for idx in 0..self.data.len() {
                res[idx] = *self.data.get(idx).unwrap();
            }
        }
        res
    }

    /// Producer interface: submit a packet to the queue, to-be-latched on 
    /// the next clock edge.
    pub fn push(&mut self, input: Packet<T, PSIZE>) {
        self.input = Some(input);
    }

    /// Consumer interface: indicate how many entries have been consumed
    /// from the output for this cycle. Consumed entries are dequeued on
    /// the next clock edge. 
    pub fn consume(&mut self, take: usize) {
        assert!(take < PSIZE);
        assert!(take < self.data.len());
        self.take = Some(take);
    }
}

impl <T, const CAP: usize, const PSIZE: usize> 
    Clocked for PacketQueue<T, CAP, PSIZE> 
    where T: Copy + Default + Debug
{
    fn name(&self) -> &str { &self.name }
    fn update(&mut self) {
        // Consume entries from the queue
        if let Some(take) = self.take {
            for _ in 0..take {
                self.data.pop_front().unwrap();
            }
            self.take = None;
        }
        // Push new entries onto the queue
        if let Some(input) = &self.input {  
            assert!(input.len() + self.data.len() <= CAP);
            for idx in 0..input.len() { 
                self.data.push_back(input[idx]);
            }
            self.input = None;
        } else { 
            unreachable!("PacketQueue {} had no input driven this cycle?",
                self.name);
        }
        self.data.make_contiguous();
    }
}


