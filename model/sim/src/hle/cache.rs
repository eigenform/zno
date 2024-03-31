
#[derive(Clone, Copy)]
pub struct CacheWay<const SZ: usize> {
    data: [u8; SZ],
}
impl <const SZ: usize> CacheWay<SZ> {
    pub fn new() -> Self {
        Self { data: [0; SZ] }
    }
}

#[derive(Clone, Copy)]
pub struct CacheSet<const WAYS: usize, const SZ: usize> {
    data: [CacheWay<SZ>; WAYS],
}
impl <const WAYS: usize, const SZ: usize> CacheSet<WAYS, SZ> {
    pub fn new() -> Self {
        Self { data: [CacheWay::new(); WAYS] }
    }
}


pub struct SetAssociativeCache
<const SETS: usize, const WAYS: usize, const SZ: usize> 
{
    data: [CacheSet<SZ, WAYS>; SETS],
}
impl <const SETS: usize, const WAYS: usize, const SZ: usize> 
SetAssociativeCache<SETS, WAYS, SZ> {
    pub fn new() -> Self {
        Self { data: [CacheSet::new(); SETS] }
    }
}




