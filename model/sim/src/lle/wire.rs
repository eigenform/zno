
use std::fmt::Debug;
use crate::lle::*;


/// Representing the state of a simulated wire.
///
/// When simulating clocked circuits, we expect all wires must be assigned a 
/// value before a simulated clock edge occurs. 
///
/// Changes to the value of a wire are instantaneous.
///
pub struct Wire<D: Copy> {
    value: Option<D>,
}
impl <D: Copy> Wire<D> {
    pub fn new() -> Self {
        Self { 
            value: None,
        }
    }
    pub fn drive(&mut self, value: D) {
        self.value = Some(value);
    }
    pub fn sample(&self) -> D {
        if let Some(value) = self.value {
            value
        } else {
            panic!("Wire has no value");
        }
    }

    pub fn reset(&mut self) {
        self.value = None;
    }
}


// By default, we want the state of all wires to be effectively cleared on 
// each simulated clock cycle. The value of all wires must be defined before 
// a clock edge occurs.  

impl <D: Copy> Clocked for Wire<D> {
    fn update(&mut self) {
        if self.value.is_none() {
            panic!("Unassigned wire");
        }
        self.value = None;
    }
}
