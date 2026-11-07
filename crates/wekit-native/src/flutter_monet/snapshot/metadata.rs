use std::collections::{HashMap, HashSet};
use std::ops::Range;

use anyhow::{Context, Result, anyhow, bail, ensure};
use memchr::{memchr, memmem};

use super::{Reader, ScanBudget, SnapshotInfo, locate_strings, unsigned_bytes};

#[derive(Clone, Debug)]
pub struct ClassInfo {
    pub reference: u32,
    pub name_reference: u32,
    pub super_class: Option<u32>,
    pub interfaces: Vec<u32>,
    pub instance_size: i32,
    pub next_field_offset: i32,
    pub unboxed_bitmap: u64,
}

#[derive(Debug)]
pub struct Metadata {
    pub classes: HashMap<u32, ClassInfo>,
    pub colors: HashMap<u32, [u64; 4]>,
    pub color_space_reference: u32,
}

struct Cluster {
    cid: u32,
    canonical: bool,
    first_ref: u32,
    count: usize,
    lengths: Vec<u32>,
    predefined: Vec<u32>,
    next_field: i32,
    instance_size: i32,
}

impl Cluster {
    fn contains(&self, reference: u32) -> bool {
        reference >= self.first_ref && ((reference - self.first_ref) as usize) < self.count
    }
}

struct Allocation {
    base_objects: u32,
    object_count: u32,
    snapshot_size: usize,
    fill_start: usize,
    clusters: Vec<Cluster>,
    kind_ranges: HashMap<u32, Vec<Range<u32>>>,
}

impl Allocation {
    fn cluster(&self, cid: u32) -> Result<&Cluster> {
        let mut matches = self.clusters.iter().filter(|cluster| cluster.cid == cid);
        let selected = matches
            .next()
            .ok_or_else(|| anyhow!("snapshot has no metadata cluster {cid}"))?;
        ensure!(
            matches.next().is_none(),
            "metadata cluster {cid} is ambiguous"
        );
        Ok(selected)
    }

    fn has_kind(&self, reference: u32, kinds: &[u32]) -> bool {
        reference <= self.base_objects
            || kinds.iter().any(|kind| {
                self.kind_ranges
                    .get(kind)
                    .is_some_and(|ranges| ranges.iter().any(|range| range.contains(&reference)))
            })
    }
}

fn reference(reader: &mut Reader<'_>, objects: u32) -> Result<u32> {
    let value = reader.reference()?;
    ensure!(
        (1..=objects).contains(&value),
        "metadata reference is out of bounds"
    );
    Ok(value)
}

fn canonical_layout(reader: &mut Reader<'_>, count: usize) -> Result<()> {
    let table_length = reader.count()?;
    let first = reader.count()?;
    ensure!(
        first <= count && table_length <= reader.data.len().saturating_mul(16),
        "invalid metadata canonical-set layout"
    );
    for _ in first..count {
        reader.unsigned()?;
    }
    Ok(())
}

fn allocation(data: &[u8], expected_objects: u32) -> Result<Allocation> {
    let mut reader = Reader::new(data, 0);
    ensure!(
        reader.bytes(4)? == b"\xf5\xf5\xdc\xdc",
        "invalid metadata snapshot magic"
    );
    let size = u64::from_le_bytes(reader.bytes(8)?.try_into().unwrap());
    let snapshot_size = usize::try_from(size)
        .ok()
        .and_then(|value| value.checked_add(4))
        .filter(|&value| value <= data.len())
        .ok_or_else(|| anyhow!("metadata snapshot size is out of bounds"))?;
    ensure!(
        u64::from_le_bytes(reader.bytes(8)?.try_into().unwrap()) == 3,
        "metadata snapshot is not FullAOT"
    );
    reader.bytes(32)?;
    let feature_start = reader.position;
    let features = data
        .get(feature_start..snapshot_size)
        .ok_or_else(|| anyhow!("metadata features are truncated"))?;
    let feature_length =
        memchr(0, features).ok_or_else(|| anyhow!("unterminated metadata features"))?;
    reader = Reader::new(&data[..snapshot_size], feature_start + feature_length + 1);
    let base_objects =
        u32::try_from(reader.unsigned()?).context("metadata base count overflows")?;
    let object_count =
        u32::try_from(reader.unsigned()?).context("metadata object count overflows")?;
    ensure!(
        object_count == expected_objects && base_objects < object_count && object_count < u32::MAX,
        "metadata does not match the parsed snapshot"
    );
    let cluster_count = reader.count()?;
    ensure!(
        cluster_count <= snapshot_size / 2,
        "metadata has too many clusters"
    );
    reader.unsigned()?;
    reader.unsigned()?;
    let mut next_ref = base_objects + 1;
    let mut clusters = Vec::new();
    for _ in 0..cluster_count {
        let tags = u32::try_from(reader.signed(32)?).context("negative metadata cluster tag")?;
        let cid = tags >> 12;
        let canonical = tags & 2 != 0;
        let count = if cid == 16 { 0 } else { reader.count()? };
        ensure!(
            count <= object_count as usize,
            "metadata cluster count is out of bounds"
        );
        let mut cluster = Cluster {
            cid,
            canonical,
            first_ref: next_ref,
            count,
            lengths: Vec::new(),
            predefined: Vec::new(),
            next_field: 0,
            instance_size: 0,
        };
        match cid {
            16 => {}
            5 => {
                ensure!(count <= 175, "unsupported predefined class allocation");
                for _ in 0..count {
                    cluster.predefined.push(
                        u32::try_from(reader.signed(32)?)
                            .context("negative predefined class ID")?,
                    );
                }
                cluster.count = count
                    .checked_add(reader.count()?)
                    .ok_or_else(|| anyhow!("class count overflows"))?;
            }
            18 => {
                for _ in 0..count {
                    reader.signed(32)?;
                }
                let deferred = reader.count()?;
                ensure!(
                    deferred <= object_count as usize,
                    "too many deferred metadata codes"
                );
                for _ in 0..deferred {
                    reader.signed(32)?;
                }
                cluster.count = count
                    .checked_add(deferred)
                    .ok_or_else(|| anyhow!("code count overflows"))?;
            }
            61 => {
                for _ in 0..count {
                    reader.signed(64)?;
                }
            }
            49..=52 => {
                if canonical {
                    canonical_layout(&mut reader, count)?;
                }
            }
            1 | 17 | 23..=26 | 28..=30 | 47 | 67 | 90 | 91 | 93 => {
                ensure!(
                    count <= reader.data.len() - reader.position,
                    "metadata lengths exceed allocation data"
                );
                for _ in 0..count {
                    let length =
                        u32::try_from(reader.unsigned()?).context("metadata length overflows")?;
                    if matches!(cid, 90 | 91 | 93) {
                        cluster.lengths.push(length);
                    }
                }
                if canonical && matches!(cid, 47 | 93) {
                    canonical_layout(&mut reader, count)?;
                }
            }
            112..=167 if (cid - 112).is_multiple_of(4) => {
                for _ in 0..count {
                    reader.unsigned()?;
                }
            }
            6..=15
            | 31..=39
            | 42
            | 43
            | 46
            | 57
            | 62
            | 64..=66
            | 70
            | 71
            | 74..=81
            | 83..=89
            | 92 => {}
            45 | 96..=111 | 175.. => {
                cluster.next_field =
                    i32::try_from(reader.signed(32)?).context("instance field count overflows")?;
                cluster.instance_size =
                    i32::try_from(reader.signed(32)?).context("instance size overflows")?;
                ensure!(
                    cluster.next_field >= 2 && cluster.instance_size >= cluster.next_field,
                    "unsupported metadata instance shape"
                );
            }
            _ => bail!("unsupported metadata allocation CID {cid}"),
        }
        next_ref = next_ref
            .checked_add(u32::try_from(cluster.count).context("metadata count overflows")?)
            .ok_or_else(|| anyhow!("metadata references overflow"))?;
        ensure!(
            next_ref <= object_count + 1,
            "metadata allocation exceeds object count"
        );
        clusters.push(cluster);
    }
    ensure!(
        next_ref == object_count + 1,
        "metadata allocation object count differs"
    );
    let mut kind_ranges: HashMap<u32, Vec<Range<u32>>> = HashMap::new();
    for cluster in &clusters {
        kind_ranges
            .entry(cluster.cid)
            .or_default()
            .push(cluster.first_ref..cluster.first_ref + cluster.count as u32);
    }
    Ok(Allocation {
        base_objects,
        object_count,
        snapshot_size,
        fill_start: reader.position,
        clusters,
        kind_ranges,
    })
}

fn types(
    data: &[u8],
    allocation: &Allocation,
    strings: &Range<usize>,
) -> Result<HashMap<u32, u32>> {
    ensure!(
        allocation
            .clusters
            .iter()
            .take(5)
            .map(|cluster| cluster.cid)
            .eq([93, 61, 62, 52, 49]),
        "unsupported early metadata cluster order"
    );
    let mut reader = Reader::new(data, strings.end);
    for _ in 0..allocation.clusters[2].count {
        reader.signed(64)?;
    }
    for _ in 0..allocation.clusters[3].count {
        for _ in 0..3 {
            reference(&mut reader, allocation.object_count)?;
        }
        for _ in 0..2 {
            ensure!(
                (0..=u16::MAX as i64).contains(&reader.signed(32)?),
                "invalid type-parameter index"
            );
        }
        reader.byte()?;
    }
    let cluster = &allocation.clusters[4];
    let mut result = HashMap::new();
    for index in 0..cluster.count {
        for _ in 0..3 {
            reference(&mut reader, allocation.object_count)?;
        }
        let flags = reader.unsigned()?;
        result.insert(
            cluster.first_ref + index as u32,
            ((flags >> 3) & 0xfffff) as u32,
        );
    }
    Ok(result)
}

#[derive(Clone, Debug)]
struct RawClass {
    cid: u32,
    reference: u32,
    references: [u32; 13],
    instance_size: i32,
    next_field: i32,
    bitmap: u64,
}

fn class_records(
    reader: &mut Reader<'_>,
    allocation: &Allocation,
    cluster: &Cluster,
    types: &HashMap<u32, u32>,
    count: usize,
) -> Result<Vec<RawClass>> {
    let mut classes = Vec::new();
    let mut seen = HashSet::new();
    for index in 0..count {
        let mut references = [0; 13];
        for value in &mut references {
            *value = reference(reader, allocation.object_count)?;
        }
        for (position, kinds) in [
            (0, &[93][..]),
            (1, &[90, 91]),
            (2, &[90, 91]),
            (3, &[90, 91]),
            (4, &[90, 91]),
            (5, &[90, 91]),
            (6, &[12]),
            (7, &[13]),
            (8, &[8]),
            (9, &[49]),
            (10, &[90, 91]),
            (11, &[49]),
            (12, &[90, 91]),
        ] {
            ensure!(
                allocation.has_kind(references[position], kinds),
                "class metadata reference has the wrong kind"
            );
        }
        let cid = u32::try_from(reader.signed(32)?).context("negative metadata class ID")?;
        if index < cluster.predefined.len() {
            ensure!(
                cid == cluster.predefined[index],
                "predefined metadata class IDs differ"
            );
        } else {
            ensure!(
                cid >= 175,
                "non-predefined metadata class has a reserved ID"
            );
        }
        ensure!(seen.insert(cid), "duplicate metadata class ID");
        let instance_size =
            i32::try_from(reader.signed(32)?).context("class instance size overflows")?;
        let next_field = i32::try_from(reader.signed(32)?).context("class field size overflows")?;
        reader.signed(32)?;
        for _ in 0..2 {
            ensure!(
                (i16::MIN as i64..=u16::MAX as i64).contains(&reader.signed(32)?),
                "invalid metadata class arity"
            );
        }
        reader.signed(32)?;
        let minimum = if index < cluster.predefined.len() {
            -2
        } else {
            0
        };
        ensure!(
            instance_size >= minimum
                && next_field >= minimum
                && i64::from(instance_size) <= (data_span(reader) / 4) as i64
                && i64::from(next_field) <= (data_span(reader) / 4) as i64,
            "metadata class layout exceeds snapshot bounds"
        );
        if references[11] > allocation.base_objects {
            ensure!(
                types.get(&references[11]) == Some(&cid),
                "class declaration type does not match its class ID"
            );
        }
        let bitmap = if index < cluster.predefined.len() || cid < 1 << 20 {
            reader.unsigned()?
        } else {
            0
        };
        classes.push(RawClass {
            cid,
            reference: cluster.first_ref + index as u32,
            references,
            instance_size,
            next_field,
            bitmap,
        });
    }
    Ok(classes)
}

fn data_span(reader: &Reader<'_>) -> usize {
    reader.data.len()
}

fn signed_bytes(mut value: i64) -> Vec<u8> {
    let mut result = Vec::new();
    while !(-64..64).contains(&value) {
        result.push((value & 127) as u8);
        value >>= 7;
    }
    result.push((value + 192) as u8);
    result
}

fn backward_references(data: &[u8], end: usize, count: usize) -> Option<usize> {
    let mut start = end;
    for _ in 0..count {
        start = start.checked_sub(1)?;
        if data[start] < 128 {
            return None;
        }
        let mut bytes = 1;
        while start > 0 && data[start - 1] < 128 {
            start -= 1;
            bytes += 1;
            if bytes > 5 {
                return None;
            }
        }
    }
    Some(start)
}

fn locate_classes(
    data: &[u8],
    allocation: &Allocation,
    types: &HashMap<u32, u32>,
    budget: &mut ScanBudget,
) -> Result<Vec<RawClass>> {
    let cluster = allocation.cluster(5)?;
    let first_cid = *cluster
        .predefined
        .first()
        .ok_or_else(|| anyhow!("class metadata has no predefined anchor"))?;
    let needle = signed_bytes(first_cid as i64);
    let mut found = None;
    for relative in memmem::find_iter(&data[allocation.fill_start..], &needle) {
        let cid_position = allocation.fill_start + relative;
        let Some(start) = backward_references(data, cid_position, 13) else {
            continue;
        };
        let mut reader = Reader::new(data, start);
        let prefix = class_records(
            &mut reader,
            allocation,
            cluster,
            types,
            cluster.count.min(3),
        );
        budget.charge(reader.position - start + 65)?;
        if prefix.is_err() {
            continue;
        }
        let mut reader = Reader::new(data, start);
        let classes = class_records(&mut reader, allocation, cluster, types, cluster.count);
        budget.charge(reader.position - start + 1)?;
        let Ok(classes) = classes else { continue };
        ensure!(found.is_none(), "snapshot class metadata is ambiguous");
        found = Some(classes);
    }
    found.ok_or_else(|| anyhow!("snapshot class metadata was not found"))
}

fn array_records(
    reader: &mut Reader<'_>,
    allocation: &Allocation,
    cluster: &Cluster,
    wanted: &HashSet<u32>,
    count: usize,
) -> Result<HashMap<u32, Vec<u32>>> {
    let mut arrays = HashMap::new();
    for index in 0..count {
        let length = reader.count()?;
        ensure!(
            length == cluster.lengths[index] as usize,
            "metadata array lengths differ"
        );
        reference(reader, allocation.object_count)?;
        ensure!(
            length <= reader.data.len() - reader.position,
            "metadata array exceeds data bounds"
        );
        let current = cluster.first_ref + index as u32;
        let mut elements = Vec::new();
        for _ in 0..length {
            let value = reference(reader, allocation.object_count)?;
            if wanted.contains(&current) {
                elements.push(value);
            }
        }
        if wanted.contains(&current) {
            arrays.insert(current, elements);
        }
    }
    Ok(arrays)
}

fn interface_arrays(
    data: &[u8],
    allocation: &Allocation,
    raw_classes: &[RawClass],
    budget: &mut ScanBudget,
) -> Result<HashMap<u32, Vec<u32>>> {
    let wanted: HashSet<_> = raw_classes
        .iter()
        .map(|class| class.references[5])
        .filter(|&value| value > allocation.base_objects)
        .collect();
    let mut arrays = HashMap::new();
    for cluster in allocation
        .clusters
        .iter()
        .filter(|cluster| matches!(cluster.cid, 90 | 91))
    {
        if !wanted.iter().any(|&reference| cluster.contains(reference)) {
            continue;
        }
        let first_length = unsigned_bytes(u64::from(cluster.lengths[0]));
        let mut found = None;
        for relative in memmem::find_iter(&data[allocation.fill_start..], &first_length) {
            let start = allocation.fill_start + relative;
            let mut reader = Reader::new(data, start);
            let prefix = array_records(
                &mut reader,
                allocation,
                cluster,
                &HashSet::new(),
                cluster.count.min(4),
            );
            budget.charge(reader.position - start + 1)?;
            if prefix.is_err() {
                continue;
            }
            let mut reader = Reader::new(data, start);
            let candidate = array_records(&mut reader, allocation, cluster, &wanted, cluster.count);
            budget.charge(reader.position - start + 1)?;
            let Ok(candidate) = candidate else { continue };
            ensure!(
                found.is_none(),
                "metadata interface-array fill is ambiguous"
            );
            found = Some(candidate);
        }
        arrays.extend(found.ok_or_else(|| anyhow!("metadata interface-array fill was not found"))?);
    }
    ensure!(
        wanted
            .iter()
            .all(|reference| arrays.contains_key(reference)),
        "metadata interface arrays are incomplete"
    );
    Ok(arrays)
}

fn validate_topology(classes: &HashMap<u32, ClassInfo>) -> Result<()> {
    let mut visited = HashSet::new();
    let mut visiting = HashSet::new();
    for &root in classes.keys() {
        let mut pending = vec![(root, false)];
        while let Some((current, exiting)) = pending.pop() {
            if exiting {
                visiting.remove(&current);
                visited.insert(current);
                continue;
            }
            if visited.contains(&current) {
                continue;
            }
            ensure!(
                visiting.insert(current),
                "metadata class hierarchy has a cycle"
            );
            let class = classes
                .get(&current)
                .ok_or_else(|| anyhow!("metadata hierarchy references a missing class"))?;
            pending.push((current, true));
            for parent in class
                .super_class
                .into_iter()
                .chain(class.interfaces.iter().copied())
            {
                pending.push((parent, false));
            }
        }
    }
    Ok(())
}

fn color_records(
    reader: &mut Reader<'_>,
    allocation: &Allocation,
    cluster: &Cluster,
    count: usize,
) -> Result<(HashMap<u32, [u64; 4]>, u32)> {
    ensure!(
        reader.unsigned()? == 0x3fc,
        "canonical Color unboxed layout differs"
    );
    let mut colors = HashMap::new();
    let mut color_space = None;
    for index in 0..count {
        let mut components = [0; 4];
        for component in &mut components {
            let mut halves = [0; 2];
            for half in &mut halves {
                *half = reader.signed(32)? as u32;
                ensure!(
                    reader.signed(32)? == 0,
                    "canonical Color compressed word is not zero extended"
                );
            }
            *component = u64::from(halves[0]) | (u64::from(halves[1]) << 32);
            let value = f64::from_bits(*component);
            ensure!(
                value.is_finite() && (0.0..=1.0).contains(&value),
                "canonical Color is outside normalized component bounds"
            );
        }
        let space = reference(reader, allocation.object_count)?;
        ensure!(
            color_space.is_none_or(|previous| previous == space),
            "canonical Color cluster mixes color spaces"
        );
        color_space = Some(space);
        colors.insert(cluster.first_ref + index as u32, components);
    }
    Ok((
        colors,
        color_space.ok_or_else(|| anyhow!("canonical Color cluster is empty"))?,
    ))
}

fn locate_colors(
    data: &[u8],
    allocation: &Allocation,
    class_id: u32,
    budget: &mut ScanBudget,
) -> Result<(HashMap<u32, [u64; 4]>, u32)> {
    let mut matches = allocation
        .clusters
        .iter()
        .filter(|cluster| cluster.cid == class_id && cluster.canonical);
    let cluster = matches
        .next()
        .ok_or_else(|| anyhow!("canonical Color allocation was not found"))?;
    ensure!(
        matches.next().is_none(),
        "canonical Color allocation is ambiguous"
    );
    ensure!(
        cluster.canonical && cluster.next_field == 11 && cluster.instance_size == 12,
        "canonical Color allocation shape differs"
    );
    let mut found = None;
    for relative in memmem::find_iter(&data[allocation.fill_start..], &unsigned_bytes(0x3fc)) {
        let start = allocation.fill_start + relative;
        let mut reader = Reader::new(data, start);
        let prefix = color_records(&mut reader, allocation, cluster, cluster.count.min(4));
        budget.charge(reader.position - start + 1)?;
        if prefix.is_err() {
            continue;
        }
        let mut reader = Reader::new(data, start);
        let colors = color_records(&mut reader, allocation, cluster, cluster.count);
        budget.charge(reader.position - start + 1)?;
        let Ok(colors) = colors else { continue };
        ensure!(found.is_none(), "canonical Color fill is ambiguous");
        found = Some(colors);
    }
    found.ok_or_else(|| anyhow!("canonical Color fill was not found"))
}

pub fn parse_metadata(
    data: &[u8],
    snapshot: &SnapshotInfo,
    color_class_id: u32,
) -> Result<Metadata> {
    let allocation = allocation(data, snapshot.object_count)?;
    let data = &data[..allocation.snapshot_size];
    let mut budget = ScanBudget {
        remaining: data.len().saturating_mul(64),
    };
    let string_cluster = allocation
        .clusters
        .first()
        .ok_or_else(|| anyhow!("metadata has no clusters"))?;
    ensure!(
        string_cluster.cid == 93 && string_cluster.canonical,
        "metadata does not start with canonical Strings"
    );
    let strings = locate_strings(
        data,
        allocation.fill_start,
        &string_cluster.lengths,
        &mut budget,
    )?;
    ensure!(
        strings.start == allocation.fill_start,
        "metadata String fill does not match allocation end"
    );
    let types = types(data, &allocation, &strings)?;
    let raw_classes = locate_classes(data, &allocation, &types, &mut budget)?;
    let arrays = interface_arrays(data, &allocation, &raw_classes, &mut budget)?;
    let mut classes = HashMap::new();
    for raw in raw_classes {
        let super_class = if raw.references[9] <= allocation.base_objects {
            None
        } else {
            Some(
                *types
                    .get(&raw.references[9])
                    .ok_or_else(|| anyhow!("class superclass type is missing"))?,
            )
        };
        let interfaces = if raw.references[5] <= allocation.base_objects {
            Vec::new()
        } else {
            arrays[&raw.references[5]]
                .iter()
                .map(|reference| {
                    types
                        .get(reference)
                        .copied()
                        .ok_or_else(|| anyhow!("class interface type is missing"))
                })
                .collect::<Result<Vec<_>>>()?
        };
        classes.insert(
            raw.cid,
            ClassInfo {
                reference: raw.reference,
                name_reference: raw.references[0],
                super_class,
                interfaces,
                instance_size: raw
                    .instance_size
                    .checked_mul(4)
                    .ok_or_else(|| anyhow!("class byte size overflows"))?,
                next_field_offset: raw
                    .next_field
                    .checked_mul(4)
                    .ok_or_else(|| anyhow!("class field byte offset overflows"))?,
                unboxed_bitmap: raw.bitmap,
            },
        );
    }
    validate_topology(&classes)?;
    let (colors, color_space_reference) =
        locate_colors(data, &allocation, color_class_id, &mut budget)?;
    Ok(Metadata {
        classes,
        colors,
        color_space_reference,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn class(parent: Option<u32>, interfaces: Vec<u32>) -> ClassInfo {
        ClassInfo {
            reference: 1,
            name_reference: 1,
            super_class: parent,
            interfaces,
            instance_size: 16,
            next_field_offset: 12,
            unboxed_bitmap: 0,
        }
    }

    #[test]
    fn validates_shared_base_interface_and_rejects_cycles_or_missing_parents() {
        let classes = HashMap::from([
            (1, class(None, vec![])),
            (2, class(Some(1), vec![5])),
            (3, class(Some(2), vec![])),
            (4, class(Some(2), vec![])),
            (5, class(None, vec![])),
        ]);
        validate_topology(&classes).unwrap();
        let mut invalid = classes.clone();
        invalid.get_mut(&1).unwrap().super_class = Some(3);
        assert!(validate_topology(&invalid).is_err());
        let mut invalid = classes;
        invalid.get_mut(&1).unwrap().super_class = Some(999);
        assert!(validate_topology(&invalid).is_err());
    }

    #[test]
    fn rejects_truncated_color_payloads_and_nonfinite_components() {
        let allocation = Allocation {
            base_objects: 1,
            object_count: 20,
            snapshot_size: 100,
            fill_start: 0,
            clusters: Vec::new(),
            kind_ranges: HashMap::new(),
        };
        let cluster = Cluster {
            cid: 200,
            canonical: true,
            first_ref: 2,
            count: 1,
            lengths: Vec::new(),
            predefined: Vec::new(),
            next_field: 11,
            instance_size: 12,
        };
        let mut data = unsigned_bytes(0x3fc);
        for _ in 0..16 {
            data.extend(signed_bytes(0));
        }
        data.push(0x81);
        let (colors, space) =
            color_records(&mut Reader::new(&data, 0), &allocation, &cluster, 1).unwrap();
        assert_eq!(space, 1);
        assert_eq!(colors[&2], [0; 4]);
        for end in 0..data.len() {
            assert!(
                color_records(&mut Reader::new(&data[..end], 0), &allocation, &cluster, 1).is_err()
            );
        }
        let mut invalid = unsigned_bytes(0x3fc);
        for half in [0_u32, 0x7ff8_0000] {
            invalid.extend(signed_bytes(half as i64));
            invalid.extend(signed_bytes(0));
        }
        assert!(color_records(&mut Reader::new(&invalid, 0), &allocation, &cluster, 1).is_err());
    }

    #[test]
    fn requires_a_unique_complete_class_block() {
        let mut data = vec![0x81; 13];
        for value in [45, 4, 2, -1, 0, 0, 0] {
            data.extend(signed_bytes(value));
        }
        data.extend(unsigned_bytes(0));
        let allocation = Allocation {
            base_objects: 1,
            object_count: 10,
            snapshot_size: data.len(),
            fill_start: 0,
            clusters: vec![Cluster {
                cid: 5,
                canonical: false,
                first_ref: 2,
                count: 1,
                lengths: Vec::new(),
                predefined: vec![45],
                next_field: 0,
                instance_size: 0,
            }],
            kind_ranges: HashMap::new(),
        };
        let types = HashMap::new();
        let mut budget = ScanBudget { remaining: 4096 };
        let classes = locate_classes(&data, &allocation, &types, &mut budget).unwrap();
        assert_eq!(classes.len(), 1);
        assert_eq!(classes[0].cid, 45);
        for end in 0..data.len() {
            let mut budget = ScanBudget { remaining: 4096 };
            assert!(locate_classes(&data[..end], &allocation, &types, &mut budget).is_err());
        }
        let mut duplicated = data.clone();
        duplicated.extend(&data);
        let mut budget = ScanBudget { remaining: 4096 };
        let error = locate_classes(&duplicated, &allocation, &types, &mut budget).unwrap_err();
        assert!(error.to_string().contains("ambiguous"));
    }

    #[test]
    fn validates_full_array_lengths_before_resolving_interfaces() {
        let data = vec![0x80, 0x81, 0x81, 0x81, 0x83, 0x82, 0x81, 0x83, 0x84];
        let allocation = Allocation {
            base_objects: 1,
            object_count: 20,
            snapshot_size: data.len(),
            fill_start: 0,
            clusters: vec![Cluster {
                cid: 90,
                canonical: false,
                first_ref: 10,
                count: 3,
                lengths: vec![0, 1, 2],
                predefined: Vec::new(),
                next_field: 0,
                instance_size: 0,
            }],
            kind_ranges: HashMap::new(),
        };
        let mut references = [1; 13];
        references[5] = 12;
        let classes = vec![RawClass {
            cid: 200,
            reference: 2,
            references,
            instance_size: 4,
            next_field: 2,
            bitmap: 0,
        }];
        let mut budget = ScanBudget { remaining: 4096 };
        let arrays = interface_arrays(&data, &allocation, &classes, &mut budget).unwrap();
        assert_eq!(arrays[&12], vec![3, 4]);
        let mut truncated = data.clone();
        truncated.pop();
        let mut budget = ScanBudget { remaining: 4096 };
        assert!(interface_arrays(&truncated, &allocation, &classes, &mut budget).is_err());
        let mut duplicated = data.clone();
        duplicated.extend(&data);
        let mut budget = ScanBudget { remaining: 4096 };
        assert!(interface_arrays(&duplicated, &allocation, &classes, &mut budget).is_err());
    }
}
