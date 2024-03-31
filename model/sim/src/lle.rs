//! Low-level emulation. 
//!
//! These are abstractions for representing/simulating clocked circuits. 
//! The idea is to *approximate* the semantics of a behavioral RTL so that 
//! it's a little bit easier to write Rust programs that look and behave 
//! *somewhat* like a simulated behavioral RTL. We're doing it this way in 
//! an attempt to avoid writing some kind of DSL inside Rust macros. 
//!
//! [ClockedState] is an example of a container used to synchronize updates to 
//! multiple simulated clocked components.
//!
//! All clocked components must implement [Clocked], which specifies how the 
//! internal state of an object should change at clock edges.
//!

pub mod wire;
pub mod register;
pub mod mem;
pub mod syncmem;
pub mod cam;
pub mod queue;

use std::fmt::Debug;
use std::cell::*;
use std::rc::*;

/// Interface to a clocked component. 
pub trait Clocked { 
    /// Simulate a clock edge, mutating some internal state. 
    fn update(&mut self);
}

/// A shared mutable reference to some clocked component.
pub type StateRef<T> = Rc<RefCell<T>>;

/// A container for components sharing the same clock signal. 
pub struct ClockedState {
    cycle: usize,
    /// The set of clocked components being tracked.
    components: Vec<StateRef<dyn Clocked>>,
}
impl ClockedState { 
    /// Create a new clock domain. 
    pub fn new() -> Self { 
        Self { 
            cycle: 0,
            components: Vec::new(),
        }
    }

    /// Move an object implementing [Clocked] into the container an return 
    /// the resulting [StateRef].
    pub fn track<T>(&mut self, obj: &StateRef<T>)
        where T: Clocked + 'static
    {
        self.components.push(obj.clone());
    }

    pub fn cycle(&self) -> usize {
        self.cycle
    }
}

impl Clocked for ClockedState {
    // Update all tracked components.
    fn update(&mut self) {
        for entry in self.components.iter() {
            entry.borrow_mut().update()
        }
        self.cycle += 1;
    }
}


