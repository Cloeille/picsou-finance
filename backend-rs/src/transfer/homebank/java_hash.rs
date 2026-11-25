//! The iteration order of a java.util.HashSet<String>, which decides the order of QIF
//! categories: by bucket of the final table, then by insertion within a bucket (a resize
//! splits each bucket without reordering it).

#[derive(Debug, Clone)]
pub struct JavaHashSet {
    keys: Vec<String>,
    capacity: usize,
}

impl Default for JavaHashSet {
    /// new HashSet<>() / new HashMap<>(): a table of 16.
    fn default() -> Self {
        Self {
            keys: Vec::new(),
            capacity: 16,
        }
    }
}

/// String.hashCode over UTF-16 units, spread as HashMap.hash does.
fn spread_hash(value: &str) -> u32 {
    let h = value.encode_utf16().fold(0i32, |h, unit| {
        h.wrapping_mul(31).wrapping_add(i32::from(unit))
    });
    let h = h as u32;
    h ^ (h >> 16)
}

impl JavaHashSet {
    /// new HashSet<>(collection) on JDK 21: HashMap.newHashMap(max(size, 12)), then addAll.
    pub fn with_initial(keys: Vec<String>) -> Self {
        let wanted = keys.len().max(12).div_ceil(3) * 4;
        let mut set = Self {
            keys: Vec::new(),
            capacity: wanted.next_power_of_two().max(1),
        };
        for key in &keys {
            set.add(key);
        }
        set
    }

    pub fn add(&mut self, key: &str) -> bool {
        if self.keys.iter().any(|k| k == key) {
            return false;
        }
        self.keys.push(key.to_owned());
        while self.keys.len() > self.capacity / 4 * 3 {
            self.capacity *= 2;
        }
        true
    }

    pub fn len(&self) -> usize {
        self.keys.len()
    }

    /// The keys in the order Java iterates them.
    pub fn iter_java(&self) -> Vec<String> {
        let mask = u32::try_from(self.capacity - 1).unwrap_or(u32::MAX);
        let mut ordered: Vec<(u32, usize, &String)> = self
            .keys
            .iter()
            .enumerate()
            .map(|(seq, key)| (spread_hash(key) & mask, seq, key))
            .collect();
        ordered.sort_by_key(|(bucket, seq, _)| (*bucket, *seq));
        ordered.into_iter().map(|(_, _, key)| key.clone()).collect()
    }
}
