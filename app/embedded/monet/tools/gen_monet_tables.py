#!/usr/bin/env python3
"""
Generate app/embedded/monet/monet_tables.json for the Monet overlay feature
(dev.ujhhgtg.wekit.features.items.beautify.monet).

## What this produces
A JSON with three sections consumed by MonetOverlayBuilder / MonetTables:
  - "versions": { "<versionCode>": { "colors": { <wechatColorName>: {l,n} } } }
      Exact per-version tables. `l`/`n` are the light/night Monet targets
      (`@android:color/system_*` or `#aarrggbb`).
  - "generic": { <name>: {l,n,v} }  -- reference (8.0.69 Play) semantic table,
      `v` = expected original ARGB. Used for unknown-version fallback: each name is
      re-verified against the LIVE WeChat arsc at runtime and dropped if drifted.
  - "brandByValue": { "<argb>": {l,n} }  -- unambiguous brand/accent colors keyed by
      original value, for discovering per-version obfuscated brand names at runtime.
  - "surfByPair": { "<lightArgb>|<nightArgb>": {l,n} }  -- unambiguous THEME-FLIPPING
      background colors keyed by their (light,night) value pair, for discovering renamed
      surface/background names at runtime. Only theme-flipping (light != night) pairs are
      included, which are provably backgrounds (never static text) -> safe to surface-map.

## Inputs (apktool decompiles; NOT in this repo)
  - Reference overlay APKs decompiled to ~/coding/apk14 and ~/coding/apk12
    (from the two reference Magisk zips "微信莫奈取色 8069 Beta（安卓{14,12}）.zip";
    originally decompiled to /tmp/apk1{2,4}, then moved to ~/coding/apk1{2,4}):
        apktool d -f -o ~/coding/apk14 <monet14>/system/priv-app/MonetWeChat.apk
        apktool d -f -o ~/coding/apk12 <monet12>/system/product/overlay/MonetWeChat.apk
    The api14 overlay's res/values{,-night}/colors.xml IS the curated ground-truth
    (wechatColorName -> Monet target).
  - WeChat decompiles at ~/coding/wechat_{8065,8067,8069,8074,8076} and
    ~/coding/wechat_8069_3020_play (apktool output; provides values/colors.xml with
    the original color values and public.xml).

## Core logic & why (see memory: monet-overlay-module-generation)
RRO matches the target by resource NAME. But WeChat's short obfuscated color names
(m, b, i, ...) are REUSED across versions with different meanings (e.g. `m` is white
in Play but brand-green in standard 8.0.69). So we CANNOT ship the static template as
is, and we CANNOT map purely by value (same ARGB -> many different curated targets;
the reference only overrides a hand-picked subset). The safe hybrid:
  1. PRUNE-VERIFY: keep a curated name for a version only if that name exists there AND
     its original light value equals the reference intent (`olv`). This preserves
     surface/neutral mappings only where the name is value-stable -> no mis-coloring.
  2. BRAND-BY-VALUE: brand/link/red/lightgreen ARGBs are semantically unique in WeChat,
     so also map any color in that version whose value hits `brandmap` (value->target
     built from primary/accent targets that are unambiguous). This recovers the brand
     colors that got renamed per version.
Conservative on surfaces, always correct on brand -> "没有误伤".

## Verification performed (desktop, since no device)
  - ARSCLib prototype: load overlay, edit COLOR ref, prune (setNull over all configs),
    getOrCreate+setValueAsReference to add, writeApk -> aapt2 parses OK, correct
    package/manifest <overlay targetPackage=com.tencent.mm isStatic>, color count matches.
  - apksig + BouncyCastle self-sign (V2/V3) -> "APK Sig Block 42" present, aapt2 badging OK.
  - FullSim.java faithfully ports MonetOverlayBuilder against each wechat_* decompile:
    for vc=3040 -> kept=50 + added=129 = 179 color overrides, 183 pruned; output valid.
  - Confirmed over-application is real: value-only transfer yields ~900 entries and would
    recolor wrong resources (98/137 name collisions on 8069) -> rejected.

Adjust the hardcoded paths below if the decompiles move.
"""
import re, os, json

# versionName-ish key -> decompile dir, and -> versionCode used as the table key
VERDIR = {
    '8065': 'wechat_8065', '8067': 'wechat_8067', '8069': 'wechat_8069', '8070': 'wechat_8070', '8071': 'wechat_8071', '8072': 'wechat_8072',
    '8074': 'wechat_8074', '8076': 'wechat_8076', '8069play': 'wechat_8069_3020_play',
}
VERCODE = {'8065': 2960, '8067': 3000, '8069': 3040, '8070': 3060, '8071': 3080, '8072': 3100, '8074': 3120, '8076': 3120, '8069play': 3020}

WECHAT_ROOT = os.path.expanduser('~/coding')
REF = f'{WECHAT_ROOT}/wechat_8069_3020_play/app/src/main/res'
OV_LIGHT = os.path.expanduser('~/coding/apk14/res/values/colors.xml')
OV_NIGHT = os.path.expanduser('~/coding/apk14/res/values-night/colors.xml')
# Output: app/embedded/monet/monet_tables.json (this script lives in app/embedded/monet/tools/).
OUT = os.path.normpath(os.path.join(os.path.dirname(__file__), '..', 'monet_tables.json'))

# Overlay-internal helper colors: present in the overlay arsc but NOT a WeChat color in
# ANY version (referenced only by the overlay's own drawables). Never emit these as
# WeChat-name overrides.
INTERNAL = {'UN_BW_90', 'aw8', 'jsqp', '红包气泡遮罩', '链接气泡遮罩', '链接气泡遮罩2'}


def cmap(p):
    return dict(re.findall(r'<color name="([^"]+)">([^<]*)</color>', open(p).read())) if os.path.exists(p) else {}


def resolve(v, m, d=0):
    """Resolve one @color/ indirection chain to a literal."""
    if d > 8 or v is None:
        return v
    x = re.match(r'@color/(.+)', v)
    if x and x.group(1) in m:
        return resolve(m[x.group(1)], m, d + 1)
    return v


def norm(v):
    """Normalize #rgb/#rrggbb/#aarrggbb -> lowercase #aarrggbb."""
    if not v:
        return None
    v = v.strip().lower()
    if re.match(r'#[0-9a-f]{6}$', v):
        v = '#ff' + v[1:]
    return v


def main():
    ovl = cmap(OV_LIGHT)
    ovn = cmap(OV_NIGHT)
    refL = cmap(REF + '/values/colors.xml')
    refN = cmap(REF + '/values-night/colors.xml')

    def refval(n, night):
        m = {**refL, **refN} if night else refL
        raw = (refN.get(n) if night else None) or refL.get(n)
        return norm(resolve(raw, m))

    # Ground truth: curated overlay name -> targets + reference original values.
    gt = {}
    for n in ovl:
        if n in INTERNAL or n not in refL:
            continue
        lv = refval(n, False)
        if lv is None:
            continue
        gt[n] = {'l': ovl[n], 'n': ovn.get(n, ovl[n]), 'olv': lv, 'onv': refval(n, True)}

    # brandmap: original ARGB -> (lightTarget, nightTarget) for UNAMBIGUOUS primary/accent colors.
    # Brand/link/red/lightgreen values are semantically unique in WeChat, so single-value keying
    # is safe (any color with that exact ARGB is that brand color, regardless of obfuscated name).
    from collections import defaultdict
    brandval = defaultdict(set)
    for n, g in gt.items():
        if 'primary' in g['l'] or 'accent' in g['l']:
            brandval[g['olv']].add((g['l'], g['n']))
    brandmap = {lv: list(s)[0] for lv, s in brandval.items() if len(s) == 1}

    # surfmap: (origLight, origNight) pair -> surface target, for UNAMBIGUOUS pairs where the color
    # FLIPS between light/night (olv != onv). A theme-flipping color is provably a *background*
    # (real surfaces invert with the theme), never static text/icon — so surface-mapping it can't
    # turn text invisible. This recovers the many background colors that WeChat renamed obfuscated
    # between the Play branch (human-readable names) and standard branches (short obfuscated names),
    # which plain name-based prune-verify misses. Bounded by the reference's own curated pairs.
    pairval = defaultdict(set)
    for n, g in gt.items():
        pairval[(g['olv'], g['onv'])].add((g['l'], g['n']))
    surfmap = {
        k: list(s)[0] for k, s in pairval.items()
        if len(s) == 1 and 'surface' in list(s)[0][0] and k[0] != k[1]
    }

    generic = {n: {'l': g['l'], 'n': g['n'], 'v': g['olv']} for n, g in gt.items()}
    brandmap_out = {lv: {'l': t[0], 'n': t[1]} for lv, t in brandmap.items()}
    # surfByPair keyed by "lv|nv" (JSON has no tuple keys).
    surfmap_out = {f'{k[0]}|{k[1]}': {'l': t[0], 'n': t[1]} for k, t in surfmap.items()}

    out = {
        'generic': generic,
        'brandByValue': brandmap_out,
        'surfByPair': surfmap_out,
        'versions': {},
    }
    for vk, vd in VERDIR.items():
        base = f'{WECHAT_ROOT}/{vd}/app/src/main/res'
        L = cmap(base + '/values/colors.xml')
        N = cmap(base + '/values-night/colors.xml')
        allm = {**L, **N}

        # Skip incomplete decompiles (no values/colors.xml). Emitting an empty exact table would
        # be worse than no table: at runtime the builder would pick it and prune everything,
        # instead of falling through to the generic (value-verified) fallback.
        if not L:
            print(f"{vk}(vc={VERCODE[vk]}): SKIPPED (no colors.xml)")
            continue

        def vval(n, night):
            m = allm if night else L
            raw = (N.get(n) if night else None) or L.get(n)
            return norm(resolve(raw, m))

        entries = {}
        # (1) prune-verify: keep curated names whose value is stable in this version
        for name, g in gt.items():
            if name in L and vval(name, False) == g['olv']:
                entries[name] = {'l': g['l'], 'n': g['n']}
        # (2) brand-by-value: recover renamed brand/accent colors
        for nm in L:
            lv = vval(nm, False)
            if lv in brandmap and nm not in entries:
                l, n = brandmap[lv]
                entries[nm] = {'l': l, 'n': n}
        # (3) surface pair-transfer: recover renamed theme-flipping background colors (safe)
        for nm in L:
            if nm in entries:
                continue
            k = (vval(nm, False), vval(nm, True))
            if k in surfmap:
                l, n = surfmap[k]
                entries[nm] = {'l': l, 'n': n}
        out['versions'][str(VERCODE[vk])] = {'colors': entries}
        print(f"{vk}(vc={VERCODE[vk]}): {len(entries)}")

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(out, open(OUT, 'w'), ensure_ascii=False, separators=(',', ':'))
    print("generic:", len(generic), "brandByValue:", len(brandmap_out),
          "surfByPair:", len(surfmap), "size:", os.path.getsize(OUT))


if __name__ == '__main__':
    main()
