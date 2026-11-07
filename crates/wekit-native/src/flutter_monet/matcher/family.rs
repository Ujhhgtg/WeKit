use std::collections::{BTreeMap, HashMap, HashSet};

use anyhow::{Result, ensure};

use super::super::snapshot::{ClassInfo, Metadata, PoolEntry, Role, parse_metadata};
use super::{
    ColorFactory, Image, POP_FRAME, RESTORE_STACK, RETURN, Target, allocation_class, call_target,
};

fn provider_class(image: &Image<'_>, entry: usize, metadata: &Metadata) -> Option<u32> {
    let range = image.range(entry)?;
    if range.start != entry || range.len() > 768 || image.frame_size(entry).is_none() {
        return None;
    }
    let mut found = None;
    for site in (range.start..range.end.saturating_sub(20)).step_by(4) {
        let Some(allocator) = image.word(site).and_then(|word| call_target(word, site)) else {
            continue;
        };
        let Some((class_id, size)) = allocation_class(image, allocator) else {
            continue;
        };
        let class: &ClassInfo = metadata.classes.get(&class_id)?;
        if class.unboxed_bitmap != 0 && class.unboxed_bitmap != 24 {
            continue;
        }
        if class.reference == 0
            || class.name_reference == 0
            || class.next_field_offset < 12
            || class.next_field_offset > class.instance_size
            || size < class.instance_size as usize
            || size >= class.instance_size as usize + 16
        {
            continue;
        }
        if !image.words(site + 4, &[0xf85f83a1, 0xb8007001]) {
            continue;
        }
        let mut cursor = site + 12;
        if !image.words(cursor, &[RESTORE_STACK, POP_FRAME, RETURN]) {
            if !image.words(cursor, &[0x1e6e1000, 0xfc00b000]) {
                continue;
            }
            cursor += 8;
            let Some((next, displacement)) = image.pool_load(cursor, 1, false) else {
                continue;
            };
            if !matches!(image.pool_value(displacement), Some(PoolEntry::Tagged(_)))
                || image.word(next) != Some(0xb8013001)
            {
                continue;
            }
            cursor = next + 4;
        }
        if cursor + 12 > range.end || !image.words(cursor, &[RESTORE_STACK, POP_FRAME, RETURN]) {
            continue;
        }
        if let Some(previous) = found
            && previous != class_id
        {
            return None;
        }
        found = Some(class_id);
    }
    found
}

fn receivers(image: &Image<'_>, metadata: &Metadata) -> HashMap<usize, HashSet<u32>> {
    let mut providers = HashMap::new();
    let mut result: HashMap<usize, HashSet<u32>> = HashMap::new();
    for range in &image.snapshot.ranges {
        for site in (range.start..range.end.saturating_sub(8)).step_by(4) {
            let Some(provider) = image.word(site).and_then(|word| call_target(word, site)) else {
                continue;
            };
            if image.word(site + 4) != Some(0xaa0003e1) {
                continue;
            }
            let Some(getter) = image
                .word(site + 8)
                .and_then(|word| call_target(word, site + 8))
            else {
                continue;
            };
            let class_id = *providers
                .entry(provider)
                .or_insert_with(|| provider_class(image, provider, metadata));
            if let Some(class_id) = class_id {
                result.entry(getter).or_default().insert(class_id);
            }
        }
    }
    result
}

pub(super) struct Expansion {
    pub(super) getters: Vec<(usize, Role)>,
    pub(super) targets: Vec<Target>,
}

pub(super) fn related_getters(
    image: &Image<'_>,
    data: &[u8],
    factory: &ColorFactory,
    named: &BTreeMap<usize, Vec<Role>>,
    anchors: &[Target],
) -> Result<Expansion> {
    let metadata = parse_metadata(data, image.snapshot, factory.class_id)?;
    let receivers = receivers(image, &metadata);
    let foreground_classes: HashSet<_> = named
        .iter()
        .filter(|(_, roles)| {
            roles
                .iter()
                .any(|role| matches!(role, Role::Fg0 | Role::Fg1))
        })
        .filter_map(|(entry, _)| receivers.get(entry))
        .flatten()
        .copied()
        .collect();
    ensure!(
        !foreground_classes.is_empty(),
        "no concrete receiver class for named foreground getters"
    );
    let mut definitions = Vec::new();
    for (entry, roles) in named {
        let Some(classes) = receivers.get(entry) else {
            continue;
        };
        if !classes
            .iter()
            .all(|class_id| foreground_classes.contains(class_id))
        {
            continue;
        }
        let Some(palette) =
            super::palette_matcher::declared_palette(image, *entry, factory, &metadata)
        else {
            continue;
        };
        for &role in roles {
            let background = matches!(
                role,
                Role::Bg0 | Role::Bg1 | Role::Bg2 | Role::Bg3 | Role::Bg4 | Role::Bg5
            );
            if background && !palette.iter().all(|color| color[0] == 1_f64.to_bits()) {
                continue;
            }
            definitions.push((role, palette));
        }
    }
    let mut result = Vec::new();
    for (entry, classes) in &receivers {
        if named.contains_key(entry)
            || !classes
                .iter()
                .all(|class_id| foreground_classes.contains(class_id))
        {
            continue;
        }
        let Some(palette) =
            super::palette_matcher::declared_palette(image, *entry, factory, &metadata)
        else {
            continue;
        };
        let mut roles = HashSet::new();
        for (role, definition) in &definitions {
            if *definition == palette {
                roles.insert(*role);
            }
        }
        ensure!(
            roles.len() <= 1,
            "anchored color getter has ambiguous semantic declarations at {entry:#x}"
        );
        if let Some(&role) = roles.iter().next() {
            result.push((*entry, role));
        }
    }
    let mut helpers = BTreeMap::<usize, HashSet<Role>>::new();
    for (entry, role) in named
        .iter()
        .flat_map(|(entry, roles)| roles.iter().map(move |role| (*entry, *role)))
        .chain(result.iter().copied())
    {
        let Some(classes) = receivers.get(&entry) else {
            continue;
        };
        if !classes
            .iter()
            .all(|class_id| foreground_classes.contains(class_id))
        {
            continue;
        }
        for helper in super::palette_matcher::selected_helpers(image, entry, factory, &metadata) {
            helpers.entry(helper).or_default().insert(role);
        }
    }
    let mut selected = BTreeMap::new();
    for (entry, roles) in helpers {
        ensure!(
            roles.len() == 1,
            "selected theme helper has ambiguous semantic declarations at {entry:#x}"
        );
        let role = *roles.iter().next().unwrap();
        result.push((entry, role));
        selected.insert(entry, vec![role]);
    }
    let mut continuations = super::derived::targets(image, factory, &metadata, &selected)?;
    continuations.extend(super::brand::targets(
        image,
        factory,
        &metadata,
        anchors,
        |provider, getter| {
            provider_class(image, provider, &metadata).is_some_and(|class_id| {
                foreground_classes.contains(&class_id)
                    && receivers
                        .get(&getter)
                        .is_some_and(|classes| classes.contains(&class_id))
            })
        },
        |provider| {
            provider_class(image, provider, &metadata)
                .is_some_and(|class_id| foreground_classes.contains(&class_id))
        },
    )?);
    result.sort_by_key(|(entry, _)| *entry);
    Ok(Expansion {
        getters: result,
        targets: continuations,
    })
}
